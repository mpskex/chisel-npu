################################################################################
# _apply_v10.tcl — V10 delta proc: wire MMALU + open 4 GB unified address map
#
# This is the new "production" topology that supersedes V9. It exists because
# the V9 stack passed 9/9 smoke tests but did NOT actually verify the MMALU
# compute path: V9 has the dma_master and ctrl_lite cells but never instantiates
# the Chisel MMALU module — top.sv is added to the fileset and synthesised, but
# the BD has no MMALU cell, so all of dma_master's io_in_a_*, io_in_b_*,
# io_in_accum_*, io_out_*, io_clct, io_ctrl_* pins get promoted as wrapper-top
# ports, end up unconnected, and the dma_master FSM stalls in S_WAIT_CLCT.
#
# V10 fixes the wiring and adds host visibility into both DDR3 banks.
#
# Delta from V7/V9 (assumes V7 BD topology is already in place):
#
#   1. Remove the standalone `ctrl_lite` and `dma_master` BD cells (V7 leaves
#      them as separate cells). They are replaced by a single `npu_subsys`
#      module cell whose Verilog wrapper (ip/vivado/xc7k480t/src/npu_subsys.v)
#      internally instantiates npu_ctrl_lite + npu_dma_master + MMALU and wires
#      every io_in_a_*, io_in_b_*, io_in_accum_*, io_out_*, io_clct,
#      io_ctrl_keep, io_ctrl_use_accum, io_ctrl_busy correctly.
#
#   2. Re-bind axi_clkconv_npu's master side and axi_dwidth_npu (both ports)
#      from c1_ui_clk to c0_ui_clk. After this step the entire NPU AXI4 chain
#      between npu_subsys/m_axi and the xbar lives in the c0_ui_clk (133 MHz)
#      domain, identical to the existing XDMA→C0 chain. This avoids redundant
#      CDC inside the xbar.
#
#   3. Drop the V7 direct connections:
#        axi_dwidth_xdma/M_AXI → mig_7series_0/S0_AXI
#        axi_dwidth_npu/M_AXI  → mig_7series_0/S1_AXI
#
#   4. Insert a 2S:2M AXI Interconnect (`axi_xbar`, xilinx.com:ip:axi_interconnect:2.1)
#      that lets BOTH XDMA and the NPU reach BOTH MIG C0 and C1:
#        S00_AXI ← axi_dwidth_xdma/M_AXI  (c0_ui_clk, 512b)
#        S01_AXI ← axi_dwidth_npu/M_AXI   (c0_ui_clk, 512b)
#        M00_AXI → mig_7series_0/S0_AXI   (c0_ui_clk, 512b — no CDC)
#        M01_AXI → mig_7series_0/S1_AXI   (c1_ui_clk, 512b — xbar inserts CDC)
#
#   5. Address space (identical for both M_AXI masters /xdma_0/M_AXI and
#      /npu_subsys/m_axi):
#        0x0000_0000 .. 0x7FFF_FFFF → C0 (2 GB)
#        0x8000_0000 .. 0xFFFF_FFFF → C1 (2 GB)
#      so a 32-bit address maps the full 4 GB of board DRAM linearly.
#
# After V10:
#   - Host writes A/B/ACCUM via /dev/xdma0_h2c_0 to any 4 GB offset; XDMA path
#     fans out through the xbar to whichever MIG bank the address maps to.
#   - NPU DMA master uses DEFAULT_BASE_A/B/ACCUM/OUT = 0x0000_0000_4000_0000
#     + small offsets (still inside C0, far from the host buffers at 0x0).
#   - ctrl_lite still answers on the same BAR2 + 0x0 register (start / done /
#     busy).
#
# Source after _apply_v7.tcl; called by build_v10_npu_xbar.tcl.
################################################################################

