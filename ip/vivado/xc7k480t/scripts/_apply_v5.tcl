################################################################################
# _apply_v5.tcl — Cumulative delta procs for V3, V4, V5
#
# These procs apply BD changes incrementally, building on top of V2.
# Each proc assumes the previous step's BD state is already in effect.
# Source order: migrate_lib.tcl → _apply_v2.tcl → _apply_v5.tcl
################################################################################

proc apply_v3_mmcm_and_rst {} {
    puts "=== _apply_v5: V3 — fabric MMCM + rst_fabric_200M ==="

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

    create_bd_cell -type ip -vlnv xilinx.com:ip:proc_sys_reset:5.0 rst_fabric_200M
    connect_bd_net [get_bd_pins clk_wiz_fabric/clk_out1]  [get_bd_pins rst_fabric_200M/slowest_sync_clk]
    connect_bd_net [get_bd_pins clk_wiz_fabric/locked]     [get_bd_pins rst_fabric_200M/dcm_locked]
    connect_bd_net [get_bd_ports reset_rtl_0]              [get_bd_pins rst_fabric_200M/ext_reset_in]

    puts "INFO: V3 MMCM+rst done."
}

proc apply_v4_byp_cdc {} {
    puts "=== _apply_v5: V4 — axi_clkconv_byp + move BYPASS to 200 MHz ==="

    create_bd_cell -type ip -vlnv xilinx.com:ip:axi_clock_converter:2.1 axi_clkconv_byp

    # Remove existing XDMA BYPASS → byp_dw connection (from V2)
    set n [get_bd_intf_nets -quiet xdma_0_M_AXI_BYPASS]
    if {$n ne ""} { delete_bd_objs $n }

    connect_bd_net [get_bd_pins xdma_0/axi_aclk]                        [get_bd_pins axi_clkconv_byp/s_axi_aclk]
    connect_bd_net [get_bd_pins xdma_0/axi_aresetn]                     [get_bd_pins axi_clkconv_byp/s_axi_aresetn]
    connect_bd_net [get_bd_pins clk_wiz_fabric/clk_out1]                [get_bd_pins axi_clkconv_byp/m_axi_aclk]
    connect_bd_net [get_bd_pins rst_fabric_200M/peripheral_aresetn]     [get_bd_pins axi_clkconv_byp/m_axi_aresetn]
    connect_bd_intf_net [get_bd_intf_pins xdma_0/M_AXI_BYPASS]         [get_bd_intf_pins axi_clkconv_byp/S_AXI]
    connect_bd_intf_net [get_bd_intf_pins axi_clkconv_byp/M_AXI]       [get_bd_intf_pins byp_dw/S_AXI]

    # Move byp_dw / byp_pc / ctrl_lite from 250 MHz to 200 MHz
    foreach {cell aclk_pin aresetn_pin} {
        byp_dw    s_axi_aclk    s_axi_aresetn
        byp_pc    aclk          aresetn
        ctrl_lite axi_aclk      axi_aresetn
    } {
        set aclk_net   [get_bd_nets -quiet -of_objects [get_bd_pins xdma_0/axi_aclk]]
        set aresetn_net [get_bd_nets -quiet -of_objects [get_bd_pins xdma_0/axi_aresetn]]
        disconnect_bd_net $aclk_net   [get_bd_pins $cell/$aclk_pin]
        disconnect_bd_net $aresetn_net [get_bd_pins $cell/$aresetn_pin]
    }
    connect_bd_net [get_bd_pins clk_wiz_fabric/clk_out1] \
        [get_bd_pins byp_dw/s_axi_aclk] \
        [get_bd_pins byp_pc/aclk]       \
        [get_bd_pins ctrl_lite/axi_aclk]
    connect_bd_net [get_bd_pins rst_fabric_200M/peripheral_aresetn] \
        [get_bd_pins byp_dw/s_axi_aresetn] \
        [get_bd_pins byp_pc/aresetn]        \
        [get_bd_pins ctrl_lite/axi_aresetn]

    puts "INFO: V4 BYPASS CDC done."
}

proc apply_v5_xdma_cc {} {
    puts "=== _apply_v5: V5 — axi_cc_xdma_in (250→200) on DDR3 data path ==="

    create_bd_cell -type ip -vlnv xilinx.com:ip:axi_clock_converter:2.1 axi_cc_xdma_in

    set n [get_bd_intf_nets -quiet xdma_0_M_AXI]
    if {$n ne ""} { delete_bd_objs $n }

    connect_bd_net [get_bd_pins xdma_0/axi_aclk]    [get_bd_pins axi_cc_xdma_in/s_axi_aclk]
    connect_bd_net [get_bd_pins xdma_0/axi_aresetn]  [get_bd_pins axi_cc_xdma_in/s_axi_aresetn]
    connect_bd_net [get_bd_pins clk_wiz_fabric/clk_out1]              [get_bd_pins axi_cc_xdma_in/m_axi_aclk]
    connect_bd_net [get_bd_pins rst_fabric_200M/peripheral_aresetn]   [get_bd_pins axi_cc_xdma_in/m_axi_aresetn]

    connect_bd_intf_net [get_bd_intf_pins xdma_0/M_AXI]          [get_bd_intf_pins axi_cc_xdma_in/S_AXI]
    connect_bd_intf_net [get_bd_intf_pins axi_cc_xdma_in/M_AXI]  [get_bd_intf_pins axi_smc/S00_AXI]

    # Move axi_smc S00 clock to 200 MHz
    disconnect_bd_net [get_bd_nets -quiet xdma_0_axi_aclk] [get_bd_pins axi_smc/aclk]
    set old_rstn_net [get_bd_nets -quiet -of_objects [get_bd_pins axi_smc/aresetn]]
    if {$old_rstn_net ne ""} { disconnect_bd_net $old_rstn_net [get_bd_pins axi_smc/aresetn] }
    connect_bd_net [get_bd_pins clk_wiz_fabric/clk_out1]            [get_bd_pins axi_smc/aclk]
    connect_bd_net [get_bd_pins rst_fabric_200M/peripheral_aresetn] [get_bd_pins axi_smc/aresetn]

    puts "INFO: V5 axi_cc_xdma_in done."
}
