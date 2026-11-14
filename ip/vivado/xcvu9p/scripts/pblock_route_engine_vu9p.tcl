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
set_param general.maxThreads 8

# Prefer the post-opt (pre-place) checkpoint; fall back to placed.
set CTX [file join $RUN_DIR hw_platform_opt.dcp]
if {![file exists $CTX]} { set CTX [file join $RUN_DIR hw_platform_placed.dcp] }
puts "INFO: opening checkpoint: $CTX"
open_checkpoint $CTX

# ── Pblock: the whole engine hierarchy into a single-SLR region ─────────────
# Add the hierarchical cell (UG909), not a flat IS_PRIMITIVE leaf list: cells
# created during opt_design/phys_opt (replicated/retimed registers) inherit the
# pblock membership only through the hierarchy.  A leaf snapshot leaves those
# new cells unconstrained, so they can land outside SLR0 and recreate long
# SLR-crossing nets.
set eng_hier [get_cells -quiet u_npu/u_engine]
if {$eng_hier eq ""} { puts "ERROR: u_npu/u_engine not found"; exit 1 }
puts "INFO: engine hierarchy cells: [llength [get_cells -quiet -hier -of_objects $eng_hier]]"
create_pblock pblock_engine
add_cells_to_pblock [get_pblocks pblock_engine] $eng_hier
# Place the whole engine in SLR0 (X0..X5, Y0..Y4 = 30 clock regions, ~394k
# LUTs for the ~177k-LUT engine).  The unconstrained placer spread it across
# SLR0 + Y5 of SLR1, straddling the SLR boundary and colliding with the
# XDMA/DDR (both in SLR1, Y5..Y9) -- the source of the routing congestion.
resize_pblock [get_pblocks pblock_engine] -add {CLOCKREGION_X0Y0:CLOCKREGION_X5Y4}
set_property IS_SOFT FALSE [get_pblocks pblock_engine]
# SNAPPING_MODE ROUTING is a 7-series/DFX setting; leave it off for a normal
# UltraScale+ hard pblock.
set_property SNAPPING_MODE OFF [get_pblocks pblock_engine]
# Put the 512-bit AXI clock converter (npu_cc) in SLR0 with the engine too.  It
# was scattered across 23 clock regions including Y5 (SLR1), making the
# engine->npu_cc path the critical one (4.66 ns route, 90%).  On the SLR0/SLR1
# side of the same pblock the s_axi flops sit near the engine and only the
# registered m_axi side crosses into SLR1 (the DDR).
set cc [get_cells -quiet u_npu_cc]
if {$cc ne ""} {
    add_cells_to_pblock [get_pblocks pblock_engine] $cc
    puts "INFO: added u_npu_cc to pblock_engine"
} else {
    puts "WARNING: u_npu_cc not found"
}
puts "INFO: pblock_engine grid = [get_property GRID_RANGES [get_pblocks pblock_engine]]"

# Floorplan the MMALU (systolic array + data feeder/collector) into a compact
# contiguous rectangle inside SLR0.  Under auto-placement the wide per-lane
# accumulator feeder pipes scatter across the SLR and the critical path becomes
# pure route (e.g. dfeed/buffer_a_29 -> buffer_a_18, 7.6 ns / 0 logic levels).
# This is the same MMALU-pblock remedy used in the xc7k480t flow.
set mmalu_cells [get_cells -quiet -hier -filter {NAME =~ *core/mmalu* && IS_PRIMITIVE == 1}]
if {[llength $mmalu_cells] > 0} {
    create_pblock pblock_mmalu
    add_cells_to_pblock [get_pblocks pblock_mmalu] $mmalu_cells
    resize_pblock [get_pblocks pblock_mmalu] -add {CLOCKREGION_X0Y0:CLOCKREGION_X4Y3}
    set_property IS_SOFT FALSE [get_pblocks pblock_mmalu]
    puts "INFO: MMALU cells=[llength $mmalu_cells] pblock_mmalu=[get_property GRID_RANGES [get_pblocks pblock_mmalu]]"
} else {
    puts "WARNING: no MMALU cells matched; skipping pblock_mmalu"
}

# NOTE: a nested accumulator-BRAM pblock (X2Y3:X3Y4) was tried to pull the
# accumMem write path closer to feedMmaLast_reg, but a hard box that small made
# the router congest (30,910 overlaps, Explore failed).  Do not re-add it
# without giving the placer more room; the engine-level SLR0 pblock is enough.

# ── Re-place with pessimistic long-net delay (UG904 ExtraNetDelay_high) ──────
# The ExtraTimingOpt placement met timing after place (-0.135) but failed after
# route (-0.226) because the placer's wire estimates were optimistic on long /
# high-fanout nets.  ExtraNetDelay_high raises those estimates so the placer
# minimises route length up front.
puts "INFO: place_design -directive ExtraTimingOpt ..."
set_param general.maxThreads 1
set_param place.disableMlde 1
place_design -directive ExtraTimingOpt
# puts "INFO: phys_opt_design -directive Explore ..."
# phys_opt_design -directive Explore

# ── Route: UG904 order Explore -> AggressiveExplore -> NoTimingRelaxation ────
# Explore reports signoff-based timing; AlternateCLBRouting is a congestion
# directive (not a timing fallback) and is only used if a route fails to
# legalize because of congestion.
set_param general.maxThreads 8
set ok 0
foreach d [list Explore AggressiveExplore NoTimingRelaxation] {
    puts "=== route_design -directive $d ==="
    if {[catch {route_design -directive $d} rerr]} {
        puts "WARNING: $d failed: $rerr"
        continue
    }
    set un [get_property UNROUTED_NETS [current_design]]
    puts "INFO: $d unrouted nets: [llength $un]"
    if {[llength $un] == 0} { set ok 1; break }
}
if {!$ok} {
    puts "=== congestion fallback: route_design -directive AlternateCLBRouting ==="
    if {![catch {route_design -directive AlternateCLBRouting} rerr]} {
        set un [get_property UNROUTED_NETS [current_design]]
        puts "INFO: AlternateCLBRouting unrouted nets: [llength $un]"
        if {[llength $un] == 0} { set ok 1 }
    }
}

report_timing_summary -file [file join $RUN_DIR vu9p_timing_pblock.rpt]
puts "INFO: timing report -> [file join $RUN_DIR vu9p_timing_pblock.rpt]"

if {!$ok} {
    puts "ERROR: routing failed even with the Pblock"
    exit 1
}

# Save the routed checkpoint, then run post-route physical optimization.
# This is the Performance_ExplorePostRoutePhysOpt mapping: post-route
# phys_opt_design -directive Explore (signoff timing).  UG904 notes a second
# run optimizes the results of the first, so follow with AggressiveExplore.
write_checkpoint -force [file join $RUN_DIR hw_platform_routed.dcp]
puts "INFO: post-route phys_opt_design -directive Explore ..."
phys_opt_design -directive Explore
report_timing_summary -max_paths 10 -file [file join $RUN_DIR vu9p_timing_postroute.rpt]
# puts "INFO: post-route phys_opt_design -directive AggressiveExplore (2nd pass) ..."
# phys_opt_design -directive AggressiveExplore
report_timing_summary -max_paths 10 -file [file join $RUN_DIR vu9p_timing_postroute.rpt]
puts "INFO: post-route timing -> [file join $RUN_DIR vu9p_timing_postroute.rpt]"

write_bitstream -force $BIT_DST
puts ""
puts "*** pblock_route_engine_vu9p COMPLETE: $BIT_DST ***"
