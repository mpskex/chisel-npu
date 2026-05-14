################################################################################
# _apply_v6.tcl — V6 delta proc: remove SmartConnect, wire NPU-style flat chain
#
# Replaces the old apply_v6_xdma_clkconv (which broke SmartConnect by routing
# M00_AXI through an external CDC, causing aclk1 domain to vanish).
#
# This proc:
#   1. Deletes axi_smc (SmartConnect)
#   2. Adds axi_clkconv_xdma (200→133) + axi_dwidth_xdma (128→512)
#   3. Wires: axi_cc_xdma_in/M → clkconv/S → dwidth/S → MIG/S0_AXI
#   4. Ties off MIG/S1_AXI with an idle AXI VIP master
#   5. Assigns XDMA M_AXI address space to MIG C0
#
# Source after _apply_v5.tcl; called by V7+ build scripts.
################################################################################

proc apply_v6r_remove_smc {} {
    puts "=== _apply_v6: V6r — remove SmartConnect, wire NPU-style flat chain ==="

    # 1. Disconnect external SmartConnect nets (interface and scalar)
    foreach intf {S00_AXI M00_AXI M01_AXI} {
        set n [get_bd_intf_nets -quiet -of_objects [get_bd_intf_pins axi_smc/$intf]]
        if {$n ne ""} { delete_bd_objs $n }
    }
    foreach net_pin {aclk aresetn aclk1 aclk2} {
        set p [get_bd_pins -quiet axi_smc/$net_pin]
        if {$p ne ""} {
            set net [get_bd_nets -quiet -of_objects $p]
            if {$net ne ""} { catch { disconnect_bd_net $net $p } }
        }
    }
    delete_bd_objs [get_bd_cells axi_smc]
    puts "INFO: axi_smc deleted."

    # 2. Remove ALL M_AXI address segments that went through the SmartConnect
    foreach slave_seg {
        /mig_7series_0/c0_memmap/c0_memaddr
        /mig_7series_0/c1_memmap/c1_memaddr
    } {
        catch { exclude_bd_addr_seg $slave_seg -target_address_space /xdma_0/M_AXI }
    }
    foreach seg_path {
        /xdma_0/M_AXI/SEG_mig_7series_0_c0_memmap_memaddr
        /xdma_0/M_AXI/SEG_mig_7series_0_c1_memmap_memaddr
        /xdma_0/M_AXI/SEG_mig_7series_0_c0_memaddr
        /xdma_0/M_AXI/SEG_mig_7series_0_c1_memaddr
    } {
        set s [get_bd_addr_segs -quiet $seg_path]
        if {$s ne ""} { catch { delete_bd_objs $s } }
    }

    # 3. NPU-style flat converter chain to MIG C0
    create_bd_cell -type ip -vlnv xilinx.com:ip:axi_clock_converter:2.1 axi_clkconv_xdma
    connect_bd_net [get_bd_pins clk_wiz_fabric/clk_out1]            [get_bd_pins axi_clkconv_xdma/s_axi_aclk]
    connect_bd_net [get_bd_pins rst_fabric_200M/peripheral_aresetn] [get_bd_pins axi_clkconv_xdma/s_axi_aresetn]
    connect_bd_net [get_bd_pins mig_7series_0/c0_ui_clk]            [get_bd_pins axi_clkconv_xdma/m_axi_aclk]
    connect_bd_net [get_bd_pins rst_mig_7series_0_133M/peripheral_aresetn] \
                   [get_bd_pins axi_clkconv_xdma/m_axi_aresetn]

    create_bd_cell -type ip -vlnv xilinx.com:ip:axi_dwidth_converter:2.1 axi_dwidth_xdma
    set_property -dict [list \
        CONFIG.SI_DATA_WIDTH {128} \
        CONFIG.MI_DATA_WIDTH {512} \
        CONFIG.SI_ID_WIDTH   {4}   \
    ] [get_bd_cells axi_dwidth_xdma]
    connect_bd_net [get_bd_pins mig_7series_0/c0_ui_clk]            [get_bd_pins axi_dwidth_xdma/s_axi_aclk]
    connect_bd_net [get_bd_pins rst_mig_7series_0_133M/peripheral_aresetn] \
                   [get_bd_pins axi_dwidth_xdma/s_axi_aresetn]

    connect_bd_intf_net [get_bd_intf_pins axi_cc_xdma_in/M_AXI]     [get_bd_intf_pins axi_clkconv_xdma/S_AXI]
    connect_bd_intf_net [get_bd_intf_pins axi_clkconv_xdma/M_AXI]   [get_bd_intf_pins axi_dwidth_xdma/S_AXI]
    connect_bd_intf_net [get_bd_intf_pins axi_dwidth_xdma/M_AXI]    [get_bd_intf_pins mig_7series_0/S0_AXI]

    # 4. Tie off MIG C1 data port with idle AXI VIP master
    create_bd_cell -type ip -vlnv xilinx.com:ip:axi_vip:1.1 mig_c1_data_vip
    set_property -dict [list \
        CONFIG.INTERFACE_MODE {MASTER} \
        CONFIG.PROTOCOL       {AXI4}   \
        CONFIG.ADDR_WIDTH     {32}     \
        CONFIG.DATA_WIDTH     {512}    \
        CONFIG.ID_WIDTH       {4}      \
    ] [get_bd_cells mig_c1_data_vip]
    connect_bd_net [get_bd_pins mig_7series_0/c1_ui_clk]            [get_bd_pins mig_c1_data_vip/aclk]
    connect_bd_net [get_bd_pins rst_mig_7series_0_133M_1/peripheral_aresetn] \
                   [get_bd_pins mig_c1_data_vip/aresetn]
    connect_bd_intf_net [get_bd_intf_pins mig_c1_data_vip/M_AXI]    [get_bd_intf_pins mig_7series_0/S1_AXI]

    # 5. Address map: XDMA M_AXI → MIG C0 at 0x0 [2G] (matches reference)
    set c0_seg [get_bd_addr_segs mig_7series_0/c0_memmap/c0_memaddr]
    assign_bd_address -target_address_space /xdma_0/M_AXI $c0_seg \
        -range 2G -offset 0x00000000 -force
    catch { exclude_bd_addr_seg /mig_7series_0/c0_s_axi_ctrl_memmap/c0_s_axi_ctrl_memaddr \
        -target_address_space /xdma_0/M_AXI }

    puts "INFO: V6r remove-SMC done."
}
