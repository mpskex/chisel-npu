################################################################################
# build_v0_baseline.tcl  — Step V0: Reference design, no changes
#
# Purpose: Re-confirm that the reference project (opened in-place) still
# passes 9/9 smoke tests.  This is the baseline before any migration deltas.
#
# Success criterion: PCIe enumerates, DDR3 C0 loopback passes (same as
# what was previously proven with top_wrapper.bit).
#
# Run from repo root:
#   ~/Xilinx/2025.2/Vivado/bin/vivado -mode batch \
#       -source ip/vivado/xc7k480t/scripts/build_v0_baseline.tcl \
#       -journal ip/vivado/xc7k480t/scripts/build_v0_baseline.jou \
#       -log     ip/vivado/xc7k480t/scripts/build_v0_baseline.log
#
# Then flash and test:
#   tool/hw/program_flash.sh ip/vivado/xc7k480t/top_v0_baseline.bit
#   python3 tool/hw/bringup_flash.py --skip-flash \
#       ip/vivado/xc7k480t/top_v0_baseline.bit
################################################################################

set SCRIPT_DIR [file normalize [file dirname [info script]]]
set MIGRATE    [file normalize $SCRIPT_DIR/..]
set BIT_DST    [file join $MIGRATE top_v0_baseline.bit]

source [file join $SCRIPT_DIR migrate_lib.tcl]

open_ref_project
assert_synth_done

# No BD changes — just re-impl and write bitstream
run_impl_and_write_bit "v0_baseline" $BIT_DST
