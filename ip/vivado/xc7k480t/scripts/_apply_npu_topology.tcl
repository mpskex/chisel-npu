################################################################################
# _apply_npu_topology.tcl — Build the production NPU BD topology from a V0
#                          (vendor reference) baseline.
#
# This is the consolidated single-script version of what used to be a 6-file
# bisect ladder (_apply_v2.tcl, _apply_v5.tcl, _apply_v6.tcl, _apply_v7.tcl,
# _apply_v10.tcl). The historical step-by-step bring-up is preserved in
# git history (commit 2b9a7a2 and earlier); the per-step build scripts have
# been retired now that the final topology is verified end-to-end on silicon.
#
# What this proc produces, in BD-cell terms:
#
#   xdma_0  (XDMA 4.2, Gen2 x8 configured, 125 MHz axi_aclk)
#     M_AXI         ── axi_cc_xdma_in ── axi_clkconv_xdma ── axi_dwidth_xdma ─┐
#     M_AXI_BYPASS  ── axi_clkconv_byp ── byp_dw ── byp_pc ── npu_subsys/s_axil
#                                                                            │
#   clk_wiz_fabric  (MMCM, 125 → 200 MHz fabric_aclk)                       │
#   rst_fabric_200M (proc_sys_reset)                                         │
#                                                                            │
#                                                                            ▼
#   axi_xbar  (axi_interconnect 2S:2M, 512-bit, c0_ui_clk arbitration domain)
#       S00 ◄── axi_dwidth_xdma/M_AXI    (c0_ui_clk)
#       S01 ◄── axi_dwidth_npu/M_AXI     (c0_ui_clk, after V10 re-bind)
#       M00 ──► mig_7series_0/S0_AXI     (c0_ui_clk, no CDC)
#       M01 ──► mig_7series_0/S1_AXI     (c1_ui_clk, async-FIFO CDC inside xbar)
#       Address map (both slaves):
#         0x0000_0000..0x7FFF_FFFF → C0 (2 GB)
#         0x8000_0000..0xFFFF_FFFF → C1 (2 GB)
#
#   npu_subsys (single BD module cell — wraps npu_ctrl_lite + npu_dma_master +
#               MMALU, all internally wired)
#     aclk         = clk_wiz_fabric/clk_out1 (200 MHz)
#     aresetn      = rst_fabric_200M/peripheral_aresetn
#     s_axil_*     ◄── byp_pc (AXI4-Lite slave, BAR2+0x0 ctrl_lite)
#     m_axi        ──► axi_clkconv_npu/S_AXI (AXI4 master to xbar)
#     c0/c1_init_calib_complete ◄── mig_7series_0 status (info only)
#
#   axi_clkconv_npu  (200 → 133 MHz on c0_ui_clk — V10 re-bind from V7's c1_ui_clk)
#   axi_dwidth_npu   (128 → 512 b on c0_ui_clk — V10 re-bind)
#
#   mig_7series_0 ports (V0 baseline IP):
#     S0_AXI       ◄── axi_xbar.M00
#     S1_AXI       ◄── axi_xbar.M01
#     S0_AXI_CTRL  ◄── mig_c0_ctrl_vip (idle AXI4-Lite VIP)
#     S1_AXI_CTRL  ◄── mig_c1_ctrl_vip (idle AXI4-Lite VIP)
#     C0_SYS_CLK   ◄── port C0_SYS_CLK_0
#     C1_SYS_CLK   ◄── port C1_SYS_CLK_0
#     sys_rst      ◄── port reset_rtl_0  (board reset, NOT XDMA aresetn — avoids
#                                          chicken-and-egg PCIe vs MIG calibration)
#
# Prereqs: BD design opened (open_bd_design [get_files {*/top.bd}]) and the
# baseline V0 vendor-reference cells already present (microblaze_0_axi_periph,
# axi_smc, ctrl_done_const, ctrl_busy_const, rst_util_ds_buf_100M, clk_wiz,
# microblaze_0, microblaze_0_local_memory). The proc is idempotent — re-running
# on a partially-applied BD will skip steps already taken.
#
# Sourcing model: this file defines the entry-point proc `apply_npu_topology`
# and a helper `_npu_rebind_clk_rst`. The main build script
# (`build_npu.tcl` or `build_npu_with_ila.tcl`) calls `apply_npu_topology`.
################################################################################

# ─── Helper ──────────────────────────────────────────────────────────────────
# Disconnect any existing clk/reset nets on a cell's named clock and reset
# pins and re-connect to a new (clock_source, reset_source) pair.
proc _npu_rebind_clk_rst {cell aclk_pin aresetn_pin new_clk new_rst} {
    set p_clk [get_bd_pins -quiet $cell/$aclk_pin]
    set p_rst [get_bd_pins -quiet $cell/$aresetn_pin]
    if {$p_clk ne ""} {
        foreach n [get_bd_nets -quiet -of_objects $p_clk] {
            catch { disconnect_bd_net $n $p_clk }
        }
        connect_bd_net [get_bd_pins $new_clk] $p_clk
    }
    if {$p_rst ne ""} {
        foreach n [get_bd_nets -quiet -of_objects $p_rst] {
            catch { disconnect_bd_net $n $p_rst }
        }
        connect_bd_net [get_bd_pins $new_rst] $p_rst
    }
}

