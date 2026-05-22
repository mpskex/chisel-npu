################################################################################
# build_v10_npu_xbar.tcl — Step V10: PRODUCTION build for testing MMALU + VALU
#
# Delta from V9 (npu_full):
#   - V9 only stitches dma_master and ctrl_lite as separate BD cells.  The
#     Chisel MMALU module (top.sv) is included in the fileset but never
#     instantiated, so dma_master's MMALU IO pins get promoted to wrapper top
#     ports and are optimized away.  The dma_master FSM stalls in S_WAIT_CLCT
#     forever because io_clct is tied to 0.
#   - V9 routes the NPU DMA master to MIG C1 only; the host can only reach
#     MIG C0 via xdma_0/M_AXI.  There is no host-driven path to the bank that
#     the NPU reads its operands from.
#
# V10 fixes both problems:
#   1. Replace ctrl_lite + dma_master BD cells with a single `npu_subsys` cell
#      whose Verilog wrapper (ip/vivado/xc7k480t/src/npu_subsys.v) instantiates
#      npu_ctrl_lite + npu_dma_master + MMALU with all internal wiring done.
#   2. Insert a 2S:2M AXI Interconnect (`axi_xbar`) so that BOTH XDMA and the
#      NPU's DMA master can address BOTH MIG channels.  The address map is
#      unified for both masters:
#          0x0000_0000 .. 0x7FFF_FFFF → MIG C0 (2 GB)
#          0x8000_0000 .. 0xFFFF_FFFF → MIG C1 (2 GB)
#      so each master sees 4 GB linear DDR3.
#
# Success criterion:
#   - 9/9 existing smoke tests still pass (PCIe, DDR3 C0 loopback, ctrl_lite).
#   - The new test_mmalu_compute.py passes: host writes A/B/ACCUM via XDMA H2C,
#     kicks ctrl, polls done, reads OUT via XDMA C2H, verifies result is
#     consistent with the MMALU multiplier path.
#
# This script supersedes build_v9_npu_full.tcl as the production build.
# build_v9 is preserved for the historical V0–V9 ladder.
################################################################################

set SCRIPT_DIR [file normalize [file dirname [info script]]]
set MIGRATE    [file normalize $SCRIPT_DIR/..]
set RTL_SRC    [file normalize [file join $MIGRATE src]]
set REPO_ROOT  [file normalize $SCRIPT_DIR/../../../..]
set BIT_DST    [file join $MIGRATE top_v10_npu_xbar.bit]

source [file join $SCRIPT_DIR migrate_lib.tcl]
source [file join $SCRIPT_DIR _apply_v2.tcl]
source [file join $SCRIPT_DIR _apply_v5.tcl]
source [file join $SCRIPT_DIR _apply_v6.tcl]
source [file join $SCRIPT_DIR _apply_v7.tcl]
source [file join $SCRIPT_DIR _apply_v10.tcl]
source [file join $SCRIPT_DIR _apply_v10_ila.tcl]

open_ref_project
assert_synth_done

# ── Add RTL sources ──────────────────────────────────────────────────────────
# All of npu_ctrl_lite.v, npu_dma_master.v, npu_subsys.v, and top.sv need to be
# part of the design before the BD references them.
foreach name {npu_ctrl_lite.v npu_dma_master.v npu_subsys.v} {
    set f [file join $RTL_SRC $name]
    if {[file exists $f]} {
        set ex [get_files -quiet -of_objects [get_filesets sources_1] $f]
        if {$ex eq ""} {
            add_files -norecurse $f
            puts "INFO: added $name"
        } else {
            puts "INFO: $name already in fileset"
        }
    } else {
        puts "ERROR: $name not found in $RTL_SRC"; exit 1
    }
}

