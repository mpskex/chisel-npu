// npu_subsys.v — NPU subsystem: ctrl_lite + dma_master + MMALU (Chisel Top)
//
// This module is instantiated as a single BD module cell.  It exposes:
//   - s_axil_*   : AXI4-Lite slave (32-bit) connected to XDMA M_AXI_BYPASS
//                  via the BYPASS CDC chain.  Host writes start the NPU;
//                  reads return done/busy status.
//   - m_axi_*    : AXI4 master (128-bit) to MIG C1 via axi_clkconv_npu and
//                  axi_dwidth_npu.  npu_dma_master reads A/B/accum matrices
//                  and writes results back.
//   - c0/c1_init_calib_complete: MIG calibration status (info only)
//
// All logic runs on fabric_aclk (200 MHz).
// The AXI-Lite slave interface is also on fabric_aclk (CDC already done
// upstream in axi_clkconv_byp).
//
// Chisel-generated MMALU:  module MMALU, K=32, N=8 (top.sv)
// DMA master parameters:   AXI_DATA_WIDTH=128, K=32
`timescale 1ns/1ps

module npu_subsys #(
    parameter AXI_ADDR_WIDTH  = 64,
    parameter AXI_DATA_WIDTH  = 128,
    parameter AXI_ID_WIDTH    = 4,
    parameter AXIL_ADDR_WIDTH = 12,
    parameter AXIL_DATA_WIDTH = 32
) (
    // ── Clocks / resets ──────────────────────────────────────────────────────
    input  wire                        aclk,        // fabric_aclk, 200 MHz
    input  wire                        aresetn,     // fabric_200M peripheral_aresetn

    // ── AXI4-Lite slave (from XDMA M_AXI_BYPASS, already at 200 MHz) ────────
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

    // ── MIG calibration status (informational) ───────────────────────────────
    input  wire                        c0_init_calib_complete,
    input  wire                        c1_init_calib_complete
);

    // ── Internal control signals ──────────────────────────────────────────────
    wire ctrl_start, ctrl_done, ctrl_busy;

    // ── MMALU ↔ DMA master wiring (K=32, N=8) ────────────────────────────────
    wire [7:0]  io_in_a_0,  io_in_a_1,  io_in_a_2,  io_in_a_3,
                io_in_a_4,  io_in_a_5,  io_in_a_6,  io_in_a_7,
                io_in_a_8,  io_in_a_9,  io_in_a_10, io_in_a_11,
                io_in_a_12, io_in_a_13, io_in_a_14, io_in_a_15,
                io_in_a_16, io_in_a_17, io_in_a_18, io_in_a_19,
                io_in_a_20, io_in_a_21, io_in_a_22, io_in_a_23,
                io_in_a_24, io_in_a_25, io_in_a_26, io_in_a_27,
                io_in_a_28, io_in_a_29, io_in_a_30, io_in_a_31;

    wire [7:0]  io_in_b_0,  io_in_b_1,  io_in_b_2,  io_in_b_3,
                io_in_b_4,  io_in_b_5,  io_in_b_6,  io_in_b_7,
                io_in_b_8,  io_in_b_9,  io_in_b_10, io_in_b_11,
                io_in_b_12, io_in_b_13, io_in_b_14, io_in_b_15,
                io_in_b_16, io_in_b_17, io_in_b_18, io_in_b_19,
                io_in_b_20, io_in_b_21, io_in_b_22, io_in_b_23,
                io_in_b_24, io_in_b_25, io_in_b_26, io_in_b_27,
                io_in_b_28, io_in_b_29, io_in_b_30, io_in_b_31;

    wire [31:0] io_in_accum_0,  io_in_accum_1,  io_in_accum_2,  io_in_accum_3,
                io_in_accum_4,  io_in_accum_5,  io_in_accum_6,  io_in_accum_7,
                io_in_accum_8,  io_in_accum_9,  io_in_accum_10, io_in_accum_11,
                io_in_accum_12, io_in_accum_13, io_in_accum_14, io_in_accum_15,
                io_in_accum_16, io_in_accum_17, io_in_accum_18, io_in_accum_19,
                io_in_accum_20, io_in_accum_21, io_in_accum_22, io_in_accum_23,
                io_in_accum_24, io_in_accum_25, io_in_accum_26, io_in_accum_27,
                io_in_accum_28, io_in_accum_29, io_in_accum_30, io_in_accum_31;

    wire        io_ctrl_keep, io_ctrl_use_accum, io_ctrl_busy;

    wire [31:0] io_out_0,  io_out_1,  io_out_2,  io_out_3,
                io_out_4,  io_out_5,  io_out_6,  io_out_7,
                io_out_8,  io_out_9,  io_out_10, io_out_11,
                io_out_12, io_out_13, io_out_14, io_out_15,
                io_out_16, io_out_17, io_out_18, io_out_19,
                io_out_20, io_out_21, io_out_22, io_out_23,
                io_out_24, io_out_25, io_out_26, io_out_27,
                io_out_28, io_out_29, io_out_30, io_out_31;

    wire        io_clct;

    // ── npu_ctrl_lite instance ────────────────────────────────────────────────
    npu_ctrl_lite #(
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
        .start(ctrl_start),
        .done (ctrl_done),
        .busy (ctrl_busy)
    );

    // ── npu_dma_master instance ───────────────────────────────────────────────
    npu_dma_master #(
        .AXI_DATA_WIDTH(AXI_DATA_WIDTH),
        .AXI_ADDR_WIDTH(AXI_ADDR_WIDTH),
        .AXI_ID_WIDTH  (AXI_ID_WIDTH)
    ) u_dma (
        .aclk   (aclk),
        .aresetn(aresetn),
        .start  (ctrl_start),
        .busy   (ctrl_busy),
        .done   (ctrl_done),
        // AXI4 master
        .m_axi_awid   (m_axi_awid),    .m_axi_awaddr (m_axi_awaddr),
        .m_axi_awlen  (m_axi_awlen),   .m_axi_awsize (m_axi_awsize),
        .m_axi_awburst(m_axi_awburst), .m_axi_awlock (m_axi_awlock),
        .m_axi_awcache(m_axi_awcache), .m_axi_awprot (m_axi_awprot),
        .m_axi_awqos  (m_axi_awqos),   .m_axi_awvalid(m_axi_awvalid),
        .m_axi_awready(m_axi_awready),
        .m_axi_wdata  (m_axi_wdata),   .m_axi_wstrb  (m_axi_wstrb),
        .m_axi_wlast  (m_axi_wlast),   .m_axi_wvalid (m_axi_wvalid),
        .m_axi_wready (m_axi_wready),
        .m_axi_bid    (m_axi_bid),     .m_axi_bresp  (m_axi_bresp),
        .m_axi_bvalid (m_axi_bvalid),  .m_axi_bready (m_axi_bready),
        .m_axi_arid   (m_axi_arid),    .m_axi_araddr (m_axi_araddr),
        .m_axi_arlen  (m_axi_arlen),   .m_axi_arsize (m_axi_arsize),
        .m_axi_arburst(m_axi_arburst), .m_axi_arlock (m_axi_arlock),
        .m_axi_arcache(m_axi_arcache), .m_axi_arprot (m_axi_arprot),
        .m_axi_arqos  (m_axi_arqos),   .m_axi_arvalid(m_axi_arvalid),
        .m_axi_arready(m_axi_arready),
        .m_axi_rdata  (m_axi_rdata),   .m_axi_rresp  (m_axi_rresp),
        .m_axi_rlast  (m_axi_rlast),   .m_axi_rvalid (m_axi_rvalid),
        .m_axi_rready (m_axi_rready),
        // MMALU output ports (driven by dma_master)
        .io_in_a_0 (io_in_a_0),  .io_in_a_1 (io_in_a_1),
        .io_in_a_2 (io_in_a_2),  .io_in_a_3 (io_in_a_3),
        .io_in_a_4 (io_in_a_4),  .io_in_a_5 (io_in_a_5),
        .io_in_a_6 (io_in_a_6),  .io_in_a_7 (io_in_a_7),
        .io_in_a_8 (io_in_a_8),  .io_in_a_9 (io_in_a_9),
        .io_in_a_10(io_in_a_10), .io_in_a_11(io_in_a_11),
        .io_in_a_12(io_in_a_12), .io_in_a_13(io_in_a_13),
        .io_in_a_14(io_in_a_14), .io_in_a_15(io_in_a_15),
        .io_in_a_16(io_in_a_16), .io_in_a_17(io_in_a_17),
        .io_in_a_18(io_in_a_18), .io_in_a_19(io_in_a_19),
        .io_in_a_20(io_in_a_20), .io_in_a_21(io_in_a_21),
        .io_in_a_22(io_in_a_22), .io_in_a_23(io_in_a_23),
        .io_in_a_24(io_in_a_24), .io_in_a_25(io_in_a_25),
        .io_in_a_26(io_in_a_26), .io_in_a_27(io_in_a_27),
        .io_in_a_28(io_in_a_28), .io_in_a_29(io_in_a_29),
        .io_in_a_30(io_in_a_30), .io_in_a_31(io_in_a_31),
        .io_in_b_0 (io_in_b_0),  .io_in_b_1 (io_in_b_1),
        .io_in_b_2 (io_in_b_2),  .io_in_b_3 (io_in_b_3),
        .io_in_b_4 (io_in_b_4),  .io_in_b_5 (io_in_b_5),
        .io_in_b_6 (io_in_b_6),  .io_in_b_7 (io_in_b_7),
        .io_in_b_8 (io_in_b_8),  .io_in_b_9 (io_in_b_9),
        .io_in_b_10(io_in_b_10), .io_in_b_11(io_in_b_11),
        .io_in_b_12(io_in_b_12), .io_in_b_13(io_in_b_13),
        .io_in_b_14(io_in_b_14), .io_in_b_15(io_in_b_15),
        .io_in_b_16(io_in_b_16), .io_in_b_17(io_in_b_17),
        .io_in_b_18(io_in_b_18), .io_in_b_19(io_in_b_19),
        .io_in_b_20(io_in_b_20), .io_in_b_21(io_in_b_21),
        .io_in_b_22(io_in_b_22), .io_in_b_23(io_in_b_23),
        .io_in_b_24(io_in_b_24), .io_in_b_25(io_in_b_25),
        .io_in_b_26(io_in_b_26), .io_in_b_27(io_in_b_27),
        .io_in_b_28(io_in_b_28), .io_in_b_29(io_in_b_29),
        .io_in_b_30(io_in_b_30), .io_in_b_31(io_in_b_31),
        .io_in_accum_0 (io_in_accum_0),  .io_in_accum_1 (io_in_accum_1),
        .io_in_accum_2 (io_in_accum_2),  .io_in_accum_3 (io_in_accum_3),
        .io_in_accum_4 (io_in_accum_4),  .io_in_accum_5 (io_in_accum_5),
        .io_in_accum_6 (io_in_accum_6),  .io_in_accum_7 (io_in_accum_7),
        .io_in_accum_8 (io_in_accum_8),  .io_in_accum_9 (io_in_accum_9),
        .io_in_accum_10(io_in_accum_10), .io_in_accum_11(io_in_accum_11),
        .io_in_accum_12(io_in_accum_12), .io_in_accum_13(io_in_accum_13),
        .io_in_accum_14(io_in_accum_14), .io_in_accum_15(io_in_accum_15),
        .io_in_accum_16(io_in_accum_16), .io_in_accum_17(io_in_accum_17),
        .io_in_accum_18(io_in_accum_18), .io_in_accum_19(io_in_accum_19),
        .io_in_accum_20(io_in_accum_20), .io_in_accum_21(io_in_accum_21),
        .io_in_accum_22(io_in_accum_22), .io_in_accum_23(io_in_accum_23),
        .io_in_accum_24(io_in_accum_24), .io_in_accum_25(io_in_accum_25),
        .io_in_accum_26(io_in_accum_26), .io_in_accum_27(io_in_accum_27),
        .io_in_accum_28(io_in_accum_28), .io_in_accum_29(io_in_accum_29),
        .io_in_accum_30(io_in_accum_30), .io_in_accum_31(io_in_accum_31),
        .io_ctrl_keep     (io_ctrl_keep),
        .io_ctrl_use_accum(io_ctrl_use_accum),
        .io_ctrl_busy     (io_ctrl_busy),
        // MMALU result ports (read by dma_master)
        .io_out_0 (io_out_0),  .io_out_1 (io_out_1),
        .io_out_2 (io_out_2),  .io_out_3 (io_out_3),
        .io_out_4 (io_out_4),  .io_out_5 (io_out_5),
        .io_out_6 (io_out_6),  .io_out_7 (io_out_7),
        .io_out_8 (io_out_8),  .io_out_9 (io_out_9),
        .io_out_10(io_out_10), .io_out_11(io_out_11),
        .io_out_12(io_out_12), .io_out_13(io_out_13),
        .io_out_14(io_out_14), .io_out_15(io_out_15),
        .io_out_16(io_out_16), .io_out_17(io_out_17),
        .io_out_18(io_out_18), .io_out_19(io_out_19),
        .io_out_20(io_out_20), .io_out_21(io_out_21),
        .io_out_22(io_out_22), .io_out_23(io_out_23),
        .io_out_24(io_out_24), .io_out_25(io_out_25),
        .io_out_26(io_out_26), .io_out_27(io_out_27),
        .io_out_28(io_out_28), .io_out_29(io_out_29),
        .io_out_30(io_out_30), .io_out_31(io_out_31),
        .io_clct(io_clct)
    );

    // ── MMALU instance (Chisel-generated, from top.sv) ────────────────────────
    MMALU u_mmalu (
        .clock            (aclk),
        .reset            (~aresetn),
        .io_in_a_0 (io_in_a_0),  .io_in_a_1 (io_in_a_1),
        .io_in_a_2 (io_in_a_2),  .io_in_a_3 (io_in_a_3),
        .io_in_a_4 (io_in_a_4),  .io_in_a_5 (io_in_a_5),
        .io_in_a_6 (io_in_a_6),  .io_in_a_7 (io_in_a_7),
        .io_in_a_8 (io_in_a_8),  .io_in_a_9 (io_in_a_9),
        .io_in_a_10(io_in_a_10), .io_in_a_11(io_in_a_11),
        .io_in_a_12(io_in_a_12), .io_in_a_13(io_in_a_13),
        .io_in_a_14(io_in_a_14), .io_in_a_15(io_in_a_15),
        .io_in_a_16(io_in_a_16), .io_in_a_17(io_in_a_17),
        .io_in_a_18(io_in_a_18), .io_in_a_19(io_in_a_19),
        .io_in_a_20(io_in_a_20), .io_in_a_21(io_in_a_21),
        .io_in_a_22(io_in_a_22), .io_in_a_23(io_in_a_23),
        .io_in_a_24(io_in_a_24), .io_in_a_25(io_in_a_25),
        .io_in_a_26(io_in_a_26), .io_in_a_27(io_in_a_27),
        .io_in_a_28(io_in_a_28), .io_in_a_29(io_in_a_29),
        .io_in_a_30(io_in_a_30), .io_in_a_31(io_in_a_31),
        .io_in_b_0 (io_in_b_0),  .io_in_b_1 (io_in_b_1),
        .io_in_b_2 (io_in_b_2),  .io_in_b_3 (io_in_b_3),
        .io_in_b_4 (io_in_b_4),  .io_in_b_5 (io_in_b_5),
        .io_in_b_6 (io_in_b_6),  .io_in_b_7 (io_in_b_7),
        .io_in_b_8 (io_in_b_8),  .io_in_b_9 (io_in_b_9),
        .io_in_b_10(io_in_b_10), .io_in_b_11(io_in_b_11),
        .io_in_b_12(io_in_b_12), .io_in_b_13(io_in_b_13),
        .io_in_b_14(io_in_b_14), .io_in_b_15(io_in_b_15),
        .io_in_b_16(io_in_b_16), .io_in_b_17(io_in_b_17),
        .io_in_b_18(io_in_b_18), .io_in_b_19(io_in_b_19),
        .io_in_b_20(io_in_b_20), .io_in_b_21(io_in_b_21),
        .io_in_b_22(io_in_b_22), .io_in_b_23(io_in_b_23),
        .io_in_b_24(io_in_b_24), .io_in_b_25(io_in_b_25),
        .io_in_b_26(io_in_b_26), .io_in_b_27(io_in_b_27),
        .io_in_b_28(io_in_b_28), .io_in_b_29(io_in_b_29),
        .io_in_b_30(io_in_b_30), .io_in_b_31(io_in_b_31),
        .io_in_accum_0 (io_in_accum_0),  .io_in_accum_1 (io_in_accum_1),
        .io_in_accum_2 (io_in_accum_2),  .io_in_accum_3 (io_in_accum_3),
        .io_in_accum_4 (io_in_accum_4),  .io_in_accum_5 (io_in_accum_5),
        .io_in_accum_6 (io_in_accum_6),  .io_in_accum_7 (io_in_accum_7),
        .io_in_accum_8 (io_in_accum_8),  .io_in_accum_9 (io_in_accum_9),
        .io_in_accum_10(io_in_accum_10), .io_in_accum_11(io_in_accum_11),
        .io_in_accum_12(io_in_accum_12), .io_in_accum_13(io_in_accum_13),
        .io_in_accum_14(io_in_accum_14), .io_in_accum_15(io_in_accum_15),
        .io_in_accum_16(io_in_accum_16), .io_in_accum_17(io_in_accum_17),
        .io_in_accum_18(io_in_accum_18), .io_in_accum_19(io_in_accum_19),
        .io_in_accum_20(io_in_accum_20), .io_in_accum_21(io_in_accum_21),
        .io_in_accum_22(io_in_accum_22), .io_in_accum_23(io_in_accum_23),
        .io_in_accum_24(io_in_accum_24), .io_in_accum_25(io_in_accum_25),
        .io_in_accum_26(io_in_accum_26), .io_in_accum_27(io_in_accum_27),
        .io_in_accum_28(io_in_accum_28), .io_in_accum_29(io_in_accum_29),
        .io_in_accum_30(io_in_accum_30), .io_in_accum_31(io_in_accum_31),
        .io_ctrl_keep     (io_ctrl_keep),
        .io_ctrl_use_accum(io_ctrl_use_accum),
        .io_ctrl_busy     (io_ctrl_busy),
        .io_out_0 (io_out_0),  .io_out_1 (io_out_1),
        .io_out_2 (io_out_2),  .io_out_3 (io_out_3),
        .io_out_4 (io_out_4),  .io_out_5 (io_out_5),
        .io_out_6 (io_out_6),  .io_out_7 (io_out_7),
        .io_out_8 (io_out_8),  .io_out_9 (io_out_9),
        .io_out_10(io_out_10), .io_out_11(io_out_11),
        .io_out_12(io_out_12), .io_out_13(io_out_13),
        .io_out_14(io_out_14), .io_out_15(io_out_15),
        .io_out_16(io_out_16), .io_out_17(io_out_17),
        .io_out_18(io_out_18), .io_out_19(io_out_19),
        .io_out_20(io_out_20), .io_out_21(io_out_21),
        .io_out_22(io_out_22), .io_out_23(io_out_23),
        .io_out_24(io_out_24), .io_out_25(io_out_25),
        .io_out_26(io_out_26), .io_out_27(io_out_27),
        .io_out_28(io_out_28), .io_out_29(io_out_29),
        .io_out_30(io_out_30), .io_out_31(io_out_31),
        .io_clct(io_clct)
    );

endmodule
