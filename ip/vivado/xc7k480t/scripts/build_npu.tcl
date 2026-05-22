################################################################################
# build_npu.tcl — Production NPU bitstream build (no ILA debugger core)
#
# Topology delivered:
#   - npu_subsys cell (ctrl_lite + npu_dma_master + MMALU, K=32)
#   - axi_xbar 2S:2M between {XDMA, NPU master} and {MIG C0, MIG C1}
#   - Unified 4 GB linear address map on both AXI masters:
#       0x0000_0000..0x7FFF_FFFF → MIG C0
#       0x8000_0000..0xFFFF_FFFF → MIG C1
#
# Outputs:
#   ip/vivado/xc7k480t/top_npu.bit
#
# Build time: ~25 min on a warm Vivado IP cache, ~75 min on a cold cache
# (first run also bootstraps the Vivado project from xc7k480t.reference/
# via scripts/bootstrap_project.tcl).
#
# Companion script (with ILA debugger core for hardware-level inspection):
#   build_npu_with_ila.tcl
################################################################################

set SCRIPT_DIR [file normalize [file dirname [info script]]]
set MIGRATE    [file normalize $SCRIPT_DIR/..]
set RTL_SRC    [file normalize [file join $MIGRATE src]]
set REPO_ROOT  [file normalize $SCRIPT_DIR/../../../..]
set BIT_DST    [file join $MIGRATE top_npu.bit]

source [file join $SCRIPT_DIR migrate_lib.tcl]
source [file join $SCRIPT_DIR _apply_npu_topology.tcl]

open_ref_project
assert_synth_done

# ── Add RTL sources ──────────────────────────────────────────────────────────
foreach name {npu_ctrl_lite.v npu_dma_master.v npu_subsys.v} {
    set f [file join $RTL_SRC $name]
    if {![file exists $f]} { puts "ERROR: $name missing"; exit 1 }
    if {[get_files -quiet -of_objects [get_filesets sources_1] $f] eq ""} {
        add_files -norecurse $f
        puts "INFO: added $name"
    }
}

# top.sv is required for MMALU module
set top_sv [file join $REPO_ROOT top.sv]
if {![file exists $top_sv]} {
    puts "ERROR: top.sv not found. Run 'make build' first."
    exit 1
}
if {[get_files -quiet -of_objects [get_filesets sources_1] $top_sv] eq ""} {
    add_files -norecurse $top_sv
    puts "INFO: added Chisel top.sv ([file size $top_sv] bytes)"
}
update_compile_order -fileset sources_1

# ── Apply BD deltas if not yet applied ──────────────────────────────────────
open_bd_design [get_files {*/top.bd}]

set has_npu_topology [expr {[get_bd_cells -quiet /axi_xbar] ne "" && \
                            [get_bd_cells -quiet /npu_subsys] ne ""}]
puts "INFO: NPU topology already present: $has_npu_topology"

if {!$has_npu_topology} {
    puts "INFO: applying NPU topology (V1..V10 squashed)..."
    apply_npu_topology $RTL_SRC
    save_bd
} else {
    # Even if topology is in place, ensure source_mgmt_mode lets the OOC
    # synth resolve module references.
    set ::_npu_save_mgmt_mode [get_property source_mgmt_mode [current_project]]
    set_property source_mgmt_mode All [current_project]
    update_compile_order -fileset sources_1
    save_bd
}

puts "INFO: BD cells: [llength [get_bd_cells]]"

# ── Regenerate IP targets after BD edits ────────────────────────────────────
puts "INFO: regenerating IP targets..."
catch { generate_target all [get_files {*/top.bd}] } gt_err
if {$gt_err ne ""} { puts "WARNING: generate_target: $gt_err" }
make_wrapper -files [get_files {*/top.bd}] -top -force
set_property top top_wrapper [get_filesets sources_1]
update_compile_order -fileset sources_1

# ── Re-run synth_1 via launch_runs so the new axi_xbar sub-IPs + npu_subsys
#    OOC IP all get DCPs and impl can link them ─────────────────────────────
puts "INFO: launching synth_1 + OOC sub-runs..."
reset_run synth_1
launch_runs synth_1 -jobs 8
set all_synth [get_runs -filter {IS_SYNTHESIS == 1}]
puts "INFO: waiting for [llength $all_synth] synthesis run(s)..."
wait_on_run $all_synth

set prog [get_property PROGRESS [get_runs synth_1]]
if {$prog ne "100%"} { puts "ERROR: synth_1 failed (PROGRESS=$prog)"; exit 1 }
puts "INFO: synth_1 done."

# Copy IP DCPs gen/ → srcs/ (link_design INBB-3 workaround)
foreach ip [get_ips -quiet] {
    set ip_dir     [get_property IP_DIR        [get_ips $ip]]
    set ip_out_dir [get_property IP_OUTPUT_DIR [get_ips $ip]]
    set src_dcp [file join $ip_out_dir ${ip}.dcp]
    set dst_dcp [file join $ip_dir     ${ip}.dcp]
    if {[file exists $src_dcp] && ![file exists $dst_dcp]} {
        file copy -force $src_dcp $dst_dcp
    }
}

open_run synth_1 -name synth_1
puts "INFO: synth_1 opened (merged OOC DCPs)."

# Restore source_mgmt_mode = None for impl
npu_restore_mgmt_mode

# Write the merged checkpoint
set synth_dcp [file join [get_property DIRECTORY [get_runs synth_1]] top_wrapper.dcp]
write_checkpoint -force $synth_dcp
puts "INFO: merged synthesis checkpoint: [file size $synth_dcp] bytes"

set ::migrate_synth_was_launch_runs 1
run_impl_and_write_bit "npu" $BIT_DST

puts ""
puts "*** build_npu COMPLETE ***"
puts "INFO: Bitstream  : $BIT_DST"
puts "INFO: Companion  : build_npu_with_ila.tcl (adds the ILA debugger core)"
