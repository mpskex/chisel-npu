################################################################################
# _apply_v2.tcl — Shared BD delta procedures for V1 and V2 applied cumulatively
#
# Sourced by build_v3_mmcm.tcl and above.  Contains two procs:
#   apply_v1_deletions  — removes MicroBlaze + related cells (V1 delta)
#   apply_v2_bypass_ctrl rtl_src — adds byp_dw + byp_pc + ctrl_lite (V2 delta)
#
# IMPORTANT: The caller must open the BD before calling these procs:
#   open_ref_project
#   assert_synth_done
#   open_bd_design [get_files {*/top.bd}]
#   apply_v1_deletions
#   apply_v2_bypass_ctrl $RTL_SRC
#   # ... add V3+ deltas ...
#   save_bd
################################################################################

proc apply_v1_deletions {} {
    puts "=== _apply_v2: V1 deletions (remove MB tree) ==="

    # NOTE: BD must already be open; do NOT call open_bd_design here.

    # Disconnect XDMA BYPASS → MB periph
    set bypass_net [get_bd_intf_nets -quiet xdma_0_M_AXI_BYPASS]
    if {$bypass_net ne ""} { delete_bd_objs $bypass_net }

    foreach ctrl_net {microblaze_0_axi_periph_M00_AXI microblaze_0_axi_periph_M01_AXI} {
        set n [get_bd_intf_nets -quiet $ctrl_net]
        if {$n ne ""} { delete_bd_objs $n }
    }

    # microblaze_0_axi_periph is an axi_interconnect appcore: any attempt to
    # enumerate or delete its internal nets via -of_objects triggers BD 41-738.
    # Safe approach: delete known boundary interface nets by name, then delete
    # the cell. Vivado cleans up internal nets automatically.
    set mb [get_bd_cells -quiet microblaze_0_axi_periph]
    if {$mb ne ""} {
        foreach known_intf {
            xdma_0_M_AXI_BYPASS
            microblaze_0_axi_periph_M00_AXI
            microblaze_0_axi_periph_M01_AXI
        } {
            set n [get_bd_intf_nets -quiet $known_intf]
            if {$n ne ""} { delete_bd_objs $n }
        }
        delete_bd_objs $mb
    }
    # Simple IP cells — can delete internal nets freely
    foreach cell {rst_util_ds_buf_100M clk_wiz} {
        set c [get_bd_cells -quiet $cell]
        if {$c ne ""} {
            delete_bd_objs [get_bd_nets -quiet -of_objects $c]
            delete_bd_objs $c
        }
    }

    set p [get_bd_ports -quiet clk_in1_0]
    if {$p ne ""} {
        delete_bd_objs [get_bd_nets -quiet -of_objects $p]
        delete_bd_objs $p
    }

    set aren [get_bd_nets -quiet ARESETN_1]
    if {$aren ne ""} { delete_bd_objs $aren }

    # Remove stale address segments from M_AXI_BYPASS → MIG AXI_CTRL
    # (these were mapped through the MB interconnect, now gone)
    foreach seg_path {
        /xdma_0/M_AXI_BYPASS/SEG_mig_7series_0_c0_s_axi_ctrl_memaddr
        /xdma_0/M_AXI_BYPASS/SEG_mig_7series_0_c1_s_axi_ctrl_memaddr
    } {
        set seg [get_bd_addr_segs -quiet $seg_path]
        if {$seg ne ""} { catch { delete_bd_objs $seg } }
    }
    catch { exclude_bd_addr_seg /mig_7series_0/c0_s_axi_ctrl_memmap/c0_s_axi_ctrl_memaddr \
        -target_address_space /xdma_0/M_AXI_BYPASS }
    catch { exclude_bd_addr_seg /mig_7series_0/c1_s_axi_ctrl_memmap/c1_s_axi_ctrl_memaddr \
        -target_address_space /xdma_0/M_AXI_BYPASS }

    # Add AXI VIP stubs for MIG S0/S1_AXI_CTRL (required ports, were driven by MB)
    puts "INFO: adding axi_vip stub masters for MIG S0/S1_AXI_CTRL"
    foreach {vip_name mig_port mig_clk mig_rst} {
        mig_c0_ctrl_vip  S0_AXI_CTRL
        mig_7series_0/c0_ui_clk  rst_mig_7series_0_133M/peripheral_aresetn
        mig_c1_ctrl_vip  S1_AXI_CTRL
        mig_7series_0/c1_ui_clk  rst_mig_7series_0_133M_1/peripheral_aresetn
    } {
        create_bd_cell -type ip -vlnv xilinx.com:ip:axi_vip:1.1 $vip_name
        set_property -dict [list \
            CONFIG.INTERFACE_MODE {MASTER} \
            CONFIG.PROTOCOL       {AXI4LITE} \
            CONFIG.ADDR_WIDTH     {32}       \
            CONFIG.DATA_WIDTH     {32}       \
        ] [get_bd_cells $vip_name]
        connect_bd_net [get_bd_pins $mig_clk] [get_bd_pins $vip_name/aclk]
        connect_bd_net [get_bd_pins $mig_rst]  [get_bd_pins $vip_name/aresetn]
        connect_bd_intf_net [get_bd_intf_pins $vip_name/M_AXI] \
                            [get_bd_intf_pins mig_7series_0/$mig_port]
    }

    puts "INFO: V1 deletions done."
}

