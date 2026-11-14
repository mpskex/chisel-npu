################################################################################
# route_norelax.tcl — re-route the known-good ExtraTimingOpt placement without
# letting the router relax timing during legalization (UG904 NoTimingRelaxation
# / HigherDelayCost).  The Explore run reached WNS +0.118 before legalization
# and relaxed to -0.226; this tests whether legalization can keep the margin.
#
# Starts from the routed checkpoint, unrouting only (placement preserved).
################################################################################
set SCRIPT_DIR [file normalize [file dirname [info script]]]
set XCVU9P     [file normalize [file join $SCRIPT_DIR ..]]
set RUN_DIR    [file join $XCVU9P prj prj.runs impl_1]
set BIT_DST    [file join $XCVU9P top_npu_vu9p.bit]
set CTX        [file join $RUN_DIR hw_platform_routed.dcp]

if {![file exists $CTX]} { puts "ERROR: missing $CTX"; exit 1 }
open_checkpoint $CTX

report_route_status -quiet -boolean_check ROUTED_FULLY
set _before [get_property SLACK [get_timing_paths -setup -max_paths 1]]
puts "ROUTE_BEFORE_WNS=$_before"

set ok 0
foreach d [list NoTimingRelaxation HigherDelayCost MoreGlobalIterations AggressiveExplore] {
    puts "=== unroute + route_design -directive $d ==="
    if {[catch {route_design -unroute} e]} { puts "unroute failed: $e"; continue }
    if {[catch {route_design -directive $d} e]} {
        puts "WARNING: $d failed: $e"
        continue
    }
    set un [get_property UNROUTED_NETS [current_design]]
    set wns [get_property SLACK [get_timing_paths -setup -max_paths 1]]
    puts "INFO: $d unrouted=[llength $un] WNS=$wns"
    if {[llength $un] == 0} { set ok 1; break }
}

report_timing_summary -max_paths 20 -file [file join $RUN_DIR vu9p_timing_norelax.rpt]
if {!$ok} { puts "ERROR: no legal route without timing relaxation"; exit 1 }

puts "INFO: post-route phys_opt_design -directive Explore x2"
phys_opt_design -directive Explore
phys_opt_design -directive Explore
report_timing_summary -max_paths 20 -file [file join $RUN_DIR vu9p_timing_norelax_final.rpt]
write_checkpoint -force [file join $RUN_DIR hw_platform_norelax.dcp]
write_bitstream -force $BIT_DST
puts "*** route_norelax COMPLETE: $BIT_DST ***"
