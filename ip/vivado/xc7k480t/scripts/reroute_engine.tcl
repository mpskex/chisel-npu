################################################################################
# reroute_engine.tcl — in-session reroute of the placed engine design with
# aggressive route directives.  Tries each directive until one routes legally.
################################################################################

set SCRIPT_DIR [file normalize [file dirname [info script]]]
set MIGRATE    [file normalize $SCRIPT_DIR/..]
set BIT_DST    [file join $MIGRATE top_npu_engine.bit]
set RUN_DIR    [file join $MIGRATE proj npu_migrate.runs impl_1]
set PLACED     [file join $RUN_DIR top_wrapper_physopt.dcp]

if {![file exists $PLACED]} { set PLACED [file join $RUN_DIR top_wrapper_placed.dcp] }
puts "INFO: using placed checkpoint: $PLACED"

open_checkpoint $PLACED

set directives [list AggressiveExplore AlternateCLBRouting]
set ok 0
foreach d $directives {
    puts "=== trying route_design -directive $d ==="
    if {[catch {route_design -directive $d} rerr]} {
        puts "WARNING: $d failed: $rerr"
        continue
    }
    set unrouted [get_property UNROUTED_NETS [current_design]]
    if {[llength $unrouted] > 0} {
        puts "WARNING: $d left [llength $unrouted] unrouted nets"
        continue
    }
    puts "INFO: $d routed legally!"
    set ok 1
    break
}

if {!$ok} {
    puts "ERROR: all route directives failed on this placement"
    exit 1
}

report_timing_summary -file [file join $RUN_DIR top_wrapper_timing_reroute.rpt]
report_utilization -file [file join $RUN_DIR top_wrapper_util_reroute.rpt]

write_bitstream -force $BIT_DST
puts ""
puts "*** reroute_engine COMPLETE: $BIT_DST ***"
