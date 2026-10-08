################################################################################
# pblock_route_engine_vu9p.tcl — floorplan the NPU engine into a compact Pblock
# and re-place + re-route the xcvu9p design to close the engine fabric timing.
#
# Root cause (202608): the streamed-issuing engine (NpuProgramEngineFrontend,
# W=8) spreads across 20 clock regions (CLOCKREGION_X0Y5..X3Y9) but occupies
# only ~13.5k sites; the critical path
#   core/slots_*_valid_reg -> core/slotsReady -> firstForUnit -> feedVs3 ->
#   rf read -> mmaluInAccum -> fifo_*_accum_reg
# is 82% ROUTE delay (6.09/7.44 ns) at 200 MHz.  Same class of problem the
# xc7k480t flow fixes with a Pblock around the MMALU: give the dense logic a
# clean rectangle so its interconnect routes in-channel.
#
# Reuses the post-synthesis checkpoint (no re-synth).  Opens hw_platform_opt.dcp,
# constrains the engine to a compact Pblock, re-places timing-first, routes
# aggressively, reports timing and writes the bitstream.
################################################################################

set SCRIPT_DIR [file normalize [file dirname [info script]]]
set XCVU9P     [file normalize [file join $SCRIPT_DIR ..]]
set RUN_DIR    [file join $XCVU9P prj prj.runs impl_1]
set BIT_DST    [file join $XCVU9P top_npu_vu9p.bit]

# Prefer the post-opt (pre-place) checkpoint; fall back to placed.
set CTX [file join $RUN_DIR hw_platform_opt.dcp]
if {![file exists $CTX]} { set CTX [file join $RUN_DIR hw_platform_placed.dcp] }
puts "INFO: opening checkpoint: $CTX"
open_checkpoint $CTX

# ── Pblock: all engine leaf cells into a compact single-SLR region ───────────
set eng_leaves [get_cells -quiet -hier -filter {NAME =~ u_npu/u_engine/* && IS_PRIMITIVE == 1}]
puts "INFO: engine primitive cells: [llength $eng_leaves]"
if {[llength $eng_leaves] == 0} {
    puts "ERROR: no engine cells matched u_npu/u_engine/*"
    exit 1
}
create_pblock pblock_engine
add_cells_to_pblock pblock_engine $eng_leaves
# Compact 4x3 clock regions (was 4x5): ~157k LUT capacity vs 62k engine LUTs.
resize_pblock pblock_engine -add {CLOCKREGION_X0Y5:CLOCKREGION_X3Y7}
set_property SNAPPING_MODE ROUTING [get_pblocks pblock_engine]
puts "INFO: pblock_engine grid = [get_property GRID_RANGES [get_pblocks pblock_engine]]"

# ── Re-place timing-first, then physical optimization ────────────────────────
puts "INFO: place_design -directive ExtraTimingOpt ..."
place_design -directive ExtraTimingOpt
puts "INFO: phys_opt_design -directive AggressiveExplore ..."
phys_opt_design -directive AggressiveExplore

# ── Route with aggressive timing directives until legal ──────────────────────
set ok 0
foreach d [list AggressiveExplore AlternateCLBRouting NoTimingRelaxation] {
    puts "=== route_design -directive $d ==="
    if {[catch {route_design -directive $d} rerr]} {
        puts "WARNING: $d failed: $rerr"
        continue
    }
    set un [get_property UNROUTED_NETS [current_design]]
    puts "INFO: $d unrouted nets: [llength $un]"
    if {[llength $un] == 0} { set ok 1; break }
}

report_timing_summary -file [file join $RUN_DIR vu9p_timing_pblock.rpt]
puts "INFO: timing report -> [file join $RUN_DIR vu9p_timing_pblock.rpt]"

if {!$ok} {
    puts "ERROR: routing failed even with the Pblock"
    exit 1
}

write_bitstream -force $BIT_DST
puts ""
puts "*** pblock_route_engine_vu9p COMPLETE: $BIT_DST ***"
