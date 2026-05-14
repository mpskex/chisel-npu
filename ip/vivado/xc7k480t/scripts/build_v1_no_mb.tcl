################################################################################
# build_v1_no_mb.tcl  — Step V1: Remove MicroBlaze and its peripheral tree
#
# Delta from V0:
#   - Delete: microblaze_0_axi_periph (axi_interconnect), rst_util_ds_buf_100M,
#             util_ds_buf (IBUF for clk_in1), clk_wiz (100 MHz generator)
#   - The XDMA M_AXI_BYPASS net was connected to microblaze_0_axi_periph/S00_AXI.
#     After deletion we tie it off via a new constant AXI-Lite terminator OR
#     simply disconnect it (XDMA will not hang: it has no initiator for BYPASS
#     unless the host writes to BAR2 — safe to leave unconnected for now).
#   - Remove MIG S0_AXI_CTRL and S1_AXI_CTRL connections (were from MB periph).
#   - clk_in1_0 port and reset_rtl_0 → clk_wiz path: remove clk_wiz, keep
#     reset_rtl_0 driving mig sys_rst and xdma sys_rst_n directly.
#
# Hypothesis: MicroBlaze, its clock tree, and its BRAM are NOT required for
# PCIe link training or DDR3 calibration.
#
# Success criterion: PCIe enumerates (lspci shows 10ee:7028), DDR3 C0 loopback
# 1 KB and 1 MB pass.  ctrl_lite / BYPASS BAR not yet connected so
# bypass_bar_accessible will FAIL (expected).
#
# Run from repo root:
#   ~/Xilinx/2025.2/Vivado/bin/vivado -mode batch \
#       -source ip/vivado/xc7k480t/scripts/build_v1_no_mb.tcl \
#       -journal ip/vivado/xc7k480t/scripts/build_v1_no_mb.jou \
#       -log     ip/vivado/xc7k480t/scripts/build_v1_no_mb.log
################################################################################

set SCRIPT_DIR [file normalize [file dirname [info script]]]
set MIGRATE    [file normalize $SCRIPT_DIR/..]
set BIT_DST    [file join $MIGRATE top_v1_no_mb.bit]

source [file join $SCRIPT_DIR migrate_lib.tcl]

open_ref_project
assert_synth_done

# ── Open the BD for editing ────────────────────────────────────────────────────
open_bd_design [get_files {*/top.bd}]

puts "=== V1: removing MicroBlaze peripheral tree ==="

# 1. Disconnect XDMA M_AXI_BYPASS from microblaze_0_axi_periph/S00_AXI
#    (delete the net; BYPASS port will be left floating — legal in Vivado
#     since XDMA only drives it when BAR2 is written by host)
set bypass_net [get_bd_intf_nets -quiet xdma_0_M_AXI_BYPASS]
if {$bypass_net ne ""} {
    puts "INFO: disconnecting xdma_0_M_AXI_BYPASS net"
    delete_bd_objs $bypass_net
}

# 2. Disconnect MIG AXI_CTRL ports from MB periph
foreach ctrl_net {microblaze_0_axi_periph_M00_AXI microblaze_0_axi_periph_M01_AXI} {
    set n [get_bd_intf_nets -quiet $ctrl_net]
    if {$n ne ""} { delete_bd_objs $n }
}

# 3. Delete the MB peripheral hierarchy
#    axi_interconnect is an "appcore" — its internal nets cannot be deleted
#    directly (BD 41-738).  Delete ONLY the boundary interface nets that cross
#    the cell boundary, then delete the cell; Vivado removes internal nets.
#    Use -boundary_type upper to restrict get_bd_pins to the cell's own pins.
set mb_periph [get_bd_cells -quiet microblaze_0_axi_periph]
if {$mb_periph ne ""} {
    # axi_interconnect is an appcore: any attempt to enumerate or delete its
    # internal nets via -of_objects triggers BD 41-738.
    # Safe approach: delete known top-level interface nets by explicit name,
    # then delete the cell. Vivado's delete_bd_objs on a hierarchy cell
    # automatically removes its internal structure.
    foreach known_intf {
        xdma_0_M_AXI_BYPASS
        microblaze_0_axi_periph_M00_AXI
        microblaze_0_axi_periph_M01_AXI
    } {
        set n [get_bd_intf_nets -quiet $known_intf]
        if {$n ne ""} { delete_bd_objs $n }
    }
    # Delete the cell — Vivado handles internal net cleanup automatically
    delete_bd_objs $mb_periph
}

