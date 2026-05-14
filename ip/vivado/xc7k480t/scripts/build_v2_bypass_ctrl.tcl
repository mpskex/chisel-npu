################################################################################
# build_v2_bypass_ctrl.tcl  — Step V2: Expose BYPASS + connect npu_ctrl_lite
#
# Delta from V1 (no MicroBlaze):
#   - Add npu_ctrl_lite RTL module (32-bit AXI-Lite slave, 4 KB)
#   - Insert axi_dwidth_converter (128b→32b) between XDMA M_AXI_BYPASS and
#     npu_ctrl_lite (XDMA BYPASS is 128-bit wide)
#   - Clock: xdma_0/axi_aclk (250 MHz, same clock domain, no CDC needed here)
#   - Reset: xdma_0/axi_aresetn drives ctrl_lite aresetn
#   - done/busy/start of ctrl_lite tied off (no DMA master yet → always 0)
#
# Hypothesis: Adding a simple AXI-Lite slave on the BYPASS path does not
# affect PCIe cold-boot training.
#
# Success criterion:
#   - PCIe enumerates (10ee:7028)
#   - DDR3 C0 loopback passes
#   - bypass_bar_accessible: read reg[0] → 0x0 (start=done=busy=0)
#
# Run from repo root:
#   ~/Xilinx/2025.2/Vivado/bin/vivado -mode batch \
#       -source ip/vivado/xc7k480t/scripts/build_v2_bypass_ctrl.tcl \
#       -journal ip/vivado/xc7k480t/scripts/build_v2_bypass_ctrl.jou \
#       -log     ip/vivado/xc7k480t/scripts/build_v2_bypass_ctrl.log
################################################################################

set SCRIPT_DIR [file normalize [file dirname [info script]]]
set MIGRATE    [file normalize $SCRIPT_DIR/..]
set RTL_SRC    [file normalize [file join $MIGRATE src]]
set BIT_DST    [file join $MIGRATE top_v2_bypass_ctrl.bit]

source [file join $SCRIPT_DIR migrate_lib.tcl]

open_ref_project
assert_synth_done

# ── Add RTL source for npu_ctrl_lite ─────────────────────────────────────────
set ctrl_v [file normalize [file join $RTL_SRC npu_ctrl_lite.v]]
if {![file exists $ctrl_v]} {
    puts "ERROR: $ctrl_v not found."; exit 1
}
add_files -norecurse $ctrl_v
update_compile_order -fileset sources_1

# ── Open BD ───────────────────────────────────────────────────────────────────
open_bd_design [get_files {*/top.bd}]

puts "=== V2: applying V1 delta (remove MB) + BYPASS ctrl_lite ==="

# ── Apply V1 deletions (delegate to shared proc) ─────────────────────────────
source [file join $SCRIPT_DIR _apply_v2.tcl]
apply_v1_deletions

# ── V2: Add axi_dwidth_converter 128b→32b for BYPASS path ────────────────────
puts "INFO: adding axi_dwidth_converter (byp_dw)"
create_bd_cell -type ip -vlnv xilinx.com:ip:axi_dwidth_converter:2.1 byp_dw
set_property -dict [list \
    CONFIG.SI_DATA_WIDTH  {128} \
    CONFIG.MI_DATA_WIDTH  {32}  \
    CONFIG.SI_ID_WIDTH    {4}   \
] [get_bd_cells byp_dw]

# ── V2: Add npu_ctrl_lite RTL module ─────────────────────────────────────────
puts "INFO: adding npu_ctrl_lite module"
create_bd_cell -type module -reference npu_ctrl_lite ctrl_lite

# Tie off user-side ports: done=0, busy=0 (no DMA master yet)
set done_const [create_bd_cell -type ip -vlnv xilinx.com:ip:xlconstant:1.1 ctrl_done_const]
set_property CONFIG.CONST_VAL {0} [get_bd_cells ctrl_done_const]
set_property CONFIG.CONST_WIDTH {1} [get_bd_cells ctrl_done_const]

set busy_const [create_bd_cell -type ip -vlnv xilinx.com:ip:xlconstant:1.1 ctrl_busy_const]
set_property CONFIG.CONST_VAL {0} [get_bd_cells ctrl_busy_const]
set_property CONFIG.CONST_WIDTH {1} [get_bd_cells ctrl_busy_const]

# ── Connect clocks and resets ─────────────────────────────────────────────────
# xdma_0/axi_aclk → byp_dw/s_axi_aclk and ctrl_lite/axi_aclk
connect_bd_net [get_bd_pins xdma_0/axi_aclk] \
    [get_bd_pins byp_dw/s_axi_aclk] \
    [get_bd_pins ctrl_lite/axi_aclk]

# xdma_0/axi_aresetn → byp_dw/s_axi_aresetn and ctrl_lite/axi_aresetn
connect_bd_net [get_bd_pins xdma_0/axi_aresetn] \
    [get_bd_pins byp_dw/s_axi_aresetn] \
    [get_bd_pins ctrl_lite/axi_aresetn]

