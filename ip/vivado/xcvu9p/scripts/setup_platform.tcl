################################################################################
# setup_platform.tcl — one-time NPU-platform IP setup for ip/vivado/xcvu9p.
#
# Run once after bootstrapping the reference project (prj/prj.xpr):
#   vivado -mode batch -source ip/vivado/xcvu9p/scripts/setup_platform.tcl
#
# Configures:
#   * XDMA  M_AXI_BYPASS (BAR control -> ctrl_lite)
#   * DDR4  MIG C0.DDR4_AxiNarrowBurst = true
#   * clk_wiz_fabric MMCM (250 MHz axi_aclk -> 200 MHz engine clock)
#   * npu_dw (128->512), byp_dw (512->32), byp_pc, npu_cc, byp_cc converters
#
# Why the DDR4 MIG needs narrow bursts: the NPU engine's AXI master is 128-bit,
# so it issues 16-byte AXI beats. The DDR4 MIG defaults to full-width-only
# (AXI_Narrow_Burst=false); without narrow-burst support the engine's
# instruction fetch / single-beat vector transfers read 0xFFFFFFFF. Enabling
# C0.DDR4_AxiNarrowBurst lets the 128->512 converter stay unpacked
# (PACKING_LEVEL=1) and every transfer complete.
#
# IMPORTANT: the engine only works at 200 MHz (a 100 MHz fabric clock breaks
# the frontend fetch). Keep clk_wiz_fabric at 200 MHz.
################################################################################

set PROJ [file normalize [file join [file dirname [info script]] .. prj prj.xpr]]
if {![file exists $PROJ]} { puts "ERROR: project not found: $PROJ"; exit 1 }
open_project $PROJ

# ── DDR4 MIG: enable narrow bursts ───────────────────────────────────────────
set mig [get_ips ddr4_0]
set_property CONFIG.C0.DDR4_AxiNarrowBurst {true} $mig
puts "DDR4 narrow = [get_property CONFIG.C0.DDR4_AxiNarrowBurst $mig]"
reset_target all $mig
generate_target all $mig
if {[get_runs -quiet ddr4_0_synth_1] eq ""} { create_ip_run $mig }
reset_run ddr4_0_synth_1
launch_runs ddr4_0_synth_1 -jobs 1
wait_on_run ddr4_0_synth_1
puts "DDR4 synth = [get_property PROGRESS [get_runs ddr4_0_synth_1]]"

# ── XDMA: BAR-bypass (M_AXI_BYPASS -> ctrl_lite) ─────────────────────────────
set xd [get_ips xdma_0]
set_property -dict [list CONFIG.axist_bypass_en {true} CONFIG.axist_bypass_size {1}] $xd
puts "XDMA bypass = [get_property CONFIG.axist_bypass_en $xd]"

# ── fabric MMCM: axi_aclk 250 MHz -> 200 MHz engine clock ────────────────────
if {[get_ips -quiet clk_wiz_fabric] eq ""} {
    create_ip -name clk_wiz -vendor xilinx.com -library ip -module_name clk_wiz_fabric
}
set_property -dict [list \
    CONFIG.PRIM_IN_FREQ {250.000} CONFIG.CLKOUT1_REQUESTED_OUT_FREQ {200.000} \
    CONFIG.PRIMITIVE {MMCM} CONFIG.USE_LOCKED {true} CONFIG.USE_RESET {true} \
    CONFIG.RESET_TYPE {ACTIVE_LOW}] [get_ips clk_wiz_fabric]

# ── NPU master 128 -> 512 upsizer (PACKING_LEVEL=1, no packing) ──────────────
if {[get_ips -quiet npu_dw] eq ""} {
    create_ip -name axi_dwidth_converter -vendor xilinx.com -library ip -module_name npu_dw
}
set_property -dict [list CONFIG.SI_DATA_WIDTH {128} CONFIG.MI_DATA_WIDTH {512} \
    CONFIG.ADDR_WIDTH {64} CONFIG.SI_ID_WIDTH {4} CONFIG.PACKING_LEVEL {1}] [get_ips npu_dw]

# ── bypass BAR 512 -> 32 downsizer + AXI4 -> AXI4-Lite ───────────────────────
if {[get_ips -quiet byp_dw] eq ""} {
    create_ip -name axi_dwidth_converter -vendor xilinx.com -library ip -module_name byp_dw
}
set_property -dict [list CONFIG.SI_DATA_WIDTH {512} CONFIG.MI_DATA_WIDTH {32} \
    CONFIG.ADDR_WIDTH {64} CONFIG.SI_ID_WIDTH {4}] [get_ips byp_dw]
if {[get_ips -quiet byp_pc] eq ""} {
    create_ip -name axi_protocol_converter -vendor xilinx.com -library ip -module_name byp_pc
}
set_property -dict [list CONFIG.DATA_WIDTH {32} CONFIG.ADDR_WIDTH {64}] [get_ips byp_pc]

# ── clock converters ─────────────────────────────────────────────────────────
if {[get_ips -quiet npu_cc] eq ""} {
    create_ip -name axi_clock_converter -vendor xilinx.com -library ip -module_name npu_cc
}
set_property -dict [list CONFIG.DATA_WIDTH {128} CONFIG.ADDR_WIDTH {64} CONFIG.ID_WIDTH {4}] [get_ips npu_cc]
if {[get_ips -quiet byp_cc] eq ""} {
    create_ip -name axi_clock_converter -vendor xilinx.com -library ip -module_name byp_cc
}
set_property -dict [list CONFIG.DATA_WIDTH {32} CONFIG.ADDR_WIDTH {64} CONFIG.ID_WIDTH {0}] [get_ips byp_cc]

generate_target all [get_ips clk_wiz_fabric npu_dw byp_dw byp_pc npu_cc byp_cc]
puts "SETUP_DONE"