# top.sv is required for the MMALU module that npu_subsys instantiates.
set top_sv [file join $REPO_ROOT top.sv]
if {![file exists $top_sv]} {
    puts "ERROR: top.sv not found. Run 'make build' first to generate Chisel output."
    exit 1
}
set ex [get_files -quiet -of_objects [get_filesets sources_1] $top_sv]
if {$ex eq ""} {
    add_files -norecurse $top_sv
    puts "INFO: added Chisel top.sv ([file size $top_sv] bytes)"
} else {
    puts "INFO: top.sv already in fileset"
}
update_compile_order -fileset sources_1

# ── Apply BD deltas as needed ───────────────────────────────────────────────
open_bd_design [get_files {*/top.bd}]

# Detect what topology level the BD is currently at.
# V9 topology marker: axi_clkconv_xdma cell present (added in V6r).
# V10 topology marker: axi_xbar cell present.
set has_v9_topology  [expr {[get_bd_cells -quiet /axi_clkconv_xdma] ne ""}]
set has_v10_topology [expr {[get_bd_cells -quiet /axi_xbar] ne ""}]
puts "INFO: BD topology — V9 markers present: $has_v9_topology, V10 markers present: $has_v10_topology"

if {!$has_v9_topology && !$has_v10_topology} {
    # Pristine external XPR: apply V1..V7 + V10
    puts "INFO: Applying V1..V7 deltas to reach V9 baseline..."
    apply_v1_deletions
    apply_v2_bypass_ctrl $RTL_SRC
    apply_v3_mmcm_and_rst
    apply_v4_byp_cdc
    apply_v5_xdma_cc
    apply_v6r_remove_smc
    apply_v7_dma_master
}

if {!$has_v10_topology} {
    puts "INFO: Applying V10 delta (axi_xbar + npu_subsys)..."
    apply_v10_xbar_and_subsys $RTL_SRC
    save_bd
} else {
    puts "INFO: V10 topology already present — skipping delta apply."
    save_bd
}

puts "INFO: V10 — BD cells: [llength [get_bd_cells]]; npu_subsys + axi_xbar in place."

# ── Regenerate IP targets after BD edits ─────────────────────────────────────
# The V10 delta added new IPs (axi_xbar with internal regslice / async-FIFO
# sub-cores).  generate_target produces the HDL stubs and XCI sources so that
# launch_runs can find them.
puts "INFO: Regenerating IP targets after V10 BD edits..."
catch { generate_target all [get_files {*/top.bd}] } gt_err
if {$gt_err ne ""} {
    puts "WARNING: generate_target returned: $gt_err (continuing)"
}
make_wrapper -files [get_files {*/top.bd}] -top -force
set_property top top_wrapper [get_filesets sources_1]
update_compile_order -fileset sources_1

# ── Re-run synthesis via launch_runs ─────────────────────────────────────────
# We CANNOT just call `synth_design -top top_wrapper` (migrate_lib's
# run_synthesis) here, because the new axi_xbar sub-IPs
# (top_axi_xbar_imp_m00_regslice_0, top_axi_xbar_imp_auto_cc_0, etc.) only
# have stub HDL — their netlists need OOC synthesis runs to exist first.
# `launch_runs synth_1` automatically schedules every OOC sub-run plus the
# main synth_1, which is exactly what the bootstrap flow already does.
puts "INFO: Launching synth_1 (and all OOC sub-runs) for V10 topology..."
reset_run synth_1
launch_runs synth_1 -jobs 8

set all_synth_runs [get_runs -filter {IS_SYNTHESIS == 1}]
puts "INFO: Waiting for [llength $all_synth_runs] synthesis run(s)..."
wait_on_run $all_synth_runs

set prog [get_property PROGRESS [get_runs synth_1]]
if {$prog ne "100%"} {
    puts "ERROR: synth_1 failed ($prog)"
    exit 1
}
puts "INFO: synth_1 PROGRESS=$prog; all OOC sub-runs done."

