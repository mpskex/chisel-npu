// npu_ctrl_lite.v
// AXI4-Lite slave providing a single CTRL/STATUS register to the host.
//
// Register map (byte offsets, 32-bit words):
//   0x000  CTRL
//          [0] start  W  : host writes 1 to assert a 1-cycle start pulse (self-clears)
//          [1] done   RO : asserted for one cycle when npu_dma_master finishes WRITE_OUT
//          [2] busy   RO : asserted while npu_dma_master is active
//          [31:3] reserved, reads 0
//
// AXI clock domain : axi_aclk (250 MHz from XDMA)
// Connected to     : XDMA M_AXI_BYPASS port (128-bit wide at top, but AXI-Lite
//                    is 32-bit here; Vivado inserts dwidth converter in top_npu.v)
//
`timescale 1ns/1ps

module npu_ctrl_lite #(
    parameter ADDR_WIDTH = 12,   // 4 KB address space
    parameter DATA_WIDTH = 32
) (
    // AXI4-Lite slave interface
    input  wire                  axi_aclk,
    input  wire                  axi_aresetn,

    // Write address channel
    input  wire [ADDR_WIDTH-1:0] s_axil_awaddr,
    input  wire [2:0]            s_axil_awprot,
    input  wire                  s_axil_awvalid,
    output reg                   s_axil_awready,

    // Write data channel
    input  wire [DATA_WIDTH-1:0] s_axil_wdata,
    input  wire [3:0]            s_axil_wstrb,
    input  wire                  s_axil_wvalid,
    output reg                   s_axil_wready,

    // Write response channel
    output reg  [1:0]            s_axil_bresp,
    output reg                   s_axil_bvalid,
    input  wire                  s_axil_bready,

    // Read address channel
    input  wire [ADDR_WIDTH-1:0] s_axil_araddr,
    input  wire [2:0]            s_axil_arprot,
    input  wire                  s_axil_arvalid,
    output reg                   s_axil_arready,

    // Read data channel
    output reg  [DATA_WIDTH-1:0] s_axil_rdata,
    output reg  [1:0]            s_axil_rresp,
    output reg                   s_axil_rvalid,
    input  wire                  s_axil_rready,

    // User-side signals
    output reg                   start,   // 1-cycle pulse to npu_dma_master
    input  wire                  done,    // from npu_dma_master (1-cycle pulse)
    input  wire                  busy     // from npu_dma_master (level)
);

    // -----------------------------------------------------------------------
    // Internal CTRL register (only bit 0 is writable; bits 1-2 are RO)
    // -----------------------------------------------------------------------
    reg         ctrl_start_r;  // sticky until cleared below

    // Latch done/busy for read-back (done is a pulse; hold until next start)
    reg         done_latch;

    always @(posedge axi_aclk) begin
        if (!axi_aresetn) begin
            done_latch <= 1'b0;
        end else begin
            if (done)
                done_latch <= 1'b1;
            else if (start)          // clear done when a new start fires
                done_latch <= 1'b0;
        end
    end

    // -----------------------------------------------------------------------
    // Write path
    // -----------------------------------------------------------------------
    // We accept AW and W simultaneously (AXI-Lite burst=1 always).
    // State: 0=idle/ready, 1=wait-for-W, 2=send-B
    reg [1:0] wr_state;
    localparam WR_IDLE = 2'd0,
               WR_DATA = 2'd1,
               WR_RESP = 2'd2;

    reg [ADDR_WIDTH-1:0] aw_addr_r;

    always @(posedge axi_aclk) begin
        if (!axi_aresetn) begin
            wr_state      <= WR_IDLE;
            s_axil_awready <= 1'b0;
            s_axil_wready  <= 1'b0;
            s_axil_bvalid  <= 1'b0;
            s_axil_bresp   <= 2'b00;
            ctrl_start_r   <= 1'b0;
            start          <= 1'b0;
        end else begin
            start <= 1'b0;  // default: no pulse

            case (wr_state)
                WR_IDLE: begin
                    s_axil_awready <= 1'b1;
                    s_axil_wready  <= 1'b1;
                    if (s_axil_awvalid && s_axil_awready) begin
                        aw_addr_r      <= s_axil_awaddr;
                        s_axil_awready <= 1'b0;
                        if (s_axil_wvalid && s_axil_wready) begin
                            // AW and W arrived together
                            wr_state      <= WR_RESP;
                            s_axil_wready <= 1'b0;
                            s_axil_bvalid <= 1'b1;
                            s_axil_bresp  <= 2'b00;
                            // Decode write
                            if (s_axil_awaddr[ADDR_WIDTH-1:2] == 0) begin
                                // CTRL register
                                if (s_axil_wstrb[0] && s_axil_wdata[0]) begin
                                    ctrl_start_r <= 1'b1;
                                    start        <= 1'b1;
                                end
                            end
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
                        if (aw_addr_r[ADDR_WIDTH-1:2] == 0) begin
                            if (s_axil_wstrb[0] && s_axil_wdata[0]) begin
                                ctrl_start_r <= 1'b1;
                                start        <= 1'b1;
                            end
                        end
                    end
                end

                WR_RESP: begin
                    ctrl_start_r <= 1'b0;
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
    reg [1:0] rd_state;
    localparam RD_IDLE = 2'd0,
               RD_DATA = 2'd1;

    reg [ADDR_WIDTH-1:0] ar_addr_r;

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
                        // Latch read data
                        if (s_axil_araddr[ADDR_WIDTH-1:2] == 0) begin
                            // CTRL/STATUS register
                            s_axil_rdata <= {
                                {DATA_WIDTH-3{1'b0}},
                                busy,           // [2]
                                done_latch,     // [1]
                                1'b0            // [0] start is W-only, reads 0
                            };
                        end else begin
                            s_axil_rdata <= {DATA_WIDTH{1'b0}};
                        end
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
