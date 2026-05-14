################################################################################
# build_v3_mmcm.tcl  — Step V3: Add fabric MMCM (200 MHz, unloaded)
#
# Delta from V2:
#   - Add clk_wiz_fabric (Vivado clk_wiz IP) generating 200 MHz fabric_aclk
#     from xdma_0/axi_aclk (250 MHz input)
#   - MMCM output is not connected to any logic yet (just instantiated)
#   - locked output tied to nothing for now
#
# Hypothesis: The MMCM alone (running, no load) does not disturb startup
# sequencing or PCIe cold-boot training.
#
# Success criterion: Same as V2 — PCIe, DDR3 C0, bypass_bar_accessible.
#
# Run from repo root:
#   ~/Xilinx/2025.2/Vivado/bin/vivado -mode batch \
#       -source ip/vivado/xc7k480t/scripts/build_v3_mmcm.tcl \
#       -journal ip/vivado/xc7k480t/scripts/build_v3_mmcm.jou \
#       -log     ip/vivado/xc7k480t/scripts/build_v3_mmcm.log
################################################################################

set SCRIPT_DIR [file normalize [file dirname [info script]]]
set MIGRATE    [file normalize $SCRIPT_DIR/..]
set RTL_SRC    [file normalize [file join $MIGRATE src]]
set BIT_DST    [file join $MIGRATE top_v3_mmcm.bit]

source [file join $SCRIPT_DIR migrate_lib.tcl]
source [file join $SCRIPT_DIR _apply_v2.tcl]

open_ref_project
assert_synth_done
open_bd_design [get_files {*/top.bd}]
apply_v1_deletions
apply_v2_bypass_ctrl $RTL_SRC

# ── V3: Add fabric MMCM ───────────────────────────────────────────────────────
puts "=== V3: adding fabric MMCM (200 MHz, unloaded) ==="

create_bd_cell -type ip -vlnv xilinx.com:ip:clk_wiz:6.0 clk_wiz_fabric
set_property -dict [list \
    CONFIG.PRIM_IN_FREQ      {250.000} \
    CONFIG.CLKOUT1_REQUESTED_OUT_FREQ {200.000} \
    CONFIG.USE_RESET         {true}    \
    CONFIG.RESET_TYPE        {ACTIVE_LOW} \
    CONFIG.USE_LOCKED        {true}    \
    CONFIG.PRIMITIVE         {MMCM}    \
] [get_bd_cells clk_wiz_fabric]

# Clock input: xdma_0/axi_aclk (250 MHz)
connect_bd_net [get_bd_pins xdma_0/axi_aclk]    [get_bd_pins clk_wiz_fabric/clk_in1]
# Reset input: xdma_0/axi_aresetn (active-low; clk_wiz uses resetn)
connect_bd_net [get_bd_pins xdma_0/axi_aresetn]  [get_bd_pins clk_wiz_fabric/resetn]
# clk_out1 (200 MHz) and locked are left unconnected

puts "INFO: V3 BD edits complete."
save_bd

puts "=== In-session synthesis ==="
run_synthesis

run_impl_and_write_bit "v3_mmcm" $BIT_DST
