# npu_top.xdc
# Board-level I/O and timing constraints for the NPU FPGA verification platform.
# Target: Xilinx xc7k480tffg1156-2, custom YPCB-00338-1P1 board
# ============================================================================

# ----------------------------------------------------------------------------
# 50 MHz single-ended board oscillator (user clock / debug)
# ----------------------------------------------------------------------------
set_property PACKAGE_PIN  AA28     [get_ports clk_50mhz]
set_property IOSTANDARD   LVCMOS33 [get_ports clk_50mhz]
create_clock -period 20.000 -name clk_50mhz [get_ports clk_50mhz]

# ----------------------------------------------------------------------------
# Active-high global reset
# ----------------------------------------------------------------------------
set_property PACKAGE_PIN  Y26      [get_ports sys_reset]
set_property IOSTANDARD   LVCMOS33 [get_ports sys_reset]

# ----------------------------------------------------------------------------
# PCIe Gen2 x8 — 100 MHz differential reference clock
# ----------------------------------------------------------------------------
set_property PACKAGE_PIN  J8       [get_ports sys_clk_p]

# ----------------------------------------------------------------------------
# PCIe GT lane placement — lanes X0Y16..Y23
# ----------------------------------------------------------------------------
set_property LOC GTXE2_CHANNEL_X0Y23 \
    [get_cells -hierarchical -filter {NAME =~ *xdma_inst*pipe_lane[0]*gtxe2_channel_i}]
set_property LOC GTXE2_CHANNEL_X0Y22 \
    [get_cells -hierarchical -filter {NAME =~ *xdma_inst*pipe_lane[1]*gtxe2_channel_i}]
set_property LOC GTXE2_CHANNEL_X0Y21 \
    [get_cells -hierarchical -filter {NAME =~ *xdma_inst*pipe_lane[2]*gtxe2_channel_i}]
set_property LOC GTXE2_CHANNEL_X0Y20 \
    [get_cells -hierarchical -filter {NAME =~ *xdma_inst*pipe_lane[3]*gtxe2_channel_i}]
set_property LOC GTXE2_CHANNEL_X0Y19 \
    [get_cells -hierarchical -filter {NAME =~ *xdma_inst*pipe_lane[4]*gtxe2_channel_i}]
set_property LOC GTXE2_CHANNEL_X0Y18 \
    [get_cells -hierarchical -filter {NAME =~ *xdma_inst*pipe_lane[5]*gtxe2_channel_i}]
set_property LOC GTXE2_CHANNEL_X0Y17 \
    [get_cells -hierarchical -filter {NAME =~ *xdma_inst*pipe_lane[6]*gtxe2_channel_i}]
set_property LOC GTXE2_CHANNEL_X0Y16 \
    [get_cells -hierarchical -filter {NAME =~ *xdma_inst*pipe_lane[7]*gtxe2_channel_i}]
set_property LOC PCIE_X0Y0 \
    [get_cells -hierarchical -filter {NAME =~ *xdma_inst*pcie_block_i}]

# ----------------------------------------------------------------------------
# PCIe TX pin assignments
# ----------------------------------------------------------------------------
set_property PACKAGE_PIN F2 [get_ports {pcie_txp[0]}]
set_property PACKAGE_PIN H2 [get_ports {pcie_txp[1]}]
set_property PACKAGE_PIN K2 [get_ports {pcie_txp[2]}]
set_property PACKAGE_PIN M2 [get_ports {pcie_txp[3]}]
set_property PACKAGE_PIN N4 [get_ports {pcie_txp[4]}]
set_property PACKAGE_PIN P2 [get_ports {pcie_txp[5]}]
set_property PACKAGE_PIN T2 [get_ports {pcie_txp[6]}]
set_property PACKAGE_PIN U4 [get_ports {pcie_txp[7]}]

# ----------------------------------------------------------------------------
# DDR3 C0 reference clock (200 MHz diff, bank 12)
# ----------------------------------------------------------------------------
set_property PACKAGE_PIN  AH27        [get_ports c0_sys_clk_p]
set_property PACKAGE_PIN  AH28        [get_ports c0_sys_clk_n]
set_property IOSTANDARD   DIFF_SSTL15 [get_ports c0_sys_clk_p]
set_property IOSTANDARD   DIFF_SSTL15 [get_ports c0_sys_clk_n]

# ----------------------------------------------------------------------------
# DDR3 C1 reference clock (200 MHz diff, bank 17)
# ----------------------------------------------------------------------------
set_property PACKAGE_PIN  G25         [get_ports c1_sys_clk_p]
set_property PACKAGE_PIN  G26         [get_ports c1_sys_clk_n]
set_property IOSTANDARD   DIFF_SSTL15 [get_ports c1_sys_clk_p]
set_property IOSTANDARD   DIFF_SSTL15 [get_ports c1_sys_clk_n]

set_property IOSTANDARD LVCMOS15 [get_ports c0_ddr3_reset_n]
set_property IOSTANDARD LVCMOS15 [get_ports c1_ddr3_reset_n]

# ----------------------------------------------------------------------------
# Simple false path on board reset (asynchronous)
# ----------------------------------------------------------------------------
set_false_path -from [get_ports sys_reset]

