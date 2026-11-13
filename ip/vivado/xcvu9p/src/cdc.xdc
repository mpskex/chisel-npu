# Async CDC between the 250 MHz axi_aclk and the 100 MHz fabric:
#   * npu_ctrl_cdc (hand-written toggle/ack ctrl bridge)
#   * npu_cc (512-bit AXI clock converter)
# The MMCM-derived fabric clock is treated as asynchronous to axi_aclk; both
# crossings use CDC structures, so no synchronous paths exist between them.
set_clock_groups -asynchronous \
  -group [get_clocks -quiet clk_out1_clk_wiz_fabric] \
  -group [get_clocks -quiet axi_aclk]
