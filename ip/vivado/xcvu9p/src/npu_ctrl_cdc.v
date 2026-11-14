// npu_ctrl_cdc.v — ctrl bridge between the 250 MHz AXI-Lite ctrl_lite and the
// 100 MHz engine.
//
// Writes: data+toggle (the 250 side latches addr/data and flips a toggle; the
// 100 side detects the synced toggle edge and pulses ctrl_we with the stable
// addr/data).
// Reads: request toggle + ack toggle (the 100 side drives the address, samples
// the combinational rdata, and flips an ack; the 250 side returns the data on
// the synced ack edge).
`timescale 1ns/1ps

module npu_ctrl_cdc #(
    parameter ADDR_W = 7,
    parameter DATA_W = 32
) (
    // ── 250 MHz ctrl side (ctrl_lite) ──
    input  wire              clk_c,
    input  wire              rst_c_n,
    input  wire [ADDR_W-1:0] wr_addr_c,
    input  wire              wr_en_c,
    input  wire [DATA_W-1:0] wr_data_c,
    input  wire [ADDR_W-1:0] rd_addr_c,
    input  wire              rd_en_c,
    output reg  [DATA_W-1:0] rd_data_c,
    output reg               rd_valid_c,

    // ── 100 MHz engine side ──
    input  wire              clk_e,
    input  wire              rst_e_n,
    output reg  [ADDR_W-1:0] ctrl_addr_e,
    output reg               ctrl_we_e,
    output reg  [DATA_W-1:0] ctrl_wdata_e,
    input  wire [DATA_W-1:0] ctrl_rdata_e
);

    // ================= 250 MHz request capture =================
    reg [ADDR_W-1:0] wr_addr_r;
    reg [DATA_W-1:0] wr_data_r;
    reg              wr_tog;
    always @(posedge clk_c or negedge rst_c_n) begin
        if (!rst_c_n) begin
            wr_addr_r <= {ADDR_W{1'b0}};
            wr_data_r <= {DATA_W{1'b0}};
            wr_tog    <= 1'b0;
        end else if (wr_en_c) begin
            wr_addr_r <= wr_addr_c;
            wr_data_r <= wr_data_c;
            wr_tog    <= ~wr_tog;
        end
    end

    reg [ADDR_W-1:0] rd_addr_r;
    reg              rd_tog;
    always @(posedge clk_c or negedge rst_c_n) begin
        if (!rst_c_n) begin
            rd_addr_r <= {ADDR_W{1'b0}};
            rd_tog    <= 1'b0;
        end else if (rd_en_c) begin
            rd_addr_r <= rd_addr_c;
            rd_tog    <= ~rd_tog;
        end
    end

    // ================= 100 MHz engine side =================
    (* ASYNC_REG = "TRUE" *) reg [2:0] wr_sync;
    always @(posedge clk_e or negedge rst_e_n) begin
        if (!rst_e_n) wr_sync <= 3'b000;
        else          wr_sync <= {wr_sync[1:0], wr_tog};
    end
    wire wr_edge = wr_sync[2] ^ wr_sync[1];

    (* ASYNC_REG = "TRUE" *) reg [2:0] rd_sync;
    always @(posedge clk_e or negedge rst_e_n) begin
        if (!rst_e_n) rd_sync <= 3'b000;
        else          rd_sync <= {rd_sync[1:0], rd_tog};
    end
    wire rd_edge = rd_sync[2] ^ rd_sync[1];

    reg [DATA_W-1:0] rd_data_e;
    reg              rd_ack_tog_e;

    localparam E_IDLE = 2'd0, E_RD_A = 2'd1, E_RD_D = 2'd2;
    reg [1:0]        e_state;
    reg              wr_pend;
    reg [ADDR_W-1:0] wr_addr_pend;
    reg [DATA_W-1:0] wr_data_pend;

    always @(posedge clk_e or negedge rst_e_n) begin
        if (!rst_e_n) begin
            e_state      <= E_IDLE;
            ctrl_addr_e  <= {ADDR_W{1'b0}};
            ctrl_we_e    <= 1'b0;
            ctrl_wdata_e <= {DATA_W{1'b0}};
            rd_data_e    <= {DATA_W{1'b0}};
            rd_ack_tog_e <= 1'b0;
            wr_pend      <= 1'b0;
            wr_addr_pend <= {ADDR_W{1'b0}};
            wr_data_pend <= {DATA_W{1'b0}};
        end else begin
            ctrl_we_e <= 1'b0;
            if (wr_edge) begin
                wr_pend      <= 1'b1;
                wr_addr_pend <= wr_addr_r;
                wr_data_pend <= wr_data_r;
            end
            case (e_state)
                E_IDLE: begin
                    if (wr_pend) begin
                        ctrl_addr_e  <= wr_addr_pend;
                        ctrl_wdata_e <= wr_data_pend;
                        ctrl_we_e    <= 1'b1;
                        wr_pend      <= 1'b0;
                    end else if (rd_edge) begin
                        ctrl_addr_e <= rd_addr_r;
                        e_state     <= E_RD_A;
                    end
                end
                E_RD_A: e_state <= E_RD_D;   // address settles
                E_RD_D: begin
                    rd_data_e    <= ctrl_rdata_e;
                    rd_ack_tog_e <= ~rd_ack_tog_e;
                    e_state      <= E_IDLE;
                end
                default: e_state <= E_IDLE;
            endcase
        end
    end

    // ================= 250 MHz read return =================
    (* ASYNC_REG = "TRUE" *) reg [2:0] rd_ack_sync;
    always @(posedge clk_c or negedge rst_c_n) begin
        if (!rst_c_n) rd_ack_sync <= 3'b000;
        else          rd_ack_sync <= {rd_ack_sync[1:0], rd_ack_tog_e};
    end
    wire rd_ack_edge = rd_ack_sync[2] ^ rd_ack_sync[1];

    always @(posedge clk_c or negedge rst_c_n) begin
        if (!rst_c_n) begin
            rd_data_c  <= {DATA_W{1'b0}};
            rd_valid_c <= 1'b0;
        end else begin
            rd_valid_c <= 1'b0;
            if (rd_ack_edge) begin
                rd_data_c  <= rd_data_e;
                rd_valid_c <= 1'b1;
            end
        end
    end

endmodule