# ===========================================================================
# CDC constraints for 200 MHz fabric architecture.
#
# Clock domains:
#   userclk2    (250 MHz) — XDMA PCIe; drives XDMA M_AXI slave and proto_conv
#   fabric_aclk (200 MHz) — MMCM from userclk2; drives MMALU, DMA master, ctrl_lite,
#                           axi_clkconv slave sides, axi_cc_*_in master sides
#   clk_pll_i/1 (133 MHz) — MIG C0/C1 UI; drives clkconv master + dwidth + MIG AXI
#
# CDC crossings — all handled by proper IP FIFOs or 2-FF synchronizers:
#   userclk2 ↔ fabric_aclk  : axi_cc_xdma_in, axi_cc_byp_in (FIFO-based async CC)
#   fabric_aclk ↔ clk_pll_i : axi_clkconv_xdma, axi_clkconv_npu (FIFO-based async CC)
#   clk_pll_i → fabric_aclk : c0/c1_calib_sync1/2 (2-FF synchronizer in top_npu.v)
#
# -include_generated_clocks ensures fabric_aclk (generated from userclk2 via MMCM)
# is automatically included in the userclk2 group.
# ===========================================================================
set_clock_groups -asynchronous \
    -group [get_clocks {userclk2 userclk1}] \
    -group [get_clocks -include_generated_clocks {clk_out1_clk_wiz_0_1}] \
    -group [get_clocks {clk_pll_i clk_pll_i_1}]

# ASYNC_REG: calib_sync FFs now live in fabric_aclk (200 MHz) domain.
# The input (c0/c1_init_calib_complete) comes from clk_pll_i (133 MHz).
set_property ASYNC_REG TRUE \
    [get_cells -hierarchical -filter {NAME =~ *calib_sync*}]

# ===========================================================================
# MMALU internal timing: broad 2-cycle MCP covering all intra-MMALU paths.
#
# fabric_aclk = 200 MHz → period = 5 ns → 2-cycle budget = 10 ns.
# The 8-bit MAC CARRY4×13 chain (~4.3 ns logic) plus up to ~4 ns routing
# fits comfortably within the 10 ns budget.
# ===========================================================================
set_multicycle_path 2 -setup \
    -from [get_cells -hierarchical -filter {NAME =~ mmalu_inst/*}] \
    -to   [get_cells -hierarchical -filter {NAME =~ mmalu_inst/*}]
set_multicycle_path 1 -hold \
    -from [get_cells -hierarchical -filter {NAME =~ mmalu_inst/*}] \
    -to   [get_cells -hierarchical -filter {NAME =~ mmalu_inst/*}]

# ===========================================================================
# MIG UI-clock high-fanout write-buffer path (clk_pll_i, 7.5 ns period)
# app_wdf_rdy_r_copy2 has fanout 1154; 2-cycle MCP is safe because it is a
# write-buffer arbitration hint that tolerates 1 extra cycle of latency.
# ===========================================================================
set_multicycle_path 2 -setup -from \
    [get_cells -hierarchical -filter {NAME =~ *app_wdf_rdy_r_copy2_reg*}] \
    -to [get_cells -hierarchical -filter {NAME =~ *wr_buffer_ram*}]
set_multicycle_path 1 -hold  -from \
    [get_cells -hierarchical -filter {NAME =~ *app_wdf_rdy_r_copy2_reg*}] \
    -to [get_cells -hierarchical -filter {NAME =~ *wr_buffer_ram*}]

# ===========================================================================
# calib_sync reset MCP — c0/c1_calib_sync2_reg (fabric_aclk domain) gating MMALU and DMA.
# calib_complete is a one-shot signal that stays HIGH after DDR3 init.
# A 2-cycle MCP is safe: fabric_aresetn is asserted for ≥2 cycles before compute.
# At 200 MHz, 2-cycle budget = 10 ns (well above the ~4 ns route delay).
# ===========================================================================
set_multicycle_path 2 -setup \
    -from [get_cells {c0_calib_sync2_reg c1_calib_sync2_reg}] \
    -to   [get_cells -hierarchical -filter {NAME =~ mmalu_inst/*}]
set_multicycle_path 1 -hold \
    -from [get_cells {c0_calib_sync2_reg c1_calib_sync2_reg}] \
    -to   [get_cells -hierarchical -filter {NAME =~ mmalu_inst/*}]
set_multicycle_path 2 -setup \
    -from [get_cells {c0_calib_sync2_reg c1_calib_sync2_reg}] \
    -to   [get_cells -hierarchical -filter {NAME =~ dma_master_inst/*}]
set_multicycle_path 1 -hold \
    -from [get_cells {c0_calib_sync2_reg c1_calib_sync2_reg}] \
    -to   [get_cells -hierarchical -filter {NAME =~ dma_master_inst/*}]

# PBLOCK removed: placement-only PBLOCK improved timing (WNS -0.782→-0.522 ns)
# but caused global congestion level 6 at the PBLOCK right edge (X0:X63,
# Y144:Y207 at 112%) making routing fail. The dfeed/buffer_accum (73K FFs)
# requires routing across the full die; any hard boundary creates a pinch point.