# ─── V1 step — remove MicroBlaze ECC subsystem ──────────────────────────────
proc _npu_step_remove_mb {} {
    puts "=== topology: removing MicroBlaze ECC subsystem ==="

    set bypass_net [get_bd_intf_nets -quiet xdma_0_M_AXI_BYPASS]
    if {$bypass_net ne ""} { delete_bd_objs $bypass_net }

    foreach ctrl_net {microblaze_0_axi_periph_M00_AXI microblaze_0_axi_periph_M01_AXI} {
        set n [get_bd_intf_nets -quiet $ctrl_net]
        if {$n ne ""} { delete_bd_objs $n }
    }

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

    # Remove stale BYPASS segments through the (now-deleted) MB interconnect
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

    # Replace the MIG S0/S1_AXI_CTRL slaves with idle AXI-Lite VIP masters
    foreach {vip_name mig_port mig_clk mig_rst} {
        mig_c0_ctrl_vip  S0_AXI_CTRL
        mig_7series_0/c0_ui_clk  rst_mig_7series_0_133M/peripheral_aresetn
        mig_c1_ctrl_vip  S1_AXI_CTRL
        mig_7series_0/c1_ui_clk  rst_mig_7series_0_133M_1/peripheral_aresetn
    } {
        if {[get_bd_cells -quiet $vip_name] eq ""} {
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
    }
    puts "INFO: MicroBlaze ECC subsystem removed."
}

# ─── V2 step — BYPASS-BAR path + ctrl_lite ─────────────────────────────────
proc _npu_step_bypass_ctrl {rtl_src} {
    puts "=== topology: BYPASS-BAR dwidth + proto_conv + ctrl_lite ==="

    set ctrl_v [file join $rtl_src npu_ctrl_lite.v]
    if {![file exists $ctrl_v]} {
        puts "ERROR: $ctrl_v not found"; exit 1
    }
    if {[get_files -quiet -of_objects [get_filesets sources_1] $ctrl_v] eq ""} {
        add_files -norecurse $ctrl_v
        update_compile_order -fileset sources_1
    }

    if {[get_bd_cells -quiet byp_dw] eq ""} {
        create_bd_cell -type ip -vlnv xilinx.com:ip:axi_dwidth_converter:2.1 byp_dw
        set_property -dict [list \
            CONFIG.SI_DATA_WIDTH {128} \
            CONFIG.MI_DATA_WIDTH {32}  \
            CONFIG.SI_ID_WIDTH   {4}   \
        ] [get_bd_cells byp_dw]
    }
    if {[get_bd_cells -quiet byp_pc] eq ""} {
        create_bd_cell -type ip -vlnv xilinx.com:ip:axi_protocol_converter:2.1 byp_pc
        set_property -dict [list \
            CONFIG.MI_PROTOCOL {AXI4LITE} \
            CONFIG.SI_PROTOCOL {AXI4}     \
            CONFIG.DATA_WIDTH  {32}       \
            CONFIG.ID_WIDTH    {4}        \
        ] [get_bd_cells byp_pc]
    }
    if {[get_bd_cells -quiet ctrl_lite] eq ""} {
        create_bd_cell -type module -reference npu_ctrl_lite ctrl_lite
    }

    foreach const_cell {ctrl_done_const ctrl_busy_const} {
        if {[get_bd_cells -quiet $const_cell] eq ""} {
            create_bd_cell -type ip -vlnv xilinx.com:ip:xlconstant:1.1 $const_cell
            set_property CONFIG.CONST_VAL   {0} [get_bd_cells $const_cell]
            set_property CONFIG.CONST_WIDTH {1} [get_bd_cells $const_cell]
        }
    }

    connect_bd_net [get_bd_pins xdma_0/axi_aclk] \
        [get_bd_pins byp_dw/s_axi_aclk] \
        [get_bd_pins byp_pc/aclk]        \
        [get_bd_pins ctrl_lite/axi_aclk]
    connect_bd_net [get_bd_pins xdma_0/axi_aresetn] \
        [get_bd_pins byp_dw/s_axi_aresetn] \
        [get_bd_pins byp_pc/aresetn]         \
        [get_bd_pins ctrl_lite/axi_aresetn]

    connect_bd_intf_net [get_bd_intf_pins xdma_0/M_AXI_BYPASS] \
                        [get_bd_intf_pins byp_dw/S_AXI]
    connect_bd_intf_net [get_bd_intf_pins byp_dw/M_AXI] \
                        [get_bd_intf_pins byp_pc/S_AXI]

    set pc [get_bd_cells byp_pc]
    set cl [get_bd_cells ctrl_lite]
    foreach {pcPin clPin} {
        m_axi_awaddr  s_axil_awaddr
        m_axi_awprot  s_axil_awprot
        m_axi_awvalid s_axil_awvalid
        s_axil_awready m_axi_awready
        m_axi_wdata   s_axil_wdata
        m_axi_wstrb   s_axil_wstrb
        m_axi_wvalid  s_axil_wvalid
        s_axil_wready m_axi_wready
        s_axil_bresp  m_axi_bresp
        s_axil_bvalid m_axi_bvalid
        m_axi_bready  s_axil_bready
        m_axi_araddr  s_axil_araddr
        m_axi_arprot  s_axil_arprot
        m_axi_arvalid s_axil_arvalid
        s_axil_arready m_axi_arready
        s_axil_rdata  m_axi_rdata
        s_axil_rresp  m_axi_rresp
        s_axil_rvalid m_axi_rvalid
        m_axi_rready  s_axil_rready
    } {
        # Always connect to byp_pc; flip src/dst based on direction
        if {[string match s_axil* $pcPin]} {
            connect_bd_net [get_bd_pins $cl/$pcPin] [get_bd_pins $pc/$clPin]
        } else {
            connect_bd_net [get_bd_pins $pc/$pcPin] [get_bd_pins $cl/$clPin]
        }
    }
    connect_bd_net [get_bd_pins ctrl_done_const/dout] [get_bd_pins ctrl_lite/done]
    connect_bd_net [get_bd_pins ctrl_busy_const/dout] [get_bd_pins ctrl_lite/busy]

    puts "INFO: BYPASS-BAR ctrl_lite path wired."
}

# ─── V3 step — Fabric MMCM (200 MHz) + reset ───────────────────────────────
proc _npu_step_mmcm {} {
    puts "=== topology: clk_wiz_fabric (125→200 MHz) + rst_fabric_200M ==="

    if {[get_bd_cells -quiet clk_wiz_fabric] eq ""} {
        create_bd_cell -type ip -vlnv xilinx.com:ip:clk_wiz:6.0 clk_wiz_fabric
        set_property -dict [list \
            CONFIG.PRIM_IN_FREQ              {250.000} \
            CONFIG.CLKOUT1_REQUESTED_OUT_FREQ {200.000} \
            CONFIG.USE_RESET                 {true}    \
            CONFIG.RESET_TYPE                {ACTIVE_LOW} \
            CONFIG.USE_LOCKED                {true}    \
            CONFIG.PRIMITIVE                 {MMCM}    \
        ] [get_bd_cells clk_wiz_fabric]
        connect_bd_net [get_bd_pins xdma_0/axi_aclk]     [get_bd_pins clk_wiz_fabric/clk_in1]
        connect_bd_net [get_bd_pins xdma_0/axi_aresetn]  [get_bd_pins clk_wiz_fabric/resetn]
    }

    if {[get_bd_cells -quiet rst_fabric_200M] eq ""} {
        create_bd_cell -type ip -vlnv xilinx.com:ip:proc_sys_reset:5.0 rst_fabric_200M
        connect_bd_net [get_bd_pins clk_wiz_fabric/clk_out1] [get_bd_pins rst_fabric_200M/slowest_sync_clk]
        connect_bd_net [get_bd_pins clk_wiz_fabric/locked]    [get_bd_pins rst_fabric_200M/dcm_locked]
        connect_bd_net [get_bd_ports reset_rtl_0]             [get_bd_pins rst_fabric_200M/ext_reset_in]
    }
    puts "INFO: fabric MMCM + reset ready."
}

# ─── V4 step — BYPASS clock crossing (125→200) ─────────────────────────────
proc _npu_step_byp_cdc {} {
    puts "=== topology: axi_clkconv_byp (125→200 MHz) ==="

    if {[get_bd_cells -quiet axi_clkconv_byp] eq ""} {
        create_bd_cell -type ip -vlnv xilinx.com:ip:axi_clock_converter:2.1 axi_clkconv_byp
    }

    set n [get_bd_intf_nets -quiet xdma_0_M_AXI_BYPASS]
    if {$n ne ""} { delete_bd_objs $n }

    connect_bd_net [get_bd_pins xdma_0/axi_aclk]                    [get_bd_pins axi_clkconv_byp/s_axi_aclk]
    connect_bd_net [get_bd_pins xdma_0/axi_aresetn]                 [get_bd_pins axi_clkconv_byp/s_axi_aresetn]
    connect_bd_net [get_bd_pins clk_wiz_fabric/clk_out1]            [get_bd_pins axi_clkconv_byp/m_axi_aclk]
    connect_bd_net [get_bd_pins rst_fabric_200M/peripheral_aresetn] [get_bd_pins axi_clkconv_byp/m_axi_aresetn]
    connect_bd_intf_net [get_bd_intf_pins xdma_0/M_AXI_BYPASS]      [get_bd_intf_pins axi_clkconv_byp/S_AXI]
    connect_bd_intf_net [get_bd_intf_pins axi_clkconv_byp/M_AXI]    [get_bd_intf_pins byp_dw/S_AXI]

    # Move byp_dw / byp_pc / ctrl_lite from 125 MHz xdma_0/axi_aclk to 200 MHz fabric
    foreach {cell aclk_pin aresetn_pin} {
        byp_dw    s_axi_aclk    s_axi_aresetn
        byp_pc    aclk          aresetn
        ctrl_lite axi_aclk      axi_aresetn
    } {
        _npu_rebind_clk_rst $cell $aclk_pin $aresetn_pin \
            clk_wiz_fabric/clk_out1 rst_fabric_200M/peripheral_aresetn
    }
    puts "INFO: BYPASS path now on fabric_aclk (200 MHz)."
}

# ─── V5 step — XDMA M_AXI clock crossing (125→200) + SMC re-clock ─────────
proc _npu_step_xdma_cc {} {
    puts "=== topology: axi_cc_xdma_in (125→200 MHz) ==="

    if {[get_bd_cells -quiet axi_cc_xdma_in] eq ""} {
        create_bd_cell -type ip -vlnv xilinx.com:ip:axi_clock_converter:2.1 axi_cc_xdma_in
    }

    set n [get_bd_intf_nets -quiet xdma_0_M_AXI]
    if {$n ne ""} { delete_bd_objs $n }

    connect_bd_net [get_bd_pins xdma_0/axi_aclk]                    [get_bd_pins axi_cc_xdma_in/s_axi_aclk]
    connect_bd_net [get_bd_pins xdma_0/axi_aresetn]                 [get_bd_pins axi_cc_xdma_in/s_axi_aresetn]
    connect_bd_net [get_bd_pins clk_wiz_fabric/clk_out1]            [get_bd_pins axi_cc_xdma_in/m_axi_aclk]
    connect_bd_net [get_bd_pins rst_fabric_200M/peripheral_aresetn] [get_bd_pins axi_cc_xdma_in/m_axi_aresetn]
    connect_bd_intf_net [get_bd_intf_pins xdma_0/M_AXI]             [get_bd_intf_pins axi_cc_xdma_in/S_AXI]

    # If the legacy axi_smc still exists from V0, keep it temporarily on
    # fabric_aclk so V6 can replace it cleanly.
    if {[get_bd_cells -quiet axi_smc] ne ""} {
        connect_bd_intf_net [get_bd_intf_pins axi_cc_xdma_in/M_AXI] [get_bd_intf_pins axi_smc/S00_AXI]
        catch { disconnect_bd_net [get_bd_nets -quiet xdma_0_axi_aclk] [get_bd_pins axi_smc/aclk] }
        set old_rstn_net [get_bd_nets -quiet -of_objects [get_bd_pins axi_smc/aresetn]]
        if {$old_rstn_net ne ""} {
            catch { disconnect_bd_net $old_rstn_net [get_bd_pins axi_smc/aresetn] }
        }
        connect_bd_net [get_bd_pins clk_wiz_fabric/clk_out1]            [get_bd_pins axi_smc/aclk]
        connect_bd_net [get_bd_pins rst_fabric_200M/peripheral_aresetn] [get_bd_pins axi_smc/aresetn]
    }
    puts "INFO: XDMA M_AXI now on fabric_aclk (200 MHz)."
}

# ─── V6 step — Remove SmartConnect; wire flat AXI chain to MIG C0 ─────────
proc _npu_step_remove_smc {} {
    puts "=== topology: remove axi_smc; flat clkconv+dwidth chain to MIG C0 ==="

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
    if {[get_bd_cells -quiet axi_smc] ne ""} { delete_bd_objs [get_bd_cells axi_smc] }

    foreach seg_path {
        /xdma_0/M_AXI/SEG_mig_7series_0_c0_memmap_memaddr
        /xdma_0/M_AXI/SEG_mig_7series_0_c1_memmap_memaddr
        /xdma_0/M_AXI/SEG_mig_7series_0_c0_memaddr
        /xdma_0/M_AXI/SEG_mig_7series_0_c1_memaddr
    } {
        set s [get_bd_addr_segs -quiet $seg_path]
        if {$s ne ""} { catch { delete_bd_objs $s } }
    }
    foreach slave_seg {
        /mig_7series_0/c0_memmap/c0_memaddr
        /mig_7series_0/c1_memmap/c1_memaddr
    } {
        catch { exclude_bd_addr_seg $slave_seg -target_address_space /xdma_0/M_AXI }
    }

    if {[get_bd_cells -quiet axi_clkconv_xdma] eq ""} {
        create_bd_cell -type ip -vlnv xilinx.com:ip:axi_clock_converter:2.1 axi_clkconv_xdma
        connect_bd_net [get_bd_pins clk_wiz_fabric/clk_out1]                       [get_bd_pins axi_clkconv_xdma/s_axi_aclk]
        connect_bd_net [get_bd_pins rst_fabric_200M/peripheral_aresetn]            [get_bd_pins axi_clkconv_xdma/s_axi_aresetn]
        connect_bd_net [get_bd_pins mig_7series_0/c0_ui_clk]                       [get_bd_pins axi_clkconv_xdma/m_axi_aclk]
        connect_bd_net [get_bd_pins rst_mig_7series_0_133M/peripheral_aresetn]     [get_bd_pins axi_clkconv_xdma/m_axi_aresetn]
    }

    if {[get_bd_cells -quiet axi_dwidth_xdma] eq ""} {
        create_bd_cell -type ip -vlnv xilinx.com:ip:axi_dwidth_converter:2.1 axi_dwidth_xdma
        set_property -dict [list \
            CONFIG.SI_DATA_WIDTH {128} \
            CONFIG.MI_DATA_WIDTH {512} \
            CONFIG.SI_ID_WIDTH   {4}   \
        ] [get_bd_cells axi_dwidth_xdma]
        connect_bd_net [get_bd_pins mig_7series_0/c0_ui_clk]                       [get_bd_pins axi_dwidth_xdma/s_axi_aclk]
        connect_bd_net [get_bd_pins rst_mig_7series_0_133M/peripheral_aresetn]     [get_bd_pins axi_dwidth_xdma/s_axi_aresetn]
    }

    connect_bd_intf_net [get_bd_intf_pins axi_cc_xdma_in/M_AXI]   [get_bd_intf_pins axi_clkconv_xdma/S_AXI]
    connect_bd_intf_net [get_bd_intf_pins axi_clkconv_xdma/M_AXI] [get_bd_intf_pins axi_dwidth_xdma/S_AXI]

    # Idle AXI VIP master on MIG C1 (temporary — will be replaced by V7 + V10 path).
    if {[get_bd_cells -quiet mig_c1_data_vip] eq ""} {
        create_bd_cell -type ip -vlnv xilinx.com:ip:axi_vip:1.1 mig_c1_data_vip
        set_property -dict [list \
            CONFIG.INTERFACE_MODE {MASTER} \
            CONFIG.PROTOCOL       {AXI4}   \
            CONFIG.ADDR_WIDTH     {32}     \
            CONFIG.DATA_WIDTH     {512}    \
            CONFIG.ID_WIDTH       {4}      \
        ] [get_bd_cells mig_c1_data_vip]
        connect_bd_net [get_bd_pins mig_7series_0/c1_ui_clk]                        [get_bd_pins mig_c1_data_vip/aclk]
        connect_bd_net [get_bd_pins rst_mig_7series_0_133M_1/peripheral_aresetn]    [get_bd_pins mig_c1_data_vip/aresetn]
        connect_bd_intf_net [get_bd_intf_pins mig_c1_data_vip/M_AXI] [get_bd_intf_pins mig_7series_0/S1_AXI]
    }
    puts "INFO: SmartConnect removed; flat axi_clkconv_xdma → axi_dwidth_xdma chain in place."
}

# ─── V7 step — NPU DMA master + path to MIG C1 ────────────────────────────
proc _npu_step_dma_master {rtl_src} {
    puts "=== topology: npu_dma_master + axi_clkconv_npu + axi_dwidth_npu → C1 ==="

    set dma_v [file join $rtl_src npu_dma_master.v]
    if {![file exists $dma_v]} {
        puts "ERROR: $dma_v not found"; exit 1
    }
    if {[get_files -quiet -of_objects [get_filesets sources_1] $dma_v] eq ""} {
        add_files -norecurse $dma_v
        update_compile_order -fileset sources_1
    }

    # Drop the V6-era VIP tie-off on MIG C1 so we can put a real master there
    set vip [get_bd_cells -quiet mig_c1_data_vip]
    if {$vip ne ""} {
        foreach n [get_bd_intf_nets -quiet -of_objects $vip] { delete_bd_objs $n }
        foreach n [get_bd_nets -quiet -of_objects $vip] { catch { delete_bd_objs $n } }
        delete_bd_objs $vip
    }

    if {[get_bd_cells -quiet axi_clkconv_npu] eq ""} {
        create_bd_cell -type ip -vlnv xilinx.com:ip:axi_clock_converter:2.1 axi_clkconv_npu
        connect_bd_net [get_bd_pins clk_wiz_fabric/clk_out1]                       [get_bd_pins axi_clkconv_npu/s_axi_aclk]
        connect_bd_net [get_bd_pins rst_fabric_200M/peripheral_aresetn]            [get_bd_pins axi_clkconv_npu/s_axi_aresetn]
        # Note: V7 used c1_ui_clk on the M side; V10's _npu_step_xbar re-binds
        # to c0_ui_clk so the entire NPU chain matches the XDMA chain.
        connect_bd_net [get_bd_pins mig_7series_0/c1_ui_clk]                       [get_bd_pins axi_clkconv_npu/m_axi_aclk]
        connect_bd_net [get_bd_pins rst_mig_7series_0_133M_1/peripheral_aresetn]   [get_bd_pins axi_clkconv_npu/m_axi_aresetn]
    }

    if {[get_bd_cells -quiet axi_dwidth_npu] eq ""} {
        create_bd_cell -type ip -vlnv xilinx.com:ip:axi_dwidth_converter:2.1 axi_dwidth_npu
        set_property -dict [list \
            CONFIG.SI_DATA_WIDTH {128} \
            CONFIG.MI_DATA_WIDTH {512} \
            CONFIG.SI_ID_WIDTH   {4}   \
        ] [get_bd_cells axi_dwidth_npu]
        connect_bd_net [get_bd_pins mig_7series_0/c1_ui_clk]                       [get_bd_pins axi_dwidth_npu/s_axi_aclk]
        connect_bd_net [get_bd_pins rst_mig_7series_0_133M_1/peripheral_aresetn]   [get_bd_pins axi_dwidth_npu/s_axi_aresetn]
    }
    connect_bd_intf_net [get_bd_intf_pins axi_clkconv_npu/M_AXI] [get_bd_intf_pins axi_dwidth_npu/S_AXI]
    connect_bd_intf_net [get_bd_intf_pins axi_dwidth_npu/M_AXI]  [get_bd_intf_pins mig_7series_0/S1_AXI]

    if {[get_bd_cells -quiet dma_master] eq ""} {
        create_bd_cell -type module -reference npu_dma_master dma_master
        connect_bd_net [get_bd_pins clk_wiz_fabric/clk_out1]            [get_bd_pins dma_master/aclk]
        connect_bd_net [get_bd_pins rst_fabric_200M/peripheral_aresetn] [get_bd_pins dma_master/aresetn]
        connect_bd_intf_net [get_bd_intf_pins dma_master/m_axi]         [get_bd_intf_pins axi_clkconv_npu/S_AXI]
    }

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

    puts "INFO: npu_dma_master wired (V7 baseline; will be wrapped by V10 into npu_subsys)."
}

# ─── V10 step — Replace ctrl_lite/dma_master with npu_subsys + axi_xbar ────
proc _npu_step_xbar_and_subsys {rtl_src} {
    puts "=== topology: V10 — npu_subsys + axi_xbar (2S:2M) + 4 GB address map ==="

    set subsys_v [file join $rtl_src npu_subsys.v]
    if {![file exists $subsys_v]} {
        puts "ERROR: $subsys_v not found"; exit 1
    }
    if {[get_files -quiet -of_objects [get_filesets sources_1] $subsys_v] eq ""} {
        add_files -norecurse $subsys_v
    }

    # The launch_runs flow needs `source_mgmt_mode = All` so the OOC IP run
    # spawned for npu_subsys can resolve the module-reference. We save the
    # caller's prior mode and restore it after synth_1 completes (in the
    # build script, after launch_runs synth_1).
    set ::_npu_save_mgmt_mode [get_property source_mgmt_mode [current_project]]
    set_property source_mgmt_mode All [current_project]
    update_compile_order -fileset sources_1
    puts "INFO: source_mgmt_mode set to All (was $::_npu_save_mgmt_mode); restored after synth."

    # Delete the V7-style ctrl_lite + dma_master cells. Don't blindly delete
    # `get_bd_nets -of_objects $cell` because that would include the SHARED
    # fabric clock distribution net.
    foreach cell {ctrl_lite dma_master} {
        set c [get_bd_cells -quiet $cell]
        if {$c ne ""} {
            foreach n [get_bd_intf_nets -quiet -of_objects $c] { catch { delete_bd_objs $n } }
            delete_bd_objs $c
        }
    }
    foreach cell {ctrl_done_const ctrl_busy_const} {
        set c [get_bd_cells -quiet $cell]
        if {$c ne ""} {
            foreach n [get_bd_nets -quiet -of_objects $c] { catch { delete_bd_objs $n } }
            delete_bd_objs $c
        }
    }

    # Re-bind the NPU AXI chain from c1_ui_clk to c0_ui_clk. The xbar then
    # handles the C0 vs C1 split with internal async FIFO on its M01 port.
    _npu_rebind_clk_rst axi_clkconv_npu m_axi_aclk m_axi_aresetn \
        mig_7series_0/c0_ui_clk rst_mig_7series_0_133M/peripheral_aresetn
    _npu_rebind_clk_rst axi_dwidth_npu s_axi_aclk s_axi_aresetn \
        mig_7series_0/c0_ui_clk rst_mig_7series_0_133M/peripheral_aresetn

    foreach intf {axi_dwidth_xdma/M_AXI axi_dwidth_npu/M_AXI} {
        set p [get_bd_intf_pins -quiet $intf]
        if {$p ne ""} {
            foreach n [get_bd_intf_nets -quiet -of_objects $p] {
                catch { delete_bd_objs $n }
            }
        }
    }

    if {[get_bd_cells -quiet axi_xbar] eq ""} {
        create_bd_cell -type ip -vlnv xilinx.com:ip:axi_interconnect:2.1 axi_xbar
        set_property -dict [list \
            CONFIG.NUM_SI               {2}   \
            CONFIG.NUM_MI               {2}   \
            CONFIG.STRATEGY             {0}   \
            CONFIG.S00_HAS_REGSLICE     {1}   \
            CONFIG.S01_HAS_REGSLICE     {1}   \
            CONFIG.M00_HAS_REGSLICE     {1}   \
            CONFIG.M01_HAS_REGSLICE     {1}   \
            CONFIG.M01_HAS_DATA_FIFO    {2}   \
        ] [get_bd_cells axi_xbar]

        connect_bd_net [get_bd_pins mig_7series_0/c0_ui_clk]                       [get_bd_pins axi_xbar/ACLK]
        connect_bd_net [get_bd_pins rst_mig_7series_0_133M/peripheral_aresetn]     [get_bd_pins axi_xbar/ARESETN]
        connect_bd_net [get_bd_pins mig_7series_0/c0_ui_clk]                       [get_bd_pins axi_xbar/S00_ACLK]
        connect_bd_net [get_bd_pins rst_mig_7series_0_133M/peripheral_aresetn]     [get_bd_pins axi_xbar/S00_ARESETN]
        connect_bd_net [get_bd_pins mig_7series_0/c0_ui_clk]                       [get_bd_pins axi_xbar/S01_ACLK]
        connect_bd_net [get_bd_pins rst_mig_7series_0_133M/peripheral_aresetn]     [get_bd_pins axi_xbar/S01_ARESETN]
        connect_bd_net [get_bd_pins mig_7series_0/c0_ui_clk]                       [get_bd_pins axi_xbar/M00_ACLK]
        connect_bd_net [get_bd_pins rst_mig_7series_0_133M/peripheral_aresetn]     [get_bd_pins axi_xbar/M00_ARESETN]
        connect_bd_net [get_bd_pins mig_7series_0/c1_ui_clk]                       [get_bd_pins axi_xbar/M01_ACLK]
        connect_bd_net [get_bd_pins rst_mig_7series_0_133M_1/peripheral_aresetn]   [get_bd_pins axi_xbar/M01_ARESETN]
    }

    connect_bd_intf_net [get_bd_intf_pins axi_dwidth_xdma/M_AXI] [get_bd_intf_pins axi_xbar/S00_AXI]
    connect_bd_intf_net [get_bd_intf_pins axi_dwidth_npu/M_AXI]  [get_bd_intf_pins axi_xbar/S01_AXI]
    connect_bd_intf_net [get_bd_intf_pins axi_xbar/M00_AXI]      [get_bd_intf_pins mig_7series_0/S0_AXI]
    connect_bd_intf_net [get_bd_intf_pins axi_xbar/M01_AXI]      [get_bd_intf_pins mig_7series_0/S1_AXI]

    # Single npu_subsys cell wraps ctrl_lite + dma_master + MMALU
    if {[get_bd_cells -quiet npu_subsys] eq ""} {
        create_bd_cell -type module -reference npu_subsys npu_subsys
    }
    connect_bd_net [get_bd_pins clk_wiz_fabric/clk_out1]            [get_bd_pins npu_subsys/aclk]
    connect_bd_net [get_bd_pins rst_fabric_200M/peripheral_aresetn] [get_bd_pins npu_subsys/aresetn]
    connect_bd_net [get_bd_pins mig_7series_0/c0_init_calib_complete] [get_bd_pins npu_subsys/c0_init_calib_complete]
    connect_bd_net [get_bd_pins mig_7series_0/c1_init_calib_complete] [get_bd_pins npu_subsys/c1_init_calib_complete]

    # AXI4 master to axi_clkconv_npu (replaces former dma_master/m_axi)
    connect_bd_intf_net [get_bd_intf_pins npu_subsys/m_axi] [get_bd_intf_pins axi_clkconv_npu/S_AXI]

    # AXI4-Lite slave from byp_pc/M_AXI — pin-by-pin (matches the V2 style)
    set pc [get_bd_cells byp_pc]
    set sub [get_bd_cells npu_subsys]
    connect_bd_net [get_bd_pins $pc/m_axi_awaddr]    [get_bd_pins $sub/s_axil_awaddr]
    connect_bd_net [get_bd_pins $pc/m_axi_awprot]    [get_bd_pins $sub/s_axil_awprot]
    connect_bd_net [get_bd_pins $pc/m_axi_awvalid]   [get_bd_pins $sub/s_axil_awvalid]
    connect_bd_net [get_bd_pins $sub/s_axil_awready] [get_bd_pins $pc/m_axi_awready]
    connect_bd_net [get_bd_pins $pc/m_axi_wdata]     [get_bd_pins $sub/s_axil_wdata]
    connect_bd_net [get_bd_pins $pc/m_axi_wstrb]     [get_bd_pins $sub/s_axil_wstrb]
    connect_bd_net [get_bd_pins $pc/m_axi_wvalid]    [get_bd_pins $sub/s_axil_wvalid]
    connect_bd_net [get_bd_pins $sub/s_axil_wready]  [get_bd_pins $pc/m_axi_wready]
    connect_bd_net [get_bd_pins $sub/s_axil_bresp]   [get_bd_pins $pc/m_axi_bresp]
    connect_bd_net [get_bd_pins $sub/s_axil_bvalid]  [get_bd_pins $pc/m_axi_bvalid]
    connect_bd_net [get_bd_pins $pc/m_axi_bready]    [get_bd_pins $sub/s_axil_bready]
    connect_bd_net [get_bd_pins $pc/m_axi_araddr]    [get_bd_pins $sub/s_axil_araddr]
    connect_bd_net [get_bd_pins $pc/m_axi_arprot]    [get_bd_pins $sub/s_axil_arprot]
    connect_bd_net [get_bd_pins $pc/m_axi_arvalid]   [get_bd_pins $sub/s_axil_arvalid]
    connect_bd_net [get_bd_pins $sub/s_axil_arready] [get_bd_pins $pc/m_axi_arready]
    connect_bd_net [get_bd_pins $sub/s_axil_rdata]   [get_bd_pins $pc/m_axi_rdata]
    connect_bd_net [get_bd_pins $sub/s_axil_rresp]   [get_bd_pins $pc/m_axi_rresp]
    connect_bd_net [get_bd_pins $sub/s_axil_rvalid]  [get_bd_pins $pc/m_axi_rvalid]
    connect_bd_net [get_bd_pins $pc/m_axi_rready]    [get_bd_pins $sub/s_axil_rready]

    # 4 GB unified address map. recreate_bd.tcl (the bootstrap) excludes both
    # C0 and C1 from xdma_0/M_AXI; we must explicitly include them back.
    set c0_seg [get_bd_addr_segs mig_7series_0/c0_memmap/c0_memaddr]
    set c1_seg [get_bd_addr_segs mig_7series_0/c1_memmap/c1_memaddr]
    foreach master_seg {
        /xdma_0/M_AXI/SEG_mig_7series_0_c0_memaddr
        /xdma_0/M_AXI/SEG_mig_7series_0_c1_memaddr
        /npu_subsys/m_axi/SEG_mig_7series_0_c0_memaddr
        /npu_subsys/m_axi/SEG_mig_7series_0_c1_memaddr
    } {
        set s [get_bd_addr_segs -quiet -excluded $master_seg]
        if {$s ne ""} { catch { include_bd_addr_seg $s } }
    }
    assign_bd_address -target_address_space /xdma_0/M_AXI    $c0_seg -range 2G -offset 0x00000000 -force
    assign_bd_address -target_address_space /xdma_0/M_AXI    $c1_seg -range 2G -offset 0x80000000 -force
    assign_bd_address -target_address_space /npu_subsys/m_axi $c0_seg -range 2G -offset 0x00000000 -force
    assign_bd_address -target_address_space /npu_subsys/m_axi $c1_seg -range 2G -offset 0x80000000 -force

    set_property top top_wrapper [get_filesets sources_1]
    update_compile_order -fileset sources_1
    puts "INFO: V10 npu_subsys + axi_xbar in place; 4 GB address map assigned."
}

# ─── Public entry point ────────────────────────────────────────────────────
#
# Build the full NPU topology, applying only the steps that are still missing
# from the currently-open BD. The proc auto-detects the starting state via
# cell-presence markers:
#
#   marker cell             →  what's already in place
#   ─────────────────────────────────────────────────────────────────────
#   microblaze_0_axi_periph →  V0 vendor baseline; need V1..V10
#   byp_dw / ctrl_lite      →  through V2
#   clk_wiz_fabric          →  through V3
#   axi_clkconv_byp         →  through V4
#   axi_cc_xdma_in          →  through V5
#   axi_clkconv_xdma        →  through V6 ("V9" baseline — what bootstrap delivers)
#   dma_master              →  through V7
#   axi_xbar + npu_subsys   →  V10 fully in place (no-op)
#
# `bootstrap_project.tcl` produces a V7-equivalent BD (it captures the
# end state of the historical V0..V9 bring-up), so for normal use only the
# V10 step actually runs.
#
# Arguments:
#   rtl_src — path to ip/vivado/xc7k480t/src (where npu_*.v live)
#
# Side effects:
#   - Sets source_mgmt_mode=All (saved into ::_npu_save_mgmt_mode); the
#     caller is responsible for restoring it before opening impl.
#   - Modifies the currently-open BD design (open_bd_design before calling).
proc apply_npu_topology {rtl_src} {
    puts "=========================================="
    puts "=== applying NPU topology               ==="
    puts "=========================================="

    set has_mb           [expr {[get_bd_cells -quiet microblaze_0_axi_periph] ne ""}]
    set has_byp          [expr {[get_bd_cells -quiet byp_dw]                 ne "" && \
                                [get_bd_cells -quiet ctrl_lite]              ne ""}]
    set has_mmcm         [expr {[get_bd_cells -quiet clk_wiz_fabric]         ne ""}]
    set has_byp_cdc      [expr {[get_bd_cells -quiet axi_clkconv_byp]        ne ""}]
    set has_xdma_cc      [expr {[get_bd_cells -quiet axi_cc_xdma_in]         ne ""}]
    set has_flat_chain   [expr {[get_bd_cells -quiet axi_clkconv_xdma]       ne ""}]
    set has_npu_dma      [expr {[get_bd_cells -quiet dma_master]             ne ""}]
    set has_xbar_subsys  [expr {[get_bd_cells -quiet axi_xbar]               ne "" && \
                                [get_bd_cells -quiet npu_subsys]             ne ""}]

    puts "INFO: BD state detection:"
    puts "  microblaze_0_axi_periph  : $has_mb (V0 baseline marker)"
    puts "  byp_dw + ctrl_lite       : $has_byp"
    puts "  clk_wiz_fabric           : $has_mmcm"
    puts "  axi_clkconv_byp          : $has_byp_cdc"
    puts "  axi_cc_xdma_in           : $has_xdma_cc"
    puts "  axi_clkconv_xdma         : $has_flat_chain (i.e. no SmartConnect)"
    puts "  dma_master               : $has_npu_dma"
    puts "  axi_xbar + npu_subsys    : $has_xbar_subsys (V10 target)"

    if {$has_mb}                 { _npu_step_remove_mb }
    if {!$has_byp}               { _npu_step_bypass_ctrl     $rtl_src }
    if {!$has_mmcm}              { _npu_step_mmcm }
    if {!$has_byp_cdc}           { _npu_step_byp_cdc }
    if {!$has_xdma_cc}           { _npu_step_xdma_cc }
    if {!$has_flat_chain}        { _npu_step_remove_smc }
    if {!$has_npu_dma}           { _npu_step_dma_master      $rtl_src }
    if {!$has_xbar_subsys}       { _npu_step_xbar_and_subsys $rtl_src }
    if {$has_xbar_subsys} {
        puts "INFO: V10 topology already present — nothing to apply."
        # Still set up source_mgmt_mode for module-ref resolution
        set ::_npu_save_mgmt_mode [get_property source_mgmt_mode [current_project]]
        set_property source_mgmt_mode All [current_project]
        update_compile_order -fileset sources_1
    }
    puts "=========================================="
    puts "=== NPU topology apply complete         ==="
    puts "=========================================="
}

# Restore source_mgmt_mode after synth_1 has run. Call this from the build
# script BEFORE open_run impl_1 / run_impl_and_write_bit.
proc npu_restore_mgmt_mode {} {
    if {[info exists ::_npu_save_mgmt_mode]} {
        set_property source_mgmt_mode $::_npu_save_mgmt_mode [current_project]
        set_property top top_wrapper [get_filesets sources_1]
        update_compile_order -fileset sources_1
        puts "INFO: source_mgmt_mode restored to $::_npu_save_mgmt_mode"
        unset ::_npu_save_mgmt_mode
    }
}
