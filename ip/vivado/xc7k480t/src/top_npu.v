// top_npu.v
// Board-level top module for the NPU FPGA verification platform.
// Target: Xilinx xc7k480tffg1156-2 (custom YPCB-00338-1P1 board)
//
// Instantiation hierarchy:
//   top_npu
//   ├── IBUFDS_GTE2            pcie_refclk_ibuf — PCIe 100 MHz ref clock
//   ├── xdma_0                                  — Xilinx XDMA PCIe DMA IP (Gen2×8, 128-bit)
//   ├── axi_clkconv_xdma       xdma_cc_inst     — 128-bit 250→133 MHz CDC (XDMA → dwidth C0)
//   ├── axi_dwidth_xdma        xdma_dw_inst     — 128→512-bit upsizer @ 133 MHz (→ MIG C0)
//   ├── axi_clkconv_npu        npu_cc_inst      — 128-bit 250→133 MHz CDC (NPU DMA → dwidth C1)
//   ├── axi_dwidth_npu         npu_dw_inst      — 128→512-bit upsizer @ 133 MHz (→ MIG C1)
//   ├── mig_7series_0          mig_inst         — Dual DDR3 MIG controller (C0+C1, 512-bit)
//   ├── axi_protocol_converter_0 proto_conv_inst — XDMA BYPASS (128b AXI4) → npu_ctrl_lite (32b AXI4L)
//   ├── npu_ctrl_lite          ctrl_lite_inst   — AXI4-Lite ctrl/status register
//   ├── npu_dma_master         dma_master_inst  — AXI4 master: DDR ↔ MMALU data mover
//   └── MMALU                  mmalu_inst       — Chisel-generated 32×32 systolic array
//
// AXI data paths (200 MHz fabric + Tier-2.5 clkconv-first reorder):
//   XDMA M_AXI (128b/250MHz) → axi_cc_xdma_in (128b,250→200MHz) → axi_clkconv_xdma (128b,200→133MHz) → axi_dwidth_xdma (128→512b@133MHz) → MIG C0
//   NPU DMA    (128b/200MHz) → axi_clkconv_npu (128b,200→133MHz) → axi_dwidth_npu  (128→512b@133MHz) → MIG C1
//   XDMA BYPASS (128b/250MHz) → proto_conv (32b@250MHz) → axi_cc_byp_in (32b,250→200MHz) → npu_ctrl_lite (200MHz)
//
// Clock domains:
//   axi_aclk    (250 MHz) — XDMA userclk2; clocks XDMA, proto_conv, axi_cc_*_in slave sides
//   fabric_aclk (200 MHz) — MMCM from axi_aclk; clocks MMALU, DMA master, ctrl_lite, clkconv slave sides
//   c0_ui_clk   (133 MHz) — MIG C0 UI clock; clocks clkconv_xdma master + dwidth_xdma
//   c1_ui_clk   (133 MHz) — MIG C1 UI clock; clocks clkconv_npu  master + dwidth_npu
//
// 200 MHz fabric gives +1 ns timing budget on all MMALU/DMA paths, guaranteeing closure.
//
`timescale 1ns/1ps

module top_npu (
    // PCIe Gen2 ×8
    input  wire        sys_clk_p,          // PCIe 100 MHz ref clock (diff, pin J8)
    input  wire        sys_clk_n,
    input  wire [7:0]  pcie_rxp,
    input  wire [7:0]  pcie_rxn,
    output wire [7:0]  pcie_txp,
    output wire [7:0]  pcie_txn,

    // Board reset and debug clock
    input  wire        sys_reset,          // active-high, pin Y26, LVCMOS33
    input  wire        clk_50mhz,          // 50 MHz, pin AA28, LVCMOS33 (debug only)

    // DDR3 C0 (bank 11/12/13, 72-bit ECC)
    input  wire        c0_sys_clk_p,       // 200 MHz diff ref, AH27
    input  wire        c0_sys_clk_n,
    output wire [14:0] c0_ddr3_addr,
    output wire [2:0]  c0_ddr3_ba,
    output wire        c0_ddr3_cas_n,
    output wire [0:0]  c0_ddr3_ck_p,
    output wire [0:0]  c0_ddr3_ck_n,
    output wire [0:0]  c0_ddr3_cke,
    output wire [0:0]  c0_ddr3_cs_n,
    inout  wire [71:0] c0_ddr3_dq,
    inout  wire [8:0]  c0_ddr3_dqs_p,
    inout  wire [8:0]  c0_ddr3_dqs_n,
    output wire [0:0]  c0_ddr3_odt,
    output wire        c0_ddr3_ras_n,
    output wire        c0_ddr3_reset_n,
    output wire        c0_ddr3_we_n,

    // DDR3 C1 (bank 16/17/18, 72-bit ECC)
    input  wire        c1_sys_clk_p,       // 200 MHz diff ref, G25
    input  wire        c1_sys_clk_n,
    output wire [14:0] c1_ddr3_addr,
    output wire [2:0]  c1_ddr3_ba,
    output wire        c1_ddr3_cas_n,
    output wire [0:0]  c1_ddr3_ck_p,
    output wire [0:0]  c1_ddr3_ck_n,
    output wire [0:0]  c1_ddr3_cke,
    output wire [0:0]  c1_ddr3_cs_n,
    inout  wire [71:0] c1_ddr3_dq,
    inout  wire [8:0]  c1_ddr3_dqs_p,
    inout  wire [8:0]  c1_ddr3_dqs_n,
    output wire [0:0]  c1_ddr3_odt,
    output wire        c1_ddr3_ras_n,
    output wire        c1_ddr3_reset_n,
    output wire        c1_ddr3_we_n
);

    // -----------------------------------------------------------------------
    // Clocks and resets
    // -----------------------------------------------------------------------
    wire        pcie_ref_clk;
    wire        axi_aclk;        // 250 MHz — from XDMA (userclk2)
    wire        axi_aresetn;
    wire        fabric_aclk;     // 200 MHz — from clk_wiz_0 (MMCM on axi_aclk)
    wire        clk_wiz_locked;
    wire        c0_ui_clk;
    wire        c1_ui_clk;
    wire        c0_ui_clk_sync_rst;
    wire        c1_ui_clk_sync_rst;
    wire        c0_init_calib_complete;
    wire        c1_init_calib_complete;

    wire        sys_resetn;
    wire        fabric_aresetn;  // 200 MHz domain reset for MMALU / DMA / ctrl
    wire        mig_sys_rst_n;

    assign sys_resetn    = ~sys_reset;
    assign mig_sys_rst_n = axi_aresetn;

    // Two-FF synchronizers in fabric_aclk (200 MHz) domain:
    // c0/c1_init_calib_complete come from clk_pll_i (133 MHz).
    // Also gates on clk_wiz_locked so fabric logic stays in reset until
    // the MMCM is stable.
    (* ASYNC_REG = "TRUE" *) reg c0_calib_sync1, c0_calib_sync2;
    (* ASYNC_REG = "TRUE" *) reg c1_calib_sync1, c1_calib_sync2;

    always @(posedge fabric_aclk) begin
        if (!axi_aresetn || !clk_wiz_locked) begin
            c0_calib_sync1 <= 1'b0; c0_calib_sync2 <= 1'b0;
            c1_calib_sync1 <= 1'b0; c1_calib_sync2 <= 1'b0;
        end else begin
            c0_calib_sync1 <= c0_init_calib_complete;
            c0_calib_sync2 <= c0_calib_sync1;
            c1_calib_sync1 <= c1_init_calib_complete;
            c1_calib_sync2 <= c1_calib_sync1;
        end
    end

    assign fabric_aresetn = axi_aresetn & clk_wiz_locked & c0_calib_sync2 & c1_calib_sync2;

    // -----------------------------------------------------------------------
    // PCIe reference clock buffer
    // -----------------------------------------------------------------------
    IBUFDS_GTE2 pcie_refclk_ibuf (
        .I(sys_clk_p), .IB(sys_clk_n), .CEB(1'b0),
        .O(pcie_ref_clk), .ODIV2()
    );

    // -----------------------------------------------------------------------
    // MMCM: axi_aclk (250 MHz) → fabric_aclk (200 MHz)
    // VCO = 250×4/1 = 1000 MHz; /5 = 200 MHz
    // fabric_aclk drives MMALU, DMA master, ctrl_lite, clkconv slave sides.
    // -----------------------------------------------------------------------
    clk_wiz_0 clk_wiz_inst (
        .clk_in1  (axi_aclk),
        .clk_out1 (fabric_aclk),
        .locked   (clk_wiz_locked)
    );

    // -----------------------------------------------------------------------
    // XDMA (PCIe Gen2 ×8, AXI-MM 128-bit, 250 MHz)
    // -----------------------------------------------------------------------
    // M_AXI wires
    wire [3:0]   xdma_awid;    wire [63:0]  xdma_awaddr;  wire [7:0]   xdma_awlen;
    wire [2:0]   xdma_awsize;  wire [1:0]   xdma_awburst; wire         xdma_awvalid;
    wire         xdma_awready; wire [127:0] xdma_wdata;   wire [15:0]  xdma_wstrb;
    wire         xdma_wlast;   wire         xdma_wvalid;  wire         xdma_wready;
    wire [3:0]   xdma_bid;     wire [1:0]   xdma_bresp;   wire         xdma_bvalid;
    wire         xdma_bready;  wire [3:0]   xdma_arid;    wire [63:0]  xdma_araddr;
    wire [7:0]   xdma_arlen;   wire [2:0]   xdma_arsize;  wire [1:0]   xdma_arburst;
    wire         xdma_arvalid; wire         xdma_arready; wire [127:0] xdma_rdata;
    wire [1:0]   xdma_rresp;   wire         xdma_rlast;   wire         xdma_rvalid;
    wire         xdma_rready;
    // M_AXI_BYPASS wires (128-bit AXI4 to protocol converter)
    wire [63:0]  byp_awaddr;   wire [2:0]   byp_awprot;   wire         byp_awvalid;
    wire         byp_awready;  wire [127:0] byp_wdata;    wire [15:0]  byp_wstrb;
    wire         byp_wvalid;   wire         byp_wready;   wire [1:0]   byp_bresp;
    wire         byp_bvalid;   wire         byp_bready;   wire [63:0]  byp_araddr;
    wire [2:0]   byp_arprot;   wire         byp_arvalid;  wire         byp_arready;
    wire [127:0] byp_rdata;    wire [1:0]   byp_rresp;    wire         byp_rvalid;
    wire         byp_rready;

    xdma_0 xdma_inst (
        .sys_clk(pcie_ref_clk), .sys_rst_n(sys_resetn),
        .pci_exp_txp(pcie_txp), .pci_exp_txn(pcie_txn),
        .pci_exp_rxp(pcie_rxp), .pci_exp_rxn(pcie_rxn),
        .axi_aclk(axi_aclk),   .axi_aresetn(axi_aresetn),
        // M_AXI (DMA data path)
        .m_axi_awid(xdma_awid),   .m_axi_awaddr(xdma_awaddr), .m_axi_awlen(xdma_awlen),
        .m_axi_awsize(xdma_awsize),.m_axi_awburst(xdma_awburst),.m_axi_awprot(3'b000),
        .m_axi_awvalid(xdma_awvalid),.m_axi_awready(xdma_awready),
        .m_axi_wdata(xdma_wdata),  .m_axi_wstrb(xdma_wstrb),  .m_axi_wlast(xdma_wlast),
        .m_axi_wvalid(xdma_wvalid),.m_axi_wready(xdma_wready),
        .m_axi_bid(xdma_bid),      .m_axi_bresp(xdma_bresp),   .m_axi_bvalid(xdma_bvalid),
        .m_axi_bready(xdma_bready),
        .m_axi_arid(xdma_arid),    .m_axi_araddr(xdma_araddr), .m_axi_arlen(xdma_arlen),
        .m_axi_arsize(xdma_arsize),.m_axi_arburst(xdma_arburst),.m_axi_arprot(3'b000),
        .m_axi_arvalid(xdma_arvalid),.m_axi_arready(xdma_arready),
        .m_axi_rdata(xdma_rdata),  .m_axi_rresp(xdma_rresp),  .m_axi_rlast(xdma_rlast),
        .m_axi_rvalid(xdma_rvalid),.m_axi_rready(xdma_rready),
        // M_AXI_BYPASS (register access path)
        .m_axib_awaddr(byp_awaddr),.m_axib_awprot(byp_awprot), .m_axib_awvalid(byp_awvalid),
        .m_axib_awready(byp_awready),.m_axib_wdata(byp_wdata), .m_axib_wstrb(byp_wstrb),
        .m_axib_wvalid(byp_wvalid),.m_axib_wready(byp_wready), .m_axib_bresp(byp_bresp),
        .m_axib_bvalid(byp_bvalid),.m_axib_bready(byp_bready), .m_axib_araddr(byp_araddr),
        .m_axib_arprot(byp_arprot),.m_axib_arvalid(byp_arvalid),.m_axib_arready(byp_arready),
        .m_axib_rdata(byp_rdata),  .m_axib_rresp(byp_rresp),   .m_axib_rvalid(byp_rvalid),
        .m_axib_rready(byp_rready),
        // Optional BYPASS burst signals — tie safe defaults so PCIe2 LUTs are not driverless
        .m_axib_wlast  (1'b1),     // BYPASS is AXI4-Lite equivalent: single beat, always last
        .m_axib_rlast  (1'b1),
        .m_axib_bid    (4'h0),
        .m_axib_rid    (4'h0),
        // Optional CFG management port — tie off (not used for DMA operation)
        .cfg_mgmt_addr           (19'h0),
        .cfg_mgmt_byte_enable    (4'h0),
        .cfg_mgmt_write          (1'b0),
        .cfg_mgmt_write_data     (32'h0),
        .cfg_mgmt_read           (1'b0),
        .cfg_mgmt_type1_cfg_reg_access(1'b0),
        // Optional user interrupt — tie off
        .usr_irq_req (1'b0)
    );

    // -----------------------------------------------------------------------
    // XDMA path:  XDMA(250MHz) → axi_cc_xdma_in(250→200MHz) → axi_clkconv_xdma(200→133MHz)
    //             → axi_dwidth_xdma(128→512b@133MHz) → MIG C0
    // -----------------------------------------------------------------------
    // Intermediate wires between axi_cc_xdma_in master and axi_clkconv_xdma slave
    // (128-bit @ fabric_aclk 200 MHz)
    wire [3:0]   xf_awid;     wire [63:0]  xf_awaddr;   wire [7:0]   xf_awlen;
    wire [2:0]   xf_awsize;   wire [1:0]   xf_awburst;  wire         xf_awvalid;
    wire         xf_awready;  wire [127:0] xf_wdata;    wire [15:0]  xf_wstrb;
    wire         xf_wlast;    wire         xf_wvalid;   wire         xf_wready;
    wire [3:0]   xf_bid;      wire [1:0]   xf_bresp;    wire         xf_bvalid;
    wire         xf_bready;   wire [3:0]   xf_arid;     wire [63:0]  xf_araddr;
    wire [7:0]   xf_arlen;    wire [2:0]   xf_arsize;   wire [1:0]   xf_arburst;
    wire         xf_arvalid;  wire         xf_arready;  wire [3:0]   xf_rid;
    wire [127:0] xf_rdata;    wire [1:0]   xf_rresp;    wire         xf_rlast;
    wire         xf_rvalid;   wire         xf_rready;

    // Clock converter: XDMA M_AXI (128b @ 250 MHz) → fabric (128b @ 200 MHz)
    axi_cc_xdma_in xdma_cc_in_inst (
        .s_axi_aclk    (axi_aclk),    .s_axi_aresetn(axi_aresetn),
        .s_axi_awid    (xdma_awid),   .s_axi_awaddr(xdma_awaddr),
        .s_axi_awlen   (xdma_awlen),  .s_axi_awsize(xdma_awsize),
        .s_axi_awburst (xdma_awburst),.s_axi_awlock(1'b0),
        .s_axi_awcache (4'b0010),     .s_axi_awprot(3'b000),
        .s_axi_awregion(4'b0000),     .s_axi_awqos(4'b0000),
        .s_axi_awvalid (xdma_awvalid),.s_axi_awready(xdma_awready),
        .s_axi_wdata   (xdma_wdata),  .s_axi_wstrb(xdma_wstrb),
        .s_axi_wlast   (xdma_wlast),  .s_axi_wvalid(xdma_wvalid),
        .s_axi_wready  (xdma_wready),
        .s_axi_bid     (xdma_bid),    .s_axi_bresp(xdma_bresp),
        .s_axi_bvalid  (xdma_bvalid), .s_axi_bready(xdma_bready),
        .s_axi_arid    (xdma_arid),   .s_axi_araddr(xdma_araddr),
        .s_axi_arlen   (xdma_arlen),  .s_axi_arsize(xdma_arsize),
        .s_axi_arburst (xdma_arburst),.s_axi_arlock(1'b0),
        .s_axi_arcache (4'b0010),     .s_axi_arprot(3'b000),
        .s_axi_arregion(4'b0000),     .s_axi_arqos(4'b0000),
        .s_axi_arvalid (xdma_arvalid),.s_axi_arready(xdma_arready),
        .s_axi_rid     (),            .s_axi_rdata(xdma_rdata),
        .s_axi_rresp   (xdma_rresp),  .s_axi_rlast(xdma_rlast),
        .s_axi_rvalid  (xdma_rvalid), .s_axi_rready(xdma_rready),
        .m_axi_aclk    (fabric_aclk), .m_axi_aresetn(fabric_aresetn),
        .m_axi_awid    (xf_awid),     .m_axi_awaddr(xf_awaddr),
        .m_axi_awlen   (xf_awlen),    .m_axi_awsize(xf_awsize),
        .m_axi_awburst (xf_awburst),  .m_axi_awlock(),
        .m_axi_awcache (),            .m_axi_awprot(),
        .m_axi_awqos   (),            .m_axi_awvalid(xf_awvalid),
        .m_axi_awready (xf_awready),
        .m_axi_wdata   (xf_wdata),    .m_axi_wstrb(xf_wstrb),
        .m_axi_wlast   (xf_wlast),    .m_axi_wvalid(xf_wvalid),
        .m_axi_wready  (xf_wready),
        .m_axi_bid     (xf_bid),      .m_axi_bresp(xf_bresp),
        .m_axi_bvalid  (xf_bvalid),   .m_axi_bready(xf_bready),
        .m_axi_arid    (xf_arid),     .m_axi_araddr(xf_araddr),
        .m_axi_arlen   (xf_arlen),    .m_axi_arsize(xf_arsize),
        .m_axi_arburst (xf_arburst),  .m_axi_arlock(),
        .m_axi_arcache (),            .m_axi_arprot(),
        .m_axi_arqos   (),            .m_axi_arvalid(xf_arvalid),
        .m_axi_arready (xf_arready),
        .m_axi_rid     (xf_rid),      .m_axi_rdata(xf_rdata),
        .m_axi_rresp   (xf_rresp),    .m_axi_rlast(xf_rlast),
        .m_axi_rvalid  (xf_rvalid),   .m_axi_rready(xf_rready)
    );

    // Intermediate wires between clkconv and dwidth (XDMA path, 128-bit @ 133 MHz)
    wire [3:0]   cc0_awid;    wire [63:0]  cc0_awaddr;  wire [7:0]   cc0_awlen;
    wire [2:0]   cc0_awsize;  wire [1:0]   cc0_awburst; wire [0:0]   cc0_awlock;
    wire [3:0]   cc0_awcache; wire [2:0]   cc0_awprot;  wire [3:0]   cc0_awqos;
    wire         cc0_awvalid; wire         cc0_awready; wire [127:0] cc0_wdata;
    wire [15:0]  cc0_wstrb;   wire         cc0_wlast;   wire         cc0_wvalid;
    wire         cc0_wready;  wire [3:0]   cc0_bid;     wire [1:0]   cc0_bresp;
    wire         cc0_bvalid;  wire         cc0_bready;  wire [3:0]   cc0_arid;
    wire [63:0]  cc0_araddr;  wire [7:0]   cc0_arlen;   wire [2:0]   cc0_arsize;
    wire [1:0]   cc0_arburst; wire [0:0]   cc0_arlock;  wire [3:0]   cc0_arcache;
    wire [2:0]   cc0_arprot;  wire [3:0]   cc0_arqos;   wire         cc0_arvalid;
    wire         cc0_arready; wire [3:0]   cc0_rid;     wire [127:0] cc0_rdata;
    wire [1:0]   cc0_rresp;   wire         cc0_rlast;   wire         cc0_rvalid;
    wire         cc0_rready;

    // MIG C0 AXI slave wires (512-bit, 133 MHz)
    wire [3:0]   mig_c0_awid;    wire [30:0]  mig_c0_awaddr;  wire [7:0]   mig_c0_awlen;
    wire [2:0]   mig_c0_awsize;  wire [1:0]   mig_c0_awburst; wire [0:0]   mig_c0_awlock;
    wire [3:0]   mig_c0_awcache; wire [2:0]   mig_c0_awprot;  wire [3:0]   mig_c0_awqos;
    wire         mig_c0_awvalid; wire         mig_c0_awready; wire [511:0] mig_c0_wdata;
    wire [63:0]  mig_c0_wstrb;   wire         mig_c0_wlast;   wire         mig_c0_wvalid;
    wire         mig_c0_wready;  wire [3:0]   mig_c0_bid;     wire [1:0]   mig_c0_bresp;
    wire         mig_c0_bvalid;  wire         mig_c0_bready;  wire [3:0]   mig_c0_arid;
    wire [30:0]  mig_c0_araddr;  wire [7:0]   mig_c0_arlen;   wire [2:0]   mig_c0_arsize;
    wire [1:0]   mig_c0_arburst; wire [0:0]   mig_c0_arlock;  wire [3:0]   mig_c0_arcache;
    wire [2:0]   mig_c0_arprot;  wire [3:0]   mig_c0_arqos;   wire         mig_c0_arvalid;
    wire         mig_c0_arready; wire [3:0]   mig_c0_rid;     wire [511:0] mig_c0_rdata;
    wire [1:0]   mig_c0_rresp;   wire         mig_c0_rlast;   wire         mig_c0_rvalid;
    wire         mig_c0_rready;

    // Clock converter: fabric (128b @ 200 MHz) → MIG C0 domain (128b @ 133 MHz).
    // s_axi driven by axi_cc_xdma_in master (xf_* signals @ fabric_aclk 200 MHz).
    axi_clkconv_xdma xdma_cc_inst (
        .s_axi_aclk    (fabric_aclk),  .s_axi_aresetn(fabric_aresetn),
        .s_axi_awid    (xf_awid),      .s_axi_awaddr(xf_awaddr),
        .s_axi_awlen   (xf_awlen),     .s_axi_awsize(xf_awsize),
        .s_axi_awburst (xf_awburst),   .s_axi_awlock(1'b0),
        .s_axi_awcache (4'b0010),      .s_axi_awprot(3'b000),
        .s_axi_awregion(4'b0000),      .s_axi_awqos(4'b0000),
        .s_axi_awvalid (xf_awvalid),   .s_axi_awready(xf_awready),
        .s_axi_wdata   (xf_wdata),     .s_axi_wstrb(xf_wstrb),
        .s_axi_wlast   (xf_wlast),     .s_axi_wvalid(xf_wvalid),
        .s_axi_wready  (xf_wready),
        .s_axi_bid     (xf_bid),       .s_axi_bresp(xf_bresp),
        .s_axi_bvalid  (xf_bvalid),    .s_axi_bready(xf_bready),
        .s_axi_arid    (xf_arid),      .s_axi_araddr(xf_araddr),
        .s_axi_arlen   (xf_arlen),     .s_axi_arsize(xf_arsize),
        .s_axi_arburst (xf_arburst),   .s_axi_arlock(1'b0),
        .s_axi_arcache (4'b0010),      .s_axi_arprot(3'b000),
        .s_axi_arregion(4'b0000),      .s_axi_arqos(4'b0000),
        .s_axi_arvalid (xf_arvalid),   .s_axi_arready(xf_arready),
        .s_axi_rid     (xf_rid),       .s_axi_rdata(xf_rdata),
        .s_axi_rresp   (xf_rresp),     .s_axi_rlast(xf_rlast),
        .s_axi_rvalid  (xf_rvalid),    .s_axi_rready(xf_rready),
        .m_axi_aclk    (c0_ui_clk),    .m_axi_aresetn(~c0_ui_clk_sync_rst),
        .m_axi_awid    (cc0_awid),      .m_axi_awaddr(cc0_awaddr),
        .m_axi_awlen   (cc0_awlen),     .m_axi_awsize(cc0_awsize),
        .m_axi_awburst (cc0_awburst),   .m_axi_awlock(cc0_awlock),
        .m_axi_awcache (cc0_awcache),   .m_axi_awprot(cc0_awprot),
        .m_axi_awqos   (cc0_awqos),     .m_axi_awvalid(cc0_awvalid),
        .m_axi_awready (cc0_awready),
        .m_axi_wdata   (cc0_wdata),     .m_axi_wstrb(cc0_wstrb),
        .m_axi_wlast   (cc0_wlast),     .m_axi_wvalid(cc0_wvalid),
        .m_axi_wready  (cc0_wready),
        .m_axi_bid     (cc0_bid),       .m_axi_bresp(cc0_bresp),
        .m_axi_bvalid  (cc0_bvalid),    .m_axi_bready(cc0_bready),
        .m_axi_arid    (cc0_arid),      .m_axi_araddr(cc0_araddr),
        .m_axi_arlen   (cc0_arlen),     .m_axi_arsize(cc0_arsize),
        .m_axi_arburst (cc0_arburst),   .m_axi_arlock(cc0_arlock),
        .m_axi_arcache (cc0_arcache),   .m_axi_arprot(cc0_arprot),
        .m_axi_arqos   (cc0_arqos),     .m_axi_arvalid(cc0_arvalid),
        .m_axi_arready (cc0_arready),
        .m_axi_rid     (cc0_rid),       .m_axi_rdata(cc0_rdata),
        .m_axi_rresp   (cc0_rresp),     .m_axi_rlast(cc0_rlast),
        .m_axi_rvalid  (cc0_rvalid),    .m_axi_rready(cc0_rready)
    );

    // Width converter: 128→512-bit @ 133 MHz (c0_ui_clk domain).
    // s_axi: 64-bit addr, 4-bit ID, 128-bit data.
    // m_axi: 31-bit addr (MIG limit), no ID, 512-bit data → MIG C0.
    axi_dwidth_xdma xdma_dw_inst (
        .s_axi_aclk    (c0_ui_clk),
        .s_axi_aresetn (~c0_ui_clk_sync_rst),
        .s_axi_awid    (cc0_awid),     .s_axi_awaddr(cc0_awaddr),
        .s_axi_awlen   (cc0_awlen),    .s_axi_awsize(cc0_awsize),
        .s_axi_awburst (cc0_awburst),  .s_axi_awlock(cc0_awlock[0]),
        .s_axi_awcache (cc0_awcache),  .s_axi_awprot(cc0_awprot),
        .s_axi_awregion(4'b0000),      .s_axi_awqos(cc0_awqos),
        .s_axi_awvalid (cc0_awvalid),  .s_axi_awready(cc0_awready),
        .s_axi_wdata   (cc0_wdata),    .s_axi_wstrb(cc0_wstrb),
        .s_axi_wlast   (cc0_wlast),    .s_axi_wvalid(cc0_wvalid),
        .s_axi_wready  (cc0_wready),
        .s_axi_bid     (cc0_bid),      .s_axi_bresp(cc0_bresp),
        .s_axi_bvalid  (cc0_bvalid),   .s_axi_bready(cc0_bready),
        .s_axi_arid    (cc0_arid),     .s_axi_araddr(cc0_araddr),
        .s_axi_arlen   (cc0_arlen),    .s_axi_arsize(cc0_arsize),
        .s_axi_arburst (cc0_arburst),  .s_axi_arlock(cc0_arlock[0]),
        .s_axi_arcache (cc0_arcache),  .s_axi_arprot(cc0_arprot),
        .s_axi_arregion(4'b0000),      .s_axi_arqos(cc0_arqos),
        .s_axi_arvalid (cc0_arvalid),  .s_axi_arready(cc0_arready),
        .s_axi_rid     (cc0_rid),      .s_axi_rdata(cc0_rdata),
        .s_axi_rresp   (cc0_rresp),    .s_axi_rlast(cc0_rlast),
        .s_axi_rvalid  (cc0_rvalid),   .s_axi_rready(cc0_rready),
        // 512-bit master (no IDs); truncate addr to 31-bit for MIG
        .m_axi_awaddr  (mig_c0_awaddr),.m_axi_awlen(mig_c0_awlen),
        .m_axi_awsize  (mig_c0_awsize),.m_axi_awburst(mig_c0_awburst),
        .m_axi_awlock  (mig_c0_awlock),.m_axi_awcache(mig_c0_awcache),
        .m_axi_awprot  (mig_c0_awprot),.m_axi_awregion(),
        .m_axi_awqos   (mig_c0_awqos), .m_axi_awvalid(mig_c0_awvalid),
        .m_axi_awready (mig_c0_awready),
        .m_axi_wdata   (mig_c0_wdata), .m_axi_wstrb(mig_c0_wstrb),
        .m_axi_wlast   (mig_c0_wlast), .m_axi_wvalid(mig_c0_wvalid),
        .m_axi_wready  (mig_c0_wready),
        .m_axi_bresp   (mig_c0_bresp), .m_axi_bvalid(mig_c0_bvalid),
        .m_axi_bready  (mig_c0_bready),
        .m_axi_araddr  (mig_c0_araddr),.m_axi_arlen(mig_c0_arlen),
        .m_axi_arsize  (mig_c0_arsize),.m_axi_arburst(mig_c0_arburst),
        .m_axi_arlock  (mig_c0_arlock),.m_axi_arcache(mig_c0_arcache),
        .m_axi_arprot  (mig_c0_arprot),.m_axi_arregion(),
        .m_axi_arqos   (mig_c0_arqos), .m_axi_arvalid(mig_c0_arvalid),
        .m_axi_arready (mig_c0_arready),
        .m_axi_rdata   (mig_c0_rdata), .m_axi_rresp(mig_c0_rresp),
        .m_axi_rlast   (mig_c0_rlast), .m_axi_rvalid(mig_c0_rvalid),
        .m_axi_rready  (mig_c0_rready)
    );
    // dwidth master has no ID outputs; tie MIG C0 ID inputs to 0
    assign mig_c0_awid = 4'h0;
    assign mig_c0_arid = 4'h0;

    // -----------------------------------------------------------------------
    // NPU DMA path: 250→133 MHz clock converter (128-bit), then 128→512-bit width converter → MIG C1
    // Tier-2.5 reorder: clkconv FIRST (narrow), dwidth SECOND (slow 133 MHz domain).
    // -----------------------------------------------------------------------
    // NPU DMA master AXI wires (128-bit, 250 MHz)
    wire [3:0]   npu_awid;    wire [63:0]  npu_awaddr;  wire [7:0]   npu_awlen;
    wire [2:0]   npu_awsize;  wire [1:0]   npu_awburst; wire         npu_awlock;
    wire [3:0]   npu_awcache; wire [2:0]   npu_awprot;  wire [3:0]   npu_awqos;
    wire         npu_awvalid; wire         npu_awready; wire [127:0] npu_wdata;
    wire [15:0]  npu_wstrb;   wire         npu_wlast;   wire         npu_wvalid;
    wire         npu_wready;  wire [3:0]   npu_bid;     wire [1:0]   npu_bresp;
    wire         npu_bvalid;  wire         npu_bready;  wire [3:0]   npu_arid;
    wire [63:0]  npu_araddr;  wire [7:0]   npu_arlen;   wire [2:0]   npu_arsize;
    wire [1:0]   npu_arburst; wire         npu_arlock;  wire [3:0]   npu_arcache;
    wire [2:0]   npu_arprot;  wire [3:0]   npu_arqos;   wire         npu_arvalid;
    wire         npu_arready; wire [127:0] npu_rdata;   wire [1:0]   npu_rresp;
    wire         npu_rlast;   wire         npu_rvalid;  wire         npu_rready;

    // Intermediate wires between clkconv and dwidth (NPU path, 128-bit @ 133 MHz)
    wire [3:0]   cc1_awid;    wire [63:0]  cc1_awaddr;  wire [7:0]   cc1_awlen;
    wire [2:0]   cc1_awsize;  wire [1:0]   cc1_awburst; wire [0:0]   cc1_awlock;
    wire [3:0]   cc1_awcache; wire [2:0]   cc1_awprot;  wire [3:0]   cc1_awqos;
    wire         cc1_awvalid; wire         cc1_awready; wire [127:0] cc1_wdata;
    wire [15:0]  cc1_wstrb;   wire         cc1_wlast;   wire         cc1_wvalid;
    wire         cc1_wready;  wire [3:0]   cc1_bid;     wire [1:0]   cc1_bresp;
    wire         cc1_bvalid;  wire         cc1_bready;  wire [3:0]   cc1_arid;
    wire [63:0]  cc1_araddr;  wire [7:0]   cc1_arlen;   wire [2:0]   cc1_arsize;
    wire [1:0]   cc1_arburst; wire [0:0]   cc1_arlock;  wire [3:0]   cc1_arcache;
    wire [2:0]   cc1_arprot;  wire [3:0]   cc1_arqos;   wire         cc1_arvalid;
    wire         cc1_arready; wire [3:0]   cc1_rid;     wire [127:0] cc1_rdata;
    wire [1:0]   cc1_rresp;   wire         cc1_rlast;   wire         cc1_rvalid;
    wire         cc1_rready;

    // MIG C1 AXI slave wires (512-bit, 133 MHz)
    wire [3:0]   mig_c1_awid;    wire [30:0]  mig_c1_awaddr;  wire [7:0]   mig_c1_awlen;
    wire [2:0]   mig_c1_awsize;  wire [1:0]   mig_c1_awburst; wire [0:0]   mig_c1_awlock;
    wire [3:0]   mig_c1_awcache; wire [2:0]   mig_c1_awprot;  wire [3:0]   mig_c1_awqos;
    wire         mig_c1_awvalid; wire         mig_c1_awready; wire [511:0] mig_c1_wdata;
    wire [63:0]  mig_c1_wstrb;   wire         mig_c1_wlast;   wire         mig_c1_wvalid;
    wire         mig_c1_wready;  wire [3:0]   mig_c1_bid;     wire [1:0]   mig_c1_bresp;
    wire         mig_c1_bvalid;  wire         mig_c1_bready;  wire [3:0]   mig_c1_arid;
    wire [30:0]  mig_c1_araddr;  wire [7:0]   mig_c1_arlen;   wire [2:0]   mig_c1_arsize;
    wire [1:0]   mig_c1_arburst; wire [0:0]   mig_c1_arlock;  wire [3:0]   mig_c1_arcache;
    wire [2:0]   mig_c1_arprot;  wire [3:0]   mig_c1_arqos;   wire         mig_c1_arvalid;
    wire         mig_c1_arready; wire [3:0]   mig_c1_rid;     wire [511:0] mig_c1_rdata;
    wire [1:0]   mig_c1_rresp;   wire         mig_c1_rlast;   wire         mig_c1_rvalid;
    wire         mig_c1_rready;

    // Clock converter: NPU DMA (128b @ fabric_aclk 200 MHz) → MIG C1 (128b @ 133 MHz).
    axi_clkconv_npu npu_cc_inst (
        .s_axi_aclk    (fabric_aclk), .s_axi_aresetn(fabric_aresetn),
        .s_axi_awid    (npu_awid),     .s_axi_awaddr(npu_awaddr),
        .s_axi_awlen   (npu_awlen),    .s_axi_awsize(npu_awsize),
        .s_axi_awburst (npu_awburst),  .s_axi_awlock(npu_awlock),
        .s_axi_awcache (npu_awcache),  .s_axi_awprot(npu_awprot),
        .s_axi_awregion(4'b0000),      .s_axi_awqos(npu_awqos),
        .s_axi_awvalid (npu_awvalid),  .s_axi_awready(npu_awready),
        .s_axi_wdata   (npu_wdata),    .s_axi_wstrb(npu_wstrb),
        .s_axi_wlast   (npu_wlast),    .s_axi_wvalid(npu_wvalid),
        .s_axi_wready  (npu_wready),
        .s_axi_bid     (npu_bid),      .s_axi_bresp(npu_bresp),
        .s_axi_bvalid  (npu_bvalid),   .s_axi_bready(npu_bready),
        .s_axi_arid    (npu_arid),     .s_axi_araddr(npu_araddr),
        .s_axi_arlen   (npu_arlen),    .s_axi_arsize(npu_arsize),
        .s_axi_arburst (npu_arburst),  .s_axi_arlock(npu_arlock),
        .s_axi_arcache (npu_arcache),  .s_axi_arprot(npu_arprot),
        .s_axi_arregion(4'b0000),      .s_axi_arqos(npu_arqos),
        .s_axi_arvalid (npu_arvalid),  .s_axi_arready(npu_arready),
        .s_axi_rid     (),             .s_axi_rdata(npu_rdata),
        .s_axi_rresp   (npu_rresp),    .s_axi_rlast(npu_rlast),
        .s_axi_rvalid  (npu_rvalid),   .s_axi_rready(npu_rready),
        .m_axi_aclk    (c1_ui_clk),   .m_axi_aresetn(~c1_ui_clk_sync_rst),
        .m_axi_awid    (cc1_awid),     .m_axi_awaddr(cc1_awaddr),
        .m_axi_awlen   (cc1_awlen),    .m_axi_awsize(cc1_awsize),
        .m_axi_awburst (cc1_awburst),  .m_axi_awlock(cc1_awlock),
        .m_axi_awcache (cc1_awcache),  .m_axi_awprot(cc1_awprot),
        .m_axi_awqos   (cc1_awqos),    .m_axi_awvalid(cc1_awvalid),
        .m_axi_awready (cc1_awready),
        .m_axi_wdata   (cc1_wdata),    .m_axi_wstrb(cc1_wstrb),
        .m_axi_wlast   (cc1_wlast),    .m_axi_wvalid(cc1_wvalid),
        .m_axi_wready  (cc1_wready),
        .m_axi_bid     (cc1_bid),      .m_axi_bresp(cc1_bresp),
        .m_axi_bvalid  (cc1_bvalid),   .m_axi_bready(cc1_bready),
        .m_axi_arid    (cc1_arid),     .m_axi_araddr(cc1_araddr),
        .m_axi_arlen   (cc1_arlen),    .m_axi_arsize(cc1_arsize),
        .m_axi_arburst (cc1_arburst),  .m_axi_arlock(cc1_arlock),
        .m_axi_arcache (cc1_arcache),  .m_axi_arprot(cc1_arprot),
        .m_axi_arqos   (cc1_arqos),    .m_axi_arvalid(cc1_arvalid),
        .m_axi_arready (cc1_arready),
        .m_axi_rid     (cc1_rid),      .m_axi_rdata(cc1_rdata),
        .m_axi_rresp   (cc1_rresp),    .m_axi_rlast(cc1_rlast),
        .m_axi_rvalid  (cc1_rvalid),   .m_axi_rready(cc1_rready)
    );

    // Width converter: NPU DMA 128→512-bit @ 133 MHz (c1_ui_clk domain).
    // s_axi: 64-bit addr, 4-bit ID, 128-bit data.
    // m_axi: 31-bit addr (MIG limit), no ID, 512-bit data → MIG C1.
    axi_dwidth_npu npu_dw_inst (
        .s_axi_aclk    (c1_ui_clk),
        .s_axi_aresetn (~c1_ui_clk_sync_rst),
        .s_axi_awid    (cc1_awid),     .s_axi_awaddr(cc1_awaddr),
        .s_axi_awlen   (cc1_awlen),    .s_axi_awsize(cc1_awsize),
        .s_axi_awburst (cc1_awburst),  .s_axi_awlock(cc1_awlock[0]),
        .s_axi_awcache (cc1_awcache),  .s_axi_awprot(cc1_awprot),
        .s_axi_awregion(4'b0000),      .s_axi_awqos(cc1_awqos),
        .s_axi_awvalid (cc1_awvalid),  .s_axi_awready(cc1_awready),
        .s_axi_wdata   (cc1_wdata),    .s_axi_wstrb(cc1_wstrb),
        .s_axi_wlast   (cc1_wlast),    .s_axi_wvalid(cc1_wvalid),
        .s_axi_wready  (cc1_wready),
        .s_axi_bid     (cc1_bid),      .s_axi_bresp(cc1_bresp),
        .s_axi_bvalid  (cc1_bvalid),   .s_axi_bready(cc1_bready),
        .s_axi_arid    (cc1_arid),     .s_axi_araddr(cc1_araddr),
        .s_axi_arlen   (cc1_arlen),    .s_axi_arsize(cc1_arsize),
        .s_axi_arburst (cc1_arburst),  .s_axi_arlock(cc1_arlock[0]),
        .s_axi_arcache (cc1_arcache),  .s_axi_arprot(cc1_arprot),
        .s_axi_arregion(4'b0000),      .s_axi_arqos(cc1_arqos),
        .s_axi_arvalid (cc1_arvalid),  .s_axi_arready(cc1_arready),
        .s_axi_rid     (cc1_rid),      .s_axi_rdata(cc1_rdata),
        .s_axi_rresp   (cc1_rresp),    .s_axi_rlast(cc1_rlast),
        .s_axi_rvalid  (cc1_rvalid),   .s_axi_rready(cc1_rready),
        // 512-bit master (no IDs); truncate addr to 31-bit for MIG
        .m_axi_awaddr  (mig_c1_awaddr),.m_axi_awlen(mig_c1_awlen),
        .m_axi_awsize  (mig_c1_awsize),.m_axi_awburst(mig_c1_awburst),
        .m_axi_awlock  (mig_c1_awlock),.m_axi_awcache(mig_c1_awcache),
        .m_axi_awprot  (mig_c1_awprot),.m_axi_awregion(),
        .m_axi_awqos   (mig_c1_awqos), .m_axi_awvalid(mig_c1_awvalid),
        .m_axi_awready (mig_c1_awready),
        .m_axi_wdata   (mig_c1_wdata), .m_axi_wstrb(mig_c1_wstrb),
        .m_axi_wlast   (mig_c1_wlast), .m_axi_wvalid(mig_c1_wvalid),
        .m_axi_wready  (mig_c1_wready),
        .m_axi_bresp   (mig_c1_bresp), .m_axi_bvalid(mig_c1_bvalid),
        .m_axi_bready  (mig_c1_bready),
        .m_axi_araddr  (mig_c1_araddr),.m_axi_arlen(mig_c1_arlen),
        .m_axi_arsize  (mig_c1_arsize),.m_axi_arburst(mig_c1_arburst),
        .m_axi_arlock  (mig_c1_arlock),.m_axi_arcache(mig_c1_arcache),
        .m_axi_arprot  (mig_c1_arprot),.m_axi_arregion(),
        .m_axi_arqos   (mig_c1_arqos), .m_axi_arvalid(mig_c1_arvalid),
        .m_axi_arready (mig_c1_arready),
        .m_axi_rdata   (mig_c1_rdata), .m_axi_rresp(mig_c1_rresp),
        .m_axi_rlast   (mig_c1_rlast), .m_axi_rvalid(mig_c1_rvalid),
        .m_axi_rready  (mig_c1_rready)
    );
    // dwidth master has no ID outputs; tie MIG C1 ID inputs to 0
    assign mig_c1_awid = 4'h0;
    assign mig_c1_arid = 4'h0;

    // -----------------------------------------------------------------------
    // AXI Protocol Converter: BYPASS (128b AXI4 @ 250 MHz) → 32b AXI4-Lite
    // Runs at axi_aclk (250 MHz). Output bridged to fabric_aclk by axi_cc_byp_in.
    // -----------------------------------------------------------------------
    // 250 MHz wires from proto_conv output → axi_cc_byp_in slave
    wire [11:0]  ctrl_250_awaddr; wire [2:0] ctrl_250_awprot;
    wire ctrl_250_awvalid, ctrl_250_awready;
    wire [31:0]  ctrl_250_wdata;  wire [3:0] ctrl_250_wstrb;
    wire ctrl_250_wvalid,  ctrl_250_wready;
    wire [1:0]   ctrl_250_bresp;  wire ctrl_250_bvalid, ctrl_250_bready;
    wire [11:0]  ctrl_250_araddr; wire [2:0] ctrl_250_arprot;
    wire ctrl_250_arvalid, ctrl_250_arready;
    wire [31:0]  ctrl_250_rdata;  wire [1:0] ctrl_250_rresp;
    wire ctrl_250_rvalid,  ctrl_250_rready;

    // 200 MHz wires from axi_cc_byp_in master → ctrl_lite_inst slave
    wire [11:0]  ctrl_awaddr; wire [2:0] ctrl_awprot; wire ctrl_awvalid, ctrl_awready;
    wire [31:0]  ctrl_wdata;  wire [3:0] ctrl_wstrb;  wire ctrl_wvalid,  ctrl_wready;
    wire [1:0]   ctrl_bresp;  wire ctrl_bvalid, ctrl_bready;
    wire [11:0]  ctrl_araddr; wire [2:0] ctrl_arprot; wire ctrl_arvalid, ctrl_arready;
    wire [31:0]  ctrl_rdata;  wire [1:0] ctrl_rresp;  wire ctrl_rvalid,  ctrl_rready;

    axi_protocol_converter_0 proto_conv_inst (
        .aclk    (axi_aclk), .aresetn(axi_aresetn),
        .s_axi_awaddr  (byp_awaddr[11:0]), .s_axi_awlen(8'h00),
        .s_axi_awsize  (3'b010),           .s_axi_awburst(2'b01),
        .s_axi_awlock  (1'b0),             .s_axi_awcache(4'b0000),
        .s_axi_awprot  (byp_awprot),       .s_axi_awregion(4'b0000),
        .s_axi_awqos   (4'b0000),          .s_axi_awvalid(byp_awvalid),
        .s_axi_awready (byp_awready),
        .s_axi_wdata   (byp_wdata[31:0]),  .s_axi_wstrb(byp_wstrb[3:0]),
        .s_axi_wlast   (1'b1),             .s_axi_wvalid(byp_wvalid),
        .s_axi_wready  (byp_wready),
        .s_axi_bresp   (byp_bresp),        .s_axi_bvalid(byp_bvalid),
        .s_axi_bready  (byp_bready),
        .s_axi_araddr  (byp_araddr[11:0]), .s_axi_arlen(8'h00),
        .s_axi_arsize  (3'b010),           .s_axi_arburst(2'b01),
        .s_axi_arlock  (1'b0),             .s_axi_arcache(4'b0000),
        .s_axi_arprot  (byp_arprot),       .s_axi_arregion(4'b0000),
        .s_axi_arqos   (4'b0000),          .s_axi_arvalid(byp_arvalid),
        .s_axi_arready (byp_arready),
        .s_axi_rdata   (byp_rdata[31:0]),  .s_axi_rresp(byp_rresp),
        .s_axi_rlast   (),                 .s_axi_rvalid(byp_rvalid),
        .s_axi_rready  (byp_rready),
        .m_axi_awaddr  (ctrl_250_awaddr),  .m_axi_awprot(ctrl_250_awprot),
        .m_axi_awvalid (ctrl_250_awvalid), .m_axi_awready(ctrl_250_awready),
        .m_axi_wdata   (ctrl_250_wdata),   .m_axi_wstrb(ctrl_250_wstrb),
        .m_axi_wvalid  (ctrl_250_wvalid),  .m_axi_wready(ctrl_250_wready),
        .m_axi_bresp   (ctrl_250_bresp),   .m_axi_bvalid(ctrl_250_bvalid),
        .m_axi_bready  (ctrl_250_bready),
        .m_axi_araddr  (ctrl_250_araddr),  .m_axi_arprot(ctrl_250_arprot),
        .m_axi_arvalid (ctrl_250_arvalid), .m_axi_arready(ctrl_250_arready),
        .m_axi_rdata   (ctrl_250_rdata),   .m_axi_rresp(ctrl_250_rresp),
        .m_axi_rvalid  (ctrl_250_rvalid),  .m_axi_rready(ctrl_250_rready)
    );
    assign byp_rdata[127:32] = 96'h0;

    // AXI4-Lite clock converter: 250 MHz (proto_conv output) → 200 MHz (fabric)
    axi_cc_byp_in byp_cc_inst (
        .s_axi_aclk    (axi_aclk),         .s_axi_aresetn(axi_aresetn),
        .s_axi_awaddr  (ctrl_250_awaddr),   .s_axi_awprot(ctrl_250_awprot),
        .s_axi_awvalid (ctrl_250_awvalid),  .s_axi_awready(ctrl_250_awready),
        .s_axi_wdata   (ctrl_250_wdata),    .s_axi_wstrb(ctrl_250_wstrb),
        .s_axi_wvalid  (ctrl_250_wvalid),   .s_axi_wready(ctrl_250_wready),
        .s_axi_bresp   (ctrl_250_bresp),    .s_axi_bvalid(ctrl_250_bvalid),
        .s_axi_bready  (ctrl_250_bready),
        .s_axi_araddr  (ctrl_250_araddr),   .s_axi_arprot(ctrl_250_arprot),
        .s_axi_arvalid (ctrl_250_arvalid),  .s_axi_arready(ctrl_250_arready),
        .s_axi_rdata   (ctrl_250_rdata),    .s_axi_rresp(ctrl_250_rresp),
        .s_axi_rvalid  (ctrl_250_rvalid),   .s_axi_rready(ctrl_250_rready),
        .m_axi_aclk    (fabric_aclk),       .m_axi_aresetn(fabric_aresetn),
        .m_axi_awaddr  (ctrl_awaddr),       .m_axi_awprot(ctrl_awprot),
        .m_axi_awvalid (ctrl_awvalid),      .m_axi_awready(ctrl_awready),
        .m_axi_wdata   (ctrl_wdata),        .m_axi_wstrb(ctrl_wstrb),
        .m_axi_wvalid  (ctrl_wvalid),       .m_axi_wready(ctrl_wready),
        .m_axi_bresp   (ctrl_bresp),        .m_axi_bvalid(ctrl_bvalid),
        .m_axi_bready  (ctrl_bready),
        .m_axi_araddr  (ctrl_araddr),       .m_axi_arprot(ctrl_arprot),
        .m_axi_arvalid (ctrl_arvalid),      .m_axi_arready(ctrl_arready),
        .m_axi_rdata   (ctrl_rdata),        .m_axi_rresp(ctrl_rresp),
        .m_axi_rvalid  (ctrl_rvalid),       .m_axi_rready(ctrl_rready)
    );

    // -----------------------------------------------------------------------
    // npu_ctrl_lite — AXI4-Lite ctrl/status register (200 MHz fabric domain)
    // -----------------------------------------------------------------------
    wire npu_start, npu_done, npu_busy;

    npu_ctrl_lite #(.ADDR_WIDTH(12), .DATA_WIDTH(32)) ctrl_lite_inst (
        .axi_aclk(fabric_aclk), .axi_aresetn(fabric_aresetn),
        .s_axil_awaddr(ctrl_awaddr),  .s_axil_awprot(ctrl_awprot),
        .s_axil_awvalid(ctrl_awvalid),.s_axil_awready(ctrl_awready),
        .s_axil_wdata(ctrl_wdata),    .s_axil_wstrb(ctrl_wstrb),
        .s_axil_wvalid(ctrl_wvalid),  .s_axil_wready(ctrl_wready),
        .s_axil_bresp(ctrl_bresp),    .s_axil_bvalid(ctrl_bvalid),
        .s_axil_bready(ctrl_bready),
        .s_axil_araddr(ctrl_araddr),  .s_axil_arprot(ctrl_arprot),
        .s_axil_arvalid(ctrl_arvalid),.s_axil_arready(ctrl_arready),
        .s_axil_rdata(ctrl_rdata),    .s_axil_rresp(ctrl_rresp),
        .s_axil_rvalid(ctrl_rvalid),  .s_axil_rready(ctrl_rready),
        .start(npu_start), .done(npu_done), .busy(npu_busy)
    );

    // -----------------------------------------------------------------------
    // MMALU port wires (K=32)
    // -----------------------------------------------------------------------
    wire [7:0]  mmalu_in_a  [0:31];
    wire [7:0]  mmalu_in_b  [0:31];
    wire [31:0] mmalu_in_accum [0:31];
    wire        mmalu_ctrl_keep, mmalu_ctrl_use_accum, mmalu_ctrl_busy;
    wire [31:0] mmalu_out   [0:31];
    wire        mmalu_clct;

    // -----------------------------------------------------------------------
    // npu_dma_master — AXI4 master (128-bit, 64-bit addr → MIG C1 via dwidth+clkconv)
    // -----------------------------------------------------------------------
    npu_dma_master #(
        .AXI_DATA_WIDTH     (128),
        .AXI_ADDR_WIDTH     (64),
        .AXI_ID_WIDTH       (4),
        .DEFAULT_BASE_A     (64'h1000_0000_0000_0000),
        .DEFAULT_BASE_B     (64'h1000_0000_0000_0100),
        .DEFAULT_BASE_ACCUM (64'h1000_0000_0000_0200),
        .DEFAULT_BASE_OUT   (64'h1000_0000_0000_0400)
    ) dma_master_inst (
        .aclk(fabric_aclk), .aresetn(fabric_aresetn),
        .start(npu_start), .busy(npu_busy), .done(npu_done),
        .m_axi_awid(npu_awid),     .m_axi_awaddr(npu_awaddr),
        .m_axi_awlen(npu_awlen),   .m_axi_awsize(npu_awsize),
        .m_axi_awburst(npu_awburst),.m_axi_awlock(npu_awlock),
        .m_axi_awcache(npu_awcache),.m_axi_awprot(npu_awprot),
        .m_axi_awqos(npu_awqos),   .m_axi_awvalid(npu_awvalid),
        .m_axi_awready(npu_awready),
        .m_axi_wdata(npu_wdata),   .m_axi_wstrb(npu_wstrb),
        .m_axi_wlast(npu_wlast),   .m_axi_wvalid(npu_wvalid),
        .m_axi_wready(npu_wready),
        .m_axi_bid(npu_bid),       .m_axi_bresp(npu_bresp),
        .m_axi_bvalid(npu_bvalid), .m_axi_bready(npu_bready),
        .m_axi_arid(npu_arid),     .m_axi_araddr(npu_araddr),
        .m_axi_arlen(npu_arlen),   .m_axi_arsize(npu_arsize),
        .m_axi_arburst(npu_arburst),.m_axi_arlock(npu_arlock),
        .m_axi_arcache(npu_arcache),.m_axi_arprot(npu_arprot),
        .m_axi_arqos(npu_arqos),   .m_axi_arvalid(npu_arvalid),
        .m_axi_arready(npu_arready),
        .m_axi_rdata(npu_rdata),   .m_axi_rresp(npu_rresp),
        .m_axi_rlast(npu_rlast),   .m_axi_rvalid(npu_rvalid),
        .m_axi_rready(npu_rready),
        // MMALU inputs (K=32)
        .io_in_a_0(mmalu_in_a[0]),  .io_in_a_1(mmalu_in_a[1]),  .io_in_a_2(mmalu_in_a[2]),  .io_in_a_3(mmalu_in_a[3]),
        .io_in_a_4(mmalu_in_a[4]),  .io_in_a_5(mmalu_in_a[5]),  .io_in_a_6(mmalu_in_a[6]),  .io_in_a_7(mmalu_in_a[7]),
        .io_in_a_8(mmalu_in_a[8]),  .io_in_a_9(mmalu_in_a[9]),  .io_in_a_10(mmalu_in_a[10]),.io_in_a_11(mmalu_in_a[11]),
        .io_in_a_12(mmalu_in_a[12]),.io_in_a_13(mmalu_in_a[13]),.io_in_a_14(mmalu_in_a[14]),.io_in_a_15(mmalu_in_a[15]),
        .io_in_a_16(mmalu_in_a[16]),.io_in_a_17(mmalu_in_a[17]),.io_in_a_18(mmalu_in_a[18]),.io_in_a_19(mmalu_in_a[19]),
        .io_in_a_20(mmalu_in_a[20]),.io_in_a_21(mmalu_in_a[21]),.io_in_a_22(mmalu_in_a[22]),.io_in_a_23(mmalu_in_a[23]),
        .io_in_a_24(mmalu_in_a[24]),.io_in_a_25(mmalu_in_a[25]),.io_in_a_26(mmalu_in_a[26]),.io_in_a_27(mmalu_in_a[27]),
        .io_in_a_28(mmalu_in_a[28]),.io_in_a_29(mmalu_in_a[29]),.io_in_a_30(mmalu_in_a[30]),.io_in_a_31(mmalu_in_a[31]),
        .io_in_b_0(mmalu_in_b[0]),  .io_in_b_1(mmalu_in_b[1]),  .io_in_b_2(mmalu_in_b[2]),  .io_in_b_3(mmalu_in_b[3]),
        .io_in_b_4(mmalu_in_b[4]),  .io_in_b_5(mmalu_in_b[5]),  .io_in_b_6(mmalu_in_b[6]),  .io_in_b_7(mmalu_in_b[7]),
        .io_in_b_8(mmalu_in_b[8]),  .io_in_b_9(mmalu_in_b[9]),  .io_in_b_10(mmalu_in_b[10]),.io_in_b_11(mmalu_in_b[11]),
        .io_in_b_12(mmalu_in_b[12]),.io_in_b_13(mmalu_in_b[13]),.io_in_b_14(mmalu_in_b[14]),.io_in_b_15(mmalu_in_b[15]),
        .io_in_b_16(mmalu_in_b[16]),.io_in_b_17(mmalu_in_b[17]),.io_in_b_18(mmalu_in_b[18]),.io_in_b_19(mmalu_in_b[19]),
        .io_in_b_20(mmalu_in_b[20]),.io_in_b_21(mmalu_in_b[21]),.io_in_b_22(mmalu_in_b[22]),.io_in_b_23(mmalu_in_b[23]),
        .io_in_b_24(mmalu_in_b[24]),.io_in_b_25(mmalu_in_b[25]),.io_in_b_26(mmalu_in_b[26]),.io_in_b_27(mmalu_in_b[27]),
        .io_in_b_28(mmalu_in_b[28]),.io_in_b_29(mmalu_in_b[29]),.io_in_b_30(mmalu_in_b[30]),.io_in_b_31(mmalu_in_b[31]),
        .io_in_accum_0(mmalu_in_accum[0]),  .io_in_accum_1(mmalu_in_accum[1]),
        .io_in_accum_2(mmalu_in_accum[2]),  .io_in_accum_3(mmalu_in_accum[3]),
        .io_in_accum_4(mmalu_in_accum[4]),  .io_in_accum_5(mmalu_in_accum[5]),
        .io_in_accum_6(mmalu_in_accum[6]),  .io_in_accum_7(mmalu_in_accum[7]),
        .io_in_accum_8(mmalu_in_accum[8]),  .io_in_accum_9(mmalu_in_accum[9]),
        .io_in_accum_10(mmalu_in_accum[10]),.io_in_accum_11(mmalu_in_accum[11]),
        .io_in_accum_12(mmalu_in_accum[12]),.io_in_accum_13(mmalu_in_accum[13]),
        .io_in_accum_14(mmalu_in_accum[14]),.io_in_accum_15(mmalu_in_accum[15]),
        .io_in_accum_16(mmalu_in_accum[16]),.io_in_accum_17(mmalu_in_accum[17]),
        .io_in_accum_18(mmalu_in_accum[18]),.io_in_accum_19(mmalu_in_accum[19]),
        .io_in_accum_20(mmalu_in_accum[20]),.io_in_accum_21(mmalu_in_accum[21]),
        .io_in_accum_22(mmalu_in_accum[22]),.io_in_accum_23(mmalu_in_accum[23]),
        .io_in_accum_24(mmalu_in_accum[24]),.io_in_accum_25(mmalu_in_accum[25]),
        .io_in_accum_26(mmalu_in_accum[26]),.io_in_accum_27(mmalu_in_accum[27]),
        .io_in_accum_28(mmalu_in_accum[28]),.io_in_accum_29(mmalu_in_accum[29]),
        .io_in_accum_30(mmalu_in_accum[30]),.io_in_accum_31(mmalu_in_accum[31]),
        .io_ctrl_keep(mmalu_ctrl_keep), .io_ctrl_use_accum(mmalu_ctrl_use_accum),
        .io_ctrl_busy(mmalu_ctrl_busy),
        .io_out_0(mmalu_out[0]),  .io_out_1(mmalu_out[1]),  .io_out_2(mmalu_out[2]),  .io_out_3(mmalu_out[3]),
        .io_out_4(mmalu_out[4]),  .io_out_5(mmalu_out[5]),  .io_out_6(mmalu_out[6]),  .io_out_7(mmalu_out[7]),
        .io_out_8(mmalu_out[8]),  .io_out_9(mmalu_out[9]),  .io_out_10(mmalu_out[10]),.io_out_11(mmalu_out[11]),
        .io_out_12(mmalu_out[12]),.io_out_13(mmalu_out[13]),.io_out_14(mmalu_out[14]),.io_out_15(mmalu_out[15]),
        .io_out_16(mmalu_out[16]),.io_out_17(mmalu_out[17]),.io_out_18(mmalu_out[18]),.io_out_19(mmalu_out[19]),
        .io_out_20(mmalu_out[20]),.io_out_21(mmalu_out[21]),.io_out_22(mmalu_out[22]),.io_out_23(mmalu_out[23]),
        .io_out_24(mmalu_out[24]),.io_out_25(mmalu_out[25]),.io_out_26(mmalu_out[26]),.io_out_27(mmalu_out[27]),
        .io_out_28(mmalu_out[28]),.io_out_29(mmalu_out[29]),.io_out_30(mmalu_out[30]),.io_out_31(mmalu_out[31]),
        .io_clct(mmalu_clct)
    );

    // -----------------------------------------------------------------------
    // MIG 7-Series Dual DDR3 Controller
    // C0 AXI slave is driven by xdma_dw_inst (no ID outputs → mig_c0_awid/arid = 4'h0 above).
    // C1 AXI slave is driven by npu_dw_inst  (no ID outputs → mig_c1_awid/arid = 4'h0 above).
    // -----------------------------------------------------------------------
    mig_7series_0 mig_inst (
        // C0 DDR3 physical
        .c0_ddr3_addr(c0_ddr3_addr),   .c0_ddr3_ba(c0_ddr3_ba),
        .c0_ddr3_cas_n(c0_ddr3_cas_n), .c0_ddr3_ck_p(c0_ddr3_ck_p),
        .c0_ddr3_ck_n(c0_ddr3_ck_n),   .c0_ddr3_cke(c0_ddr3_cke),
        .c0_ddr3_cs_n(c0_ddr3_cs_n),   .c0_ddr3_dq(c0_ddr3_dq),
        .c0_ddr3_dqs_p(c0_ddr3_dqs_p), .c0_ddr3_dqs_n(c0_ddr3_dqs_n),
        .c0_ddr3_odt(c0_ddr3_odt),     .c0_ddr3_ras_n(c0_ddr3_ras_n),
        .c0_ddr3_reset_n(c0_ddr3_reset_n),.c0_ddr3_we_n(c0_ddr3_we_n),
        .c0_sys_clk_p(c0_sys_clk_p),   .c0_sys_clk_n(c0_sys_clk_n),
        .c0_ui_clk(c0_ui_clk),         .c0_ui_clk_sync_rst(c0_ui_clk_sync_rst),
        .c0_init_calib_complete(c0_init_calib_complete),
        // C0 AXI4 slave (512-bit, driven by xdma_cc_inst master side)
        .c0_s_axi_awid(mig_c0_awid),     .c0_s_axi_awaddr(mig_c0_awaddr),
        .c0_s_axi_awlen(mig_c0_awlen),   .c0_s_axi_awsize(mig_c0_awsize),
        .c0_s_axi_awburst(mig_c0_awburst),.c0_s_axi_awlock(mig_c0_awlock),
        .c0_s_axi_awcache(mig_c0_awcache),.c0_s_axi_awprot(mig_c0_awprot),
        .c0_s_axi_awqos(mig_c0_awqos),   .c0_s_axi_awvalid(mig_c0_awvalid),
        .c0_s_axi_awready(mig_c0_awready),
        .c0_s_axi_wdata(mig_c0_wdata),   .c0_s_axi_wstrb(mig_c0_wstrb),
        .c0_s_axi_wlast(mig_c0_wlast),   .c0_s_axi_wvalid(mig_c0_wvalid),
        .c0_s_axi_wready(mig_c0_wready),
        .c0_s_axi_bid(mig_c0_bid),       .c0_s_axi_bresp(mig_c0_bresp),
        .c0_s_axi_bvalid(mig_c0_bvalid), .c0_s_axi_bready(mig_c0_bready),
        .c0_s_axi_arid(mig_c0_arid),     .c0_s_axi_araddr(mig_c0_araddr),
        .c0_s_axi_arlen(mig_c0_arlen),   .c0_s_axi_arsize(mig_c0_arsize),
        .c0_s_axi_arburst(mig_c0_arburst),.c0_s_axi_arlock(mig_c0_arlock),
        .c0_s_axi_arcache(mig_c0_arcache),.c0_s_axi_arprot(mig_c0_arprot),
        .c0_s_axi_arqos(mig_c0_arqos),   .c0_s_axi_arvalid(mig_c0_arvalid),
        .c0_s_axi_arready(mig_c0_arready),
        .c0_s_axi_rid(mig_c0_rid),       .c0_s_axi_rdata(mig_c0_rdata),
        .c0_s_axi_rresp(mig_c0_rresp),   .c0_s_axi_rlast(mig_c0_rlast),
        .c0_s_axi_rvalid(mig_c0_rvalid), .c0_s_axi_rready(mig_c0_rready),
        // C0 AXI4-Lite ctrl (tied off — ECC ctrl not used for bring-up)
        .c0_s_axi_ctrl_awvalid(1'b0), .c0_s_axi_ctrl_awaddr(11'h0),
        .c0_s_axi_ctrl_wvalid(1'b0),  .c0_s_axi_ctrl_wdata(32'h0),
        .c0_s_axi_ctrl_bready(1'b1),  .c0_s_axi_ctrl_arvalid(1'b0),
        .c0_s_axi_ctrl_araddr(11'h0), .c0_s_axi_ctrl_rready(1'b1),
        // C0 optional maintenance/reset ports (tie off)
        .c0_aresetn       (axi_aresetn),
        .c0_app_sr_req    (1'b0),
        .c0_app_ref_req   (1'b0),
        .c0_app_zq_req    (1'b0),
        // C1 DDR3 physical
        .c1_ddr3_addr(c1_ddr3_addr),   .c1_ddr3_ba(c1_ddr3_ba),
        .c1_ddr3_cas_n(c1_ddr3_cas_n), .c1_ddr3_ck_p(c1_ddr3_ck_p),
        .c1_ddr3_ck_n(c1_ddr3_ck_n),   .c1_ddr3_cke(c1_ddr3_cke),
        .c1_ddr3_cs_n(c1_ddr3_cs_n),   .c1_ddr3_dq(c1_ddr3_dq),
        .c1_ddr3_dqs_p(c1_ddr3_dqs_p), .c1_ddr3_dqs_n(c1_ddr3_dqs_n),
        .c1_ddr3_odt(c1_ddr3_odt),     .c1_ddr3_ras_n(c1_ddr3_ras_n),
        .c1_ddr3_reset_n(c1_ddr3_reset_n),.c1_ddr3_we_n(c1_ddr3_we_n),
        .c1_sys_clk_p(c1_sys_clk_p),   .c1_sys_clk_n(c1_sys_clk_n),
        .c1_ui_clk(c1_ui_clk),         .c1_ui_clk_sync_rst(c1_ui_clk_sync_rst),
        .c1_init_calib_complete(c1_init_calib_complete),
        // C1 AXI4 slave (512-bit, driven by npu_cc_inst master side)
        .c1_s_axi_awid(mig_c1_awid),     .c1_s_axi_awaddr(mig_c1_awaddr),
        .c1_s_axi_awlen(mig_c1_awlen),   .c1_s_axi_awsize(mig_c1_awsize),
        .c1_s_axi_awburst(mig_c1_awburst),.c1_s_axi_awlock(mig_c1_awlock),
        .c1_s_axi_awcache(mig_c1_awcache),.c1_s_axi_awprot(mig_c1_awprot),
        .c1_s_axi_awqos(mig_c1_awqos),   .c1_s_axi_awvalid(mig_c1_awvalid),
        .c1_s_axi_awready(mig_c1_awready),
        .c1_s_axi_wdata(mig_c1_wdata),   .c1_s_axi_wstrb(mig_c1_wstrb),
        .c1_s_axi_wlast(mig_c1_wlast),   .c1_s_axi_wvalid(mig_c1_wvalid),
        .c1_s_axi_wready(mig_c1_wready),
        .c1_s_axi_bid(mig_c1_bid),       .c1_s_axi_bresp(mig_c1_bresp),
        .c1_s_axi_bvalid(mig_c1_bvalid), .c1_s_axi_bready(mig_c1_bready),
        .c1_s_axi_arid(mig_c1_arid),     .c1_s_axi_araddr(mig_c1_araddr),
        .c1_s_axi_arlen(mig_c1_arlen),   .c1_s_axi_arsize(mig_c1_arsize),
        .c1_s_axi_arburst(mig_c1_arburst),.c1_s_axi_arlock(mig_c1_arlock),
        .c1_s_axi_arcache(mig_c1_arcache),.c1_s_axi_arprot(mig_c1_arprot),
        .c1_s_axi_arqos(mig_c1_arqos),   .c1_s_axi_arvalid(mig_c1_arvalid),
        .c1_s_axi_arready(mig_c1_arready),
        .c1_s_axi_rid(mig_c1_rid),       .c1_s_axi_rdata(mig_c1_rdata),
        .c1_s_axi_rresp(mig_c1_rresp),   .c1_s_axi_rlast(mig_c1_rlast),
        .c1_s_axi_rvalid(mig_c1_rvalid), .c1_s_axi_rready(mig_c1_rready),
        // C1 AXI4-Lite ctrl (tied off)
        .c1_s_axi_ctrl_awvalid(1'b0), .c1_s_axi_ctrl_awaddr(11'h0),
        .c1_s_axi_ctrl_wvalid(1'b0),  .c1_s_axi_ctrl_wdata(32'h0),
        .c1_s_axi_ctrl_bready(1'b1),  .c1_s_axi_ctrl_arvalid(1'b0),
        .c1_s_axi_ctrl_araddr(11'h0), .c1_s_axi_ctrl_rready(1'b1),
        // C1 optional maintenance/reset ports (tie off)
        .c1_aresetn       (axi_aresetn),
        .c1_app_sr_req    (1'b0),
        .c1_app_ref_req   (1'b0),
        .c1_app_zq_req    (1'b0),
        .sys_rst(mig_sys_rst_n)
    );

    // -----------------------------------------------------------------------
    // MMALU — Chisel-generated 32×32 systolic array (from top.sv, K=32)
    // Clocked on fabric_aclk (200 MHz); reset is active-high (inverted fabric_aresetn)
    // -----------------------------------------------------------------------
    MMALU mmalu_inst (
        .clock(fabric_aclk), .reset(~fabric_aresetn),
        // K=32 port map
        .io_in_a_0(mmalu_in_a[0]),  .io_in_a_1(mmalu_in_a[1]),  .io_in_a_2(mmalu_in_a[2]),  .io_in_a_3(mmalu_in_a[3]),
        .io_in_a_4(mmalu_in_a[4]),  .io_in_a_5(mmalu_in_a[5]),  .io_in_a_6(mmalu_in_a[6]),  .io_in_a_7(mmalu_in_a[7]),
        .io_in_a_8(mmalu_in_a[8]),  .io_in_a_9(mmalu_in_a[9]),  .io_in_a_10(mmalu_in_a[10]),.io_in_a_11(mmalu_in_a[11]),
        .io_in_a_12(mmalu_in_a[12]),.io_in_a_13(mmalu_in_a[13]),.io_in_a_14(mmalu_in_a[14]),.io_in_a_15(mmalu_in_a[15]),
        .io_in_a_16(mmalu_in_a[16]),.io_in_a_17(mmalu_in_a[17]),.io_in_a_18(mmalu_in_a[18]),.io_in_a_19(mmalu_in_a[19]),
        .io_in_a_20(mmalu_in_a[20]),.io_in_a_21(mmalu_in_a[21]),.io_in_a_22(mmalu_in_a[22]),.io_in_a_23(mmalu_in_a[23]),
        .io_in_a_24(mmalu_in_a[24]),.io_in_a_25(mmalu_in_a[25]),.io_in_a_26(mmalu_in_a[26]),.io_in_a_27(mmalu_in_a[27]),
        .io_in_a_28(mmalu_in_a[28]),.io_in_a_29(mmalu_in_a[29]),.io_in_a_30(mmalu_in_a[30]),.io_in_a_31(mmalu_in_a[31]),
        .io_in_b_0(mmalu_in_b[0]),  .io_in_b_1(mmalu_in_b[1]),  .io_in_b_2(mmalu_in_b[2]),  .io_in_b_3(mmalu_in_b[3]),
        .io_in_b_4(mmalu_in_b[4]),  .io_in_b_5(mmalu_in_b[5]),  .io_in_b_6(mmalu_in_b[6]),  .io_in_b_7(mmalu_in_b[7]),
        .io_in_b_8(mmalu_in_b[8]),  .io_in_b_9(mmalu_in_b[9]),  .io_in_b_10(mmalu_in_b[10]),.io_in_b_11(mmalu_in_b[11]),
        .io_in_b_12(mmalu_in_b[12]),.io_in_b_13(mmalu_in_b[13]),.io_in_b_14(mmalu_in_b[14]),.io_in_b_15(mmalu_in_b[15]),
        .io_in_b_16(mmalu_in_b[16]),.io_in_b_17(mmalu_in_b[17]),.io_in_b_18(mmalu_in_b[18]),.io_in_b_19(mmalu_in_b[19]),
        .io_in_b_20(mmalu_in_b[20]),.io_in_b_21(mmalu_in_b[21]),.io_in_b_22(mmalu_in_b[22]),.io_in_b_23(mmalu_in_b[23]),
        .io_in_b_24(mmalu_in_b[24]),.io_in_b_25(mmalu_in_b[25]),.io_in_b_26(mmalu_in_b[26]),.io_in_b_27(mmalu_in_b[27]),
        .io_in_b_28(mmalu_in_b[28]),.io_in_b_29(mmalu_in_b[29]),.io_in_b_30(mmalu_in_b[30]),.io_in_b_31(mmalu_in_b[31]),
        .io_in_accum_0(mmalu_in_accum[0]),  .io_in_accum_1(mmalu_in_accum[1]),
        .io_in_accum_2(mmalu_in_accum[2]),  .io_in_accum_3(mmalu_in_accum[3]),
        .io_in_accum_4(mmalu_in_accum[4]),  .io_in_accum_5(mmalu_in_accum[5]),
        .io_in_accum_6(mmalu_in_accum[6]),  .io_in_accum_7(mmalu_in_accum[7]),
        .io_in_accum_8(mmalu_in_accum[8]),  .io_in_accum_9(mmalu_in_accum[9]),
        .io_in_accum_10(mmalu_in_accum[10]),.io_in_accum_11(mmalu_in_accum[11]),
        .io_in_accum_12(mmalu_in_accum[12]),.io_in_accum_13(mmalu_in_accum[13]),
        .io_in_accum_14(mmalu_in_accum[14]),.io_in_accum_15(mmalu_in_accum[15]),
        .io_in_accum_16(mmalu_in_accum[16]),.io_in_accum_17(mmalu_in_accum[17]),
        .io_in_accum_18(mmalu_in_accum[18]),.io_in_accum_19(mmalu_in_accum[19]),
        .io_in_accum_20(mmalu_in_accum[20]),.io_in_accum_21(mmalu_in_accum[21]),
        .io_in_accum_22(mmalu_in_accum[22]),.io_in_accum_23(mmalu_in_accum[23]),
        .io_in_accum_24(mmalu_in_accum[24]),.io_in_accum_25(mmalu_in_accum[25]),
        .io_in_accum_26(mmalu_in_accum[26]),.io_in_accum_27(mmalu_in_accum[27]),
        .io_in_accum_28(mmalu_in_accum[28]),.io_in_accum_29(mmalu_in_accum[29]),
        .io_in_accum_30(mmalu_in_accum[30]),.io_in_accum_31(mmalu_in_accum[31]),
        .io_ctrl_keep(mmalu_ctrl_keep), .io_ctrl_use_accum(mmalu_ctrl_use_accum),
        .io_ctrl_busy(mmalu_ctrl_busy),
        .io_out_0(mmalu_out[0]),  .io_out_1(mmalu_out[1]),  .io_out_2(mmalu_out[2]),  .io_out_3(mmalu_out[3]),
        .io_out_4(mmalu_out[4]),  .io_out_5(mmalu_out[5]),  .io_out_6(mmalu_out[6]),  .io_out_7(mmalu_out[7]),
        .io_out_8(mmalu_out[8]),  .io_out_9(mmalu_out[9]),  .io_out_10(mmalu_out[10]),.io_out_11(mmalu_out[11]),
        .io_out_12(mmalu_out[12]),.io_out_13(mmalu_out[13]),.io_out_14(mmalu_out[14]),.io_out_15(mmalu_out[15]),
        .io_out_16(mmalu_out[16]),.io_out_17(mmalu_out[17]),.io_out_18(mmalu_out[18]),.io_out_19(mmalu_out[19]),
        .io_out_20(mmalu_out[20]),.io_out_21(mmalu_out[21]),.io_out_22(mmalu_out[22]),.io_out_23(mmalu_out[23]),
        .io_out_24(mmalu_out[24]),.io_out_25(mmalu_out[25]),.io_out_26(mmalu_out[26]),.io_out_27(mmalu_out[27]),
        .io_out_28(mmalu_out[28]),.io_out_29(mmalu_out[29]),.io_out_30(mmalu_out[30]),.io_out_31(mmalu_out[31]),
        .io_clct(mmalu_clct)
    );

endmodule