# ── Connect AXI interfaces ────────────────────────────────────────────────────
# XDMA M_AXI_BYPASS (128b) → byp_dw S_AXI (128b)
connect_bd_intf_net [get_bd_intf_pins xdma_0/M_AXI_BYPASS] \
    [get_bd_intf_pins byp_dw/S_AXI]

# byp_dw M_AXI (32b) → ctrl_lite s_axil_* (32b)
# npu_ctrl_lite has flat ports, not a packaged AXI interface, so connect
# individual port buses via a connection helper net.
# NOTE: byp_dw M_AXI is a standard AXI4 interface; ctrl_lite expects AXI-Lite
# (no burst length, no ID, no cache, no prot beyond wstrb).
# Use an AXI Protocol Converter to downgrade AXI4→AXI4-Lite, then connect
# the flat AXI-Lite signals manually.

puts "INFO: adding axi_protocol_converter (byp_pc) AXI4→AXI4-Lite"
create_bd_cell -type ip -vlnv xilinx.com:ip:axi_protocol_converter:2.1 byp_pc
set_property -dict [list \
    CONFIG.MI_PROTOCOL {AXI4LITE} \
    CONFIG.SI_PROTOCOL {AXI4}     \
    CONFIG.DATA_WIDTH  {32}       \
    CONFIG.ID_WIDTH    {4}        \
] [get_bd_cells byp_pc]

connect_bd_net [get_bd_pins xdma_0/axi_aclk]    [get_bd_pins byp_pc/aclk]
connect_bd_net [get_bd_pins xdma_0/axi_aresetn]  [get_bd_pins byp_pc/aresetn]
connect_bd_intf_net [get_bd_intf_pins byp_dw/M_AXI] [get_bd_intf_pins byp_pc/S_AXI]

# byp_pc M_AXI (AXI4-Lite, 32b) → ctrl_lite flat s_axil_* ports
set pc [get_bd_cells byp_pc]
set cl [get_bd_cells ctrl_lite]

# AW channel
connect_bd_net [get_bd_pins $pc/m_axi_awaddr]  [get_bd_pins $cl/s_axil_awaddr]
connect_bd_net [get_bd_pins $pc/m_axi_awprot]  [get_bd_pins $cl/s_axil_awprot]
connect_bd_net [get_bd_pins $pc/m_axi_awvalid] [get_bd_pins $cl/s_axil_awvalid]
connect_bd_net [get_bd_pins $cl/s_axil_awready] [get_bd_pins $pc/m_axi_awready]
# W channel
connect_bd_net [get_bd_pins $pc/m_axi_wdata]   [get_bd_pins $cl/s_axil_wdata]
connect_bd_net [get_bd_pins $pc/m_axi_wstrb]   [get_bd_pins $cl/s_axil_wstrb]
connect_bd_net [get_bd_pins $pc/m_axi_wvalid]  [get_bd_pins $cl/s_axil_wvalid]
connect_bd_net [get_bd_pins $cl/s_axil_wready] [get_bd_pins $pc/m_axi_wready]
# B channel
connect_bd_net [get_bd_pins $cl/s_axil_bresp]  [get_bd_pins $pc/m_axi_bresp]
connect_bd_net [get_bd_pins $cl/s_axil_bvalid] [get_bd_pins $pc/m_axi_bvalid]
connect_bd_net [get_bd_pins $pc/m_axi_bready]  [get_bd_pins $cl/s_axil_bready]
# AR channel
connect_bd_net [get_bd_pins $pc/m_axi_araddr]  [get_bd_pins $cl/s_axil_araddr]
connect_bd_net [get_bd_pins $pc/m_axi_arprot]  [get_bd_pins $cl/s_axil_arprot]
connect_bd_net [get_bd_pins $pc/m_axi_arvalid] [get_bd_pins $cl/s_axil_arvalid]
connect_bd_net [get_bd_pins $cl/s_axil_arready] [get_bd_pins $pc/m_axi_arready]
# R channel
connect_bd_net [get_bd_pins $cl/s_axil_rdata]  [get_bd_pins $pc/m_axi_rdata]
connect_bd_net [get_bd_pins $cl/s_axil_rresp]  [get_bd_pins $pc/m_axi_rresp]
connect_bd_net [get_bd_pins $cl/s_axil_rvalid] [get_bd_pins $pc/m_axi_rvalid]
connect_bd_net [get_bd_pins $pc/m_axi_rready]  [get_bd_pins $cl/s_axil_rready]

# ── Tie off user-side ports ───────────────────────────────────────────────────
connect_bd_net [get_bd_pins ctrl_done_const/dout] [get_bd_pins ctrl_lite/done]
connect_bd_net [get_bd_pins ctrl_busy_const/dout] [get_bd_pins ctrl_lite/busy]
# start output of ctrl_lite is left unconnected (no DMA master yet)

puts "INFO: V2 BD edits complete."
save_bd

# ── Re-synthesise ─────────────────────────────────────────────────────────────
puts "=== In-session synthesis ==="
run_synthesis

run_impl_and_write_bit "v2_bypass_ctrl" $BIT_DST
