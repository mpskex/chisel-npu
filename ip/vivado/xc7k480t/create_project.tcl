# create_project.tcl
# Vivado 2025.2 batch script — NPU FPGA verification platform
# Target : xc7k480tffg1156-2  (custom YPCB-00338-1P1 board)
#
# Usage:
#   ~/Xilinx/2025.2/Vivado/bin/vivado -mode batch -source create_project.tcl
#
# What this script does:
#   1. Copies top.sv (Chisel MMALU output) from repo root into src/
#   2. Creates Vivado project npu_fpga/ under this directory
#   3. Adds all hand-written RTL sources (top_npu.v, npu_*.v, top.sv)
#   4. Creates and configures three Xilinx IPs:
#        xdma_0               — PCIe Gen2 ×8 DMA IP (XDMA 4.1)
#        mig_7series_0        — Dual DDR3 MIG controller (4.2)
#        axi_smc              — AXI SmartConnect 2SI/2MI/3CLK (1.0)
#        axi_dwidth_converter_0 — 128→32-bit AXI-Lite downsizer (1.1)
#   5. Adds I/O + timing constraints
#   6. Sets top_npu as the top-level module
#   7. Launches synthesis (4 jobs) and waits; reports status
# ============================================================================

# ---------------------------------------------------------------------------
# Paths
# ---------------------------------------------------------------------------
set script_dir [file dirname [file normalize [info script]]]
set repo_root  [file normalize [file join $script_dir .. .. ..]]
set proj_dir   "$script_dir/npu_fpga"
set src_dir    "$script_dir/src"
set data_dir   "$script_dir/data"
set constr_dir "$script_dir/constrs"

puts "INFO: script_dir = $script_dir"
puts "INFO: repo_root  = $repo_root"

# ---------------------------------------------------------------------------
# Copy Chisel-generated top.sv from repo root
# ---------------------------------------------------------------------------
set top_sv_src "$repo_root/top.sv"
set top_sv_dst "$src_dir/top.sv"

if {[file exists $top_sv_src]} {
    file copy -force $top_sv_src $top_sv_dst
    puts "INFO: Copied top.sv → $top_sv_dst"
} else {
    puts "WARNING: $top_sv_src not found. Run 'make build' first to generate top.sv."
    puts "WARNING: Continuing without top.sv — synthesis will fail until it is present."
}

# ---------------------------------------------------------------------------
# Create project
# ---------------------------------------------------------------------------
create_project npu_fpga $proj_dir -part xc7k480tffg1156-2 -force
set_property target_language  Verilog [current_project]
set_property simulator_language Mixed  [current_project]

# ---------------------------------------------------------------------------
# Add RTL sources
# ---------------------------------------------------------------------------
add_files [list \
    "$src_dir/top_npu.v"        \
    "$src_dir/npu_ctrl_lite.v"  \
    "$src_dir/npu_dma_master.v" \
]

# top.sv is SystemVerilog; add separately so Vivado sets file_type correctly
if {[file exists $top_sv_dst]} {
    add_files $top_sv_dst
    set_property file_type {SystemVerilog} [get_files $top_sv_dst]
}

# ---------------------------------------------------------------------------
# Add constraints
# ---------------------------------------------------------------------------
add_files -fileset constrs_1 "$constr_dir/npu_top.xdc"
set_property used_in_synthesis  true  [get_files "$constr_dir/npu_top.xdc"]
set_property used_in_implementation true [get_files "$constr_dir/npu_top.xdc"]

# ---------------------------------------------------------------------------
# IP 1: XDMA 4.1 — PCIe Gen2 ×8, AXI-MM, 128-bit, 250 MHz
# ---------------------------------------------------------------------------
puts "INFO: Creating xdma_0 IP..."
create_ip \
    -name xdma \
    -vendor xilinx.com \
    -library ip \
    -version 4.2 \
    -module_name xdma_0