# 4. Delete rst_util_ds_buf_100M (was clocked by clk_wiz/clk_out1 = 100 MHz)
set rst100 [get_bd_cells -quiet rst_util_ds_buf_100M]
if {$rst100 ne ""} {
    delete_bd_objs [get_bd_nets -quiet -of_objects $rst100]
    delete_bd_objs $rst100
}

# 5. Delete clk_wiz (100 MHz generator, no longer needed)
#    First disconnect nets driving clk_wiz inputs
set cwiz [get_bd_cells -quiet clk_wiz]
if {$cwiz ne ""} {
    delete_bd_objs [get_bd_nets -quiet -of_objects $cwiz]
    delete_bd_objs $cwiz
}

# 6. Delete util_ds_buf (IBUF for PCIe refclock — WAIT: this is the PCIe
#    reference clock buffer feeding xdma_0/sys_clk!  Keep it.)
#    Actually util_ds_buf feeds BOTH xdma_0/sys_clk AND clk_wiz/clk_in1.
#    We only need to remove the clk_wiz connection, which is already gone
#    after step 5.  util_ds_buf stays.
puts "INFO: util_ds_buf kept (feeds xdma_0/sys_clk)"

# 7. Remove unused clk_in1_0 top-level port (was input to clk_wiz)
set p [get_bd_ports -quiet clk_in1_0]
if {$p ne ""} {
    delete_bd_objs [get_bd_nets -quiet -of_objects $p]
    delete_bd_objs $p
}

# 8. ARESETN for XDMA was driven to microblaze's ARESETN/S00_ARESETN.
#    Net ARESETN_1 is now dangling — remove it.
set aren [get_bd_nets -quiet ARESETN_1]
if {$aren ne ""} { delete_bd_objs $aren }

# 9. axi_smc still needs its aclk/aresetn.
#    aclk:    xdma_0/axi_aclk → axi_smc/aclk           (net xdma_0_axi_aclk, unchanged)
#    aresetn: rst_mig_7series_0_133M/peripheral_aresetn (net unchanged)
#    Both are still intact — no action needed.

# 10b. MIG S0_AXI_CTRL and S1_AXI_CTRL were driven by MB periph.
#      Now disconnected → add AXI VIP passthrough master stubs so Vivado
#      can generate the BD wrapper (S_AXI_CTRL is a required port).
#      VIP in MASTER mode drives AXI signals; it synthesises to a simple stub.
puts "INFO: adding axi_vip stub masters for MIG S0/S1_AXI_CTRL"
foreach {vip_name mig_port mig_clk mig_rst} {
    mig_c0_ctrl_vip  S0_AXI_CTRL  mig_7series_0/c0_ui_clk  rst_mig_7series_0_133M/peripheral_aresetn
    mig_c1_ctrl_vip  S1_AXI_CTRL  mig_7series_0/c1_ui_clk  rst_mig_7series_0_133M_1/peripheral_aresetn
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

# 10. Remove stale address segments that referenced the now-deleted MB periph.
#     With M_AXI_BYPASS disconnected, any address map entries pointing from
#     xdma_0/M_AXI_BYPASS to MIG AXI_CTRL slaves are invalid and must be
#     removed before validate_bd_design.
foreach seg_path {
    /xdma_0/M_AXI_BYPASS/SEG_mig_7series_0_c0_s_axi_ctrl_memaddr
    /xdma_0/M_AXI_BYPASS/SEG_mig_7series_0_c1_s_axi_ctrl_memaddr
} {
    set seg [get_bd_addr_segs -quiet $seg_path]
    if {$seg ne ""} {
        puts "INFO: removing stale address segment $seg_path"
        catch { delete_bd_objs $seg }
    }
}
# Also try exclude_bd_addr_seg as a fallback
catch { exclude_bd_addr_seg /mig_7series_0/c0_s_axi_ctrl_memmap/c0_s_axi_ctrl_memaddr \
    -target_address_space /xdma_0/M_AXI_BYPASS }
catch { exclude_bd_addr_seg /mig_7series_0/c1_s_axi_ctrl_memmap/c1_s_axi_ctrl_memaddr \
    -target_address_space /xdma_0/M_AXI_BYPASS }

puts "INFO: V1 BD edits complete."
save_bd

# ── Re-run synthesis from scratch (BD changed) ─────────────────────────────────
puts "=== In-session synthesis ==="
run_synthesis

run_impl_and_write_bit "v1_no_mb" $BIT_DST
