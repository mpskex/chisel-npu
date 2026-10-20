################################################################################
# last_mile.tcl — UG906 Stage 3 "Last Mile" timing closure on the planned
# xcvu9p routed checkpoint (entry: fully routed, WNS >= -0.250).
#
#   phys_opt_design -clock_opt -retime -lut_opt
#   place_design   -directive LastMile
#   phys_opt_design -directive Explore
#   route_design    -directive Explore
#   phys_opt_design -directive Explore
#   phys_opt_design -directive Explore
################################################################################
set SCRIPT_DIR [file normalize [file dirname [info script]]]
set XCVU9P     [file normalize [file join $SCRIPT_DIR ..]]
set RUN_DIR    [file join $XCVU9P prj prj.runs impl_1]
set BIT_DST    [file join $XCVU9P top_npu_vu9p.bit]
set CTX        [file join $RUN_DIR hw_platform_routed.dcp]

if {![file exists $CTX]} { puts "ERROR: missing $CTX"; exit 1 }
open_checkpoint $CTX

# ── Entry criteria ──────────────────────────────────────────────────────────
if {[report_route_status -quiet -boolean_check ROUTED_FULLY] == 0} {
    puts "ERROR: design is not fully routed"; exit 1
}
set _slack [get_property SLACK [get_timing_paths -setup -max_paths 1]]
puts "LASTMILE_ENTRY_WNS=$_slack"
if {$_slack < -0.250} {
    puts "ERROR: WNS $_slack < -0.250 (Last Mile entry criteria not met)"; exit 1
}
report_qor_assessment -max_paths 100 -file [file join $RUN_DIR vu9p_rqa_lastmile.rpt]

# ── Last Mile sequence (UG906) ──────────────────────────────────────────────
puts "INFO: phys_opt_design -clock_opt -retime -lut_opt"
phys_opt_design -clock_opt -retime -lut_opt
write_checkpoint -force [file join $RUN_DIR hw_platform_lastmile_preplace.dcp]

puts "INFO: place_design -directive LastMile"
place_design -directive LastMile
write_checkpoint -force [file join $RUN_DIR hw_platform_lastmile_placed.dcp]

puts "INFO: phys_opt_design -directive Explore (post-place)"
phys_opt_design -directive Explore

puts "INFO: route_design -directive Explore"
route_design -directive Explore

puts "INFO: phys_opt_design -directive Explore (post-route 1)"
phys_opt_design -directive Explore
puts "INFO: phys_opt_design -directive Explore (post-route 2)"
phys_opt_design -directive Explore

report_timing_summary -max_paths 20 -file [file join $RUN_DIR vu9p_timing_lastmile.rpt]
write_checkpoint -force [file join $RUN_DIR hw_platform_lastmile.dcp]
write_bitstream -force $BIT_DST
puts "*** last_mile COMPLETE: $BIT_DST ***"