proc apply_v2_bypass_ctrl {rtl_src} {
    puts "=== _apply_v2: V2 additions (BYPASS dwidth + protocol_conv + ctrl_lite) ==="

    # Add RTL source
    set ctrl_v [file join $rtl_src npu_ctrl_lite.v]
    if {![file exists $ctrl_v]} {
        puts "ERROR: $ctrl_v not found"; exit 1
    }
    add_files -norecurse $ctrl_v
    update_compile_order -fileset sources_1

    # axi_dwidth_converter 128→32
    create_bd_cell -type ip -vlnv xilinx.com:ip:axi_dwidth_converter:2.1 byp_dw
    set_property -dict [list \
        CONFIG.SI_DATA_WIDTH {128} \
        CONFIG.MI_DATA_WIDTH {32}  \
        CONFIG.SI_ID_WIDTH   {4}   \
    ] [get_bd_cells byp_dw]

    # AXI4 → AXI4-Lite protocol converter
    create_bd_cell -type ip -vlnv xilinx.com:ip:axi_protocol_converter:2.1 byp_pc
    set_property -dict [list \
        CONFIG.MI_PROTOCOL {AXI4LITE} \
        CONFIG.SI_PROTOCOL {AXI4}     \
        CONFIG.DATA_WIDTH  {32}       \
        CONFIG.ID_WIDTH    {4}        \
    ] [get_bd_cells byp_pc]

    # npu_ctrl_lite RTL module
    create_bd_cell -type module -reference npu_ctrl_lite ctrl_lite

    # Constant tie-offs for done/busy
    create_bd_cell -type ip -vlnv xilinx.com:ip:xlconstant:1.1 ctrl_done_const
    set_property CONFIG.CONST_VAL   {0} [get_bd_cells ctrl_done_const]
    set_property CONFIG.CONST_WIDTH {1} [get_bd_cells ctrl_done_const]

    create_bd_cell -type ip -vlnv xilinx.com:ip:xlconstant:1.1 ctrl_busy_const
    set_property CONFIG.CONST_VAL   {0} [get_bd_cells ctrl_busy_const]
    set_property CONFIG.CONST_WIDTH {1} [get_bd_cells ctrl_busy_const]

    # Clocks/resets
    connect_bd_net [get_bd_pins xdma_0/axi_aclk] \
        [get_bd_pins byp_dw/s_axi_aclk] \
        [get_bd_pins byp_pc/aclk]        \
        [get_bd_pins ctrl_lite/axi_aclk]
    connect_bd_net [get_bd_pins xdma_0/axi_aresetn] \
        [get_bd_pins byp_dw/s_axi_aresetn] \
        [get_bd_pins byp_pc/aresetn]         \
        [get_bd_pins ctrl_lite/axi_aresetn]

    # AXI path: XDMA BYPASS → byp_dw → byp_pc → ctrl_lite (flat)
    connect_bd_intf_net [get_bd_intf_pins xdma_0/M_AXI_BYPASS] \
                        [get_bd_intf_pins byp_dw/S_AXI]
    connect_bd_intf_net [get_bd_intf_pins byp_dw/M_AXI] \
                        [get_bd_intf_pins byp_pc/S_AXI]

    set pc [get_bd_cells byp_pc]
    set cl [get_bd_cells ctrl_lite]
    connect_bd_net [get_bd_pins $pc/m_axi_awaddr]   [get_bd_pins $cl/s_axil_awaddr]
    connect_bd_net [get_bd_pins $pc/m_axi_awprot]   [get_bd_pins $cl/s_axil_awprot]
    connect_bd_net [get_bd_pins $pc/m_axi_awvalid]  [get_bd_pins $cl/s_axil_awvalid]
    connect_bd_net [get_bd_pins $cl/s_axil_awready] [get_bd_pins $pc/m_axi_awready]
    connect_bd_net [get_bd_pins $pc/m_axi_wdata]    [get_bd_pins $cl/s_axil_wdata]
    connect_bd_net [get_bd_pins $pc/m_axi_wstrb]    [get_bd_pins $cl/s_axil_wstrb]
    connect_bd_net [get_bd_pins $pc/m_axi_wvalid]   [get_bd_pins $cl/s_axil_wvalid]
    connect_bd_net [get_bd_pins $cl/s_axil_wready]  [get_bd_pins $pc/m_axi_wready]
    connect_bd_net [get_bd_pins $cl/s_axil_bresp]   [get_bd_pins $pc/m_axi_bresp]
    connect_bd_net [get_bd_pins $cl/s_axil_bvalid]  [get_bd_pins $pc/m_axi_bvalid]
    connect_bd_net [get_bd_pins $pc/m_axi_bready]   [get_bd_pins $cl/s_axil_bready]
    connect_bd_net [get_bd_pins $pc/m_axi_araddr]   [get_bd_pins $cl/s_axil_araddr]
    connect_bd_net [get_bd_pins $pc/m_axi_arprot]   [get_bd_pins $cl/s_axil_arprot]
    connect_bd_net [get_bd_pins $pc/m_axi_arvalid]  [get_bd_pins $cl/s_axil_arvalid]
    connect_bd_net [get_bd_pins $cl/s_axil_arready] [get_bd_pins $pc/m_axi_arready]
    connect_bd_net [get_bd_pins $cl/s_axil_rdata]   [get_bd_pins $pc/m_axi_rdata]
    connect_bd_net [get_bd_pins $cl/s_axil_rresp]   [get_bd_pins $pc/m_axi_rresp]
    connect_bd_net [get_bd_pins $cl/s_axil_rvalid]  [get_bd_pins $pc/m_axi_rvalid]
    connect_bd_net [get_bd_pins $pc/m_axi_rready]   [get_bd_pins $cl/s_axil_rready]

    connect_bd_net [get_bd_pins ctrl_done_const/dout] [get_bd_pins ctrl_lite/done]
    connect_bd_net [get_bd_pins ctrl_busy_const/dout] [get_bd_pins ctrl_lite/busy]

    puts "INFO: V2 additions done."
}
