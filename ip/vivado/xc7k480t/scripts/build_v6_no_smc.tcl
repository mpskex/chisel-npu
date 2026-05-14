################################################################################
# build_v6_no_smc.tcl — Step V6: Remove SmartConnect; wire NPU-style flat chain
#
# WHY SmartConnect was removed (not adapted):
#   The reference SmartConnect handles 200→133 MHz CDC internally via its
#   aclk1 port (bound to M00_AXI → MIG C0 at 133 MHz).  Any attempt to
#   insert an external clock converter on the M00_AXI output path breaks the
#   SmartConnect's internal aclk1 clock-domain association, causing generate_target
#   to drop the psr_aclk1 sub-IP and producing a functionally broken netlist.
#
#   The NPU design does NOT use SmartConnect — it chains standalone AXI IPs:
#     axi_cc_xdma_in (250→200) → axi_clkconv_xdma (200→133) → axi_dwidth_xdma (128→512) → MIG
#   This step replaces the SmartConnect with exactly that chain, matching the
#   NPU topology and avoiding the SmartConnect regeneration problem entirely.
#
# Delta from V5 (axi_cc_xdma_in on XDMA data path):
#   - Delete axi_smc (SmartConnect) and its address-map segments
#   - Add axi_clkconv_xdma: AXI clock converter 200 → 133 MHz (fabric → MIG C0)
#   - Add axi_dwidth_xdma: AXI data-width converter 128 → 512 bits
#   - Wire: axi_cc_xdma_in/M → clkconv/S → dwidth/S → MIG/S0_AXI
#   - Tie off MIG/S1_AXI with an AXI VIP master (C1 idle — calibrates but no traffic)
#   - Assign XDMA M_AXI address space to MIG C0 (1 GB at 0x0)
#
# Hypothesis: Replacing SmartConnect with the NPU's flat converter chain does
#             NOT break PCIe cold-boot training, and DDR3 C0 still passes.
#
# Success criterion:
#   - PCIe enumerates (10ee:7028)
#   - DDR3 C0 loopback 1 KB and 1 MB pass
#   - bypass_bar_accessible: reg[0] = 0x0
#
# Run from repo root:
#   XDMA_REF_XPR=/path/to/XC7K480T_XDMA_Test.xpr \
#   ~/Xilinx/2025.2/Vivado/bin/vivado -mode batch \
#       -source ip/vivado/xc7k480t/scripts/build_v6_no_smc.tcl \
#       -journal ip/vivado/xc7k480t/scripts/build_v6_no_smc.jou \
#       -log     ip/vivado/xc7k480t/scripts/build_v6_no_smc.log
################################################################################

set SCRIPT_DIR [file normalize [file dirname [info script]]]
set MIGRATE    [file normalize $SCRIPT_DIR/..]
set RTL_SRC    [file normalize [file join $MIGRATE src]]
set BIT_DST    [file join $MIGRATE top_v6_no_smc.bit]

source [file join $SCRIPT_DIR migrate_lib.tcl]
source [file join $SCRIPT_DIR _apply_v2.tcl]
source [file join $SCRIPT_DIR _apply_v5.tcl]

open_ref_project
assert_synth_done
open_bd_design [get_files {*/top.bd}]

# ── V1–V5 cumulative deltas ───────────────────────────────────────────────────
apply_v1_deletions               ;# remove MB, clk_wiz, rst_util; add VIP stubs
apply_v2_bypass_ctrl $RTL_SRC    ;# BYPASS → byp_dw → byp_pc → ctrl_lite (250 MHz)
apply_v3_mmcm_and_rst            ;# fabric MMCM 200 MHz + rst_fabric_200M
apply_v4_byp_cdc                 ;# move BYPASS path to 200 MHz via clock converter
apply_v5_xdma_cc                 ;# axi_cc_xdma_in (250→200) on XDMA data path

# ── V6: Remove SmartConnect; wire NPU-style flat converter chain ──────────────
puts "=== V6: removing SmartConnect; wiring NPU-style flat chain ==="

# 1. Disconnect external SmartConnect nets
foreach intf {S00_AXI M00_AXI M01_AXI} {
    set n [get_bd_intf_nets -quiet -of_objects [get_bd_intf_pins axi_smc/$intf]]
    if {$n ne ""} { delete_bd_objs $n }
}
# Disconnect axi_smc's clock/reset nets (scalar)
foreach net_pin {
    aclk    aresetn
    aclk1   aclk2
} {
    set p [get_bd_pins -quiet axi_smc/$net_pin]
    if {$p ne ""} {
        set net [get_bd_nets -quiet -of_objects $p]
        if {$net ne ""} { catch { disconnect_bd_net $net $p } }
    }
}

# 2. Delete the SmartConnect cell (plain IP — no appcore restriction)
delete_bd_objs [get_bd_cells axi_smc]
puts "INFO: axi_smc deleted."

