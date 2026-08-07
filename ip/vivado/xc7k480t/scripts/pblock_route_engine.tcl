################################################################################
# pblock_route_engine.tcl — floorplan the MMALU into a contiguous Pblock and
# re-place + route the engine design.
#
# The MMALU (105K LUTs / 139K FFs, 300K cells) spreads across the whole die
# under auto-placement; the routers give up with ~7-10K node overlaps.  A
# Pblock gives the systolic array a clean rectangle so its dense local
# interconnect routes in-channel.
################################################################################

set SCRIPT_DIR [file normalize [file dirname [info script]]]
set MIGRATE    [file normalize $SCRIPT_DIR/..]
set BIT_DST    [file join $MIGRATE top_npu_engine.bit]
set RUN_DIR    [file join $MIGRATE proj npu_migrate.runs impl_1]
set PLACED     [file join $RUN_DIR top_wrapper_physopt.dcp]

puts "INFO: opening placed checkpoint: $PLACED"
open_checkpoint $PLACED

set mmalu_cells [get_cells -hier -filter {NAME =~ *core/mmalu*}]
puts "INFO: MMALU cells: [llength $mmalu_cells]"

# Clean rectangle for the MMALU (device slice grid X0..189, Y86..399).
# 96 cols × 156 rows ≈ 15K slices ≈ 120K LUT / 240K FF capacity.
create_pblock pblock_mmalu
add_cells_to_pblock pblock_mmalu $mmalu_cells
resize_pblock pblock_mmalu -add {SLICE_X0Y86:SLICE_X94Y399}
set_property SNAPPING_MODE ROUTING [get_pblocks pblock_mmalu]
puts "INFO: pblock_mmalu = [get_property RANGE [get_pblocks pblock_mmalu]]"

# Re-place with the floorplan, then route.
place_design -directive SpreadLogic_high
puts "INFO: placement done"

set ok 0
foreach d [list AggressiveExplore AlternateCLBRouting] {
    puts "=== route_design -directive $d ==="
    if {[catch {route_design -directive $d} rerr]} {
        puts "WARNING: $d failed: $rerr"
        continue
    }
    set unrouted [get_property UNROUTED_NETS [current_design]]
    puts "INFO: $d unrouted nets: [llength $unrouted]"
    if {[llength $unrouted] == 0} {
        set ok 1
        break
    }
}

if {!$ok} {
    puts "ERROR: routing failed even with the Pblock"
    exit 1
}

report_timing_summary -file [file join $RUN_DIR top_wrapper_timing_pblock.rpt]
write_bitstream -force $BIT_DST
puts ""
puts "*** pblock_route_engine COMPLETE: $BIT_DST ***"
