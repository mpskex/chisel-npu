// npu_engine_ctrl_lite.v — AXI4-Lite slave exposing the program engine ctrl
// register map.  Runs in the 250 MHz axi_aclk domain; the engine-side ctrl
// requests cross to the 100 MHz fabric via npu_ctrl_cdc.
//
// Register map (byte offsets, 32-bit words; word index = addr[ADDR_WIDTH-1:2]):
//   0x00 CTRL · 0x04 FRAMES · 0x08 STATUS · 0x0C ERR_INFO · 0x10 FETCH_STATS
//   0x14 PROG_LEN · 0x18 DBG_CLCT · 0x1C DBG_MMA_ACC · 0x20..0x2C trajectory
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

    // Engine-side requests (cross to the 100 MHz domain in npu_ctrl_cdc)
    output reg  [6:0]            wr_addr,
    output reg                   wr_en,
    output reg  [31:0]           wr_data,
    output reg  [6:0]            rd_addr,
    output reg                   rd_en,
    input  wire [31:0]           rd_data,
    input  wire                  rd_valid
);

    localparam WR_IDLE = 2'd0, WR_DATA = 2'd1, WR_RESP = 2'd2;
    localparam RD_IDLE = 2'd0, RD_WAIT = 2'd1, RD_DATA = 2'd2;

    reg [1:0]  wr_state;
    reg [ADDR_WIDTH-1:0] aw_addr_r;
    reg [1:0]  rd_state;

    wire [6:0] aw_word = {s_axil_awaddr[6:2], 2'b00};
    wire [6:0] ar_word = {s_axil_araddr[6:2], 2'b00};

    // -----------------------------------------------------------------------
    // Write path: capture the AXI-Lite write into a one-cycle engine request.
    // -----------------------------------------------------------------------
    always @(posedge axi_aclk) begin
        if (!axi_aresetn) begin
            wr_state       <= WR_IDLE;
            s_axil_awready <= 1'b0;
            s_axil_wready  <= 1'b0;
            s_axil_bvalid  <= 1'b0;
            s_axil_bresp   <= 2'b00;
            wr_addr        <= 7'd0;
            wr_en          <= 1'b0;
            wr_data        <= 32'd0;
        end else begin
            wr_en <= 1'b0;  // default: no write pulse

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
                            wr_addr       <= aw_word;
                            wr_en         <= 1'b1;
                            wr_data       <= s_axil_wdata;
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
                        wr_addr       <= {aw_addr_r[6:2], 2'b00};
                        wr_en         <= 1'b1;
                        wr_data       <= s_axil_wdata;
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
    // Read path: issue the request, wait for the CDC ack, then return data.
    // -----------------------------------------------------------------------
    always @(posedge axi_aclk) begin
        if (!axi_aresetn) begin
            rd_state       <= RD_IDLE;
            s_axil_arready <= 1'b0;
            s_axil_rvalid  <= 1'b0;
            s_axil_rdata   <= {DATA_WIDTH{1'b0}};
            s_axil_rresp   <= 2'b00;
            rd_addr        <= 7'd0;
            rd_en          <= 1'b0;
        end else begin
            rd_en <= 1'b0;  // default: no read request pulse

            case (rd_state)
                RD_IDLE: begin
                    s_axil_arready <= 1'b1;
                    if (s_axil_arvalid && s_axil_arready) begin
                        s_axil_arready <= 1'b0;
                        rd_addr        <= ar_word;
                        rd_en          <= 1'b1;
                        rd_state       <= RD_WAIT;
                    end
                end

                RD_WAIT: begin
                    if (rd_valid) begin
                        s_axil_rdata  <= rd_data;
                        s_axil_rvalid <= 1'b1;
                        s_axil_rresp  <= 2'b00;
                        rd_state      <= RD_DATA;
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