# 3. Remove ALL M_AXI address segments that went through the SmartConnect.
#    Use exclude_bd_addr_seg to properly remove both assigned and pending segments.
foreach slave_seg {
    /mig_7series_0/c0_memmap/c0_memaddr
    /mig_7series_0/c1_memmap/c1_memaddr
    /mig_7series_0/c0_s_axi_ctrl_memmap/c0_s_axi_ctrl_memaddr
    /mig_7series_0/c1_s_axi_ctrl_memmap/c1_s_axi_ctrl_memaddr
} {
    # Try unassign first, then exclude
    catch { set_property offset {} [get_bd_addr_segs \
        /xdma_0/M_AXI/SEG_mig_7series_0_[string map {/ _} [lindex [split $slave_seg /] end]]] }
    catch { exclude_bd_addr_seg $slave_seg -target_address_space /xdma_0/M_AXI }
    # Also delete the seg object if it exists
    foreach seg_path [list \
        /xdma_0/M_AXI/SEG_mig_7series_0_c0_memmap_memaddr \
        /xdma_0/M_AXI/SEG_mig_7series_0_c1_memmap_memaddr \
        /xdma_0/M_AXI/SEG_mig_7series_0_c1_memaddr \
        /xdma_0/M_AXI/SEG_mig_7series_0_c0_memaddr \
    ] {
        set s [get_bd_addr_segs -quiet $seg_path]
        if {$s ne ""} { catch { delete_bd_objs $s } }
    }
}

# 4. Build the NPU-style flat converter chain to MIG C0
puts "INFO: creating axi_clkconv_xdma (200→133 MHz)"
create_bd_cell -type ip -vlnv xilinx.com:ip:axi_clock_converter:2.1 axi_clkconv_xdma
# S side: 200 MHz (fabric_aclk, same as axi_cc_xdma_in M side)
connect_bd_net [get_bd_pins clk_wiz_fabric/clk_out1]            [get_bd_pins axi_clkconv_xdma/s_axi_aclk]
connect_bd_net [get_bd_pins rst_fabric_200M/peripheral_aresetn] [get_bd_pins axi_clkconv_xdma/s_axi_aresetn]
# M side: 133 MHz (MIG C0 ui_clk)
connect_bd_net [get_bd_pins mig_7series_0/c0_ui_clk]            [get_bd_pins axi_clkconv_xdma/m_axi_aclk]
connect_bd_net [get_bd_pins rst_mig_7series_0_133M/peripheral_aresetn] \
               [get_bd_pins axi_clkconv_xdma/m_axi_aresetn]

puts "INFO: creating axi_dwidth_xdma (128→512 bits)"
create_bd_cell -type ip -vlnv xilinx.com:ip:axi_dwidth_converter:2.1 axi_dwidth_xdma
set_property -dict [list \
    CONFIG.SI_DATA_WIDTH {128} \
    CONFIG.MI_DATA_WIDTH {512} \
    CONFIG.SI_ID_WIDTH   {4}   \
] [get_bd_cells axi_dwidth_xdma]
# Both sides at 133 MHz
connect_bd_net [get_bd_pins mig_7series_0/c0_ui_clk]            [get_bd_pins axi_dwidth_xdma/s_axi_aclk]
connect_bd_net [get_bd_pins rst_mig_7series_0_133M/peripheral_aresetn] \
               [get_bd_pins axi_dwidth_xdma/s_axi_aresetn]

# Data path: axi_cc_xdma_in → axi_clkconv_xdma → axi_dwidth_xdma → MIG C0
connect_bd_intf_net [get_bd_intf_pins axi_cc_xdma_in/M_AXI]     [get_bd_intf_pins axi_clkconv_xdma/S_AXI]
connect_bd_intf_net [get_bd_intf_pins axi_clkconv_xdma/M_AXI]   [get_bd_intf_pins axi_dwidth_xdma/S_AXI]
connect_bd_intf_net [get_bd_intf_pins axi_dwidth_xdma/M_AXI]    [get_bd_intf_pins mig_7series_0/S0_AXI]
puts "INFO: NPU flat chain wired to MIG C0."

# 5. Tie off MIG C1 / S1_AXI with an idle AXI VIP master
#    (MIG C1 calibrates but receives no traffic — it stays powered and ready)
puts "INFO: adding AXI VIP idle master for MIG C1 data port"
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

# 6. Assign XDMA M_AXI address space to MIG C0 at offset 0x0 (2 GB range)
#    This must match the reference design: MIG C0 @ 0x0 [2G]
set c0_seg [get_bd_addr_segs mig_7series_0/c0_memmap/c0_memaddr]
assign_bd_address -target_address_space /xdma_0/M_AXI $c0_seg \
    -range 2G -offset 0x00000000 -force
puts "INFO: MIG C0 assigned to xdma_0/M_AXI at 0x0 [2G]"
# Exclude MIG AXI_CTRL from M_AXI (only accessible via VIP stubs)
catch { exclude_bd_addr_seg /mig_7series_0/c0_s_axi_ctrl_memmap/c0_s_axi_ctrl_memaddr \
    -target_address_space /xdma_0/M_AXI }

puts "INFO: V6 BD edits complete."
save_bd

puts "=== In-session synthesis ==="
run_synthesis

run_impl_and_write_bit "v6_no_smc" $BIT_DST