set_property -dict [list \
    CONFIG.functional_mode           {DMA}              \
    CONFIG.mode_selection            {Basic}            \
    CONFIG.pl_link_cap_max_link_width {X8}              \
    CONFIG.pl_link_cap_max_link_speed {5.0_GT/s}        \
    CONFIG.axi_data_width            {128_bit}          \
    CONFIG.axisten_freq              {250}              \
    CONFIG.axi_addr_width            {64}               \
    CONFIG.xdma_wnum_chnl            {2}                \
    CONFIG.xdma_rnum_chnl            {2}                \
    CONFIG.axist_bypass_en           {true}             \
    CONFIG.axist_bypass_size         {1}                \
    CONFIG.axist_bypass_scale        {Megabytes}        \
    CONFIG.axi_bypass_64bit_en       {true}             \
    CONFIG.pf0_bar0_enabled          {true}             \
    CONFIG.pf0_bar0_type             {Memory}           \
    CONFIG.pf0_bar0_size             {128}              \
    CONFIG.pf0_bar0_scale            {Kilobytes}        \
    CONFIG.pf0_msix_enabled          {true}             \
    CONFIG.pf0_msix_cap_table_size   {01F}              \
    CONFIG.pf0_msix_cap_table_offset {8000}             \
    CONFIG.pf0_msix_cap_pba_offset   {8FC0}             \
    CONFIG.pf0_device_id             {7028}             \
    CONFIG.vendor_id                 {10EE}             \
] [get_ips xdma_0]

generate_target all [get_ips xdma_0]
puts "INFO: xdma_0 done."

# ---------------------------------------------------------------------------
# IP 2: MIG 7-Series 4.2 — Dual DDR3, 72-bit ECC, 512-bit AXI
# The mig_a.prj file carries all pin, timing, and controller parameters.
# ---------------------------------------------------------------------------
puts "INFO: Creating mig_7series_0 IP..."
create_ip \
    -name mig_7series \
    -vendor xilinx.com \
    -library ip \
    -version 4.2 \
    -module_name mig_7series_0

# Point to the mig_a.prj file using a path relative to the IP output directory.
# Vivado resolves CONFIG.XML_INPUT_FILE relative to the IP gen_directory, which
# Vivado sets to <proj>/<proj>.gen/sources_1/ip/mig_7series_0 by default.
# We therefore supply an absolute path here to guarantee correct resolution.
set_property -dict [list \
    CONFIG.XML_INPUT_FILE        [file normalize "$data_dir/mig_a.prj"] \
    CONFIG.RESET_BOARD_INTERFACE {Custom}                                \
    CONFIG.MIG_DONT_TOUCH_PARAM  {Custom}                                \
    CONFIG.BOARD_MIG_PARAM       {Custom}                                \
] [get_ips mig_7series_0]

generate_target all [get_ips mig_7series_0]
puts "INFO: mig_7series_0 done."

# ---------------------------------------------------------------------------
# IPs 3a-3d: AXI bridge chain — clock conversion then width conversion per DDR channel.
#
# Both SmartConnect and AXI Interconnect are IPI-only in Vivado 2025.2.
# For a pure-RTL project we wire clock-converters and width-converters
# directly, giving each master its own dedicated DDR channel.
#
# REORDERED topology (Tier-2.5 timing fix):
#   XDMA M_AXI (128b/250MHz) → axi_clkconv_xdma (128b, 250→133MHz) → axi_dwidth_xdma (128→512b @133MHz) → MIG C0
#   NPU DMA    (128b/250MHz) → axi_clkconv_npu  (128b, 250→133MHz) → axi_dwidth_npu  (128→512b @133MHz) → MIG C1
#
# Rationale: axi_dwidth_converter internal CMD_QUEUE → mi_register_slice path was
# the WNS critical path at 250 MHz (4 ns budget, 3.9 ns data path).  Moving the
# dwidth converter to the 133 MHz domain gives it a 7.5 ns budget — ≥1 ns margin.
# axi_clock_converter at 128-bit is also 4× smaller (fewer BRAM FIFOs).
#
# axi_clock_converter : DATA_WIDTH=128, ACLK_ASYNC=1
# axi_dwidth_converter: SI_DATA_WIDTH=128, MI_DATA_WIDTH=512, single 133 MHz clock
# ---------------------------------------------------------------------------

# 3a: AXI clock converter — XDMA path (128-bit, 250 MHz → 133 MHz)
puts "INFO: Creating axi_clkconv_xdma IP..."
create_ip \
    -name axi_clock_converter \
    -vendor xilinx.com \
    -library ip \
    -version 2.1 \
    -module_name axi_clkconv_xdma

