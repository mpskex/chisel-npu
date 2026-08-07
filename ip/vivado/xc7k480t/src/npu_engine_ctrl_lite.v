// npu_engine_ctrl_lite.v
// AXI4-Lite slave exposing the program engine's ctrl register map to the
// host (replaces the single-register npu_ctrl_lite.v).
//
// Register map (byte offsets, 32-bit words; word index = addr[ADDR_WIDTH-1:2]):
//   0x00  CTRL          [0] start (W, edge) [1] done (RO) [2] busy (RO)
//   0x04  FRAMES        W  (reserved config)
//   0x08  STATUS        [31] illegal | [30:16] frames_done | [15:0] pc
//   0x0C  ERR_INFO      W of the faulting instruction (RO)
//   0x10  FETCH_STATS   [31:16] prefetches | [15:0] misses
//   0x14  PROG_LEN      W  instruction count
//
// User-side: ctrl_addr[4:0] / ctrl_we / ctrl_wdata / ctrl_rdata drive the
// Chisel NpuProgramEngineFrontend directly.
`timescale 1ns/1ps

module npu_engine_ctrl_lite #(
    parameter ADDR_WIDTH = 12,   // 4 KB address space
    parameter DATA_WIDTH = 32
) (
    input  wire                  axi_aclk,
    input  wire                  axi_aresetn,

    // AXI4-Lite slave
    input  wire [ADDR_WIDTH-1:0] s_axil_awaddr,
    input  wire [2:0]            s_axil_awprot,
    input  wire                  s_axil_awvalid,
    output reg                   s_axil_awready,
    input  wire [DATA_WIDTH-1:0] s_axil_wdata,
    input  wire [3:0]            s_axil_wstrb,
    input  wire                  s_axil_wvalid,
    output reg                   s_axil_wready,
    output reg  [1:0]            s_axil_bresp,
    output reg                   s_axil_bvalid,
    input  wire                  s_axil_bready,
    input  wire [ADDR_WIDTH-1:0] s_axil_araddr,
    input  wire [2:0]            s_axil_arprot,
    input  wire                  s_axil_arvalid,
    output reg                   s_axil_arready,
    output reg  [DATA_WIDTH-1:0] s_axil_rdata,
    output reg  [1:0]            s_axil_rresp,
    output reg                   s_axil_rvalid,
    input  wire                  s_axil_rready,

    // User-side: Chisel engine ctrl interface
    output       [4:0]            ctrl_addr,
    output                        ctrl_we,
    output       [31:0]           ctrl_wdata,
    input  wire [31:0]            ctrl_rdata
);

    localparam WR_IDLE = 2'd0, WR_DATA = 2'd1, WR_RESP = 2'd2;
    localparam RD_IDLE = 2'd0, RD_DATA = 2'd1;

    reg [1:0]  wr_state;
    reg [ADDR_WIDTH-1:0] aw_addr_r;
    reg [1:0]  rd_state;
    reg [ADDR_WIDTH-1:0] ar_addr_r;

    // User-side registers (single driver each; see the assigns below).
    reg [4:0]  w_ctrl_addr;
    reg        w_ctrl_we;
    reg [31:0] w_ctrl_wdata;
    reg [4:0]  r_ctrl_addr;

    // ctrl_addr carries BYTE offsets (matches the Chisel frontend's decode:
    // 0x00 CTRL, 0x04 FRAMES, 0x08 STATUS, 0x0C ERR_INFO, 0x10 FETCH_STATS,
    // 0x14 PROG_LEN).  During the AR-acceptance cycle the address is taken
    // combinationally from araddr so the rdata latch captures the CURRENT
    // transaction (a one-cycle lag would return the previous register).
    wire [4:0] ar_addr_comb = {s_axil_araddr[4:2], 2'b00};
    wire       ar_sel       = (rd_state == RD_IDLE) &&
                              s_axil_arvalid && s_axil_arready;

    assign ctrl_addr  = w_ctrl_we ? w_ctrl_addr
                      : ar_sel    ? ar_addr_comb
                      :             r_ctrl_addr;
    assign ctrl_we    = w_ctrl_we;
    assign ctrl_wdata = w_ctrl_wdata;

    // -----------------------------------------------------------------------
    // Write path
    // -----------------------------------------------------------------------
    always @(posedge axi_aclk) begin
        if (!axi_aresetn) begin
            wr_state       <= WR_IDLE;
            s_axil_awready <= 1'b0;
            s_axil_wready  <= 1'b0;
            s_axil_bvalid  <= 1'b0;
            s_axil_bresp   <= 2'b00;
            w_ctrl_addr    <= 5'd0;
            w_ctrl_we      <= 1'b0;
            w_ctrl_wdata   <= 32'd0;
        end else begin
            w_ctrl_we <= 1'b0;  // default: no write pulse

            case (wr_state)
                WR_IDLE: begin
                    s_axil_awready <= 1'b1;
                    s_axil_wready  <= 1'b1;
                    if (s_axil_awvalid && s_axil_awready) begin
                        aw_addr_r      <= s_axil_awaddr;
                        s_axil_awready <= 1'b0;
                        if (s_axil_wvalid && s_axil_wready) begin
                            wr_state      <= WR_RESP;
                            s_axil_wready <= 1'b0;
                            s_axil_bvalid <= 1'b1;
                            s_axil_bresp  <= 2'b00;
                            w_ctrl_addr   <= {s_axil_awaddr[4:2], 2'b00};
                            w_ctrl_we     <= 1'b1;
                            w_ctrl_wdata  <= s_axil_wdata;
                        end else begin
                            wr_state <= WR_DATA;
                        end
                    end
                end

                WR_DATA: begin
                    if (s_axil_wvalid && s_axil_wready) begin
                        wr_state      <= WR_RESP;
                        s_axil_wready <= 1'b0;
                        s_axil_bvalid <= 1'b1;
                        s_axil_bresp  <= 2'b00;
                        w_ctrl_addr   <= {aw_addr_r[4:2], 2'b00};
                        w_ctrl_we     <= 1'b1;
                        w_ctrl_wdata  <= s_axil_wdata;
                    end
                end

                WR_RESP: begin
                    if (s_axil_bvalid && s_axil_bready) begin
                        s_axil_bvalid  <= 1'b0;
                        wr_state       <= WR_IDLE;
                        s_axil_awready <= 1'b1;
                        s_axil_wready  <= 1'b1;
                    end
                end

                default: wr_state <= WR_IDLE;
            endcase
        end
    end

    // -----------------------------------------------------------------------
    // Read path
    // -----------------------------------------------------------------------
    always @(posedge axi_aclk) begin
        if (!axi_aresetn) begin
            rd_state       <= RD_IDLE;
            s_axil_arready <= 1'b0;
            s_axil_rvalid  <= 1'b0;
            s_axil_rdata   <= {DATA_WIDTH{1'b0}};
            s_axil_rresp   <= 2'b00;
        end else begin
            case (rd_state)
                RD_IDLE: begin
                    s_axil_arready <= 1'b1;
                    if (s_axil_arvalid && s_axil_arready) begin
                        ar_addr_r      <= s_axil_araddr;
                        s_axil_arready <= 1'b0;
                        rd_state       <= RD_DATA;
                        s_axil_rvalid  <= 1'b1;
                        s_axil_rresp   <= 2'b00;
                        r_ctrl_addr    <= {s_axil_araddr[4:2], 2'b00};
                        // Latch the engine read in the SAME cycle: ctrl_addr
                        // is driven combinationally from araddr here (ar_sel),
                        // so the captured value is the CURRENT register.
                        s_axil_rdata   <= ctrl_rdata;
                    end
                end

                RD_DATA: begin
                    if (s_axil_rvalid && s_axil_rready) begin
                        s_axil_rvalid  <= 1'b0;
                        rd_state       <= RD_IDLE;
                        s_axil_arready <= 1'b1;
                    end
                end

                default: rd_state <= RD_IDLE;
            endcase
        end
    end

endmodule
