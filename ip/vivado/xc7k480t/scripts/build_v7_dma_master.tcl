################################################################################
# build_v7_dma_master.tcl — Step V7: Add npu_dma_master → MIG C1
#
# Delta from V6 (no-SMC flat chain, MIG C1 tied off with VIP):
#   - Remove mig_c1_data_vip (C1 tie-off)
#   - Add axi_clkconv_npu (200→133) for DMA master → MIG C1 data path
#   - Add axi_dwidth_npu (128→512) for DMA master → MIG C1 data path
#   - Instantiate npu_dma_master as AXI4 master into MIG C1
#   - Wire ctrl_lite/start → dma_master/start; done/busy → ctrl_lite
#
# Hypothesis: DMA master logic accessing MIG C1 does not affect PCIe training.
#
# Success criterion:
#   - PCIe enumerates, DDR3 C0 loopback passes
#   - bypass_bar_accessible passes (ctrl_lite still present)
#
# Run:
#   XDMA_REF_XPR=... vivado -mode batch -source .../build_v7_dma_master.tcl ...
################################################################################

set SCRIPT_DIR [file normalize [file dirname [info script]]]
set MIGRATE    [file normalize $SCRIPT_DIR/..]
set RTL_SRC    [file normalize [file join $MIGRATE src]]
set BIT_DST    [file join $MIGRATE top_v7_dma_master.bit]

source [file join $SCRIPT_DIR migrate_lib.tcl]
source [file join $SCRIPT_DIR _apply_v2.tcl]
source [file join $SCRIPT_DIR _apply_v5.tcl]
source [file join $SCRIPT_DIR _apply_v6.tcl]

open_ref_project
assert_synth_done

# Add DMA master RTL source
set dma_v [file join $RTL_SRC npu_dma_master.v]
if {![file exists $dma_v]} { puts "ERROR: $dma_v not found"; exit 1 }
add_files -norecurse $dma_v
update_compile_order -fileset sources_1

open_bd_design [get_files {*/top.bd}]
apply_v1_deletions
apply_v2_bypass_ctrl $RTL_SRC
apply_v3_mmcm_and_rst
apply_v4_byp_cdc
apply_v5_xdma_cc
apply_v6r_remove_smc

# ── V7: Replace mig_c1_data_vip with npu_dma_master ─────────────────────────
puts "=== V7: adding npu_dma_master → MIG C1 ==="

# Remove C1 VIP tie-off (installed by V6r)
set vip [get_bd_cells -quiet mig_c1_data_vip]
if {$vip ne ""} {
    foreach n [get_bd_intf_nets -quiet -of_objects $vip] { delete_bd_objs $n }
    foreach n [get_bd_nets -quiet -of_objects $vip] { catch { delete_bd_objs $n } }
    delete_bd_objs $vip
}
puts "INFO: mig_c1_data_vip removed."

# axi_clock_converter 200→133 for DMA master → MIG C1
create_bd_cell -type ip -vlnv xilinx.com:ip:axi_clock_converter:2.1 axi_clkconv_npu
connect_bd_net [get_bd_pins clk_wiz_fabric/clk_out1]            [get_bd_pins axi_clkconv_npu/s_axi_aclk]
connect_bd_net [get_bd_pins rst_fabric_200M/peripheral_aresetn] [get_bd_pins axi_clkconv_npu/s_axi_aresetn]
connect_bd_net [get_bd_pins mig_7series_0/c1_ui_clk]            [get_bd_pins axi_clkconv_npu/m_axi_aclk]
connect_bd_net [get_bd_pins rst_mig_7series_0_133M_1/peripheral_aresetn] \
               [get_bd_pins axi_clkconv_npu/m_axi_aresetn]

# axi_dwidth_converter 128→512 for DMA master → MIG C1
create_bd_cell -type ip -vlnv xilinx.com:ip:axi_dwidth_converter:2.1 axi_dwidth_npu
set_property -dict [list \
    CONFIG.SI_DATA_WIDTH {128} \
    CONFIG.MI_DATA_WIDTH {512} \
    CONFIG.SI_ID_WIDTH   {4}   \
] [get_bd_cells axi_dwidth_npu]
connect_bd_net [get_bd_pins mig_7series_0/c1_ui_clk]            [get_bd_pins axi_dwidth_npu/s_axi_aclk]
connect_bd_net [get_bd_pins rst_mig_7series_0_133M_1/peripheral_aresetn] \
               [get_bd_pins axi_dwidth_npu/s_axi_aresetn]

# Chain: clkconv_npu → dwidth_npu → MIG C1
connect_bd_intf_net [get_bd_intf_pins axi_clkconv_npu/M_AXI]    [get_bd_intf_pins axi_dwidth_npu/S_AXI]
connect_bd_intf_net [get_bd_intf_pins axi_dwidth_npu/M_AXI]     [get_bd_intf_pins mig_7series_0/S1_AXI]

# npu_dma_master
create_bd_cell -type module -reference npu_dma_master dma_master
connect_bd_net [get_bd_pins clk_wiz_fabric/clk_out1]            [get_bd_pins dma_master/aclk]
connect_bd_net [get_bd_pins rst_fabric_200M/peripheral_aresetn] [get_bd_pins dma_master/aresetn]
connect_bd_intf_net [get_bd_intf_pins dma_master/m_axi]         [get_bd_intf_pins axi_clkconv_npu/S_AXI]

# ctrl_lite start/done/busy — remove tie-off constants, connect to dma_master
set done_const [get_bd_cells -quiet ctrl_done_const]
set busy_const [get_bd_cells -quiet ctrl_busy_const]
if {$done_const ne ""} {
    foreach n [get_bd_nets -quiet -of_objects $done_const] { catch { delete_bd_objs $n } }
    delete_bd_objs $done_const
}
if {$busy_const ne ""} {
    foreach n [get_bd_nets -quiet -of_objects $busy_const] { catch { delete_bd_objs $n } }
    delete_bd_objs $busy_const
}
connect_bd_net [get_bd_pins ctrl_lite/start]  [get_bd_pins dma_master/start]
connect_bd_net [get_bd_pins dma_master/done]  [get_bd_pins ctrl_lite/done]
connect_bd_net [get_bd_pins dma_master/busy]  [get_bd_pins ctrl_lite/busy]

puts "INFO: V7 BD edits complete."
save_bd

puts "=== In-session synthesis ==="
run_synthesis

run_impl_and_write_bit "v7_dma_master" $BIT_DST
