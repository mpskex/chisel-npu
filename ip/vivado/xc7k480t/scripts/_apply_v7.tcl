################################################################################
# _apply_v7.tcl — V7 delta proc: add npu_dma_master → MIG C1
#
# Removes the mig_c1_data_vip tie-off from V6r and replaces it with the
# full DMA master path (axi_clkconv_npu + axi_dwidth_npu + npu_dma_master).
# Source after _apply_v6.tcl.
################################################################################

proc apply_v7_dma_master {} {
    puts "=== _apply_v7: V7 — npu_dma_master → MIG C1 ==="

    # Remove C1 VIP tie-off
    set vip [get_bd_cells -quiet mig_c1_data_vip]
    if {$vip ne ""} {
        foreach n [get_bd_intf_nets -quiet -of_objects $vip] { delete_bd_objs $n }
        foreach n [get_bd_nets -quiet -of_objects $vip] { catch { delete_bd_objs $n } }
        delete_bd_objs $vip
    }

    # Clock converter 200→133
    create_bd_cell -type ip -vlnv xilinx.com:ip:axi_clock_converter:2.1 axi_clkconv_npu
    connect_bd_net [get_bd_pins clk_wiz_fabric/clk_out1]            [get_bd_pins axi_clkconv_npu/s_axi_aclk]
    connect_bd_net [get_bd_pins rst_fabric_200M/peripheral_aresetn] [get_bd_pins axi_clkconv_npu/s_axi_aresetn]
    connect_bd_net [get_bd_pins mig_7series_0/c1_ui_clk]            [get_bd_pins axi_clkconv_npu/m_axi_aclk]
    connect_bd_net [get_bd_pins rst_mig_7series_0_133M_1/peripheral_aresetn] \
                   [get_bd_pins axi_clkconv_npu/m_axi_aresetn]

    # Width converter 128→512
    create_bd_cell -type ip -vlnv xilinx.com:ip:axi_dwidth_converter:2.1 axi_dwidth_npu
    set_property -dict [list \
        CONFIG.SI_DATA_WIDTH {128} \
        CONFIG.MI_DATA_WIDTH {512} \
        CONFIG.SI_ID_WIDTH   {4}   \
    ] [get_bd_cells axi_dwidth_npu]
    connect_bd_net [get_bd_pins mig_7series_0/c1_ui_clk]            [get_bd_pins axi_dwidth_npu/s_axi_aclk]
    connect_bd_net [get_bd_pins rst_mig_7series_0_133M_1/peripheral_aresetn] \
                   [get_bd_pins axi_dwidth_npu/s_axi_aresetn]
    connect_bd_intf_net [get_bd_intf_pins axi_clkconv_npu/M_AXI]    [get_bd_intf_pins axi_dwidth_npu/S_AXI]
    connect_bd_intf_net [get_bd_intf_pins axi_dwidth_npu/M_AXI]     [get_bd_intf_pins mig_7series_0/S1_AXI]

    # DMA master
    create_bd_cell -type module -reference npu_dma_master dma_master
    connect_bd_net [get_bd_pins clk_wiz_fabric/clk_out1]            [get_bd_pins dma_master/aclk]
    connect_bd_net [get_bd_pins rst_fabric_200M/peripheral_aresetn] [get_bd_pins dma_master/aresetn]
    connect_bd_intf_net [get_bd_intf_pins dma_master/m_axi]         [get_bd_intf_pins axi_clkconv_npu/S_AXI]

    # ctrl_lite start/done/busy
    foreach const_cell {ctrl_done_const ctrl_busy_const} {
        set c [get_bd_cells -quiet $const_cell]
        if {$c ne ""} {
            foreach n [get_bd_nets -quiet -of_objects $c] { catch { delete_bd_objs $n } }
            delete_bd_objs $c
        }
    }
    connect_bd_net [get_bd_pins ctrl_lite/start]  [get_bd_pins dma_master/start]
    connect_bd_net [get_bd_pins dma_master/done]  [get_bd_pins ctrl_lite/done]
    connect_bd_net [get_bd_pins dma_master/busy]  [get_bd_pins ctrl_lite/busy]

    puts "INFO: V7 done."
}
