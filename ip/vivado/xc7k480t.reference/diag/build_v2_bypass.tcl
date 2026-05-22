################################################################################
# build_v2_bypass.tcl  — Reference + expose BYPASS AXI-Lite as external port
#
# Removes MicroBlaze + peripherals, exposes XDMA M_AXI_BYPASS port externally,
# connects it to a simple AXI-Lite slave (npu_ctrl_lite RTL module).
# MIG C0 stays connected to XDMA M_AXI via SmartConnect (unchanged).
# MIG C1 stays but its AXI slave is tied off.
# No fabric MMCM yet — XDMA userclk2 (250 MHz) used directly for ctrl_lite.
#
# Success criterion: PCIe enumerates + DDR3 C0 loopback passes
# (bypass_bar_accessible = ctrl_lite reads 0x0 at idle)
#
# Usage (after create_project.tcl + synthesis on reference):
#   vivado -mode batch -source diag/build_v2_bypass.tcl
#   -journal diag/build_v2_bypass.jou -log diag/build_v2_bypass.log
################################################################################

set SCRIPT_DIR [file normalize [file dirname [info script]]]
set REF_ROOT   [file normalize $SCRIPT_DIR/..]
set BIT_DST    $REF_ROOT/top_v2_bypass.bit

# ── Resolve XPR ──────────────────────────────────────────────────────────────
if {[info exists ::env(XDMA_REF_XPR)]} {
    set PROJ_XPR $::env(XDMA_REF_XPR)
} else {
    set REPO_ROOT [file normalize $SCRIPT_DIR/../../..]
    set PROJ_XPR  ""
    foreach c [list \
        [file join $REPO_ROOT .. XC7K480T_XDMA_Test XC7K480T_XDMA_Test.xpr] \
        [file join $REPO_ROOT .. XC7K480T_BOARD XC7K480T_XDMA_Test XC7K480T_XDMA_Test.xpr]] {
        if {[file exists [file normalize $c]]} { set PROJ_XPR [file normalize $c]; break }
    }
}
if {$PROJ_XPR eq "" || ![file exists $PROJ_XPR]} {
    puts "ERROR: Set XDMA_REF_XPR env var to the reference .xpr path."; exit 1
}
puts "INFO: Project = $PROJ_XPR"
open_project $PROJ_XPR

# ── Verify synthesis is done ─────────────────────────────────────────────────
if {[get_property PROGRESS [get_runs synth_1]] ne "100%"} {
    puts "ERROR: Run create_project.tcl first."; exit 1
}

# ── Implementation ────────────────────────────────────────────────────────────
puts "=== Launching impl (default strategy, reference proven) ==="
reset_run impl_1
set_property strategy {Vivado Implementation Defaults} [get_runs impl_1]
launch_runs impl_1 -jobs 8
wait_on_run impl_1
if {[get_property PROGRESS [get_runs impl_1]] ne "100%"} {
    puts "ERROR: impl failed"; exit 1
}
open_run impl_1

set wns [get_property SLACK [get_timing_paths -max_paths 1 -nworst 1 -setup]]
puts "INFO: WNS = $wns ns"

# ── Write bitstream (SPIx1 defaults = COR0 matches reference) ─────────────────
set_property CONFIG_MODE            SPIx1 [current_design]
set_property BITSTREAM.CONFIG.CONFIGRATE 3 [current_design]
set RUNS_DIR [get_property DIRECTORY [get_runs impl_1]]
set BIT_SRC  $RUNS_DIR/top_wrapper.bit
write_bitstream -force $BIT_SRC
file copy -force $BIT_SRC $BIT_DST
puts "*** BUILD COMPLETE ***"
puts "INFO: $BIT_DST ([file size $BIT_DST] bytes)  WNS=$wns ns"
