// npu_engine_subsys.v — NPU program-engine subsystem (replaces npu_subsys.v).
//
// Instantiates the Chisel NpuProgramEngineFrontend (top.sv) plus an AXI4-Lite
// adapter exposing the 6-register ctrl map:
//   0x00 CTRL (start/done/busy) · 0x04 FRAMES · 0x08 STATUS ·
//   0x0C ERR_INFO · 0x10 FETCH_STATS · 0x14 PROG_LEN
//
// Same external interface as the legacy npu_subsys.v (s_axil + m_axi + MIG
// calibration), so the Vivado BD cell can be swapped in place.
//
// All logic runs on fabric_aclk (200 MHz).
`timescale 1ns/1ps

module npu_engine_subsys #(
    parameter AXI_ADDR_WIDTH  = 64,
    parameter AXI_DATA_WIDTH  = 128,
    parameter AXI_ID_WIDTH    = 4,
    parameter AXIL_ADDR_WIDTH = 12,
    parameter AXIL_DATA_WIDTH = 32
) (
    input  wire                        aclk,
    input  wire                        aresetn,

    // ── AXI4-Lite slave (from XDMA M_AXI_BYPASS) ────────────────────────────
    input  wire [AXIL_ADDR_WIDTH-1:0]  s_axil_awaddr,
    input  wire [2:0]                  s_axil_awprot,
    input  wire                        s_axil_awvalid,
    output wire                        s_axil_awready,
    input  wire [AXIL_DATA_WIDTH-1:0]  s_axil_wdata,
    input  wire [3:0]                  s_axil_wstrb,
    input  wire                        s_axil_wvalid,
    output wire                        s_axil_wready,
    output wire [1:0]                  s_axil_bresp,
    output wire                        s_axil_bvalid,
    input  wire                        s_axil_bready,
    input  wire [AXIL_ADDR_WIDTH-1:0]  s_axil_araddr,
    input  wire [2:0]                  s_axil_arprot,
    input  wire                        s_axil_arvalid,
    output wire                        s_axil_arready,
    output wire [AXIL_DATA_WIDTH-1:0]  s_axil_rdata,
    output wire [1:0]                  s_axil_rresp,
    output wire                        s_axil_rvalid,
    input  wire                        s_axil_rready,

    // ── AXI4 master (to axi_clkconv_npu → MIG C1) ────────────────────────────
    output wire [AXI_ID_WIDTH-1:0]     m_axi_awid,
    output wire [AXI_ADDR_WIDTH-1:0]   m_axi_awaddr,
    output wire [7:0]                  m_axi_awlen,
    output wire [2:0]                  m_axi_awsize,
    output wire [1:0]                  m_axi_awburst,
    output wire                        m_axi_awlock,
    output wire [3:0]                  m_axi_awcache,
    output wire [2:0]                  m_axi_awprot,
    output wire [3:0]                  m_axi_awqos,
    output wire                        m_axi_awvalid,
    input  wire                        m_axi_awready,
    output wire [AXI_DATA_WIDTH-1:0]   m_axi_wdata,
    output wire [AXI_DATA_WIDTH/8-1:0] m_axi_wstrb,
    output wire                        m_axi_wlast,
    output wire                        m_axi_wvalid,
    input  wire                        m_axi_wready,
    input  wire [AXI_ID_WIDTH-1:0]     m_axi_bid,
    input  wire [1:0]                  m_axi_bresp,
    input  wire                        m_axi_bvalid,
    output wire                        m_axi_bready,
    output wire [AXI_ID_WIDTH-1:0]     m_axi_arid,
    output wire [AXI_ADDR_WIDTH-1:0]   m_axi_araddr,
    output wire [7:0]                  m_axi_arlen,
    output wire [2:0]                  m_axi_arsize,
    output wire [1:0]                  m_axi_arburst,
    output wire                        m_axi_arlock,
    output wire [3:0]                  m_axi_arcache,
    output wire [2:0]                  m_axi_arprot,
    output wire [3:0]                  m_axi_arqos,
    output wire                        m_axi_arvalid,
    input  wire                        m_axi_arready,
    input  wire [AXI_DATA_WIDTH-1:0]   m_axi_rdata,
    input  wire [1:0]                  m_axi_rresp,
    input  wire                        m_axi_rlast,
    input  wire                        m_axi_rvalid,
    output wire                        m_axi_rready,

    input  wire                        c0_init_calib_complete,
    input  wire                        c1_init_calib_complete
);

    wire [4:0]  ctrl_addr;
    wire        ctrl_we;
    wire [31:0] ctrl_wdata;
    wire [31:0] ctrl_rdata;

    // ── AXI4-Lite adapter (6-register map) ───────────────────────────────────
    npu_engine_ctrl_lite #(
        .ADDR_WIDTH(AXIL_ADDR_WIDTH),
        .DATA_WIDTH(AXIL_DATA_WIDTH)
    ) u_ctrl_lite (
        .axi_aclk      (aclk),
        .axi_aresetn   (aresetn),
        .s_axil_awaddr (s_axil_awaddr),
        .s_axil_awprot (s_axil_awprot),
        .s_axil_awvalid(s_axil_awvalid),
        .s_axil_awready(s_axil_awready),
        .s_axil_wdata  (s_axil_wdata),
        .s_axil_wstrb  (s_axil_wstrb),
        .s_axil_wvalid (s_axil_wvalid),
        .s_axil_wready (s_axil_wready),
        .s_axil_bresp  (s_axil_bresp),
        .s_axil_bvalid (s_axil_bvalid),
        .s_axil_bready (s_axil_bready),
        .s_axil_araddr (s_axil_araddr),
        .s_axil_arprot (s_axil_arprot),
        .s_axil_arvalid(s_axil_arvalid),
        .s_axil_arready(s_axil_arready),
        .s_axil_rdata  (s_axil_rdata),
        .s_axil_rresp  (s_axil_rresp),
        .s_axil_rvalid (s_axil_rvalid),
        .s_axil_rready (s_axil_rready),
        .ctrl_addr (ctrl_addr),
        .ctrl_we   (ctrl_we),
        .ctrl_wdata(ctrl_wdata),
        .ctrl_rdata(ctrl_rdata)
    );

    // ── Chisel program engine (top.sv) ───────────────────────────────────────
    wire [31:0] e_awaddr, e_araddr;
    wire [7:0]  e_awlen,  e_arlen;
    wire [2:0]  e_awsize, e_arsize;
    wire [1:0]  e_awburst, e_arburst;
    wire        e_awvalid, e_arvalid, e_wvalid, e_wlast, e_bready, e_rready;
    wire [127:0] e_wdata;
    wire [15:0] e_wstrb;

    NpuProgramEngineFrontend u_engine (
        .clock           (aclk),
        .reset           (~aresetn),
        .io_ctrl_addr    (ctrl_addr),
        .io_ctrl_we      (ctrl_we),
        .io_ctrl_wdata   (ctrl_wdata),
        .io_ctrl_rdata   (ctrl_rdata),
        .io_m_axi_awaddr (e_awaddr),
        .io_m_axi_awlen  (e_awlen),
        .io_m_axi_awsize (e_awsize),
        .io_m_axi_awburst(e_awburst),
        .io_m_axi_awvalid(e_awvalid),
        .io_m_axi_awready(m_axi_awready),
        .io_m_axi_wdata  (e_wdata),
        .io_m_axi_wstrb  (e_wstrb),
        .io_m_axi_wlast  (e_wlast),
        .io_m_axi_wvalid (e_wvalid),
        .io_m_axi_wready (m_axi_wready),
        .io_m_axi_bvalid (m_axi_bvalid),
        .io_m_axi_bready (e_bready),
        .io_m_axi_araddr (e_araddr),
        .io_m_axi_arlen  (e_arlen),
        .io_m_axi_arsize (e_arsize),
        .io_m_axi_arburst(e_arburst),
        .io_m_axi_arvalid(e_arvalid),
        .io_m_axi_arready(m_axi_arready),
        .io_m_axi_rdata  (m_axi_rdata),
        .io_m_axi_rlast  (m_axi_rlast),
        .io_m_axi_rvalid (m_axi_rvalid),
        .io_m_axi_rready (e_rready)
    );

    // Engine AXI master (32-bit address) → subsystem outputs (64-bit)
    assign m_axi_awaddr  = {32'd0, e_awaddr};
    assign m_axi_awlen   = e_awlen;
    assign m_axi_awsize  = e_awsize;
    assign m_axi_awburst = e_awburst;
    assign m_axi_awvalid = e_awvalid;
    assign m_axi_wdata   = e_wdata;
    assign m_axi_wstrb   = e_wstrb;
    assign m_axi_wlast   = e_wlast;
    assign m_axi_wvalid  = e_wvalid;
    assign m_axi_bready  = e_bready;
    assign m_axi_araddr  = {32'd0, e_araddr};
    assign m_axi_arlen   = e_arlen;
    assign m_axi_arsize  = e_arsize;
    assign m_axi_arburst = e_arburst;
    assign m_axi_arvalid = e_arvalid;
    assign m_axi_rready  = e_rready;

    // Unused AXI sidebands (engine has no IDs/attributes)
    assign m_axi_awid    = {AXI_ID_WIDTH{1'b0}};
    assign m_axi_awlock  = 1'b0;
    assign m_axi_awcache = 4'b0000;
    assign m_axi_awprot  = 3'b000;
    assign m_axi_awqos   = 4'b0000;
    assign m_axi_arid    = {AXI_ID_WIDTH{1'b0}};
    assign m_axi_arlock  = 1'b0;
    assign m_axi_arcache = 4'b0000;
    assign m_axi_arprot  = 3'b000;
    assign m_axi_arqos   = 4'b0000;

endmodule
