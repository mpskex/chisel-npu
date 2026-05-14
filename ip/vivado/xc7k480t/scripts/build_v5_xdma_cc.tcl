################################################################################
# build_v5_xdma_cc.tcl  — Step V5: Insert axi_cc_xdma_in on XDMA→MIG data path
#
# Delta from V4:
#   - Add axi_cc_xdma_in: AXI clock converter 250 MHz → 200 MHz on the
#     XDMA M_AXI → axi_smc path (the main DDR3 data path)
#   - axi_smc S00_AXI now clocked at 200 MHz (fabric_aclk)
#   - axi_smc/aresetn and aclk driven from 200 MHz domain
#
# Hypothesis: Introducing the first clock converter on the DDR3 data path
# does not break PCIe training (it only affects fabric logic, not PCIe startup).
#
# Success criterion: PCIe + DDR3 C0 loopback still pass.
#
# Run:
#   vivado -mode batch -source .../build_v5_xdma_cc.tcl ...
################################################################################

set SCRIPT_DIR [file normalize [file dirname [info script]]]
set MIGRATE    [file normalize $SCRIPT_DIR/..]
set RTL_SRC    [file normalize [file join $MIGRATE src]]
set BIT_DST    [file join $MIGRATE top_v5_xdma_cc.bit]

source [file join $SCRIPT_DIR migrate_lib.tcl]
source [file join $SCRIPT_DIR _apply_v2.tcl]

open_ref_project
assert_synth_done
open_bd_design [get_files {*/top.bd}]
apply_v1_deletions
apply_v2_bypass_ctrl $RTL_SRC

# ── V3 MMCM ──────────────────────────────────────────────────────────────────
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

# ── V4 proc_sys_reset + BYPASS CDC ───────────────────────────────────────────
create_bd_cell -type ip -vlnv xilinx.com:ip:proc_sys_reset:5.0 rst_fabric_200M
connect_bd_net [get_bd_pins clk_wiz_fabric/clk_out1]  [get_bd_pins rst_fabric_200M/slowest_sync_clk]
connect_bd_net [get_bd_pins clk_wiz_fabric/locked]     [get_bd_pins rst_fabric_200M/dcm_locked]
connect_bd_net [get_bd_ports reset_rtl_0]              [get_bd_pins rst_fabric_200M/ext_reset_in]

create_bd_cell -type ip -vlnv xilinx.com:ip:axi_clock_converter:2.1 axi_clkconv_byp
delete_bd_objs [get_bd_intf_nets -quiet xdma_0_M_AXI_BYPASS]
connect_bd_net [get_bd_pins xdma_0/axi_aclk]                         [get_bd_pins axi_clkconv_byp/s_axi_aclk]
connect_bd_net [get_bd_pins xdma_0/axi_aresetn]                      [get_bd_pins axi_clkconv_byp/s_axi_aresetn]
connect_bd_net [get_bd_pins clk_wiz_fabric/clk_out1]                 [get_bd_pins axi_clkconv_byp/m_axi_aclk]
connect_bd_net [get_bd_pins rst_fabric_200M/peripheral_aresetn]      [get_bd_pins axi_clkconv_byp/m_axi_aresetn]
connect_bd_intf_net [get_bd_intf_pins xdma_0/M_AXI_BYPASS]          [get_bd_intf_pins axi_clkconv_byp/S_AXI]
connect_bd_intf_net [get_bd_intf_pins axi_clkconv_byp/M_AXI]        [get_bd_intf_pins byp_dw/S_AXI]

foreach {cell pin_aclk pin_aresetn} {
    byp_dw    s_axi_aclk    s_axi_aresetn
    byp_pc    aclk          aresetn
    ctrl_lite axi_aclk      axi_aresetn
} {
    disconnect_bd_net [get_bd_nets xdma_0_axi_aclk]   [get_bd_pins $cell/$pin_aclk]
    disconnect_bd_net [get_bd_nets xdma_0_axi_aresetn] [get_bd_pins $cell/$pin_aresetn]
}
connect_bd_net [get_bd_pins clk_wiz_fabric/clk_out1]            \
    [get_bd_pins byp_dw/s_axi_aclk] [get_bd_pins byp_pc/aclk]  \
    [get_bd_pins ctrl_lite/axi_aclk]
connect_bd_net [get_bd_pins rst_fabric_200M/peripheral_aresetn] \
    [get_bd_pins byp_dw/s_axi_aresetn] [get_bd_pins byp_pc/aresetn] \
    [get_bd_pins ctrl_lite/axi_aresetn]

# ── V5: axi_cc_xdma_in on XDMA M_AXI → axi_smc path ────────────────────────
puts "=== V5: inserting axi_cc_xdma_in (250→200) on DDR3 data path ==="
create_bd_cell -type ip -vlnv xilinx.com:ip:axi_clock_converter:2.1 axi_cc_xdma_in

# Disconnect existing xdma_0_M_AXI → axi_smc/S00_AXI
delete_bd_objs [get_bd_intf_nets -quiet xdma_0_M_AXI]

# S side: 250 MHz (xdma_0/axi_aclk)
connect_bd_net [get_bd_pins xdma_0/axi_aclk]    [get_bd_pins axi_cc_xdma_in/s_axi_aclk]
connect_bd_net [get_bd_pins xdma_0/axi_aresetn]  [get_bd_pins axi_cc_xdma_in/s_axi_aresetn]
# M side: 200 MHz (fabric_aclk)
connect_bd_net [get_bd_pins clk_wiz_fabric/clk_out1]              [get_bd_pins axi_cc_xdma_in/m_axi_aclk]
connect_bd_net [get_bd_pins rst_fabric_200M/peripheral_aresetn]   [get_bd_pins axi_cc_xdma_in/m_axi_aresetn]

connect_bd_intf_net [get_bd_intf_pins xdma_0/M_AXI]             [get_bd_intf_pins axi_cc_xdma_in/S_AXI]
connect_bd_intf_net [get_bd_intf_pins axi_cc_xdma_in/M_AXI]     [get_bd_intf_pins axi_smc/S00_AXI]

# Move axi_smc aclk/aresetn to 200 MHz
disconnect_bd_net [get_bd_nets xdma_0_axi_aclk]   [get_bd_pins axi_smc/aclk]
disconnect_bd_net [get_bd_nets -quiet rst_mig_7series_0_133M_peripheral_aresetn] \
    [get_bd_pins axi_smc/aresetn]
connect_bd_net [get_bd_pins clk_wiz_fabric/clk_out1]            [get_bd_pins axi_smc/aclk]
connect_bd_net [get_bd_pins rst_fabric_200M/peripheral_aresetn] [get_bd_pins axi_smc/aresetn]

puts "INFO: V5 BD edits complete."
save_bd

puts "=== In-session synthesis ==="
run_synthesis

run_impl_and_write_bit "v5_xdma_cc" $BIT_DST