set_property -dict [list \
    CONFIG.ADDR_WIDTH {64}  \
    CONFIG.DATA_WIDTH {128} \
    CONFIG.ID_WIDTH   {4}   \
    CONFIG.ACLK_ASYNC {1}   \
] [get_ips axi_clkconv_xdma]
generate_target all [get_ips axi_clkconv_xdma]
# Patch OOC XDC: slave at 250 MHz (userclk2), master at 133 MHz (c0_ui_clk)
set cc_xdma_ooc [glob -nocomplain \
    $proj_dir/npu_fpga.gen/sources_1/ip/axi_clkconv_xdma/*_ooc.xdc]
if {[llength $cc_xdma_ooc] > 0} {
    set f [lindex $cc_xdma_ooc 0]
    set fd [open $f w]
    puts $fd "# OOC clocks patched by create_project.tcl"
    puts $fd "# Slave (s_axi_aclk): 200 MHz (fabric_aclk — downstream of axi_cc_xdma_in)"
    puts $fd "create_clock -period 5.000 \[get_ports s_axi_aclk\]"
    puts $fd "# Master (m_axi_aclk): 133 MHz (c0_ui_clk from MIG C0)"
    puts $fd "create_clock -period 7.500 \[get_ports m_axi_aclk\]"
    close $fd
    puts "INFO: Patched OOC XDC for axi_clkconv_xdma → 5 ns / 7.5 ns"
}
puts "INFO: axi_clkconv_xdma done."

# 3b: AXI width converter — XDMA path (128→512-bit, runs at 133 MHz)
puts "INFO: Creating axi_dwidth_xdma IP..."
create_ip \
    -name axi_dwidth_converter \
    -vendor xilinx.com \
    -library ip \
    -version 2.1 \
    -module_name axi_dwidth_xdma

set_property -dict [list \
    CONFIG.ADDR_WIDTH     {64}  \
    CONFIG.SI_DATA_WIDTH  {128} \
    CONFIG.MI_DATA_WIDTH  {512} \
    CONFIG.SI_ID_WIDTH    {4}   \
    CONFIG.PROTOCOL       {AXI4} \
] [get_ips axi_dwidth_xdma]
generate_target all [get_ips axi_dwidth_xdma]
# Patch OOC XDC: single clock at 133 MHz (c0_ui_clk domain after clkconv)
set dw_xdma_ooc [glob -nocomplain \
    $proj_dir/npu_fpga.gen/sources_1/ip/axi_dwidth_xdma/*_ooc.xdc]
if {[llength $dw_xdma_ooc] > 0} {
    set f [lindex $dw_xdma_ooc 0]
    set fd [open $f w]
    puts $fd "# OOC clock patched by create_project.tcl (Tier-2.5: dwidth in 133 MHz domain)"
    puts $fd "create_clock -period 7.500 \[get_ports s_axi_aclk\]"
    close $fd
    puts "INFO: Patched OOC XDC for axi_dwidth_xdma → 7.5 ns (133 MHz)"
}
puts "INFO: axi_dwidth_xdma done."

# 3c: AXI clock converter — NPU DMA path (128-bit, 250 MHz → 133 MHz)
puts "INFO: Creating axi_clkconv_npu IP..."
create_ip \
    -name axi_clock_converter \
    -vendor xilinx.com \
    -library ip \
    -version 2.1 \
    -module_name axi_clkconv_npu

set_property -dict [list \
    CONFIG.ADDR_WIDTH {64}  \
    CONFIG.DATA_WIDTH {128} \
    CONFIG.ID_WIDTH   {4}   \
    CONFIG.ACLK_ASYNC {1}   \
] [get_ips axi_clkconv_npu]
generate_target all [get_ips axi_clkconv_npu]
# Patch OOC XDC: slave at 250 MHz (userclk2), master at 133 MHz (c1_ui_clk)
set cc_npu_ooc [glob -nocomplain \
    $proj_dir/npu_fpga.gen/sources_1/ip/axi_clkconv_npu/*_ooc.xdc]
if {[llength $cc_npu_ooc] > 0} {
    set f [lindex $cc_npu_ooc 0]
    set fd [open $f w]
    puts $fd "# OOC clocks patched by create_project.tcl"
    puts $fd "# Slave (s_axi_aclk): 200 MHz (fabric_aclk — from DMA master)"
    puts $fd "create_clock -period 5.000 \[get_ports s_axi_aclk\]"
    puts $fd "# Master (m_axi_aclk): 133 MHz (c1_ui_clk from MIG C1)"
    puts $fd "create_clock -period 7.500 \[get_ports m_axi_aclk\]"
    close $fd
    puts "INFO: Patched OOC XDC for axi_clkconv_npu → 5 ns / 7.5 ns"
}
puts "INFO: axi_clkconv_npu done."

# 3d: AXI width converter — NPU DMA path (128→512-bit, runs at 133 MHz)
puts "INFO: Creating axi_dwidth_npu IP..."
create_ip \
    -name axi_dwidth_converter \
    -vendor xilinx.com \
    -library ip \
    -version 2.1 \
    -module_name axi_dwidth_npu

set_property -dict [list \
    CONFIG.ADDR_WIDTH     {64}  \
    CONFIG.SI_DATA_WIDTH  {128} \
    CONFIG.MI_DATA_WIDTH  {512} \
    CONFIG.SI_ID_WIDTH    {4}   \
    CONFIG.PROTOCOL       {AXI4} \
] [get_ips axi_dwidth_npu]
generate_target all [get_ips axi_dwidth_npu]
# Patch OOC XDC: single clock at 133 MHz (c1_ui_clk domain after clkconv)
set dw_npu_ooc [glob -nocomplain \
    $proj_dir/npu_fpga.gen/sources_1/ip/axi_dwidth_npu/*_ooc.xdc]
if {[llength $dw_npu_ooc] > 0} {
    set f [lindex $dw_npu_ooc 0]
    set fd [open $f w]
    puts $fd "# OOC clock patched by create_project.tcl (Tier-2.5: dwidth in 133 MHz domain)"
    puts $fd "create_clock -period 7.500 \[get_ports s_axi_aclk\]"
    close $fd
    puts "INFO: Patched OOC XDC for axi_dwidth_npu → 7.5 ns (133 MHz)"
}
puts "INFO: axi_dwidth_npu done."

# ---------------------------------------------------------------------------
# IP 4: AXI Protocol Converter 2.1 — AXI4-Full 128-bit (BYPASS) → AXI4-Lite 32-bit
# ---------------------------------------------------------------------------
puts "INFO: Creating axi_protocol_converter_0 IP..."
create_ip \
    -name axi_protocol_converter \
    -vendor xilinx.com \
    -library ip \
    -version 2.1 \
    -module_name axi_protocol_converter_0

set_property -dict [list \
    CONFIG.SI_PROTOCOL    {AXI4}     \
    CONFIG.MI_PROTOCOL    {AXI4LITE} \
    CONFIG.DATA_WIDTH     {32}       \
    CONFIG.ADDR_WIDTH     {12}       \
    CONFIG.ID_WIDTH       {0}        \
] [get_ips axi_protocol_converter_0]

generate_target all [get_ips axi_protocol_converter_0]
puts "INFO: axi_protocol_converter_0 done."

# ---------------------------------------------------------------------------
# IP 5: clk_wiz_0 — MMCM fabric clock: 250 MHz (axi_aclk) → 200 MHz
#
# Provides a 200 MHz fabric clock for MMALU, DMA master, ctrl_lite, and the
# slave sides of the AXI clock converters.  The 250 MHz XDMA AXI interface
# is bridged to 200 MHz by axi_cc_xdma_in / axi_cc_byp_in below.
#
# MMCM calculation: VCO = 250 × 4 / 1 = 1000 MHz; /5 = 200 MHz (within range)
# ---------------------------------------------------------------------------
puts "INFO: Creating clk_wiz_0 IP (250→200 MHz)..."
create_ip \
    -name clk_wiz \
    -vendor xilinx.com \
    -library ip \
    -version 6.0 \
    -module_name clk_wiz_0

set_property -dict [list \
    CONFIG.PRIMITIVE              {MMCM}     \
    CONFIG.PRIM_IN_FREQ           {250.000}  \
    CONFIG.CLKOUT1_REQUESTED_OUT_FREQ {200.000} \
    CONFIG.USE_LOCKED             {true}     \
    CONFIG.USE_RESET              {false}    \
    CONFIG.CLKOUT1_DRIVES         {BUFG}     \
] [get_ips clk_wiz_0]

generate_target all [get_ips clk_wiz_0]
puts "INFO: clk_wiz_0 done."

# ---------------------------------------------------------------------------
# IP 6: axi_cc_xdma_in — AXI4 clock converter: 250 MHz → 200 MHz (XDMA M_AXI path)
#   Bridges XDMA M_AXI (128-bit @ userclk2 250 MHz) into the 200 MHz fabric domain.
#   Placed BEFORE axi_clkconv_xdma so the entire fabric runs at 200 MHz.
# ---------------------------------------------------------------------------
puts "INFO: Creating axi_cc_xdma_in IP (XDMA 250→200 MHz bridge)..."
create_ip \
    -name axi_clock_converter \
    -vendor xilinx.com \
    -library ip \
    -version 2.1 \
    -module_name axi_cc_xdma_in

set_property -dict [list \
    CONFIG.ADDR_WIDTH {64}  \
    CONFIG.DATA_WIDTH {128} \
    CONFIG.ID_WIDTH   {4}   \
    CONFIG.ACLK_ASYNC {1}   \
] [get_ips axi_cc_xdma_in]

generate_target all [get_ips axi_cc_xdma_in]
# Patch OOC XDC: slave at 250 MHz (userclk2), master at 200 MHz (fabric_aclk)
set cc_xi_ooc [glob -nocomplain \
    $proj_dir/npu_fpga.gen/sources_1/ip/axi_cc_xdma_in/*_ooc.xdc]
if {[llength $cc_xi_ooc] > 0} {
    set f [lindex $cc_xi_ooc 0]
    set fd [open $f w]
    puts $fd "create_clock -period 4.000 \[get_ports s_axi_aclk\]"
    puts $fd "create_clock -period 5.000 \[get_ports m_axi_aclk\]"
    close $fd
    puts "INFO: Patched OOC XDC for axi_cc_xdma_in → 4 ns / 5 ns"
}
puts "INFO: axi_cc_xdma_in done."

# ---------------------------------------------------------------------------
# IP 7: axi_cc_byp_in — AXI4-Lite clock converter: 250 MHz → 200 MHz (BYPASS path)
#   Bridges XDMA M_AXI_BYPASS → proto_conv output (32-bit AXI4L @ 250 MHz)
#   into the 200 MHz fabric domain for ctrl_lite_inst.
# ---------------------------------------------------------------------------
puts "INFO: Creating axi_cc_byp_in IP (BYPASS 250→200 MHz bridge)..."
create_ip \
    -name axi_clock_converter \
    -vendor xilinx.com \
    -library ip \
    -version 2.1 \
    -module_name axi_cc_byp_in

set_property -dict [list \
    CONFIG.PROTOCOL   {AXI4LITE} \
    CONFIG.ADDR_WIDTH {12}       \
    CONFIG.DATA_WIDTH {32}       \
    CONFIG.ID_WIDTH   {0}        \
    CONFIG.ACLK_ASYNC {1}        \
] [get_ips axi_cc_byp_in]

generate_target all [get_ips axi_cc_byp_in]
set cc_bi_ooc [glob -nocomplain \
    $proj_dir/npu_fpga.gen/sources_1/ip/axi_cc_byp_in/*_ooc.xdc]
if {[llength $cc_bi_ooc] > 0} {
    set f [lindex $cc_bi_ooc 0]
    set fd [open $f w]
    puts $fd "create_clock -period 4.000 \[get_ports s_axi_aclk\]"
    puts $fd "create_clock -period 5.000 \[get_ports m_axi_aclk\]"
    close $fd
    puts "INFO: Patched OOC XDC for axi_cc_byp_in → 4 ns / 5 ns"
}
puts "INFO: axi_cc_byp_in done."

# ---------------------------------------------------------------------------
# Set top-level and update compile order
# ---------------------------------------------------------------------------
set_property top top_npu [current_fileset]
update_compile_order -fileset sources_1
update_compile_order -fileset sim_1

puts "INFO: Compile order updated. Top = top_npu"

# ---------------------------------------------------------------------------
# Synthesis run setup
# ---------------------------------------------------------------------------
set_property strategy Flow_PerfOptimized_high [get_runs synth_1]
set_property STEPS.SYNTH_DESIGN.ARGS.FLATTEN_HIERARCHY rebuilt [get_runs synth_1]
set_property STEPS.SYNTH_DESIGN.ARGS.DIRECTIVE PerformanceOptimized [get_runs synth_1]

# ---------------------------------------------------------------------------
# Launch synthesis
# ---------------------------------------------------------------------------
puts "INFO: Launching synthesis (4 jobs). This may take 30-60 minutes for MMALU 64x64..."
launch_runs synth_1 -jobs 4
wait_on_run synth_1

# Report result
set synth_status [get_property STATUS [get_runs synth_1]]
if {[get_property PROGRESS [get_runs synth_1]] eq "100%"} {
    puts "INFO: Synthesis completed successfully. Status: $synth_status"
} else {
    puts "ERROR: Synthesis did not complete. Status: $synth_status"
    puts "ERROR: Check $proj_dir/npu_fpga.runs/synth_1/runme.log for details."
}

# ---------------------------------------------------------------------------
# Save and close
# ---------------------------------------------------------------------------
save_project_as npu_fpga $proj_dir -force
close_project

puts "INFO: Project saved to $proj_dir/npu_fpga.xpr"
puts "INFO: Done."
