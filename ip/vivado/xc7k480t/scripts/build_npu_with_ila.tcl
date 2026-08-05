################################################################################
# build_npu_with_ila.tcl — Production NPU bitstream + ILA debugger core
#
# Identical to build_npu.tcl except an ILA core (u_npu_ila) is inserted
# post-synth, wired to every (* mark_debug = "true" *) net in the design.
# The matching .ltx probes file is written next to the bitstream so Vivado
# HW Manager can enumerate the probes interactively.
#
# Outputs:
#   ip/vivado/xc7k480t/top_npu_with_ila.bit
#   ip/vivado/xc7k480t/top_npu_with_ila.ltx
#
# Capture flow:
#   1. Build this bitstream and flash it (tool/hw/bringup_flash.py).
#   2. Use Vivado HW Manager (or its TCL API) to open the device, load the
#      .ltx file, set a trigger (e.g. state == 4'd6 = S_READ_ACC_R), arm the
#      ILA, then kick the NPU via PCIe ctrl_lite. After the trigger fires,
#      upload_hw_ila_data / write_hw_ila_data dumps a CSV waveform.
#
# Build time: same as build_npu.tcl (~25-75 min) — the ILA adds <1 min.
################################################################################

set SCRIPT_DIR [file normalize [file dirname [info script]]]
set MIGRATE    [file normalize $SCRIPT_DIR/..]
set RTL_SRC    [file normalize [file join $MIGRATE src]]
set REPO_ROOT  [file normalize $SCRIPT_DIR/../../../..]
set BIT_DST    [file join $MIGRATE top_npu_with_ila.bit]
set LTX_DST    [file join $MIGRATE top_npu_with_ila.ltx]

source [file join $SCRIPT_DIR migrate_lib.tcl]
source [file join $SCRIPT_DIR _apply_npu_topology.tcl]
source [file join $SCRIPT_DIR _apply_npu_ila.tcl]

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
    set ::_npu_save_mgmt_mode [get_property source_mgmt_mode [current_project]]
    set_property source_mgmt_mode All [current_project]
    update_compile_order -fileset sources_1
    save_bd
}

# ── Regenerate IP targets + re-launch synth_1 ───────────────────────────────
puts "INFO: regenerating IP targets..."
catch { generate_target all [get_files {*/top.bd}] } gt_err
if {$gt_err ne ""} { puts "WARNING: generate_target: $gt_err" }
make_wrapper -files [get_files {*/top.bd}] -top -force
set_property top top_wrapper [get_filesets sources_1]
update_compile_order -fileset sources_1

puts "INFO: launching synth_1 + OOC sub-runs..."
reset_run synth_1
launch_runs synth_1 -jobs [vivado_jobs]
set all_synth [get_runs -filter {IS_SYNTHESIS == 1}]
wait_on_run $all_synth

set prog [get_property PROGRESS [get_runs synth_1]]
if {$prog ne "100%"} { puts "ERROR: synth_1 failed"; exit 1 }
puts "INFO: synth_1 done."

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
puts "INFO: synth_1 opened."

# ── Insert ILA debugger core ────────────────────────────────────────────────
puts "INFO: inserting ILA debugger core (u_npu_ila)..."
insert_npu_ila

# Restore source_mgmt_mode for impl
npu_restore_mgmt_mode

# Write the merged checkpoint
set synth_dcp [file join [get_property DIRECTORY [get_runs synth_1]] top_wrapper.dcp]
write_checkpoint -force $synth_dcp
puts "INFO: merged synthesis checkpoint: [file size $synth_dcp] bytes"

set ::migrate_synth_was_launch_runs 1
run_impl_and_write_bit "npu_with_ila" $BIT_DST

# ── Emit the .ltx probes file next to the bitstream ─────────────────────────
catch { open_run impl_1 -name impl_1 }
set runs_dir [get_property DIRECTORY [get_runs impl_1]]
set ltx_src  [file join $runs_dir top_wrapper.ltx]
if {[catch {write_debug_probes -force $ltx_src} _err]} {
    puts "WARN: write_debug_probes: $_err"
} else {
    if {[file exists $ltx_src]} {
        file copy -force $ltx_src $LTX_DST
        puts "INFO: ILA probes file: $LTX_DST ([file size $LTX_DST] bytes)"
    }
}

puts ""
puts "*** build_npu_with_ila COMPLETE ***"
puts "INFO: Bitstream : $BIT_DST"
puts "INFO: .ltx file : $LTX_DST  (load into Vivado HW Manager alongside .bit)"
