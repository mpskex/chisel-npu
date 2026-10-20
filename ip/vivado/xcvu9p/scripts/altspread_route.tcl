################################################################################
# altspread_route.tcl — place with AltSpreadLogic_high (congestion-oriented,
# the README's known-good strategy for this NPU bus layout), then route with
# escalating directives until the design is legally routed.
#
# Reuses the post-opt checkpoint (no re-synth).
################################################################################
set SCRIPT_DIR [file normalize [file dirname [info script]]]
set XCVU9P     [file normalize [file join $SCRIPT_DIR ..]]
set RUN_DIR    [file join $XCVU9P prj prj.runs impl_1]
set BIT_DST    [file join $XCVU9P top_npu_vu9p.bit]
set CTX        [file join $RUN_DIR hw_platform_opt.dcp]

if {![file exists $CTX]} { puts "ERROR: missing $CTX"; exit 1 }
open_checkpoint $CTX

puts "INFO: place_design -directive AltSpreadLogic_high"
place_design -directive AltSpreadLogic_high
puts "INFO: phys_opt_design -directive AggressiveExplore"
phys_opt_design -directive AggressiveExplore
report_timing_summary -max_paths 5 -file [file join $RUN_DIR vu9p_timing_altspread_placed.rpt]

set ok 0
foreach d [list AggressiveExplore AlternateCLBRouting Default] {
    puts "=== route_design -directive $d ==="
    if {[catch {route_design -directive $d} rerr]} {
        puts "WARNING: $d failed: $rerr"
        continue
    }
    set un [get_property UNROUTED_NETS [current_design]]
    puts "INFO: $d unrouted nets: [llength $un]"
    if {[llength $un] == 0} { set ok 1; break }
}

report_timing_summary -max_paths 10 -file [file join $RUN_DIR vu9p_timing_altspread.rpt]
puts "INFO: timing report -> [file join $RUN_DIR vu9p_timing_altspread.rpt]"
if {!$ok} { puts "ERROR: routing failed with AltSpreadLogic_high"; exit 1 }

write_bitstream -force $BIT_DST
puts "*** altspread_route COMPLETE: $BIT_DST ***"