# Internal helper: disconnect any existing clk/reset nets on a cell's
# named clock/reset pins, then re-connect to a new clock and reset source.
proc _v10_rebind_clk_rst {cell aclk_pin aresetn_pin new_clk new_rst} {
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

proc apply_v10_xbar_and_subsys {rtl_src} {
    puts "=== _apply_v10: V10 — npu_subsys + axi_xbar (2S:2M) → C0 + C1 ==="

    # ── 0. Add npu_subsys.v to the project source set ─────────────────────────
    set subsys_v [file join $rtl_src npu_subsys.v]
    if {![file exists $subsys_v]} {
        puts "ERROR: $subsys_v not found"; exit 1
    }
    # Only add if not already in the fileset
    set existing [get_files -quiet -of_objects [get_filesets sources_1] $subsys_v]
    if {$existing eq ""} {
        add_files -norecurse $subsys_v
        puts "INFO: added npu_subsys.v"
    } else {
        puts "INFO: npu_subsys.v already in fileset"
    }

    # migrate_lib.tcl sets source_mgmt_mode = None to keep top_wrapper as the
    # design top, but this DISABLES module-reference resolution in Vivado:
    #   ERROR: [filemgmt 56-587] Failed to resolve reference. Nothing was found
    #   in the project to match the name npu_subsys
    # The fix is to flip back to automatic compile order, refresh the compile
    # order so Vivado sees the new Verilog module, then restore None after
    # all BD module-ref cells have been created.  We save the prior mode and
    # restore it at the end of this proc.
    set ::_v10_save_mgmt_mode [get_property source_mgmt_mode [current_project]]
    set_property source_mgmt_mode All [current_project]
    update_compile_order -fileset sources_1
    puts "INFO: source_mgmt_mode temporarily set to All (was $::_v10_save_mgmt_mode)"

    # ── 1. Remove the standalone ctrl_lite and dma_master cells ───────────────
    # V7 left ctrl_lite and dma_master as separate BD module cells, with the
    # AXI4-Lite handshake wired pin-by-pin from byp_pc to ctrl_lite and the
    # AXI4 master interface wired from dma_master to axi_clkconv_npu.
    #
    # IMPORTANT: We cannot blindly delete `get_bd_nets -of_objects $cell` here
    # because those nets include the shared clock/reset distribution (e.g.,
    # clk_wiz_fabric/clk_out1 fans out to byp_dw, byp_pc, axi_cc_xdma_in,
    # axi_clkconv_xdma, axi_clkconv_npu, ctrl_lite, dma_master). Deleting
    # that net would orphan the other peripherals.
    #
    # Safe pattern: delete only the cell's INTERFACE nets (point-to-point),
    # then delete the cell. Vivado auto-removes the cell's pin from any
    # remaining shared scalar nets without dropping the net itself.
    foreach cell {ctrl_lite dma_master} {
        set c [get_bd_cells -quiet $cell]
        if {$c ne ""} {
            # Delete interface nets attached to the cell (these are
            # point-to-point: dma_master/m_axi ↔ axi_clkconv_npu/S_AXI etc.).
            foreach n [get_bd_intf_nets -quiet -of_objects $c] {
                catch { delete_bd_objs $n }
            }
            # Do NOT delete scalar nets -of_objects — they include shared
            # clock/reset distribution. Just delete the cell; Vivado will
            # remove this cell's pin from each net and leave the net for
            # the remaining endpoints.
            delete_bd_objs $c
            puts "INFO: deleted BD cell '$cell'"
        }
    }

    # Any leftover xlconstant tie-offs for done/busy (V2 originally inserted
    # ctrl_done_const / ctrl_busy_const; V7 deletes them, but defensively
    # remove again in case the BD is in an intermediate state).
    foreach cell {ctrl_done_const ctrl_busy_const} {
        set c [get_bd_cells -quiet $cell]
        if {$c ne ""} {
            # These are isolated constants with no shared fanout — safe to
            # delete attached nets along with the cell.
            foreach n [get_bd_nets -quiet -of_objects $c] {
                catch { delete_bd_objs $n }
            }
            delete_bd_objs $c
            puts "INFO: deleted leftover '$cell'"
        }
    }

    # ── 2. Re-bind axi_clkconv_npu and axi_dwidth_npu to c0_ui_clk ────────────
    # V7 wired these to c1_ui_clk because the NPU master was destined for MIG
    # C1. With the new xbar, the NPU path joins the XDMA path at c0_ui_clk
    # (133 MHz from MIG C0) before the xbar. The xbar then handles the C0/C1
    # split, inserting CDC on its M01 port (to C1) where needed.
    _v10_rebind_clk_rst axi_clkconv_npu m_axi_aclk m_axi_aresetn \
        mig_7series_0/c0_ui_clk rst_mig_7series_0_133M/peripheral_aresetn
    _v10_rebind_clk_rst axi_dwidth_npu  s_axi_aclk  s_axi_aresetn  \
        mig_7series_0/c0_ui_clk rst_mig_7series_0_133M/peripheral_aresetn
    puts "INFO: axi_clkconv_npu/axi_dwidth_npu re-bound to c0_ui_clk."

    # ── 3. Drop the V7 direct connections to MIG ─────────────────────────────
    foreach intf {axi_dwidth_xdma/M_AXI axi_dwidth_npu/M_AXI} {
        set p [get_bd_intf_pins -quiet $intf]
        if {$p ne ""} {
            foreach n [get_bd_intf_nets -quiet -of_objects $p] {
                catch { delete_bd_objs $n }
            }
            puts "INFO: cleared intf nets on $intf"
        }
    }

    # ── 4. Create the 2S:2M axi_xbar ─────────────────────────────────────────
    # Configuration:
    #   NUM_SI = 2, NUM_MI = 2
    #   ACLK = c0_ui_clk (133 MHz)
    #   S00/S01/M00 on c0_ui_clk (synchronous to ACLK — no CDC needed)
    #   M01 on c1_ui_clk (different clock → need async-FIFO based CDC)
    #
    # Register slices are added on every port to help WNS.  On M01 we ALSO
    # enable HAS_DATA_FIFO so the interconnect inserts asynchronous FIFOs
    # (clock-domain crossings); register slices alone are synchronous and
    # would not safely cross from c0_ui_clk to c1_ui_clk.
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
    puts "INFO: axi_xbar created (2S:2M, M01 with async FIFO for c0→c1 CDC)."

    # ── 4a. Connect xbar clocks and resets ───────────────────────────────────
    # ACLK (arbitration domain) = c0_ui_clk
    connect_bd_net [get_bd_pins mig_7series_0/c0_ui_clk] \
                   [get_bd_pins axi_xbar/ACLK]
    connect_bd_net [get_bd_pins rst_mig_7series_0_133M/peripheral_aresetn] \
                   [get_bd_pins axi_xbar/ARESETN]
    # S00 (from XDMA path) — c0_ui_clk
    connect_bd_net [get_bd_pins mig_7series_0/c0_ui_clk] \
                   [get_bd_pins axi_xbar/S00_ACLK]
    connect_bd_net [get_bd_pins rst_mig_7series_0_133M/peripheral_aresetn] \
                   [get_bd_pins axi_xbar/S00_ARESETN]
    # S01 (from NPU path) — c0_ui_clk (we re-bound the converter chain above)
    connect_bd_net [get_bd_pins mig_7series_0/c0_ui_clk] \
                   [get_bd_pins axi_xbar/S01_ACLK]
    connect_bd_net [get_bd_pins rst_mig_7series_0_133M/peripheral_aresetn] \
                   [get_bd_pins axi_xbar/S01_ARESETN]
    # M00 → MIG C0 — c0_ui_clk
    connect_bd_net [get_bd_pins mig_7series_0/c0_ui_clk] \
                   [get_bd_pins axi_xbar/M00_ACLK]
    connect_bd_net [get_bd_pins rst_mig_7series_0_133M/peripheral_aresetn] \
                   [get_bd_pins axi_xbar/M00_ARESETN]
    # M01 → MIG C1 — c1_ui_clk (xbar handles CDC internally)
    connect_bd_net [get_bd_pins mig_7series_0/c1_ui_clk] \
                   [get_bd_pins axi_xbar/M01_ACLK]
    connect_bd_net [get_bd_pins rst_mig_7series_0_133M_1/peripheral_aresetn] \
                   [get_bd_pins axi_xbar/M01_ARESETN]

    # ── 4b. Connect data interfaces through the xbar ─────────────────────────
    connect_bd_intf_net [get_bd_intf_pins axi_dwidth_xdma/M_AXI] \
                        [get_bd_intf_pins axi_xbar/S00_AXI]
    connect_bd_intf_net [get_bd_intf_pins axi_dwidth_npu/M_AXI] \
                        [get_bd_intf_pins axi_xbar/S01_AXI]
    connect_bd_intf_net [get_bd_intf_pins axi_xbar/M00_AXI] \
                        [get_bd_intf_pins mig_7series_0/S0_AXI]
    connect_bd_intf_net [get_bd_intf_pins axi_xbar/M01_AXI] \
                        [get_bd_intf_pins mig_7series_0/S1_AXI]
    puts "INFO: axi_xbar fully wired (S00, S01, M00, M01)."

    # ── 5. Create the npu_subsys cell and wire it in ─────────────────────────
    # npu_subsys is a Verilog module wrapper that contains, internally:
    #   - npu_ctrl_lite (BAR2 AXI4-Lite CTRL register)
    #   - npu_dma_master (AXI4 master FSM, K=32, N=8)
    #   - MMALU         (Chisel-generated module from top.sv)
    # All MMALU↔DMA master ports are wired inside the .v file, so the BD only
    # needs to expose its AXI4-Lite slave (s_axil_*) and AXI4 master (m_axi_*)
    # interfaces, plus calibration-status info inputs and clock/reset.
    create_bd_cell -type module -reference npu_subsys npu_subsys
    puts "INFO: npu_subsys cell created."

    # Clock + reset (fabric 200 MHz domain, same as the deleted ctrl_lite +
    # dma_master used).
    connect_bd_net [get_bd_pins clk_wiz_fabric/clk_out1] \
                   [get_bd_pins npu_subsys/aclk]
    connect_bd_net [get_bd_pins rst_fabric_200M/peripheral_aresetn] \
                   [get_bd_pins npu_subsys/aresetn]

    # MIG calibration status (informational inputs; npu_subsys does not act on
    # them but the ports must be driven).
    connect_bd_net [get_bd_pins mig_7series_0/c0_init_calib_complete] \
                   [get_bd_pins npu_subsys/c0_init_calib_complete]
    connect_bd_net [get_bd_pins mig_7series_0/c1_init_calib_complete] \
                   [get_bd_pins npu_subsys/c1_init_calib_complete]

    # AXI4 master interface: npu_subsys/m_axi → axi_clkconv_npu/S_AXI
    # The dma_master cell formerly fed axi_clkconv_npu directly; now the
    # equivalent connection comes from inside npu_subsys.
    connect_bd_intf_net [get_bd_intf_pins npu_subsys/m_axi] \
                        [get_bd_intf_pins axi_clkconv_npu/S_AXI]
    puts "INFO: npu_subsys/m_axi → axi_clkconv_npu/S_AXI wired."

    # AXI4-Lite slave interface: byp_pc/m_axi_* → npu_subsys/s_axil_*
    # The npu_ctrl_lite.v interface naming (s_axil_*) is identical to what
    # _apply_v2.tcl used when wiring byp_pc to ctrl_lite, so we mirror that
    # pin-by-pin pattern (Vivado does not auto-bundle s_axil_* into an
    # interface in this BD).
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
    puts "INFO: npu_subsys/s_axil_* ↔ byp_pc/m_axi_* wired."

    # ── 6. Assign address segments ───────────────────────────────────────────
    # The host (xdma_0/M_AXI) and the NPU master (npu_subsys/m_axi) must see
    # the SAME 4 GB linear address space:
    #   0x0000_0000..0x7FFF_FFFF → MIG C0
    #   0x8000_0000..0xFFFF_FFFF → MIG C1
    #
    # CRITICAL: the bootstrapped BD (from recreate_bd.tcl) explicitly excludes
    # BOTH C0 and C1 from /xdma_0/M_AXI (it pre-dates V6r). The exclusion is
    # persisted as BD metadata. `assign_bd_address -force` alone does NOT
    # override an existing exclusion. We must explicitly `include_bd_addr_seg`
    # first, then `assign_bd_address` to set the offset / range.
    #
    # Without this, the host's writes to C0 (offset 0) hit the xbar with no
    # routing decode for that address → SLVERR → /dev/xdma0_h2c_0 write fails.
    set c0_seg [get_bd_addr_segs mig_7series_0/c0_memmap/c0_memaddr]
    set c1_seg [get_bd_addr_segs mig_7series_0/c1_memmap/c1_memaddr]

    # First, force-include both segments in xdma_0/M_AXI (this un-excludes any
    # prior exclude_bd_addr_seg calls). The include_bd_addr_seg command takes
    # the MASTER-side excluded segment path
    # (e.g. /xdma_0/M_AXI/SEG_mig_7series_0_c0_memaddr), not the source slave
    # segment. Check `get_bd_addr_segs -excluded ...` first so we only try to
    # include segments that are actually currently excluded; assign_bd_address
    # below handles the rest.
    foreach master_seg {
        /xdma_0/M_AXI/SEG_mig_7series_0_c0_memaddr
        /xdma_0/M_AXI/SEG_mig_7series_0_c1_memaddr
        /npu_subsys/m_axi/SEG_mig_7series_0_c0_memaddr
        /npu_subsys/m_axi/SEG_mig_7series_0_c1_memaddr
    } {
        set s [get_bd_addr_segs -quiet -excluded $master_seg]
        if {$s ne ""} {
            puts "INFO: include_bd_addr_seg $s"
            catch { include_bd_addr_seg $s }
        }
    }

    # Now assign the 2 GB segments at the correct offsets.
    assign_bd_address -target_address_space /xdma_0/M_AXI $c0_seg \
        -range 2G -offset 0x00000000 -force
    assign_bd_address -target_address_space /xdma_0/M_AXI $c1_seg \
        -range 2G -offset 0x80000000 -force
    assign_bd_address -target_address_space /npu_subsys/m_axi $c0_seg \
        -range 2G -offset 0x00000000 -force
    assign_bd_address -target_address_space /npu_subsys/m_axi $c1_seg \
        -range 2G -offset 0x80000000 -force

    # Verify both segments are now present in xdma_0/M_AXI (defensive check).
    set xdma_segs [get_bd_addr_segs -of_objects \
                       [get_bd_addr_spaces /xdma_0/M_AXI]]
    puts "INFO: /xdma_0/M_AXI now has [llength $xdma_segs] segment(s):"
    foreach s $xdma_segs {
        set off [get_property OFFSET $s]
        set rng [get_property RANGE  $s]
        puts "  $s  off=$off range=$rng"
    }
    if {[llength $xdma_segs] < 2} {
        puts "ERROR: V10 expects 2 segments (C0+C1) in /xdma_0/M_AXI; got [llength $xdma_segs]"
        puts "       Address-space include/assign for C0 or C1 did not stick."
        exit 1
    }
    set npu_segs [get_bd_addr_segs -of_objects \
                      [get_bd_addr_spaces /npu_subsys/m_axi]]
    puts "INFO: /npu_subsys/m_axi has [llength $npu_segs] segment(s):"
    foreach s $npu_segs {
        set off [get_property OFFSET $s]
        set rng [get_property RANGE  $s]
        puts "  $s  off=$off range=$rng"
    }

    # MIG ECC control ports remain on their own VIP masters (set up in V1):
    #   mig_c0_ctrl_vip / mig_c1_ctrl_vip → mig/S0_AXI_CTRL / S1_AXI_CTRL
    # No change required here.

    # Pin top_wrapper as the design top while keeping source_mgmt_mode = All.
    # The launch_runs child process must inherit source_mgmt_mode = All so it
    # can resolve module references to npu_subsys (and npu_dma_master /
    # npu_ctrl_lite, which are now instantiated inside npu_subsys.v). The
    # caller (build_v10_npu_xbar.tcl) restores source_mgmt_mode to the prior
    # value AFTER synth_1 completes, just before impl.
    set_property top top_wrapper [get_filesets sources_1]
    update_compile_order -fileset sources_1
    puts "INFO: top pinned to top_wrapper; source_mgmt_mode kept at All for synth"

    puts "INFO: V10 apply complete."
}
