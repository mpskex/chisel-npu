################################################################################
# build_impl_only.tcl — resume a completed synthesis into impl + bitstream.
# Reuses the existing synth_1 DCP (top.sv unchanged since synthesis); resets
# only impl_1.  Used after an interrupted build.
################################################################################
set SCRIPT_DIR [file normalize [file dirname [info script]]]
set XCVU9P     [file normalize $SCRIPT_DIR/..]
set PROJ       [file join $XCVU9P prj prj.xpr]
set BIT_DST    [file join $XCVU9P top_npu_vu9p.bit]

if {![file exists $PROJ]} { puts "ERROR: project not found: $PROJ"; exit 1 }
open_project $PROJ

set synth_prog [get_property PROGRESS [get_runs synth_1]]
puts "SYNTH_PROGRESS=$synth_prog"
if {$synth_prog ne "100%"} { puts "ERROR: synth_1 not complete"; exit 1 }

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

set jobs 4
if {[info exists ::env(VIVADO_JOBS)]} { set jobs $::env(VIVADO_JOBS) }
set_param general.maxThreads 8
set_property STEPS.PLACE_DESIGN.ARGS.DIRECTIVE ExtraTimingOpt [get_runs impl_1]
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
puts "IMPL_BUILD_DONE"
