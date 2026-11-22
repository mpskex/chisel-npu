################################################################################
# impl_1thread_noml.tcl — known-good fallback implementation
#
# ML estimators off, placement single-threaded, physical optimization and
# routing at 2 threads.  This is the recipe that produced the measured
# 2026-11-13 A+B+P bitstream (WNS +0.056 ns, 0 unrouted nets).  Use it when
# the parallel ML-on flow cannot get a clean route through Vivado 2025.2's
# intermittent SIGSEGVs.
#
# Prerequisite: hw_platform_opt.dcp from build_npu_vu9p.tcl (or
# NPU_STOP_AFTER_OPT=1).  Output: ip/vivado/xcvu9p/top_npu_vu9p.bit.
################################################################################

set SCRIPT_DIR [file normalize [file dirname [info script]]]
set XCVU9P     [file normalize [file join $SCRIPT_DIR ..]]
set REPO_ROOT  [file normalize [file join $XCVU9P ../../..]]
set RUN_DIR    [file join $XCVU9P prj prj.runs impl_1]
set BIT_DST    [file join $XCVU9P top_npu_vu9p.bit]

set CTX [file join $RUN_DIR hw_platform_opt.dcp]
if {![file exists $CTX]} { puts "ERROR: missing post-opt checkpoint: $CTX"; exit 1 }
puts "INFO: opening checkpoint: $CTX"
open_checkpoint $CTX

set CDC_XDC [file join $REPO_ROOT ip vivado xcvu9p src cdc.xdc]
if {[file exists $CDC_XDC]} {
    read_xdc $CDC_XDC
    puts "INFO: read CDC constraints: $CDC_XDC"
} else {
    puts "WARNING: missing CDC constraints: $CDC_XDC"
}

set_param general.maxThreads 2
set_param place.3d.congestionML 0
set_param place.disableMlde 1
# The ML-congestion predictor's LgbmTree destructor can segfault in a GOMP
# worker thread (HCMBoostTreeEstimator::~HCMBoostTreeEstimator).  Placement is
# single-threaded here; phys_opt/route keep 2 threads.
set_param general.maxThreads 1
puts "INFO: place_design -directive ExtraTimingOpt (ML off, 1 thread)"
place_design -directive ExtraTimingOpt
set_param general.maxThreads 2
write_checkpoint -force [file join $RUN_DIR hw_platform_placed.dcp]

puts "INFO: phys_opt_design (default, 2 threads)"
phys_opt_design
write_checkpoint -force [file join $RUN_DIR hw_platform_physopt.dcp]

puts "INFO: route_design (default, 2 threads)"
route_design

set un [get_property UNROUTED_NETS [current_design]]
puts "INFO: unrouted nets: [llength $un]"
if {[llength $un] != 0} { puts "ERROR: unrouted nets remain"; exit 1 }

set ts_rpt [file join $REPO_ROOT build latest_timing_summary.rpt]
set drc_rpt [file join $REPO_ROOT build latest_drc.routed.rpt]
report_timing_summary -max_paths 20 -file $ts_rpt
report_drc -file $drc_rpt

set fh [open $ts_rpt r]
set txt [read $fh]
close $fh
set wns ""
if {[regexp {WNS\(ns\)[^\n]*TNS\(ns\)[^\n]*\n[^\n]*\n\s*([-+0-9.eE]+)} $txt -> w]} {
    set wns $w
}
puts "POSTROUTE_WNS=$wns"
if {![string is double -strict $wns]} { puts "ERROR: no WNS"; exit 1 }
if {$wns < -1.0} {
    puts "ERROR: WNS $wns below the -1.0 ns gate; not publishing a bitstream"
    exit 1
}
write_bitstream -force $BIT_DST
puts "*** IMPL_1THREAD_NOML COMPLETE: $BIT_DST (WNS=$wns) ***"
