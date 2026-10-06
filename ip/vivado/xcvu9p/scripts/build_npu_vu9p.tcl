################################################################################
# build_npu_vu9p.tcl — NPU program-engine bitstream for xcvu9p-flgb2104-2-e
#
# Integrates the Chisel NpuProgramEngineFrontend (top.sv) with the Alivu9p
# reference platform (XDMA + 4x MIG DDR4 + axi_interconnect) via the
# NPU-integrated top `hw_platform` (ip/vivado/xcvu9p/src/hw_platform.v).
#
# Run `scripts/setup_platform.tcl` once first (DDR4 narrow burst, XDMA bypass,
# fabric MMCM, converter IPs).
#
# Usage:
#   vivado -mode batch -source ip/vivado/xcvu9p/scripts/build_npu_vu9p.tcl
#
# Output: ip/vivado/xcvu9p/top_npu_vu9p.bit
################################################################################

set SCRIPT_DIR [file normalize [file dirname [info script]]]
set XCVU9P     [file normalize $SCRIPT_DIR/..]
set REPO_ROOT  [file normalize [file join $XCVU9P ../../..]]
set SRC_DIR    [file join $XCVU9P src]
set PROJ       [file join $XCVU9P prj prj.xpr]
set BIT_DST    [file join $XCVU9P top_npu_vu9p.bit]

if {![file exists $PROJ]} { puts "ERROR: project not found: $PROJ"; exit 1 }
if {![file exists [file join $REPO_ROOT top.sv]]} { puts "ERROR: top.sv missing (run make build)"; exit 1 }

open_project $PROJ

# ── Add NPU RTL + Chisel top ────────────────────────────────────────────────
foreach f [list npu_engine_ctrl_lite.v npu_engine_subsys.v hw_platform.v] {
    set path [file join $SRC_DIR $f]
    if {[get_files -quiet -of_objects [get_filesets sources_1] $path] eq ""} {
        add_files -norecurse $path
    }
}
set top_sv [file join $REPO_ROOT top.sv]
if {[get_files -quiet -of_objects [get_filesets sources_1] $top_sv] eq ""} {
    add_files -norecurse $top_sv
}
set_property top hw_platform [get_filesets sources_1]
update_compile_order -fileset sources_1
puts "TOP=[get_property top [get_filesets sources_1]]"

set jobs 4
if {[info exists ::env(VIVADO_JOBS)]} { set jobs $::env(VIVADO_JOBS) }

# ── Synthesis (all runs, incl. OOC IP) ──────────────────────────────────────
set_param general.maxThreads 2
reset_run synth_1
launch_runs synth_1 -jobs 1
wait_on_run synth_1
set prog [get_property PROGRESS [get_runs synth_1]]
puts "SYNTH_PROGRESS=$prog"
if {$prog ne "100%"} { puts "SYNTH_STATUS=[get_property STATUS [get_runs synth_1]]"; exit 1 }

# Copy IP DCPs gen -> ip (link_design INBB-3 workaround)
foreach ip [get_ips -quiet] {
    set ip_dir     [get_property IP_DIR        [get_ips $ip]]
    set ip_out_dir [get_property IP_OUTPUT_DIR [get_ips $ip]]
    set src_dcp [file join $ip_out_dir ${ip}.dcp]
    set dst_dcp [file join $ip_dir     ${ip}.dcp]
    if {[file exists $src_dcp] && ![file exists $dst_dcp]} {
        file copy -force $src_dcp $dst_dcp
    }
}

# ── Implementation + bitstream ──────────────────────────────────────────────
set_param general.maxThreads 8
set_property STEPS.PLACE_DESIGN.ARGS.DIRECTIVE AltSpreadLogic_high [get_runs impl_1]
reset_run impl_1
launch_runs impl_1 -to_step write_bitstream -jobs $jobs
wait_on_run impl_1
set iprog [get_property PROGRESS [get_runs impl_1]]
puts "IMPL_PROGRESS=$iprog"
if {$iprog ne "100%"} { puts "IMPL_STATUS=[get_property STATUS [get_runs impl_1]]"; exit 1 }

foreach b [glob -nocomplain [file join $XCVU9P prj prj.runs impl_1 *.bit]] {
    file copy -force $b $BIT_DST
    puts "BIT=$BIT_DST"
}
puts "BUILD_DONE"
