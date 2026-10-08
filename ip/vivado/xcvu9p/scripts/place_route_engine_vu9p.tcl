################################################################################
# place_route_engine_vu9p.tcl — timing-driven re-place + route of the xcvu9p
# design WITHOUT a Pblock (free routability), to close the engine fabric timing.
#
# The Pblock variant (pblock_route_engine_vu9p.tcl) closed setup at phys_opt
# (WNS +0.037, from -2.314) but the 4x3 clock-region Pblock then made routing
# congested/unroutable.  This variant keeps the effective part — the
# ExtraTimingOpt timing-driven placement — and leaves the router unconstrained.
################################################################################

set SCRIPT_DIR [file normalize [file dirname [info script]]]
set XCVU9P     [file normalize [file join $SCRIPT_DIR ..]]
set RUN_DIR    [file join $XCVU9P prj prj.runs impl_1]
set BIT_DST    [file join $XCVU9P top_npu_vu9p.bit]

set CTX [file join $RUN_DIR hw_platform_opt.dcp]
if {![file exists $CTX]} { set CTX [file join $RUN_DIR hw_platform_placed.dcp] }
puts "INFO: opening checkpoint: $CTX"
open_checkpoint $CTX

puts "INFO: place_design -directive ExtraTimingOpt ..."
place_design -directive ExtraTimingOpt
puts "INFO: phys_opt_design -directive AggressiveExplore ..."
phys_opt_design -directive AggressiveExplore

set ok 0
foreach d [list AggressiveExplore AlternateCLBRouting MoreGlobalIterations] {
    puts "=== route_design -directive $d ==="
    if {[catch {route_design -directive $d} rerr]} {
        puts "WARNING: $d failed: $rerr"
        continue
    }
    set un [get_property UNROUTED_NETS [current_design]]
    puts "INFO: $d unrouted nets: [llength $un]"
    if {[llength $un] == 0} { set ok 1; break }
}

report_timing_summary -file [file join $RUN_DIR vu9p_timing_place_route.rpt]
puts "INFO: timing report -> [file join $RUN_DIR vu9p_timing_place_route.rpt]"

if {!$ok} {
    puts "ERROR: routing failed"
    exit 1
}

write_bitstream -force $BIT_DST
puts ""
puts "*** place_route_engine_vu9p COMPLETE: $BIT_DST ***"
