################################################################################
# rerun_impl_engine.tcl — impl-only rerun for the engine build with a
# congestion-focused strategy (the router failed with 6901 overlaps under
# Performance_Explore).  Reuses the completed synth DCPs.
################################################################################

set SCRIPT_DIR [file normalize [file dirname [info script]]]
set MIGRATE    [file normalize $SCRIPT_DIR/..]
set BIT_DST    [file join $MIGRATE top_npu_engine.bit]

source [file join $SCRIPT_DIR migrate_lib.tcl]

open_ref_project

if {![info exists ::env(VIVADO_IMPL_STRATEGY)] || $::env(VIVADO_IMPL_STRATEGY) eq ""} {
    set ::env(VIVADO_IMPL_STRATEGY) "Congestion_SpreadLogic_high"
}
puts "INFO: impl strategy = $::env(VIVADO_IMPL_STRATEGY)"

set ::migrate_synth_was_launch_runs 1
run_impl_and_write_bit "npu_engine_rerun" $BIT_DST

puts ""
puts "*** rerun_impl_engine COMPLETE ***"
