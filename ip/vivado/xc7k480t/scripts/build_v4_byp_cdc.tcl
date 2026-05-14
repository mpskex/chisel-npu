################################################################################
# build_v4_byp_cdc.tcl  — Step V4: Move ctrl_lite to fabric_aclk via CDC
#
# Delta from V3:
#   - Add axi_clkconv_byp: AXI clock converter 250 MHz → 200 MHz on BYPASS path
#   - byp_pc M_AXI now clocked at 200 MHz (fabric_aclk)
#   - ctrl_lite axi_aclk = fabric_aclk (200 MHz)
#   - ctrl_lite reset now from a proc_sys_reset driven by clk_wiz_fabric/locked
#
# Hypothesis: An AXI clock converter on the BYPASS path does not affect
# PCIe cold-boot training.
#
# Success criterion: Same as V2/V3.
#
# Run:
#   vivado -mode batch -source .../build_v4_byp_cdc.tcl ...
################################################################################

set SCRIPT_DIR [file normalize [file dirname [info script]]]
set MIGRATE    [file normalize $SCRIPT_DIR/..]
set RTL_SRC    [file normalize [file join $MIGRATE src]]
set BIT_DST    [file join $MIGRATE top_v4_byp_cdc.bit]

source [file join $SCRIPT_DIR migrate_lib.tcl]
source [file join $SCRIPT_DIR _apply_v2.tcl]

open_ref_project
assert_synth_done
open_bd_design [get_files {*/top.bd}]
apply_v1_deletions
apply_v2_bypass_ctrl $RTL_SRC

# ── V3: fabric MMCM ──────────────────────────────────────────────────────────
create_bd_cell -type ip -vlnv xilinx.com:ip:clk_wiz:6.0 clk_wiz_fabric
set_property -dict [list \
    CONFIG.PRIM_IN_FREQ      {250.000} \
    CONFIG.CLKOUT1_REQUESTED_OUT_FREQ {200.000} \
    CONFIG.USE_RESET         {true}    \
    CONFIG.RESET_TYPE        {ACTIVE_LOW} \
    CONFIG.USE_LOCKED        {true}    \
    CONFIG.PRIMITIVE         {MMCM}    \
] [get_bd_cells clk_wiz_fabric]
connect_bd_net [get_bd_pins xdma_0/axi_aclk]    [get_bd_pins clk_wiz_fabric/clk_in1]
connect_bd_net [get_bd_pins xdma_0/axi_aresetn]  [get_bd_pins clk_wiz_fabric/resetn]

# ── V4: proc_sys_reset for 200 MHz domain ────────────────────────────────────
puts "=== V4: adding proc_sys_reset for fabric_aclk ==="
create_bd_cell -type ip -vlnv xilinx.com:ip:proc_sys_reset:5.0 rst_fabric_200M
connect_bd_net [get_bd_pins clk_wiz_fabric/clk_out1]  [get_bd_pins rst_fabric_200M/slowest_sync_clk]
connect_bd_net [get_bd_pins clk_wiz_fabric/locked]     [get_bd_pins rst_fabric_200M/dcm_locked]
# Drive ext_reset_in from board reset port
connect_bd_net [get_bd_ports reset_rtl_0] [get_bd_pins rst_fabric_200M/ext_reset_in]

# ── V4: AXI clock converter 250→200 on BYPASS path ───────────────────────────
puts "=== V4: inserting axi_clkconv_byp (250→200) ==="
create_bd_cell -type ip -vlnv xilinx.com:ip:axi_clock_converter:2.1 axi_clkconv_byp
# Re-route: XDMA BYPASS → axi_clkconv_byp (S side @250) → byp_dw (M side @200)
# First disconnect current XDMA BYPASS → byp_dw connection
delete_bd_objs [get_bd_intf_nets -quiet xdma_0_M_AXI_BYPASS]

connect_bd_net [get_bd_pins xdma_0/axi_aclk]                          [get_bd_pins axi_clkconv_byp/s_axi_aclk]
connect_bd_net [get_bd_pins xdma_0/axi_aresetn]                       [get_bd_pins axi_clkconv_byp/s_axi_aresetn]
connect_bd_net [get_bd_pins clk_wiz_fabric/clk_out1]                  [get_bd_pins axi_clkconv_byp/m_axi_aclk]
connect_bd_net [get_bd_pins rst_fabric_200M/peripheral_aresetn]       [get_bd_pins axi_clkconv_byp/m_axi_aresetn]

connect_bd_intf_net [get_bd_intf_pins xdma_0/M_AXI_BYPASS]           [get_bd_intf_pins axi_clkconv_byp/S_AXI]
connect_bd_intf_net [get_bd_intf_pins axi_clkconv_byp/M_AXI]         [get_bd_intf_pins byp_dw/S_AXI]

# Move byp_dw, byp_pc, ctrl_lite to 200 MHz clock/reset
foreach cell {byp_dw byp_pc ctrl_lite} {
    # disconnect 250 MHz aclk/aresetn from these cells
    disconnect_bd_net [get_bd_nets xdma_0_axi_aclk]    [get_bd_pins $cell/[expr {$cell eq "ctrl_lite" ? "axi_aclk" : ($cell eq "byp_dw" ? "s_axi_aclk" : "aclk")}]]
    disconnect_bd_net [get_bd_nets xdma_0_axi_aresetn]  [get_bd_pins $cell/[expr {$cell eq "ctrl_lite" ? "axi_aresetn" : ($cell eq "byp_dw" ? "s_axi_aresetn" : "aresetn")}]]
}
# Reconnect to 200 MHz
connect_bd_net [get_bd_pins clk_wiz_fabric/clk_out1]             \
    [get_bd_pins byp_dw/s_axi_aclk]                              \
    [get_bd_pins byp_pc/aclk]                                    \
    [get_bd_pins ctrl_lite/axi_aclk]
connect_bd_net [get_bd_pins rst_fabric_200M/peripheral_aresetn]  \
    [get_bd_pins byp_dw/s_axi_aresetn]                           \
    [get_bd_pins byp_pc/aresetn]                                  \
    [get_bd_pins ctrl_lite/axi_aresetn]

puts "INFO: V4 BD edits complete."
save_bd

puts "=== In-session synthesis ==="
run_synthesis

run_impl_and_write_bit "v4_byp_cdc" $BIT_DST
