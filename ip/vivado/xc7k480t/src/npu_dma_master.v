// npu_dma_master.v
// AXI4 master FSM — DDR3 ↔ MMALU data mover (K=32).
//
// Fix A: a 1-cycle pipeline register is inserted between the AXI read-data
// channel and the staging buffers (a_buf / b_buf / acc_buf).
//   - m_axi_rdata is registered into rdata_pipe on each accepted beat.
//   - All buffer unpacking uses rdata_pipe, triggered by rpipe_valid
//     (asserted the cycle AFTER an AXI beat is accepted).
//   - State transitions use rpipe_valid && rlast_pipe.
// This breaks the long LUT-mux chain through npu_dw_inst (which caused
// WNS = -0.1 ns) without any impact on correctness — Verilog's non-blocking
// assignment semantics guarantee that rdata_pipe reads the PREVIOUS cycle's
// data when it is simultaneously written with new data.
//
// Data sizes (K=32, N=8, accBits=32):
//   io_in_a/b  : 32 × 8-bit  = 32 B   → 2 beats of 128-bit  (ARLEN=1)
//   io_in_accum: 32 × 32-bit = 128 B  → 8 beats of 128-bit  (ARLEN=7)
//   io_out     : 32 × 32-bit = 128 B  → 8 beats (ARLEN=7)
//
`timescale 1ns/1ps

module npu_dma_master #(
    parameter AXI_DATA_WIDTH = 128,
    parameter AXI_ADDR_WIDTH = 64,
    parameter AXI_ID_WIDTH   = 4,
    // V10: with the new axi_xbar (2S:2M), the NPU master and the host see the
    // same unified 4 GB address map:
    //   0x0000_0000 .. 0x7FFF_FFFF → MIG C0
    //   0x8000_0000 .. 0xFFFF_FFFF → MIG C1
    // The default operand region lives at +1 GB inside MIG C0 so it is far
    // from any host PCIe DMA staging that typically sits near 0x0.  The
    // 64-bit high half is zero; the xbar only decodes the low 32 bits.
    //   A     @ 0x0_4000_0000 ( 32 B = 32 × int8 )
    //   B     @ 0x0_4000_0100 ( 32 B = 32 × int8 )
    //   ACCUM @ 0x0_4000_0200 (128 B = 32 × int32)
    //   OUT   @ 0x0_4000_0400 (128 B = 32 × int32)
    parameter [63:0] DEFAULT_BASE_A     = 64'h0000_0000_4000_0000,
    parameter [63:0] DEFAULT_BASE_B     = 64'h0000_0000_4000_0100,
    parameter [63:0] DEFAULT_BASE_ACCUM = 64'h0000_0000_4000_0200,
    parameter [63:0] DEFAULT_BASE_OUT   = 64'h0000_0000_4000_0400
) (
    input  wire                       aclk,
    input  wire                       aresetn,
    input  wire                       start,
    output reg                        busy,
    output reg                        done,

    // AXI4 master interface
    output reg  [AXI_ID_WIDTH-1:0]   m_axi_awid,
    output reg  [AXI_ADDR_WIDTH-1:0] m_axi_awaddr,
    output reg  [7:0]                 m_axi_awlen,
    output reg  [2:0]                 m_axi_awsize,
    output reg  [1:0]                 m_axi_awburst,
    output reg                        m_axi_awlock,
    output reg  [3:0]                 m_axi_awcache,
    output reg  [2:0]                 m_axi_awprot,
    output reg  [3:0]                 m_axi_awqos,
    output reg                        m_axi_awvalid,
    input  wire                       m_axi_awready,

    output reg  [AXI_DATA_WIDTH-1:0]   m_axi_wdata,
    output reg  [AXI_DATA_WIDTH/8-1:0] m_axi_wstrb,
    output reg                         m_axi_wlast,
    output reg                         m_axi_wvalid,
    input  wire                        m_axi_wready,

    input  wire [AXI_ID_WIDTH-1:0]   m_axi_bid,
    input  wire [1:0]                 m_axi_bresp,
    input  wire                       m_axi_bvalid,
    output reg                        m_axi_bready,

    output reg  [AXI_ID_WIDTH-1:0]   m_axi_arid,
    output reg  [AXI_ADDR_WIDTH-1:0] m_axi_araddr,
    output reg  [7:0]                 m_axi_arlen,
    output reg  [2:0]                 m_axi_arsize,
    output reg  [1:0]                 m_axi_arburst,
    output reg                        m_axi_arlock,
    output reg  [3:0]                 m_axi_arcache,
    output reg  [2:0]                 m_axi_arprot,
    output reg  [3:0]                 m_axi_arqos,
    output reg                        m_axi_arvalid,
    input  wire                       m_axi_arready,

    input  wire [AXI_DATA_WIDTH-1:0] m_axi_rdata,
    input  wire [1:0]                 m_axi_rresp,
    input  wire                       m_axi_rlast,
    input  wire                       m_axi_rvalid,
    output reg                        m_axi_rready,

    // MMALU ports (K=32)
    output reg  [7:0]  io_in_a_0,  io_in_a_1,  io_in_a_2,  io_in_a_3,
                       io_in_a_4,  io_in_a_5,  io_in_a_6,  io_in_a_7,
                       io_in_a_8,  io_in_a_9,  io_in_a_10, io_in_a_11,
                       io_in_a_12, io_in_a_13, io_in_a_14, io_in_a_15,
                       io_in_a_16, io_in_a_17, io_in_a_18, io_in_a_19,
                       io_in_a_20, io_in_a_21, io_in_a_22, io_in_a_23,
                       io_in_a_24, io_in_a_25, io_in_a_26, io_in_a_27,
                       io_in_a_28, io_in_a_29, io_in_a_30, io_in_a_31,

    output reg  [7:0]  io_in_b_0,  io_in_b_1,  io_in_b_2,  io_in_b_3,
                       io_in_b_4,  io_in_b_5,  io_in_b_6,  io_in_b_7,
                       io_in_b_8,  io_in_b_9,  io_in_b_10, io_in_b_11,
                       io_in_b_12, io_in_b_13, io_in_b_14, io_in_b_15,
                       io_in_b_16, io_in_b_17, io_in_b_18, io_in_b_19,
                       io_in_b_20, io_in_b_21, io_in_b_22, io_in_b_23,
                       io_in_b_24, io_in_b_25, io_in_b_26, io_in_b_27,
                       io_in_b_28, io_in_b_29, io_in_b_30, io_in_b_31,

    output reg  [31:0] io_in_accum_0,  io_in_accum_1,  io_in_accum_2,  io_in_accum_3,
                       io_in_accum_4,  io_in_accum_5,  io_in_accum_6,  io_in_accum_7,
                       io_in_accum_8,  io_in_accum_9,  io_in_accum_10, io_in_accum_11,
                       io_in_accum_12, io_in_accum_13, io_in_accum_14, io_in_accum_15,
                       io_in_accum_16, io_in_accum_17, io_in_accum_18, io_in_accum_19,
                       io_in_accum_20, io_in_accum_21, io_in_accum_22, io_in_accum_23,
                       io_in_accum_24, io_in_accum_25, io_in_accum_26, io_in_accum_27,
                       io_in_accum_28, io_in_accum_29, io_in_accum_30, io_in_accum_31,

    output reg         io_ctrl_keep,
    output reg         io_ctrl_use_accum,
    output reg         io_ctrl_busy,

    input  wire [31:0] io_out_0,  io_out_1,  io_out_2,  io_out_3,
                       io_out_4,  io_out_5,  io_out_6,  io_out_7,
                       io_out_8,  io_out_9,  io_out_10, io_out_11,
                       io_out_12, io_out_13, io_out_14, io_out_15,
                       io_out_16, io_out_17, io_out_18, io_out_19,
                       io_out_20, io_out_21, io_out_22, io_out_23,
                       io_out_24, io_out_25, io_out_26, io_out_27,
                       io_out_28, io_out_29, io_out_30, io_out_31,

    input  wire        io_clct
);

    // -----------------------------------------------------------------------
    // Staging buffers (zero-initialised on reset so a dropped/spurious beat
    // cannot leak stale data from a previous run into a new MMA cycle).
    // -----------------------------------------------------------------------
    reg [7:0]  a_buf   [0:31];
    reg [7:0]  b_buf   [0:31];
    reg [31:0] acc_buf [0:31];
    reg [31:0] out_buf [0:31];

    (* mark_debug = "true" *) reg [3:0] beat_cnt;

    integer _bi;
    initial begin
        for (_bi = 0; _bi < 32; _bi = _bi + 1) begin
            a_buf  [_bi] = 8'h00;
            b_buf  [_bi] = 8'h00;
            acc_buf[_bi] = 32'h0000_0000;
            out_buf[_bi] = 32'h0000_0000;
        end
    end

    // -----------------------------------------------------------------------
    // Fix A: AXI read-data pipeline register
    // rdata_pipe / rlast_pipe are loaded on every accepted beat.
    // rpipe_valid fires the cycle AFTER acceptance.
    // All buffer writes use rdata_pipe+rpipe_valid instead of m_axi_rdata+rvalid.
    // -----------------------------------------------------------------------
    (* mark_debug = "true" *) reg [AXI_DATA_WIDTH-1:0] rdata_pipe;
    (* mark_debug = "true" *) reg                       rlast_pipe;
    (* mark_debug = "true" *) reg                       rpipe_valid;

    // -----------------------------------------------------------------------
    // Fix B: read-timeout retry.  The 128→512 axi_dwidth_npu converter +
    // axi_xbar S01 path intermittently DROPS an entire AXI read transaction
    // (the AR is accepted but RVALID never comes back — observed as the FSM
    // stuck in S_READ_B_R with rvalid=0 forever).  A retry counter in each
    // read-data state re-issues the AR when no beat arrives for
    // READ_TIMEOUT_MAX cycles.  Since the lost transaction never responds,
    // re-issuing cannot produce duplicate data.
    // -----------------------------------------------------------------------
    localparam [15:0] READ_TIMEOUT_MAX = 16'h4000;   // 16384 cyc @ 200 MHz = 82 µs
    (* mark_debug = "true" *) reg [15:0] read_timeout_cnt;

    // ──────────────────────────────────────────────────────────────────────
    // ILA debug shadows for the AXI read-handshake signals (which are
    // module ports — we can't tag them directly with mark_debug, so we
    // register them on every clock and expose those shadow regs to the ILA).
    // ──────────────────────────────────────────────────────────────────────
    (* mark_debug = "true" *) reg                       dbg_rvalid;
    (* mark_debug = "true" *) reg                       dbg_rready;
    (* mark_debug = "true" *) reg                       dbg_rlast;
    (* mark_debug = "true" *) reg [31:0]                dbg_rdata_lo;
    (* mark_debug = "true" *) reg                       dbg_arvalid;
    (* mark_debug = "true" *) reg                       dbg_arready;
    (* mark_debug = "true" *) reg [3:0]                 dbg_state_d1;
    (* mark_debug = "true" *) reg [3:0]                 dbg_beat_cnt_d1;
    always @(posedge aclk) begin
        dbg_rvalid       <= m_axi_rvalid;
        dbg_rready       <= m_axi_rready;
        dbg_rlast        <= m_axi_rlast;
        dbg_rdata_lo     <= m_axi_rdata[31:0];
        dbg_arvalid      <= m_axi_arvalid;
        dbg_arready      <= m_axi_arready;
        dbg_state_d1     <= state;
        dbg_beat_cnt_d1  <= beat_cnt;
    end
    // The rdrop fix has been removed (it did not solve the off-by-one and
    // made the symptom worse). Test data shows that with positive-WNS V10
    // and zero-initialised acc_buf, beat 0 of each read burst is consistently
    // lost in the xbar/dwidth-converter chain rather than being dropped here.
    // Treat rpipe_use as plain rpipe_valid until a permanent FSM redesign or
    // an ILA capture can pinpoint where the beat is being lost.
    wire                      rpipe_use = rpipe_valid;

    always @(posedge aclk) begin
        if (!aresetn) begin
            rdata_pipe  <= {AXI_DATA_WIDTH{1'b0}};
            rlast_pipe  <= 1'b0;
            rpipe_valid <= 1'b0;
        end else begin
            // Load pipeline register when a beat is accepted
            if (m_axi_rvalid && m_axi_rready) begin
                rdata_pipe  <= m_axi_rdata;
                rlast_pipe  <= m_axi_rlast;
                rpipe_valid <= 1'b1;
            end else begin
                rpipe_valid <= 1'b0;
            end
        end
    end

    // -----------------------------------------------------------------------
    // State machine
    // -----------------------------------------------------------------------
    localparam [3:0]
        S_IDLE       = 4'd0,
        S_READ_A_AR  = 4'd1,
        S_READ_A_R   = 4'd2,
        S_READ_B_AR  = 4'd3,
        S_READ_B_R   = 4'd4,
        S_READ_ACC_AR= 4'd5,
        S_READ_ACC_R = 4'd6,
        S_KICK       = 4'd7,
        S_WAIT_CLCT  = 4'd8,
        S_WR_AW      = 4'd9,
        S_WR_W       = 4'd10,
        S_WR_B       = 4'd11,
        S_DONE       = 4'd12;

    (* mark_debug = "true" *) reg [3:0] state;

    localparam AXI_SIZE_128   = 3'd4;
    localparam AXI_BURST_INCR = 2'b01;
    localparam AXI_ID         = 4'h1;

    // A/B: 2 beats (ARLEN=1); ACCUM/OUT: 8 beats (ARLEN=7)
    localparam ARLEN_AB   = 8'd1;
    localparam ARLEN_FULL = 8'd7;

    always @(posedge aclk) begin
        if (!aresetn) begin
            state             <= S_IDLE;
            busy              <= 1'b0;
            done              <= 1'b0;
            beat_cnt          <= 4'd0;
            read_timeout_cnt  <= 16'h0;
            m_axi_arvalid     <= 1'b0;
            m_axi_rready      <= 1'b0;
            m_axi_awvalid     <= 1'b0;
            m_axi_wvalid      <= 1'b0;
            m_axi_bready      <= 1'b0;
            io_ctrl_keep      <= 1'b0;
            io_ctrl_use_accum <= 1'b0;
            io_ctrl_busy      <= 1'b0;
            m_axi_awid    <= AXI_ID;
            m_axi_awsize  <= AXI_SIZE_128;
            m_axi_awburst <= AXI_BURST_INCR;
            m_axi_awlock  <= 1'b0;
            m_axi_awcache <= 4'b0010;
            m_axi_awprot  <= 3'b000;
            m_axi_awqos   <= 4'b0000;
            m_axi_wstrb   <= {(AXI_DATA_WIDTH/8){1'b1}};
            m_axi_arid    <= AXI_ID;
            m_axi_arsize  <= AXI_SIZE_128;
            m_axi_arburst <= AXI_BURST_INCR;
            m_axi_arlock  <= 1'b0;
            m_axi_arcache <= 4'b0010;
            m_axi_arprot  <= 3'b000;
            m_axi_arqos   <= 4'b0000;
        end else begin
            done <= 1'b0;

            case (state)
                // ----------------------------------------------------------
                S_IDLE: begin
                    busy         <= 1'b0;
                    io_ctrl_keep <= 1'b0;
                    if (start) begin
                        busy  <= 1'b1;
                        state <= S_READ_A_AR;
                    end
                end

                // ----------------------------------------------------------
                // Read matrix A (32 B, 2 beats, ARLEN=1)
                // ----------------------------------------------------------
                S_READ_A_AR: begin
                    m_axi_araddr  <= DEFAULT_BASE_A;
                    m_axi_arlen   <= ARLEN_AB;
                    m_axi_arvalid <= 1'b1;
                    beat_cnt      <= 4'd0;
                    if (m_axi_arready && m_axi_arvalid) begin
                        m_axi_arvalid <= 1'b0;
                        // Keep rready asserted across read phases: deasserting it
                        // between A's rlast and the next AR handshake lets the
                        // dwidth-converter/xbar chain stall and LOSE the first
                        // beat of the next burst (intermittent S_READ_B_R hang).
                        m_axi_rready  <= 1'b1;
                        state         <= S_READ_A_R;
                    end
                end

                S_READ_A_R: begin
                    // Do NOT deassert rready on rlast — see S_READ_A_AR comment.
                    // Read-timeout retry: re-issue the AR if the transaction is
                    // dropped by the xbar/dwidth chain (no beat for a long time).
                    if (rpipe_use)
                        read_timeout_cnt <= 16'h0;
                    else if (read_timeout_cnt != READ_TIMEOUT_MAX)
                        read_timeout_cnt <= read_timeout_cnt + 16'h1;
                    if (read_timeout_cnt == READ_TIMEOUT_MAX) begin
                        read_timeout_cnt <= 16'h0;
                        state            <= S_READ_A_AR;
                    end
                    // Buffer unpack from pipeline register (1 cycle after acceptance)
                    if (rpipe_use) begin
                        // beat N: bytes [beat_cnt*16 .. beat_cnt*16+15]
                        a_buf[beat_cnt*16 + 0]  <= rdata_pipe[7:0];
                        a_buf[beat_cnt*16 + 1]  <= rdata_pipe[15:8];
                        a_buf[beat_cnt*16 + 2]  <= rdata_pipe[23:16];
                        a_buf[beat_cnt*16 + 3]  <= rdata_pipe[31:24];
                        a_buf[beat_cnt*16 + 4]  <= rdata_pipe[39:32];
                        a_buf[beat_cnt*16 + 5]  <= rdata_pipe[47:40];
                        a_buf[beat_cnt*16 + 6]  <= rdata_pipe[55:48];
                        a_buf[beat_cnt*16 + 7]  <= rdata_pipe[63:56];
                        a_buf[beat_cnt*16 + 8]  <= rdata_pipe[71:64];
                        a_buf[beat_cnt*16 + 9]  <= rdata_pipe[79:72];
                        a_buf[beat_cnt*16 + 10] <= rdata_pipe[87:80];
                        a_buf[beat_cnt*16 + 11] <= rdata_pipe[95:88];
                        a_buf[beat_cnt*16 + 12] <= rdata_pipe[103:96];
                        a_buf[beat_cnt*16 + 13] <= rdata_pipe[111:104];
                        a_buf[beat_cnt*16 + 14] <= rdata_pipe[119:112];
                        a_buf[beat_cnt*16 + 15] <= rdata_pipe[127:120];
                        beat_cnt <= beat_cnt + 1;
                        if (rlast_pipe)
                            state <= S_READ_B_AR;
                    end
                end

                // ----------------------------------------------------------
                // Read matrix B (32 B, 2 beats)
                // ----------------------------------------------------------
                S_READ_B_AR: begin
                    m_axi_araddr  <= DEFAULT_BASE_B;
                    m_axi_arlen   <= ARLEN_AB;
                    m_axi_arvalid <= 1'b1;
                    beat_cnt      <= 4'd0;
                    if (m_axi_arready && m_axi_arvalid) begin
                        m_axi_arvalid <= 1'b0;
                        // rready stays asserted (see S_READ_A_AR comment)
                        state         <= S_READ_B_R;
                    end
                end

                S_READ_B_R: begin
                    // rready stays asserted (see S_READ_A_AR comment)
                    // Read-timeout retry: re-issue the B AR if dropped.
                    if (rpipe_use)
                        read_timeout_cnt <= 16'h0;
                    else if (read_timeout_cnt != READ_TIMEOUT_MAX)
                        read_timeout_cnt <= read_timeout_cnt + 16'h1;
                    if (read_timeout_cnt == READ_TIMEOUT_MAX) begin
                        read_timeout_cnt <= 16'h0;
                        state            <= S_READ_B_AR;
                    end
                    if (rpipe_use) begin
                        b_buf[beat_cnt*16 + 0]  <= rdata_pipe[7:0];
                        b_buf[beat_cnt*16 + 1]  <= rdata_pipe[15:8];
                        b_buf[beat_cnt*16 + 2]  <= rdata_pipe[23:16];
                        b_buf[beat_cnt*16 + 3]  <= rdata_pipe[31:24];
                        b_buf[beat_cnt*16 + 4]  <= rdata_pipe[39:32];
                        b_buf[beat_cnt*16 + 5]  <= rdata_pipe[47:40];
                        b_buf[beat_cnt*16 + 6]  <= rdata_pipe[55:48];
                        b_buf[beat_cnt*16 + 7]  <= rdata_pipe[63:56];
                        b_buf[beat_cnt*16 + 8]  <= rdata_pipe[71:64];
                        b_buf[beat_cnt*16 + 9]  <= rdata_pipe[79:72];
                        b_buf[beat_cnt*16 + 10] <= rdata_pipe[87:80];
                        b_buf[beat_cnt*16 + 11] <= rdata_pipe[95:88];
                        b_buf[beat_cnt*16 + 12] <= rdata_pipe[103:96];
                        b_buf[beat_cnt*16 + 13] <= rdata_pipe[111:104];
                        b_buf[beat_cnt*16 + 14] <= rdata_pipe[119:112];
                        b_buf[beat_cnt*16 + 15] <= rdata_pipe[127:120];
                        beat_cnt <= beat_cnt + 1;
                        if (rlast_pipe)
                            state <= S_READ_ACC_AR;
                    end
                end

                // ----------------------------------------------------------
                // Read accumulator init (128 B, 8 beats, ARLEN=7)
                // ----------------------------------------------------------
                S_READ_ACC_AR: begin
                    m_axi_araddr  <= DEFAULT_BASE_ACCUM;
                    m_axi_arlen   <= ARLEN_FULL;
                    m_axi_arvalid <= 1'b1;
                    beat_cnt      <= 4'd0;
                    if (m_axi_arready && m_axi_arvalid) begin
                        m_axi_arvalid <= 1'b0;
                        // rready stays asserted (see S_READ_A_AR comment)
                        state         <= S_READ_ACC_R;
                    end
                end

                S_READ_ACC_R: begin
                    // rready stays asserted (see S_READ_A_AR comment)
                    // Read-timeout retry: re-issue the ACCUM AR if dropped.
                    if (rpipe_use)
                        read_timeout_cnt <= 16'h0;
                    else if (read_timeout_cnt != READ_TIMEOUT_MAX)
                        read_timeout_cnt <= read_timeout_cnt + 16'h1;
                    if (read_timeout_cnt == READ_TIMEOUT_MAX) begin
                        read_timeout_cnt <= 16'h0;
                        state            <= S_READ_ACC_AR;
                    end
                    if (rpipe_use) begin
                        // 4 words per beat
                        acc_buf[beat_cnt*4 + 0] <= rdata_pipe[31:0];
                        acc_buf[beat_cnt*4 + 1] <= rdata_pipe[63:32];
                        acc_buf[beat_cnt*4 + 2] <= rdata_pipe[95:64];
                        acc_buf[beat_cnt*4 + 3] <= rdata_pipe[127:96];
                        beat_cnt <= beat_cnt + 1;
                        if (rlast_pipe)
                            state <= S_KICK;
                    end
                end

                // ----------------------------------------------------------
                // Drive MMALU ports and pulse io_ctrl_busy
                // ----------------------------------------------------------
                S_KICK: begin
                    io_in_a_0  <= a_buf[0];  io_in_a_1  <= a_buf[1];
                    io_in_a_2  <= a_buf[2];  io_in_a_3  <= a_buf[3];
                    io_in_a_4  <= a_buf[4];  io_in_a_5  <= a_buf[5];
                    io_in_a_6  <= a_buf[6];  io_in_a_7  <= a_buf[7];
                    io_in_a_8  <= a_buf[8];  io_in_a_9  <= a_buf[9];
                    io_in_a_10 <= a_buf[10]; io_in_a_11 <= a_buf[11];
                    io_in_a_12 <= a_buf[12]; io_in_a_13 <= a_buf[13];
                    io_in_a_14 <= a_buf[14]; io_in_a_15 <= a_buf[15];
                    io_in_a_16 <= a_buf[16]; io_in_a_17 <= a_buf[17];
                    io_in_a_18 <= a_buf[18]; io_in_a_19 <= a_buf[19];
                    io_in_a_20 <= a_buf[20]; io_in_a_21 <= a_buf[21];
                    io_in_a_22 <= a_buf[22]; io_in_a_23 <= a_buf[23];
                    io_in_a_24 <= a_buf[24]; io_in_a_25 <= a_buf[25];
                    io_in_a_26 <= a_buf[26]; io_in_a_27 <= a_buf[27];
                    io_in_a_28 <= a_buf[28]; io_in_a_29 <= a_buf[29];
                    io_in_a_30 <= a_buf[30]; io_in_a_31 <= a_buf[31];

                    io_in_b_0  <= b_buf[0];  io_in_b_1  <= b_buf[1];
                    io_in_b_2  <= b_buf[2];  io_in_b_3  <= b_buf[3];
                    io_in_b_4  <= b_buf[4];  io_in_b_5  <= b_buf[5];
                    io_in_b_6  <= b_buf[6];  io_in_b_7  <= b_buf[7];
                    io_in_b_8  <= b_buf[8];  io_in_b_9  <= b_buf[9];
                    io_in_b_10 <= b_buf[10]; io_in_b_11 <= b_buf[11];
                    io_in_b_12 <= b_buf[12]; io_in_b_13 <= b_buf[13];
                    io_in_b_14 <= b_buf[14]; io_in_b_15 <= b_buf[15];
                    io_in_b_16 <= b_buf[16]; io_in_b_17 <= b_buf[17];
                    io_in_b_18 <= b_buf[18]; io_in_b_19 <= b_buf[19];
                    io_in_b_20 <= b_buf[20]; io_in_b_21 <= b_buf[21];
                    io_in_b_22 <= b_buf[22]; io_in_b_23 <= b_buf[23];
                    io_in_b_24 <= b_buf[24]; io_in_b_25 <= b_buf[25];
                    io_in_b_26 <= b_buf[26]; io_in_b_27 <= b_buf[27];
                    io_in_b_28 <= b_buf[28]; io_in_b_29 <= b_buf[29];
                    io_in_b_30 <= b_buf[30]; io_in_b_31 <= b_buf[31];

                    io_in_accum_0  <= acc_buf[0];  io_in_accum_1  <= acc_buf[1];
                    io_in_accum_2  <= acc_buf[2];  io_in_accum_3  <= acc_buf[3];
                    io_in_accum_4  <= acc_buf[4];  io_in_accum_5  <= acc_buf[5];
                    io_in_accum_6  <= acc_buf[6];  io_in_accum_7  <= acc_buf[7];
                    io_in_accum_8  <= acc_buf[8];  io_in_accum_9  <= acc_buf[9];
                    io_in_accum_10 <= acc_buf[10]; io_in_accum_11 <= acc_buf[11];
                    io_in_accum_12 <= acc_buf[12]; io_in_accum_13 <= acc_buf[13];
                    io_in_accum_14 <= acc_buf[14]; io_in_accum_15 <= acc_buf[15];
                    io_in_accum_16 <= acc_buf[16]; io_in_accum_17 <= acc_buf[17];
                    io_in_accum_18 <= acc_buf[18]; io_in_accum_19 <= acc_buf[19];
                    io_in_accum_20 <= acc_buf[20]; io_in_accum_21 <= acc_buf[21];
                    io_in_accum_22 <= acc_buf[22]; io_in_accum_23 <= acc_buf[23];
                    io_in_accum_24 <= acc_buf[24]; io_in_accum_25 <= acc_buf[25];
                    io_in_accum_26 <= acc_buf[26]; io_in_accum_27 <= acc_buf[27];
                    io_in_accum_28 <= acc_buf[28]; io_in_accum_29 <= acc_buf[29];
                    io_in_accum_30 <= acc_buf[30]; io_in_accum_31 <= acc_buf[31];

                    io_ctrl_use_accum <= 1'b1;
                    io_ctrl_keep      <= 1'b0;
                    io_ctrl_busy      <= 1'b1;
                    state             <= S_WAIT_CLCT;
                end

                // ----------------------------------------------------------
                // Wait for MMALU to finish (io_clct)
                // ----------------------------------------------------------
                S_WAIT_CLCT: begin
                    io_ctrl_busy <= 1'b0;
                    io_ctrl_keep <= 1'b1;
                    if (io_clct) begin
                        out_buf[0]  <= io_out_0;  out_buf[1]  <= io_out_1;
                        out_buf[2]  <= io_out_2;  out_buf[3]  <= io_out_3;
                        out_buf[4]  <= io_out_4;  out_buf[5]  <= io_out_5;
                        out_buf[6]  <= io_out_6;  out_buf[7]  <= io_out_7;
                        out_buf[8]  <= io_out_8;  out_buf[9]  <= io_out_9;
                        out_buf[10] <= io_out_10; out_buf[11] <= io_out_11;
                        out_buf[12] <= io_out_12; out_buf[13] <= io_out_13;
                        out_buf[14] <= io_out_14; out_buf[15] <= io_out_15;
                        out_buf[16] <= io_out_16; out_buf[17] <= io_out_17;
                        out_buf[18] <= io_out_18; out_buf[19] <= io_out_19;
                        out_buf[20] <= io_out_20; out_buf[21] <= io_out_21;
                        out_buf[22] <= io_out_22; out_buf[23] <= io_out_23;
                        out_buf[24] <= io_out_24; out_buf[25] <= io_out_25;
                        out_buf[26] <= io_out_26; out_buf[27] <= io_out_27;
                        out_buf[28] <= io_out_28; out_buf[29] <= io_out_29;
                        out_buf[30] <= io_out_30; out_buf[31] <= io_out_31;
                        io_ctrl_keep <= 1'b0;
                        state        <= S_WR_AW;
                        beat_cnt     <= 4'd0;
                    end
                end

                // ----------------------------------------------------------
                // Write result to DDR (128 B, 8 beats, ARLEN=7)
                //
                // V10 BUG FIX: the original S_WR_W had an off-by-one because
                // `m_axi_wdata` was only updated AFTER each accepted handshake
                // (via NB inside the IF branch). The very first beat sent
                // therefore carried the stale wdata value from the previous
                // burst (or from reset/initial), and the body's `bc*4` index
                // ran from 0..6 over 7 handshakes, but the 8th handshake (with
                // wlast=1) advanced wdata to `bc=7*4 = out_buf[28..31]` AFTER
                // its own wdata had already been latched (= out_buf[24..27]).
                // Net effect: out_buf[28..31] was never sent to DDR3, and the
                // host saw an off-by-one shift in OUT.
                //
                // The fix has two parts:
                //   (a) Pre-load m_axi_wdata with out_buf[0..3] AT the AW
                //       handshake (S_WR_AW → S_WR_W transition).
                //   (b) In S_WR_W's IF branch, advance the NEXT wdata to
                //       out_buf[(beat_cnt+1)*4 + 0..3] so that beat N carries
                //       out_buf[N*4 + 0..3] and the final beat (bc=7, wlast=1)
                //       sends out_buf[28..31] correctly.
                // ----------------------------------------------------------
                S_WR_AW: begin
                    m_axi_awaddr  <= DEFAULT_BASE_OUT;
                    m_axi_awlen   <= ARLEN_FULL;
                    m_axi_awvalid <= 1'b1;
                    if (m_axi_awready && m_axi_awvalid) begin
                        m_axi_awvalid <= 1'b0;
                        m_axi_wvalid  <= 1'b1;
                        m_axi_wlast   <= 1'b0;
                        // (a) Pre-load the FIRST beat data so that handshake 0
                        // in S_WR_W carries out_buf[0..3] (not stale data).
                        m_axi_wdata   <= {
                            out_buf[3], out_buf[2], out_buf[1], out_buf[0]
                        };
                        state         <= S_WR_W;
                    end
                end

                S_WR_W: begin
                    if (m_axi_wready && m_axi_wvalid) begin
                        // (b) Advance to NEXT beat's data for the upcoming
                        // handshake. Indexing (beat_cnt+1)*4 means after
                        // handshake N (bc=N), wdata is set up for beat N+1.
                        m_axi_wdata <= {
                            out_buf[(beat_cnt + 1)*4 + 3],
                            out_buf[(beat_cnt + 1)*4 + 2],
                            out_buf[(beat_cnt + 1)*4 + 1],
                            out_buf[(beat_cnt + 1)*4 + 0]
                        };
                        beat_cnt <= beat_cnt + 1;
                        if (beat_cnt == 4'd6)
                            m_axi_wlast <= 1'b1;
                        if (m_axi_wlast) begin
                            m_axi_wvalid <= 1'b0;
                            m_axi_bready <= 1'b1;
                            state        <= S_WR_B;
                        end
                    end
                    // No need for an else branch any more — wdata is
                    // pre-loaded in S_WR_AW and advanced lock-step with
                    // beat_cnt, so it stays at the correct beat value across
                    // any back-pressure cycle (wready=0).
                end

                S_WR_B: begin
                    if (m_axi_bvalid && m_axi_bready) begin
                        m_axi_bready <= 1'b0;
                        state        <= S_DONE;
                    end
                end

                S_DONE: begin
                    done  <= 1'b1;
                    busy  <= 1'b0;
                    state <= S_IDLE;
                end

                default: state <= S_IDLE;
            endcase
        end
    end

endmodule
