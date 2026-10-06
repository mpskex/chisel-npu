// npu_vu9p_top.v — NPU program-engine on the xcvu9p (Alivu9p) platform.
//
// Derived from the reference hw_platform.v. Adds:
//   * NPU engine subsystem (Chisel NpuProgramEngineFrontend, top.sv) running
//     on a 200 MHz fabric clock (clk_wiz_fabric MMCM from axi_aclk/250 MHz),
//     as the S01 user master of the DDR4 xbar (ddr_top), via
//       128b AXI clock converter (200->250) + 128->512 dwidth converter.
//   * XDMA M_AXI_BYPASS (m_axib_*, 512b @250) -> 512->32 dwidth -> 32b AXI
//     clock converter (250->200) -> AXI4->AXI4-Lite protocol converter ->
//     engine ctrl_lite (BAR2 MMIO).
//
// Everything else (XDMA M_AXI -> DDR4 S00, 4x MIG DDR4, GTY PCIe) is the
// reference platform.
`timescale 1ns/1ps

module hw_platform
(
  input                   [15:0] pcie_rxp,
  input                   [15:0] pcie_rxn,
  output                  [15:0] pcie_txp,
  output                  [15:0] pcie_txn,
  input                          pcie_refclk_p,
  input                          pcie_refclk_n,
  input                          pcie_rstn,

  input                          c0_sys_clk_p,
  input                          c0_sys_clk_n,
  output                         c0_ddr4_act_n,
  output [16:0]                  c0_ddr4_adr,
  output [1:0]                   c0_ddr4_ba,
  output [1:0]                   c0_ddr4_bg,
  output [0:0]                   c0_ddr4_cke,
  output [0:0]                   c0_ddr4_odt,
  output [0:0]                   c0_ddr4_cs_n,
  output [0:0]                   c0_ddr4_ck_t,
  output [0:0]                   c0_ddr4_ck_c,
  output                         c0_ddr4_reset_n,
  inout  [8:0]                   c0_ddr4_dm_dbi_n,
  inout  [71:0]                  c0_ddr4_dq,
  inout  [8:0]                   c0_ddr4_dqs_c,
  inout  [8:0]                   c0_ddr4_dqs_t,

  input                          c1_sys_clk_p,
  input                          c1_sys_clk_n,
  output                         c1_ddr4_act_n,
  output [16:0]                  c1_ddr4_adr,
  output [1:0]                   c1_ddr4_ba,
  output [1:0]                   c1_ddr4_bg,
  output [0:0]                   c1_ddr4_cke,
  output [0:0]                   c1_ddr4_odt,
  output [0:0]                   c1_ddr4_cs_n,
  output [0:0]                   c1_ddr4_ck_t,
  output [0:0]                   c1_ddr4_ck_c,
  output                         c1_ddr4_reset_n,
  inout  [8:0]                   c1_ddr4_dm_dbi_n,
  inout  [71:0]                  c1_ddr4_dq,
  inout  [8:0]                   c1_ddr4_dqs_c,
  inout  [8:0]                   c1_ddr4_dqs_t,

  input                          c2_sys_clk_p,
  input                          c2_sys_clk_n,
  output                         c2_ddr4_act_n,
  output [16:0]                  c2_ddr4_adr,
  output [1:0]                   c2_ddr4_ba,
  output [1:0]                   c2_ddr4_bg,
  output [0:0]                   c2_ddr4_cke,
  output [0:0]                   c2_ddr4_odt,
  output [0:0]                   c2_ddr4_cs_n,
  output [0:0]                   c2_ddr4_ck_t,
  output [0:0]                   c2_ddr4_ck_c,
  output                         c2_ddr4_reset_n,
  inout  [8:0]                   c2_ddr4_dm_dbi_n,
  inout  [71:0]                  c2_ddr4_dq,
  inout  [8:0]                   c2_ddr4_dqs_c,
  inout  [8:0]                   c2_ddr4_dqs_t,

  input                          c3_sys_clk_p,
  input                          c3_sys_clk_n,
  output                         c3_ddr4_act_n,
  output [16:0]                  c3_ddr4_adr,
  output [1:0]                   c3_ddr4_ba,
  output [1:0]                   c3_ddr4_bg,
  output [0:0]                   c3_ddr4_cke,
  output [0:0]                   c3_ddr4_odt,
  output [0:0]                   c3_ddr4_cs_n,
  output [0:0]                   c3_ddr4_ck_t,
  output [0:0]                   c3_ddr4_ck_c,
  output                         c3_ddr4_reset_n,
  inout  [8:0]                   c3_ddr4_dm_dbi_n,
  inout  [71:0]                  c3_ddr4_dq,
  inout  [8:0]                   c3_ddr4_dqs_c,
  inout  [8:0]                   c3_ddr4_dqs_t
);

  wire          pcie_refclk_gt;
  wire          pcie_refclk;
  wire          pcie_rstn_int;
  wire          axi_aclk;
  wire          axi_aresetn;
  wire          user_lnk_up;

  IBUFDS_GTE4 pcie_refclk_buf (
    .CEB   (1'b0),
    .I     (pcie_refclk_p),
    .IB    (pcie_refclk_n),
    .O     (pcie_refclk_gt),
    .ODIV2 (pcie_refclk)
  );
  IBUF pcie_rstn_ibuf_inst (.I(pcie_rstn), .O(pcie_rstn_int));

  // ── 200 MHz fabric clock for the NPU engine ────────────────────────────────
  wire clk_fabric;
  wire mmcm_locked;
  clk_wiz_fabric u_clk_wiz_fabric (
    .clk_in1  (axi_aclk),
    .resetn   (axi_aresetn),
    .clk_out1 (clk_fabric),
    .locked   (mmcm_locked)
  );

  // active-low async-assert / sync-deassert reset for the fabric domain
  wire arst_n = axi_aresetn & mmcm_locked;
  reg  [1:0] fab_rstn_sync;
  always @(posedge clk_fabric or negedge arst_n)
    if (!arst_n) fab_rstn_sync <= 2'b00;
    else         fab_rstn_sync <= {fab_rstn_sync[0], 1'b1};
  wire aresetn_fabric = fab_rstn_sync[1];

  // ── XDMA M_AXI (S00 of ddr_top) — same nets as hw_platform.v ───────────────
  wire              m_axi_awready;
  wire              m_axi_wready;
  wire [3 : 0]      m_axi_bid;
  wire [1 : 0]      m_axi_bresp;
  wire              m_axi_bvalid;
  wire              m_axi_arready;
  wire [3 : 0]      m_axi_rid;
  wire [511 : 0]    m_axi_rdata;
  wire [1 : 0]      m_axi_rresp;
  wire              m_axi_rlast;
  wire              m_axi_rvalid;
  wire [3 : 0]      m_axi_awid;
  wire [63 : 0]     m_axi_awaddr;
  wire [7 : 0]      m_axi_awlen;
  wire [2 : 0]      m_axi_awsize;
  wire [1 : 0]      m_axi_awburst;
  wire [2 : 0]      m_axi_awprot;
  wire              m_axi_awvalid;
  wire              m_axi_awlock;
  wire [3 : 0]      m_axi_awcache;
  wire [511 : 0]    m_axi_wdata;
  wire [63 : 0]     m_axi_wstrb;
  wire              m_axi_wlast;
  wire              m_axi_wvalid;
  wire              m_axi_bready;
  wire [3 : 0]      m_axi_arid;
  wire [63 : 0]     m_axi_araddr;
  wire [7 : 0]      m_axi_arlen;
  wire [2 : 0]      m_axi_arsize;
  wire [1 : 0]      m_axi_arburst;
  wire [2 : 0]      m_axi_arprot;
  wire              m_axi_arvalid;
  wire              m_axi_arlock;
  wire [3 : 0]      m_axi_arcache;
  wire              m_axi_rready;

  // ── XDMA M_AXI_BYPASS (BAR MMIO) ───────────────────────────────────────────
  wire [3 : 0]      m_axib_awid;
  wire [63 : 0]     m_axib_awaddr;
  wire [7 : 0]      m_axib_awlen;
  wire [2 : 0]      m_axib_awsize;
  wire [1 : 0]      m_axib_awburst;
  wire [2 : 0]      m_axib_awprot;
  wire              m_axib_awvalid;
  wire              m_axib_awready;
  wire              m_axib_awlock;
  wire [3 : 0]      m_axib_awcache;
  wire [511 : 0]    m_axib_wdata;
  wire [63 : 0]     m_axib_wstrb;
  wire              m_axib_wlast;
  wire              m_axib_wvalid;
  wire              m_axib_wready;
  wire [3 : 0]      m_axib_bid;
  wire [1 : 0]      m_axib_bresp;
  wire              m_axib_bvalid;
  wire              m_axib_bready;
  wire [3 : 0]      m_axib_arid;
  wire [63 : 0]     m_axib_araddr;
  wire [7 : 0]      m_axib_arlen;
  wire [2 : 0]      m_axib_arsize;
  wire [1 : 0]      m_axib_arburst;
  wire [2 : 0]      m_axib_arprot;
  wire              m_axib_arvalid;
  wire              m_axib_arready;
  wire              m_axib_arlock;
  wire [3 : 0]      m_axib_arcache;
  wire [3 : 0]      m_axib_rid;
  wire [511 : 0]    m_axib_rdata;
  wire [1 : 0]      m_axib_rresp;
  wire              m_axib_rlast;
  wire              m_axib_rvalid;
  wire              m_axib_rready;

  xdma_0 xdma_0 (
    .sys_clk                    (pcie_refclk                  ),
    .sys_clk_gt                 (pcie_refclk_gt               ),
    .sys_rst_n                  (pcie_rstn_int                ),
    .user_lnk_up                (user_lnk_up                  ),

    .pci_exp_txp                (pcie_txp                     ),
    .pci_exp_txn                (pcie_txn                     ),
    .pci_exp_rxp                (pcie_rxp                     ),
    .pci_exp_rxn                (pcie_rxn                     ),

    .axi_aclk                   (axi_aclk                     ),
    .axi_aresetn                (axi_aresetn                  ),
    .usr_irq_req                ('d0                          ),
    .usr_irq_ack                (),
    .msi_enable                 (),
    .msi_vector_width           (),

    .m_axi_awready              (m_axi_awready                ),
    .m_axi_wready               (m_axi_wready                 ),
    .m_axi_bid                  (m_axi_bid                    ),
    .m_axi_bresp                (m_axi_bresp                  ),
    .m_axi_bvalid               (m_axi_bvalid                 ),
    .m_axi_arready              (m_axi_arready                ),
    .m_axi_rid                  (m_axi_rid                    ),
    .m_axi_rdata                (m_axi_rdata                  ),
    .m_axi_rresp                (m_axi_rresp                  ),
    .m_axi_rlast                (m_axi_rlast                  ),
    .m_axi_rvalid               (m_axi_rvalid                 ),
    .m_axi_awid                 (m_axi_awid                   ),
    .m_axi_awaddr               (m_axi_awaddr                 ),
    .m_axi_awlen                (m_axi_awlen                  ),
    .m_axi_awsize               (m_axi_awsize                 ),
    .m_axi_awburst              (m_axi_awburst                ),
    .m_axi_awprot               (m_axi_awprot                 ),
    .m_axi_awvalid              (m_axi_awvalid                ),
    .m_axi_awlock               (m_axi_awlock                 ),
    .m_axi_awcache              (m_axi_awcache                ),
    .m_axi_wdata                (m_axi_wdata                  ),
    .m_axi_wstrb                (m_axi_wstrb                  ),
    .m_axi_wlast                (m_axi_wlast                  ),
    .m_axi_wvalid               (m_axi_wvalid                 ),
    .m_axi_bready               (m_axi_bready                 ),
    .m_axi_arid                 (m_axi_arid                   ),
    .m_axi_araddr               (m_axi_araddr                 ),
    .m_axi_arlen                (m_axi_arlen                  ),
    .m_axi_arsize               (m_axi_arsize                 ),
    .m_axi_arburst              (m_axi_arburst                ),
    .m_axi_arprot               (m_axi_arprot                 ),
    .m_axi_arvalid              (m_axi_arvalid                ),
    .m_axi_arlock               (m_axi_arlock                 ),
    .m_axi_arcache              (m_axi_arcache                ),
    .m_axi_rready               (m_axi_rready                 ),

    .m_axib_awid                (m_axib_awid                  ),
    .m_axib_awaddr              (m_axib_awaddr                ),
    .m_axib_awlen               (m_axib_awlen                 ),
    .m_axib_awsize              (m_axib_awsize                ),
    .m_axib_awburst             (m_axib_awburst               ),
    .m_axib_awprot              (m_axib_awprot                ),
    .m_axib_awvalid             (m_axib_awvalid               ),
    .m_axib_awready             (m_axib_awready               ),
    .m_axib_awlock              (m_axib_awlock                ),
    .m_axib_awcache             (m_axib_awcache               ),
    .m_axib_wdata               (m_axib_wdata                 ),
    .m_axib_wstrb               (m_axib_wstrb                 ),
    .m_axib_wlast               (m_axib_wlast                 ),
    .m_axib_wvalid              (m_axib_wvalid                ),
    .m_axib_wready              (m_axib_wready                ),
    .m_axib_bid                 (m_axib_bid                   ),
    .m_axib_bresp               (m_axib_bresp                 ),
    .m_axib_bvalid              (m_axib_bvalid                ),
    .m_axib_bready              (m_axib_bready                ),
    .m_axib_arid                (m_axib_arid                  ),
    .m_axib_araddr              (m_axib_araddr                ),
    .m_axib_arlen               (m_axib_arlen                 ),
    .m_axib_arsize              (m_axib_arsize                ),
    .m_axib_arburst             (m_axib_arburst               ),
    .m_axib_arprot              (m_axib_arprot                ),
    .m_axib_arvalid             (m_axib_arvalid               ),
    .m_axib_arready             (m_axib_arready               ),
    .m_axib_arlock              (m_axib_arlock                ),
    .m_axib_arcache             (m_axib_arcache               ),
    .m_axib_rid                 (m_axib_rid                   ),
    .m_axib_rdata               (m_axib_rdata                 ),
    .m_axib_rresp               (m_axib_rresp                 ),
    .m_axib_rlast               (m_axib_rlast                 ),
    .m_axib_rvalid              (m_axib_rvalid                ),
    .m_axib_rready              (m_axib_rready                ),

    .cfg_mgmt_addr              ('d1),
    .cfg_mgmt_write             ('d1),
    .cfg_mgmt_write_data        ('d1),
    .cfg_mgmt_byte_enable       ('d0),
    .cfg_mgmt_read              ('d0),
    .cfg_mgmt_read_data         (),
    .cfg_mgmt_read_write_done   ()
  );

  // ── NPU engine AXI master (128b @ fabric) ──────────────────────────────────
  wire [3 : 0]      npu_awid;
  wire [63 : 0]     npu_awaddr;
  wire [7 : 0]      npu_awlen;
  wire [2 : 0]      npu_awsize;
  wire [1 : 0]      npu_awburst;
  wire [2 : 0]      npu_awprot;
  wire              npu_awvalid;
  wire              npu_awready;
  wire              npu_awlock;
  wire [3 : 0]      npu_awcache;
  wire [3 : 0]      npu_awqos;
  wire [127 : 0]    npu_wdata;
  wire [15 : 0]     npu_wstrb;
  wire              npu_wlast;
  wire              npu_wvalid;
  wire              npu_wready;
  wire [3 : 0]      npu_bid;
  wire [1 : 0]      npu_bresp;
  wire              npu_bvalid;
  wire              npu_bready;
  wire [3 : 0]      npu_arid;
  wire [63 : 0]     npu_araddr;
  wire [7 : 0]      npu_arlen;
  wire [2 : 0]      npu_arsize;
  wire [1 : 0]      npu_arburst;
  wire [2 : 0]      npu_arprot;
  wire              npu_arvalid;
  wire              npu_arready;
  wire              npu_arlock;
  wire [3 : 0]      npu_arcache;
  wire [3 : 0]      npu_arqos;
  wire [3 : 0]      npu_rid;
  wire [127 : 0]    npu_rdata;
  wire [1 : 0]      npu_rresp;
  wire              npu_rlast;
  wire              npu_rvalid;
  wire              npu_rready;

  // ── NPU master after 200->250 clock crossing (128b @ axi_aclk) ─────────────
  wire [3 : 0]      npu250_awid;
  wire [63 : 0]     npu250_awaddr;
  wire [7 : 0]      npu250_awlen;
  wire [2 : 0]      npu250_awsize;
  wire [1 : 0]      npu250_awburst;
  wire [0 : 0]      npu250_awlock;
  wire [3 : 0]      npu250_awcache;
  wire [2 : 0]      npu250_awprot;
  wire [3 : 0]      npu250_awregion;
  wire [3 : 0]      npu250_awqos;
  wire              npu250_awvalid;
  wire              npu250_awready;
  wire [127 : 0]    npu250_wdata;
  wire [15 : 0]     npu250_wstrb;
  wire              npu250_wlast;
  wire              npu250_wvalid;
  wire              npu250_wready;
  wire [3 : 0]      npu250_bid;
  wire [1 : 0]      npu250_bresp;
  wire              npu250_bvalid;
  wire              npu250_bready;
  wire [3 : 0]      npu250_arid;
  wire [63 : 0]     npu250_araddr;
  wire [7 : 0]      npu250_arlen;
  wire [2 : 0]      npu250_arsize;
  wire [1 : 0]      npu250_arburst;
  wire [0 : 0]      npu250_arlock;
  wire [3 : 0]      npu250_arcache;
  wire [2 : 0]      npu250_arprot;
  wire [3 : 0]      npu250_arregion;
  wire [3 : 0]      npu250_arqos;
  wire              npu250_arvalid;
  wire              npu250_arready;
  wire [3 : 0]      npu250_rid;
  wire [127 : 0]    npu250_rdata;
  wire [1 : 0]      npu250_rresp;
  wire              npu250_rlast;
  wire              npu250_rvalid;
  wire              npu250_rready;

  npu_cc u_npu_cc (
    .s_axi_aclk     (clk_fabric),
    .s_axi_aresetn  (aresetn_fabric),
    .s_axi_awid     (npu_awid),
    .s_axi_awaddr   (npu_awaddr),
    .s_axi_awlen    (npu_awlen),
    .s_axi_awsize   (npu_awsize),
    .s_axi_awburst  (npu_awburst),
    .s_axi_awlock   (npu_awlock),
    .s_axi_awcache  (npu_awcache),
    .s_axi_awprot   (npu_awprot),
    .s_axi_awregion (4'b0),
    .s_axi_awqos    (npu_awqos),
    .s_axi_awvalid  (npu_awvalid),
    .s_axi_awready  (npu_awready),
    .s_axi_wdata    (npu_wdata),
    .s_axi_wstrb    (npu_wstrb),
    .s_axi_wlast    (npu_wlast),
    .s_axi_wvalid   (npu_wvalid),
    .s_axi_wready   (npu_wready),
    .s_axi_bid      (npu_bid),
    .s_axi_bresp    (npu_bresp),
    .s_axi_bvalid   (npu_bvalid),
    .s_axi_bready   (npu_bready),
    .s_axi_arid     (npu_arid),
    .s_axi_araddr   (npu_araddr),
    .s_axi_arlen    (npu_arlen),
    .s_axi_arsize   (npu_arsize),
    .s_axi_arburst  (npu_arburst),
    .s_axi_arlock   (npu_arlock),
    .s_axi_arcache  (npu_arcache),
    .s_axi_arprot   (npu_arprot),
    .s_axi_arregion (4'b0),
    .s_axi_arqos    (npu_arqos),
    .s_axi_arvalid  (npu_arvalid),
    .s_axi_arready  (npu_arready),
    .s_axi_rid      (npu_rid),
    .s_axi_rdata    (npu_rdata),
    .s_axi_rresp    (npu_rresp),
    .s_axi_rlast    (npu_rlast),
    .s_axi_rvalid   (npu_rvalid),
    .s_axi_rready   (npu_rready),
    .m_axi_aclk     (axi_aclk),
    .m_axi_aresetn  (axi_aresetn),
    .m_axi_awid     (npu250_awid),
    .m_axi_awaddr   (npu250_awaddr),
    .m_axi_awlen    (npu250_awlen),
    .m_axi_awsize   (npu250_awsize),
    .m_axi_awburst  (npu250_awburst),
    .m_axi_awlock   (npu250_awlock),
    .m_axi_awcache  (npu250_awcache),
    .m_axi_awprot   (npu250_awprot),
    .m_axi_awregion (npu250_awregion),
    .m_axi_awqos    (npu250_awqos),
    .m_axi_awvalid  (npu250_awvalid),
    .m_axi_awready  (npu250_awready),
    .m_axi_wdata    (npu250_wdata),
    .m_axi_wstrb    (npu250_wstrb),
    .m_axi_wlast    (npu250_wlast),
    .m_axi_wvalid   (npu250_wvalid),
    .m_axi_wready   (npu250_wready),
    .m_axi_bid      (npu250_bid),
    .m_axi_bresp    (npu250_bresp),
    .m_axi_bvalid   (npu250_bvalid),
    .m_axi_bready   (npu250_bready),
    .m_axi_arid     (npu250_arid),
    .m_axi_araddr   (npu250_araddr),
    .m_axi_arlen    (npu250_arlen),
    .m_axi_arsize   (npu250_arsize),
    .m_axi_arburst  (npu250_arburst),
    .m_axi_arlock   (npu250_arlock),
    .m_axi_arcache  (npu250_arcache),
    .m_axi_arprot   (npu250_arprot),
    .m_axi_arregion (npu250_arregion),
    .m_axi_arqos    (npu250_arqos),
    .m_axi_arvalid  (npu250_arvalid),
    .m_axi_arready  (npu250_arready),
    .m_axi_rid      (npu250_rid),
    .m_axi_rdata    (npu250_rdata),
    .m_axi_rresp    (npu250_rresp),
    .m_axi_rlast    (npu250_rlast),
    .m_axi_rvalid   (npu250_rvalid),
    .m_axi_rready   (npu250_rready)
  );

  // ── NPU 128->512 upsize (S01 of ddr_top) ───────────────────────────────────
  wire [63 : 0]     npu512_awaddr;
  wire [7 : 0]      npu512_awlen;
  wire [2 : 0]      npu512_awsize;
  wire [1 : 0]      npu512_awburst;
  wire [0 : 0]      npu512_awlock;
  wire [3 : 0]      npu512_awcache;
  wire [2 : 0]      npu512_awprot;
  wire [3 : 0]      npu512_awqos;
  wire              npu512_awvalid;
  wire              npu512_awready;
  wire [511 : 0]    npu512_wdata;
  wire [63 : 0]     npu512_wstrb;
  wire              npu512_wlast;
  wire              npu512_wvalid;
  wire              npu512_wready;
  wire [1 : 0]      npu512_bresp;
  wire              npu512_bvalid;
  wire              npu512_bready;
  wire [63 : 0]     npu512_araddr;
  wire [7 : 0]      npu512_arlen;
  wire [2 : 0]      npu512_arsize;
  wire [1 : 0]      npu512_arburst;
  wire [0 : 0]      npu512_arlock;
  wire [3 : 0]      npu512_arcache;
  wire [2 : 0]      npu512_arprot;
  wire [3 : 0]      npu512_arqos;
  wire              npu512_arvalid;
  wire              npu512_arready;
  wire [511 : 0]    npu512_rdata;
  wire [1 : 0]      npu512_rresp;
  wire              npu512_rlast;
  wire              npu512_rvalid;
  wire              npu512_rready;

  npu_dw u_npu_dw (
    .s_axi_aclk     (axi_aclk),
    .s_axi_aresetn  (axi_aresetn),
    .s_axi_awid     (npu250_awid),
    .s_axi_awaddr   (npu250_awaddr),
    .s_axi_awlen    (npu250_awlen),
    .s_axi_awsize   (npu250_awsize),
    .s_axi_awburst  (npu250_awburst),
    .s_axi_awlock   (npu250_awlock),
    .s_axi_awcache  (npu250_awcache),
    .s_axi_awprot   (npu250_awprot),
    .s_axi_awregion (npu250_awregion),
    .s_axi_awqos    (npu250_awqos),
    .s_axi_awvalid  (npu250_awvalid),
    .s_axi_awready  (npu250_awready),
    .s_axi_wdata    (npu250_wdata),
    .s_axi_wstrb    (npu250_wstrb),
    .s_axi_wlast    (npu250_wlast),
    .s_axi_wvalid   (npu250_wvalid),
    .s_axi_wready   (npu250_wready),
    .s_axi_bid      (npu250_bid),
    .s_axi_bresp    (npu250_bresp),
    .s_axi_bvalid   (npu250_bvalid),
    .s_axi_bready   (npu250_bready),
    .s_axi_arid     (npu250_arid),
    .s_axi_araddr   (npu250_araddr),
    .s_axi_arlen    (npu250_arlen),
    .s_axi_arsize   (npu250_arsize),
    .s_axi_arburst  (npu250_arburst),
    .s_axi_arlock   (npu250_arlock),
    .s_axi_arcache  (npu250_arcache),
    .s_axi_arprot   (npu250_arprot),
    .s_axi_arregion (npu250_arregion),
    .s_axi_arqos    (npu250_arqos),
    .s_axi_arvalid  (npu250_arvalid),
    .s_axi_arready  (npu250_arready),
    .s_axi_rid      (npu250_rid),
    .s_axi_rdata    (npu250_rdata),
    .s_axi_rresp    (npu250_rresp),
    .s_axi_rlast    (npu250_rlast),
    .s_axi_rvalid   (npu250_rvalid),
    .s_axi_rready   (npu250_rready),
    .m_axi_awaddr   (npu512_awaddr),
    .m_axi_awlen    (npu512_awlen),
    .m_axi_awsize   (npu512_awsize),
    .m_axi_awburst  (npu512_awburst),
    .m_axi_awlock   (npu512_awlock),
    .m_axi_awcache  (npu512_awcache),
    .m_axi_awprot   (npu512_awprot),
    .m_axi_awregion (),
    .m_axi_awqos    (npu512_awqos),
    .m_axi_awvalid  (npu512_awvalid),
    .m_axi_awready  (npu512_awready),
    .m_axi_wdata    (npu512_wdata),
    .m_axi_wstrb    (npu512_wstrb),
    .m_axi_wlast    (npu512_wlast),
    .m_axi_wvalid   (npu512_wvalid),
    .m_axi_wready   (npu512_wready),
    .m_axi_bresp    (npu512_bresp),
    .m_axi_bvalid   (npu512_bvalid),
    .m_axi_bready   (npu512_bready),
    .m_axi_araddr   (npu512_araddr),
    .m_axi_arlen    (npu512_arlen),
    .m_axi_arsize   (npu512_arsize),
    .m_axi_arburst  (npu512_arburst),
    .m_axi_arlock   (npu512_arlock),
    .m_axi_arcache  (npu512_arcache),
    .m_axi_arprot   (npu512_arprot),
    .m_axi_arregion (),
    .m_axi_arqos    (npu512_arqos),
    .m_axi_arvalid  (npu512_arvalid),
    .m_axi_arready  (npu512_arready),
    .m_axi_rdata    (npu512_rdata),
    .m_axi_rresp    (npu512_rresp),
    .m_axi_rlast    (npu512_rlast),
    .m_axi_rvalid   (npu512_rvalid),
    .m_axi_rready   (npu512_rready)
  );

  // ── Bypass BAR: 512->32 @250, then 250->200, then AXI4->AXI4-Lite ──────────
  wire [63 : 0]     byp32_awaddr;
  wire [7 : 0]      byp32_awlen;
  wire [2 : 0]      byp32_awsize;
  wire [1 : 0]      byp32_awburst;
  wire [0 : 0]      byp32_awlock;
  wire [3 : 0]      byp32_awcache;
  wire [2 : 0]      byp32_awprot;
  wire [3 : 0]      byp32_awregion;
  wire [3 : 0]      byp32_awqos;
  wire              byp32_awvalid;
  wire              byp32_awready;
  wire [31 : 0]     byp32_wdata;
  wire [3 : 0]      byp32_wstrb;
  wire              byp32_wlast;
  wire              byp32_wvalid;
  wire              byp32_wready;
  wire [1 : 0]      byp32_bresp;
  wire              byp32_bvalid;
  wire              byp32_bready;
  wire [63 : 0]     byp32_araddr;
  wire [7 : 0]      byp32_arlen;
  wire [2 : 0]      byp32_arsize;
  wire [1 : 0]      byp32_arburst;
  wire [0 : 0]      byp32_arlock;
  wire [3 : 0]      byp32_arcache;
  wire [2 : 0]      byp32_arprot;
  wire [3 : 0]      byp32_arregion;
  wire [3 : 0]      byp32_arqos;
  wire              byp32_arvalid;
  wire              byp32_arready;
  wire [31 : 0]     byp32_rdata;
  wire [1 : 0]      byp32_rresp;
  wire              byp32_rlast;
  wire              byp32_rvalid;
  wire              byp32_rready;

  // after 250->200 clock crossing (32b AXI4 @ fabric)
  wire [63 : 0]     byp200_awaddr;
  wire [7 : 0]      byp200_awlen;
  wire [2 : 0]      byp200_awsize;
  wire [1 : 0]      byp200_awburst;
  wire [0 : 0]      byp200_awlock;
  wire [3 : 0]      byp200_awcache;
  wire [2 : 0]      byp200_awprot;
  wire [3 : 0]      byp200_awregion;
  wire [3 : 0]      byp200_awqos;
  wire              byp200_awvalid;
  wire              byp200_awready;
  wire [31 : 0]     byp200_wdata;
  wire [3 : 0]      byp200_wstrb;
  wire              byp200_wlast;
  wire              byp200_wvalid;
  wire              byp200_wready;
  wire [1 : 0]      byp200_bresp;
  wire              byp200_bvalid;
  wire              byp200_bready;
  wire [63 : 0]     byp200_araddr;
  wire [7 : 0]      byp200_arlen;
  wire [2 : 0]      byp200_arsize;
  wire [1 : 0]      byp200_arburst;
  wire [0 : 0]      byp200_arlock;
  wire [3 : 0]      byp200_arcache;
  wire [2 : 0]      byp200_arprot;
  wire [3 : 0]      byp200_arregion;
  wire [3 : 0]      byp200_arqos;
  wire              byp200_arvalid;
  wire              byp200_arready;
  wire [31 : 0]     byp200_rdata;
  wire [1 : 0]      byp200_rresp;
  wire              byp200_rlast;
  wire              byp200_rvalid;
  wire              byp200_rready;

  // AXI4-Lite to ctrl_lite (@ fabric)
  wire [63 : 0]     ctrl_awaddr;
  wire [2 : 0]      ctrl_awprot;
  wire              ctrl_awvalid;
  wire              ctrl_awready;
  wire [31 : 0]     ctrl_wdata;
  wire [3 : 0]      ctrl_wstrb;
  wire              ctrl_wvalid;
  wire              ctrl_wready;
  wire [1 : 0]      ctrl_bresp;
  wire              ctrl_bvalid;
  wire              ctrl_bready;
  wire [63 : 0]     ctrl_araddr;
  wire [2 : 0]      ctrl_arprot;
  wire              ctrl_arvalid;
  wire              ctrl_arready;
  wire [31 : 0]     ctrl_rdata;
  wire [1 : 0]      ctrl_rresp;
  wire              ctrl_rvalid;
  wire              ctrl_rready;

  byp_dw u_byp_dw (
    .s_axi_aclk     (axi_aclk),
    .s_axi_aresetn  (axi_aresetn),
    .s_axi_awid     (m_axib_awid),
    .s_axi_awaddr   (m_axib_awaddr),
    .s_axi_awlen    (m_axib_awlen),
    .s_axi_awsize   (m_axib_awsize),
    .s_axi_awburst  (m_axib_awburst),
    .s_axi_awlock   (m_axib_awlock),
    .s_axi_awcache  (m_axib_awcache),
    .s_axi_awprot   (m_axib_awprot),
    .s_axi_awregion (4'b0),
    .s_axi_awqos    (4'b0),
    .s_axi_awvalid  (m_axib_awvalid),
    .s_axi_awready  (m_axib_awready),
    .s_axi_wdata    (m_axib_wdata),
    .s_axi_wstrb    (m_axib_wstrb),
    .s_axi_wlast    (m_axib_wlast),
    .s_axi_wvalid   (m_axib_wvalid),
    .s_axi_wready   (m_axib_wready),
    .s_axi_bid      (m_axib_bid),
    .s_axi_bresp    (m_axib_bresp),
    .s_axi_bvalid   (m_axib_bvalid),
    .s_axi_bready   (m_axib_bready),
    .s_axi_arid     (m_axib_arid),
    .s_axi_araddr   (m_axib_araddr),
    .s_axi_arlen    (m_axib_arlen),
    .s_axi_arsize   (m_axib_arsize),
    .s_axi_arburst  (m_axib_arburst),
    .s_axi_arlock   (m_axib_arlock),
    .s_axi_arcache  (m_axib_arcache),
    .s_axi_arprot   (m_axib_arprot),
    .s_axi_arregion (4'b0),
    .s_axi_arqos    (4'b0),
    .s_axi_arvalid  (m_axib_arvalid),
    .s_axi_arready  (m_axib_arready),
    .s_axi_rid      (m_axib_rid),
    .s_axi_rdata    (m_axib_rdata),
    .s_axi_rresp    (m_axib_rresp),
    .s_axi_rlast    (m_axib_rlast),
    .s_axi_rvalid   (m_axib_rvalid),
    .s_axi_rready   (m_axib_rready),
    .m_axi_awaddr   (byp32_awaddr),
    .m_axi_awlen    (byp32_awlen),
    .m_axi_awsize   (byp32_awsize),
    .m_axi_awburst  (byp32_awburst),
    .m_axi_awlock   (byp32_awlock),
    .m_axi_awcache  (byp32_awcache),
    .m_axi_awprot   (byp32_awprot),
    .m_axi_awregion (byp32_awregion),
    .m_axi_awqos    (byp32_awqos),
    .m_axi_awvalid  (byp32_awvalid),
    .m_axi_awready  (byp32_awready),
    .m_axi_wdata    (byp32_wdata),
    .m_axi_wstrb    (byp32_wstrb),
    .m_axi_wlast    (byp32_wlast),
    .m_axi_wvalid   (byp32_wvalid),
    .m_axi_wready   (byp32_wready),
    .m_axi_bresp    (byp32_bresp),
    .m_axi_bvalid   (byp32_bvalid),
    .m_axi_bready   (byp32_bready),
    .m_axi_araddr   (byp32_araddr),
    .m_axi_arlen    (byp32_arlen),
    .m_axi_arsize   (byp32_arsize),
    .m_axi_arburst  (byp32_arburst),
    .m_axi_arlock   (byp32_arlock),
    .m_axi_arcache  (byp32_arcache),
    .m_axi_arprot   (byp32_arprot),
    .m_axi_arregion (byp32_arregion),
    .m_axi_arqos    (byp32_arqos),
    .m_axi_arvalid  (byp32_arvalid),
    .m_axi_arready  (byp32_arready),
    .m_axi_rdata    (byp32_rdata),
    .m_axi_rresp    (byp32_rresp),
    .m_axi_rlast    (byp32_rlast),
    .m_axi_rvalid   (byp32_rvalid),
    .m_axi_rready   (byp32_rready)
  );

  byp_cc u_byp_cc (
    .s_axi_aclk     (axi_aclk),
    .s_axi_aresetn  (axi_aresetn),
    .s_axi_awaddr   (byp32_awaddr),
    .s_axi_awlen    (byp32_awlen),
    .s_axi_awsize   (byp32_awsize),
    .s_axi_awburst  (byp32_awburst),
    .s_axi_awlock   (byp32_awlock),
    .s_axi_awcache  (byp32_awcache),
    .s_axi_awprot   (byp32_awprot),
    .s_axi_awregion (byp32_awregion),
    .s_axi_awqos    (byp32_awqos),
    .s_axi_awvalid  (byp32_awvalid),
    .s_axi_awready  (byp32_awready),
    .s_axi_wdata    (byp32_wdata),
    .s_axi_wstrb    (byp32_wstrb),
    .s_axi_wlast    (byp32_wlast),
    .s_axi_wvalid   (byp32_wvalid),
    .s_axi_wready   (byp32_wready),
    .s_axi_bresp    (byp32_bresp),
    .s_axi_bvalid   (byp32_bvalid),
    .s_axi_bready   (byp32_bready),
    .s_axi_araddr   (byp32_araddr),
    .s_axi_arlen    (byp32_arlen),
    .s_axi_arsize   (byp32_arsize),
    .s_axi_arburst  (byp32_arburst),
    .s_axi_arlock   (byp32_arlock),
    .s_axi_arcache  (byp32_arcache),
    .s_axi_arprot   (byp32_arprot),
    .s_axi_arregion (byp32_arregion),
    .s_axi_arqos    (byp32_arqos),
    .s_axi_arvalid  (byp32_arvalid),
    .s_axi_arready  (byp32_arready),
    .s_axi_rdata    (byp32_rdata),
    .s_axi_rresp    (byp32_rresp),
    .s_axi_rlast    (byp32_rlast),
    .s_axi_rvalid   (byp32_rvalid),
    .s_axi_rready   (byp32_rready),
    .m_axi_aclk     (clk_fabric),
    .m_axi_aresetn  (aresetn_fabric),
    .m_axi_awaddr   (byp200_awaddr),
    .m_axi_awlen    (byp200_awlen),
    .m_axi_awsize   (byp200_awsize),
    .m_axi_awburst  (byp200_awburst),
    .m_axi_awlock   (byp200_awlock),
    .m_axi_awcache  (byp200_awcache),
    .m_axi_awprot   (byp200_awprot),
    .m_axi_awregion (byp200_awregion),
    .m_axi_awqos    (byp200_awqos),
    .m_axi_awvalid  (byp200_awvalid),
    .m_axi_awready  (byp200_awready),
    .m_axi_wdata    (byp200_wdata),
    .m_axi_wstrb    (byp200_wstrb),
    .m_axi_wlast    (byp200_wlast),
    .m_axi_wvalid   (byp200_wvalid),
    .m_axi_wready   (byp200_wready),
    .m_axi_bresp    (byp200_bresp),
    .m_axi_bvalid   (byp200_bvalid),
    .m_axi_bready   (byp200_bready),
    .m_axi_araddr   (byp200_araddr),
    .m_axi_arlen    (byp200_arlen),
    .m_axi_arsize   (byp200_arsize),
    .m_axi_arburst  (byp200_arburst),
    .m_axi_arlock   (byp200_arlock),
    .m_axi_arcache  (byp200_arcache),
    .m_axi_arprot   (byp200_arprot),
    .m_axi_arregion (byp200_arregion),
    .m_axi_arqos    (byp200_arqos),
    .m_axi_arvalid  (byp200_arvalid),
    .m_axi_arready  (byp200_arready),
    .m_axi_rdata    (byp200_rdata),
    .m_axi_rresp    (byp200_rresp),
    .m_axi_rlast    (byp200_rlast),
    .m_axi_rvalid   (byp200_rvalid),
    .m_axi_rready   (byp200_rready)
  );

  byp_pc u_byp_pc (
    .aclk           (clk_fabric),
    .aresetn        (aresetn_fabric),
    .s_axi_awaddr   (byp200_awaddr),
    .s_axi_awlen    (byp200_awlen),
    .s_axi_awsize   (byp200_awsize),
    .s_axi_awburst  (byp200_awburst),
    .s_axi_awlock   (byp200_awlock),
    .s_axi_awcache  (byp200_awcache),
    .s_axi_awprot   (byp200_awprot),
    .s_axi_awregion (byp200_awregion),
    .s_axi_awqos    (byp200_awqos),
    .s_axi_awvalid  (byp200_awvalid),
    .s_axi_awready  (byp200_awready),
    .s_axi_wdata    (byp200_wdata),
    .s_axi_wstrb    (byp200_wstrb),
    .s_axi_wlast    (byp200_wlast),
    .s_axi_wvalid   (byp200_wvalid),
    .s_axi_wready   (byp200_wready),
    .s_axi_bresp    (byp200_bresp),
    .s_axi_bvalid   (byp200_bvalid),
    .s_axi_bready   (byp200_bready),
    .s_axi_araddr   (byp200_araddr),
    .s_axi_arlen    (byp200_arlen),
    .s_axi_arsize   (byp200_arsize),
    .s_axi_arburst  (byp200_arburst),
    .s_axi_arlock   (byp200_arlock),
    .s_axi_arcache  (byp200_arcache),
    .s_axi_arprot   (byp200_arprot),
    .s_axi_arregion (byp200_arregion),
    .s_axi_arqos    (byp200_arqos),
    .s_axi_arvalid  (byp200_arvalid),
    .s_axi_arready  (byp200_arready),
    .s_axi_rdata    (byp200_rdata),
    .s_axi_rresp    (byp200_rresp),
    .s_axi_rlast    (byp200_rlast),
    .s_axi_rvalid   (byp200_rvalid),
    .s_axi_rready   (byp200_rready),
    .m_axi_awaddr   (ctrl_awaddr),
    .m_axi_awprot   (ctrl_awprot),
    .m_axi_awvalid  (ctrl_awvalid),
    .m_axi_awready  (ctrl_awready),
    .m_axi_wdata    (ctrl_wdata),
    .m_axi_wstrb    (ctrl_wstrb),
    .m_axi_wvalid   (ctrl_wvalid),
    .m_axi_wready   (ctrl_wready),
    .m_axi_bresp    (ctrl_bresp),
    .m_axi_bvalid   (ctrl_bvalid),
    .m_axi_bready   (ctrl_bready),
    .m_axi_araddr   (ctrl_araddr),
    .m_axi_arprot   (ctrl_arprot),
    .m_axi_arvalid  (ctrl_arvalid),
    .m_axi_arready  (ctrl_arready),
    .m_axi_rdata    (ctrl_rdata),
    .m_axi_rresp    (ctrl_rresp),
    .m_axi_rvalid   (ctrl_rvalid),
    .m_axi_rready   (ctrl_rready)
  );

  // ── NPU program engine (top.sv) — 200 MHz fabric domain ────────────────────
  npu_engine_subsys u_npu (
    .aclk                    (clk_fabric),
    .aresetn                 (aresetn_fabric),
    .s_axil_awaddr           (ctrl_awaddr[11:0]),
    .s_axil_awprot           (ctrl_awprot),
    .s_axil_awvalid          (ctrl_awvalid),
    .s_axil_awready          (ctrl_awready),
    .s_axil_wdata            (ctrl_wdata),
    .s_axil_wstrb            (ctrl_wstrb),
    .s_axil_wvalid           (ctrl_wvalid),
    .s_axil_wready           (ctrl_wready),
    .s_axil_bresp            (ctrl_bresp),
    .s_axil_bvalid           (ctrl_bvalid),
    .s_axil_bready           (ctrl_bready),
    .s_axil_araddr           (ctrl_araddr[11:0]),
    .s_axil_arprot           (ctrl_arprot),
    .s_axil_arvalid          (ctrl_arvalid),
    .s_axil_arready          (ctrl_arready),
    .s_axil_rdata            (ctrl_rdata),
    .s_axil_rresp            (ctrl_rresp),
    .s_axil_rvalid           (ctrl_rvalid),
    .s_axil_rready           (ctrl_rready),
    .m_axi_awid              (npu_awid),
    .m_axi_awaddr            (npu_awaddr),
    .m_axi_awlen             (npu_awlen),
    .m_axi_awsize            (npu_awsize),
    .m_axi_awburst           (npu_awburst),
    .m_axi_awlock            (npu_awlock),
    .m_axi_awcache           (npu_awcache),
    .m_axi_awprot            (npu_awprot),
    .m_axi_awqos             (npu_awqos),
    .m_axi_awvalid           (npu_awvalid),
    .m_axi_awready           (npu_awready),
    .m_axi_wdata             (npu_wdata),
    .m_axi_wstrb             (npu_wstrb),
    .m_axi_wlast             (npu_wlast),
    .m_axi_wvalid            (npu_wvalid),
    .m_axi_wready            (npu_wready),
    .m_axi_bid               (npu_bid),
    .m_axi_bresp             (npu_bresp),
    .m_axi_bvalid            (npu_bvalid),
    .m_axi_bready            (npu_bready),
    .m_axi_arid              (npu_arid),
    .m_axi_araddr            (npu_araddr),
    .m_axi_arlen             (npu_arlen),
    .m_axi_arsize            (npu_arsize),
    .m_axi_arburst           (npu_arburst),
    .m_axi_arlock            (npu_arlock),
    .m_axi_arcache           (npu_arcache),
    .m_axi_arprot            (npu_arprot),
    .m_axi_arqos             (npu_arqos),
    .m_axi_arvalid           (npu_arvalid),
    .m_axi_arready           (npu_arready),
    .m_axi_rdata             (npu_rdata),
    .m_axi_rresp             (npu_rresp),
    .m_axi_rlast             (npu_rlast),
    .m_axi_rvalid            (npu_rvalid),
    .m_axi_rready            (npu_rready),
    .c0_init_calib_complete  (1'b1),
    .c1_init_calib_complete  (1'b1)
  );

  // ── DDR4 subsystem: S00 = XDMA, S01 = NPU (512b) ──────────────────────────
  ddr_top ddr_top (
    .axi_clk            (axi_aclk                 ),
    .axi_rst            (~axi_aresetn             ),
    .m_axi_awready      (m_axi_awready            ),
    .m_axi_awid         (m_axi_awid               ),
    .m_axi_awaddr       (m_axi_awaddr             ),
    .m_axi_awuser       (),
    .m_axi_awlen        (m_axi_awlen              ),
    .m_axi_awsize       (m_axi_awsize             ),
    .m_axi_awburst      (m_axi_awburst            ),
    .m_axi_awprot       (m_axi_awprot             ),
    .m_axi_awvalid      (m_axi_awvalid            ),
    .m_axi_awlock       (m_axi_awlock             ),
    .m_axi_awcache      (m_axi_awcache            ),
    .m_axi_wready       (m_axi_wready             ),
    .m_axi_wdata        (m_axi_wdata              ),
    .m_axi_wuser        (),
    .m_axi_wstrb        (m_axi_wstrb              ),
    .m_axi_wlast        (m_axi_wlast              ),
    .m_axi_wvalid       (m_axi_wvalid             ),
    .m_axi_bid          (m_axi_bid                ),
    .m_axi_bresp        (m_axi_bresp              ),
    .m_axi_bvalid       (m_axi_bvalid             ),
    .m_axi_bready       (m_axi_bready             ),
    .m_axi_arready      (m_axi_arready            ),
    .m_axi_arid         (m_axi_arid               ),
    .m_axi_araddr       (m_axi_araddr             ),
    .m_axi_aruser       (),
    .m_axi_arlen        (m_axi_arlen              ),
    .m_axi_arsize       (m_axi_arsize             ),
    .m_axi_arburst      (m_axi_arburst            ),
    .m_axi_arprot       (m_axi_arprot             ),
    .m_axi_arvalid      (m_axi_arvalid            ),
    .m_axi_arlock       (m_axi_arlock             ),
    .m_axi_arcache      (m_axi_arcache            ),
    .m_axi_rid          (m_axi_rid                ),
    .m_axi_rdata        (m_axi_rdata              ),
    .m_axi_rresp        (m_axi_rresp              ),
    .m_axi_rlast        (m_axi_rlast              ),
    .m_axi_rvalid       (m_axi_rvalid             ),
    .m_axi_rready       (m_axi_rready             ),

    .m_axi_user_awready (npu512_awready           ),
    .m_axi_user_awid    (4'b0                     ),
    .m_axi_user_awaddr  (npu512_awaddr            ),
    .m_axi_user_awuser  (32'b0                    ),
    .m_axi_user_awlen   (npu512_awlen             ),
    .m_axi_user_awsize  (npu512_awsize            ),
    .m_axi_user_awburst (npu512_awburst           ),
    .m_axi_user_awprot  (npu512_awprot            ),
    .m_axi_user_awvalid (npu512_awvalid           ),
    .m_axi_user_awlock  (npu512_awlock            ),
    .m_axi_user_awcache (npu512_awcache           ),
    .m_axi_user_wready  (npu512_wready            ),
    .m_axi_user_wdata   (npu512_wdata             ),
    .m_axi_user_wuser   (64'b0                    ),
    .m_axi_user_wstrb   (npu512_wstrb             ),
    .m_axi_user_wlast   (npu512_wlast             ),
    .m_axi_user_wvalid  (npu512_wvalid            ),
    .m_axi_user_bid     (),
    .m_axi_user_bresp   (npu512_bresp             ),
    .m_axi_user_bvalid  (npu512_bvalid            ),
    .m_axi_user_bready  (npu512_bready            ),
    .m_axi_user_arready (npu512_arready           ),
    .m_axi_user_arid    (4'b0                     ),
    .m_axi_user_araddr  (npu512_araddr            ),
    .m_axi_user_aruser  (32'b0                    ),
    .m_axi_user_arlen   (npu512_arlen             ),
    .m_axi_user_arsize  (npu512_arsize            ),
    .m_axi_user_arburst (npu512_arburst           ),
    .m_axi_user_arprot  (npu512_arprot            ),
    .m_axi_user_arvalid (npu512_arvalid           ),
    .m_axi_user_arlock  (npu512_arlock            ),
    .m_axi_user_arcache (npu512_arcache           ),
    .m_axi_user_rid     (),
    .m_axi_user_rdata   (npu512_rdata             ),
    .m_axi_user_rresp   (npu512_rresp             ),
    .m_axi_user_rlast   (npu512_rlast             ),
    .m_axi_user_rvalid  (npu512_rvalid            ),
    .m_axi_user_rready  (npu512_rready            ),

    .c0_sys_clk_p       (c0_sys_clk_p               ),
    .c0_sys_clk_n       (c0_sys_clk_n               ),
    .c0_ddr4_act_n      (c0_ddr4_act_n              ),
    .c0_ddr4_adr        (c0_ddr4_adr                ),
    .c0_ddr4_ba         (c0_ddr4_ba                 ),
    .c0_ddr4_bg         (c0_ddr4_bg                 ),
    .c0_ddr4_cke        (c0_ddr4_cke                ),
    .c0_ddr4_odt        (c0_ddr4_odt                ),
    .c0_ddr4_cs_n       (c0_ddr4_cs_n               ),
    .c0_ddr4_ck_t       (c0_ddr4_ck_t               ),
    .c0_ddr4_ck_c       (c0_ddr4_ck_c               ),
    .c0_ddr4_reset_n    (c0_ddr4_reset_n            ),
    .c0_ddr4_dm_dbi_n   (c0_ddr4_dm_dbi_n           ),
    .c0_ddr4_dq         (c0_ddr4_dq                 ),
    .c0_ddr4_dqs_c      (c0_ddr4_dqs_c              ),
    .c0_ddr4_dqs_t      (c0_ddr4_dqs_t              ),

    .c1_sys_clk_p       (c1_sys_clk_p               ),
    .c1_sys_clk_n       (c1_sys_clk_n               ),
    .c1_ddr4_act_n      (c1_ddr4_act_n              ),
    .c1_ddr4_adr        (c1_ddr4_adr                ),
    .c1_ddr4_ba         (c1_ddr4_ba                 ),
    .c1_ddr4_bg         (c1_ddr4_bg                 ),
    .c1_ddr4_cke        (c1_ddr4_cke                ),
    .c1_ddr4_odt        (c1_ddr4_odt                ),
    .c1_ddr4_cs_n       (c1_ddr4_cs_n               ),
    .c1_ddr4_ck_t       (c1_ddr4_ck_t               ),
    .c1_ddr4_ck_c       (c1_ddr4_ck_c               ),
    .c1_ddr4_reset_n    (c1_ddr4_reset_n            ),
    .c1_ddr4_dm_dbi_n   (c1_ddr4_dm_dbi_n           ),
    .c1_ddr4_dq         (c1_ddr4_dq                 ),
    .c1_ddr4_dqs_c      (c1_ddr4_dqs_c              ),
    .c1_ddr4_dqs_t      (c1_ddr4_dqs_t              ),

    .c2_sys_clk_p       (c2_sys_clk_p               ),
    .c2_sys_clk_n       (c2_sys_clk_n               ),
    .c2_ddr4_act_n      (c2_ddr4_act_n              ),
    .c2_ddr4_adr        (c2_ddr4_adr                ),
    .c2_ddr4_ba         (c2_ddr4_ba                 ),
    .c2_ddr4_bg         (c2_ddr4_bg                 ),
    .c2_ddr4_cke        (c2_ddr4_cke                ),
    .c2_ddr4_odt        (c2_ddr4_odt                ),
    .c2_ddr4_cs_n       (c2_ddr4_cs_n               ),
    .c2_ddr4_ck_t       (c2_ddr4_ck_t               ),
    .c2_ddr4_ck_c       (c2_ddr4_ck_c               ),
    .c2_ddr4_reset_n    (c2_ddr4_reset_n            ),
    .c2_ddr4_dm_dbi_n   (c2_ddr4_dm_dbi_n           ),
    .c2_ddr4_dq         (c2_ddr4_dq                 ),
    .c2_ddr4_dqs_c      (c2_ddr4_dqs_c              ),
    .c2_ddr4_dqs_t      (c2_ddr4_dqs_t              ),

    .c3_sys_clk_p       (c3_sys_clk_p               ),
    .c3_sys_clk_n       (c3_sys_clk_n               ),
    .c3_ddr4_act_n      (c3_ddr4_act_n              ),
    .c3_ddr4_adr        (c3_ddr4_adr                ),
    .c3_ddr4_ba         (c3_ddr4_ba                 ),
    .c3_ddr4_bg         (c3_ddr4_bg                 ),
    .c3_ddr4_cke        (c3_ddr4_cke                ),
    .c3_ddr4_odt        (c3_ddr4_odt                ),
    .c3_ddr4_cs_n       (c3_ddr4_cs_n               ),
    .c3_ddr4_ck_t       (c3_ddr4_ck_t               ),
    .c3_ddr4_ck_c       (c3_ddr4_ck_c               ),
    .c3_ddr4_reset_n    (c3_ddr4_reset_n            ),
    .c3_ddr4_dm_dbi_n   (c3_ddr4_dm_dbi_n           ),
    .c3_ddr4_dq         (c3_ddr4_dq                 ),
    .c3_ddr4_dqs_c      (c3_ddr4_dqs_c              ),
    .c3_ddr4_dqs_t      (c3_ddr4_dqs_t              )
  );

endmodule