# ── Restore source_mgmt_mode to None for impl stage ──────────────────────────
# _apply_v10 left source_mgmt_mode = "All" so launch_runs synth_1 could
# resolve the npu_subsys module reference.  Now that synthesis is done, flip
# back to "None" so impl preserves top_wrapper as the design top (matches the
# migrate_lib.tcl convention).
if {[info exists ::_v10_save_mgmt_mode]} {
    set_property source_mgmt_mode $::_v10_save_mgmt_mode [current_project]
    set_property top top_wrapper [get_filesets sources_1]
    update_compile_order -fileset sources_1
    puts "INFO: source_mgmt_mode restored to $::_v10_save_mgmt_mode after synth"
    unset ::_v10_save_mgmt_mode
}

# Copy IP synthesis DCPs from gen/ → srcs/ so that impl link_design can find
# them at IP_DIR/<ip>.dcp (same INBB-3 workaround the bootstrap uses).
puts "INFO: Copying IP synthesis DCPs from gen/ → srcs/ ..."
set n_copied 0
foreach ip [get_ips -quiet] {
    set ip_dir     [get_property IP_DIR        [get_ips $ip]]
    set ip_out_dir [get_property IP_OUTPUT_DIR [get_ips $ip]]
    set src_dcp [file join $ip_out_dir ${ip}.dcp]
    set dst_dcp [file join $ip_dir     ${ip}.dcp]
    if {[file exists $src_dcp] && ![file exists $dst_dcp]} {
        file copy -force $src_dcp $dst_dcp
        incr n_copied
    }
}
puts "INFO: $n_copied IP DCP(s) copied."

# Open synth_1 so it collects the OOC results
open_run synth_1 -name synth_1
puts "INFO: synth_1 opened (merged OOC DCPs)."

# ── Insert ILA debug core for V10 beat-0 read-loss investigation ────────────
# This step scans for (* mark_debug = "true" *) attributes in the synthesized
# design and wires the matching nets to an `u_v10_ila` core. After impl, the
# .ltx file alongside the .bit will let HW Manager show these signals.
#
# To turn ILA off (smaller bitstream, no debug), set V10_NO_ILA env var.
if {[info exists ::env(V10_NO_ILA)] && $::env(V10_NO_ILA) eq "1"} {
    puts "INFO: V10_NO_ILA=1 — skipping ILA insertion"
} else {
    insert_v10_ila
}

# Write the merged checkpoint so impl picks up an OOC-resolved DCP.
set synth_dcp [file join [get_property DIRECTORY [get_runs synth_1]] top_wrapper.dcp]
write_checkpoint -force $synth_dcp
puts "INFO: Merged synthesis checkpoint: [file size $synth_dcp] bytes"

# Mark migrate_synth_was_launch_runs so run_impl_and_write_bit picks the
# launch_runs impl_1 path (proper INBB-3 / DCP linkage).
set ::migrate_synth_was_launch_runs 1

run_impl_and_write_bit "v10_npu_xbar" $BIT_DST

# ── Write the ILA probes file alongside the bitstream ──────────────────────
# Vivado HW Manager needs the .ltx file to enumerate ILA cores + probes.
if {![info exists ::env(V10_NO_ILA)] || $::env(V10_NO_ILA) ne "1"} {
    set ltx_dst [file rootname $BIT_DST].ltx
    set runs_dir [get_property DIRECTORY [get_runs impl_1]]
    set ltx_src  [file join $runs_dir top_wrapper.ltx]
    # open_run may already have run earlier; ignore "already open" errors
    catch { open_run impl_1 -name impl_1 }
    if {[catch {write_debug_probes -force $ltx_src} _err]} {
        puts "WARN: write_debug_probes failed: $_err"
    } else {
        if {[file exists $ltx_src]} {
            file copy -force $ltx_src $ltx_dst
            puts "INFO: ILA probes file: $ltx_dst ([file size $ltx_dst] bytes)"
        }
    }
}
