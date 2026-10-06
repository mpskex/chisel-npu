module ddr_test
(

   input                               axi_clk,
   input                               start,

(* MARK_DEBUG="true" *)   output                              c0_ddr4_aresetn,
(* MARK_DEBUG="true" *)   output  [3:0]                       c0_ddr4_s_axi_awid,
(* MARK_DEBUG="true" *)   output  [34:0]                      c0_ddr4_s_axi_awaddr,
(* MARK_DEBUG="true" *)   output  [7:0]                       c0_ddr4_s_axi_awlen,
(* MARK_DEBUG="true" *)   output  [2:0]                       c0_ddr4_s_axi_awsize,
(* MARK_DEBUG="true" *)   output  [1:0]                       c0_ddr4_s_axi_awburst,
(* MARK_DEBUG="true" *)   output  [0:0]                       c0_ddr4_s_axi_awlock,
(* MARK_DEBUG="true" *)   output  [3:0]                       c0_ddr4_s_axi_awcache,
(* MARK_DEBUG="true" *)   output  [2:0]                       c0_ddr4_s_axi_awprot,
(* MARK_DEBUG="true" *)   output  [3:0]                       c0_ddr4_s_axi_awqos,
(* MARK_DEBUG="true" *)   output                              c0_ddr4_s_axi_awvalid,
(* MARK_DEBUG="true" *)   input                               c0_ddr4_s_axi_awready,

(* MARK_DEBUG="true" *)   output  [511:0]                     c0_ddr4_s_axi_wdata,
(* MARK_DEBUG="true" *)   output  [63:0]                      c0_ddr4_s_axi_wstrb,
(* MARK_DEBUG="true" *)   output                              c0_ddr4_s_axi_wlast,
(* MARK_DEBUG="true" *)   output                              c0_ddr4_s_axi_wvalid,
(* MARK_DEBUG="true" *)   input                               c0_ddr4_s_axi_wready,

(* MARK_DEBUG="true" *)   output                              c0_ddr4_s_axi_bready,
(* MARK_DEBUG="true" *)   input [3:0]                         c0_ddr4_s_axi_bid,
(* MARK_DEBUG="true" *)   input [1:0]                         c0_ddr4_s_axi_bresp,
(* MARK_DEBUG="true" *)   input                               c0_ddr4_s_axi_bvalid,

(* MARK_DEBUG="true" *)   output  [3:0]                       c0_ddr4_s_axi_arid,
(* MARK_DEBUG="true" *)   output  [34:0]                      c0_ddr4_s_axi_araddr,
(* MARK_DEBUG="true" *)   output  [7:0]                       c0_ddr4_s_axi_arlen,
(* MARK_DEBUG="true" *)   output  [2:0]                       c0_ddr4_s_axi_arsize,
(* MARK_DEBUG="true" *)   output  [1:0]                       c0_ddr4_s_axi_arburst,
(* MARK_DEBUG="true" *)   output  [0:0]                       c0_ddr4_s_axi_arlock,
(* MARK_DEBUG="true" *)   output  [3:0]                       c0_ddr4_s_axi_arcache,
(* MARK_DEBUG="true" *)   output  [2:0]                       c0_ddr4_s_axi_arprot,
(* MARK_DEBUG="true" *)   output  [3:0]                       c0_ddr4_s_axi_arqos,
(* MARK_DEBUG="true" *)   output                              c0_ddr4_s_axi_arvalid,
(* MARK_DEBUG="true" *)   input                               c0_ddr4_s_axi_arready,

(* MARK_DEBUG="true" *)   output                              c0_ddr4_s_axi_rready,
(* MARK_DEBUG="true" *)   input [3:0]                         c0_ddr4_s_axi_rid,
(* MARK_DEBUG="true" *)   input [511:0]                       c0_ddr4_s_axi_rdata,
(* MARK_DEBUG="true" *)   input [1:0]                         c0_ddr4_s_axi_rresp,
(* MARK_DEBUG="true" *)   input                               c0_ddr4_s_axi_rlast,
(* MARK_DEBUG="true" *)   input                               c0_ddr4_s_axi_rvalid,

(* MARK_DEBUG="true" *)    output reg                         compare_err,
(* MARK_DEBUG="true" *)    output                             compare_finish

);


(* MARK_DEBUG="true" *)reg     start_d1 = 'd0;
reg     start_d2 = 'd0;

always @(posedge axi_clk) begin
    start_d1 <= start;
    start_d2 <= start_d1;
end

(* MARK_DEBUG="true" *)reg     [7:0]   burst_cnt = 'd0;
(* MARK_DEBUG="true" *)reg     [31:0]  burst_num = 'd0;

//  FSM
parameter  IDLE = 'd0;
parameter  START = 'd1;
parameter  AW_CH = 'd2;
parameter  W_CH = 'd3;
parameter  B_CH = 'd4;
parameter  W_END = 'd5;
parameter  AR_CH = 'd6;
parameter  R_CH = 'd7;
parameter  R_END = 'd8;
parameter  TOTAL_NUM = 'd256;

(* MARK_DEBUG="true" *)reg [3:0]   cur_state = 'd0;
(* MARK_DEBUG="true" *)reg [4:0]   nxt_state = 'd0;

always @(posedge axi_clk) begin
    cur_state <= nxt_state;
end

  
// sec 
always @(*) begin
    case (cur_state)
    IDLE:
        if(~start_d2 && start_d1)
            nxt_state = START;
        else 
            nxt_state = IDLE;
    START:
            nxt_state = AW_CH;
    AW_CH:
        if(c0_ddr4_s_axi_awvalid && c0_ddr4_s_axi_awready)
            nxt_state = W_CH;
        else
            nxt_state = AW_CH;
    W_CH:
        if(c0_ddr4_s_axi_wvalid && c0_ddr4_s_axi_wready && burst_cnt == 'd255)
            nxt_state = B_CH;
        else
            nxt_state = W_CH;
    B_CH:
        if(c0_ddr4_s_axi_bvalid && c0_ddr4_s_axi_bready && burst_num == TOTAL_NUM)
            nxt_state = W_END;
        else if(c0_ddr4_s_axi_bvalid && c0_ddr4_s_axi_bready)
            nxt_state = AW_CH;
        else 
            nxt_state = B_CH;
    W_END:
            nxt_state = AR_CH;
    AR_CH: 
        if(c0_ddr4_s_axi_arvalid && c0_ddr4_s_axi_arready)
            nxt_state = R_CH;
        else 
            nxt_state = AR_CH;
    R_CH:
        if(c0_ddr4_s_axi_rready && c0_ddr4_s_axi_rvalid && c0_ddr4_s_axi_rlast && burst_num == TOTAL_NUM)
            nxt_state = R_END;
        else  if(c0_ddr4_s_axi_rready && c0_ddr4_s_axi_rvalid && c0_ddr4_s_axi_rlast)
            nxt_state = AR_CH;
        else 
            nxt_state = R_CH;
        default: 
            nxt_state = IDLE;
    endcase
end

// third aw
(* MARK_DEBUG="true" *)reg [34:0]  axi_awaddr = 'd0;
// reg         axi_awvalid = 'd0;
reg    axi_awvalid  = 'd0;

assign c0_ddr4_s_axi_awid ='d0;
assign c0_ddr4_s_axi_awsize = 'd6;
assign c0_ddr4_s_axi_awlen = 'd255;
assign c0_ddr4_s_axi_awaddr = axi_awaddr;
assign c0_ddr4_s_axi_awburst = 'b1;
assign c0_ddr4_s_axi_awlock = 'd0;
assign c0_ddr4_s_axi_awcache = 'd0;
assign c0_ddr4_s_axi_awprot = 'd0;
assign c0_ddr4_s_axi_awqos = 'd0;
assign c0_ddr4_s_axi_awvalid = axi_awvalid;

always @(posedge axi_clk) begin
    if(cur_state == AW_CH &&  c0_ddr4_s_axi_awready && axi_awvalid)
        axi_awvalid <= 'd0; 
    else if(cur_state == AW_CH)
        axi_awvalid <= 'd1;
    else 
        axi_awvalid <= 'd0;   
end

always @(posedge axi_clk) begin
    axi_awaddr[34:14] <= burst_num;
    axi_awaddr[13:0] <= 'd0;
end

always @(posedge axi_clk) begin
    if(cur_state == START || cur_state == W_END)
        burst_num <= 'd0;
    else if((c0_ddr4_s_axi_bvalid && c0_ddr4_s_axi_bready) || (c0_ddr4_s_axi_rready && c0_ddr4_s_axi_rvalid && c0_ddr4_s_axi_rlast))
        burst_num <= burst_num + 1;
    else 
        burst_num <= burst_num;
end

always @(posedge axi_clk) begin
    if(cur_state == AW_CH || cur_state == AR_CH)
        burst_cnt <= 'd0;
    else if((c0_ddr4_s_axi_wvalid && c0_ddr4_s_axi_wready) || (c0_ddr4_s_axi_rready && c0_ddr4_s_axi_rvalid))
        burst_cnt <= burst_cnt + 'd1;
    else    
        burst_cnt <= burst_cnt;
end

// W_CH
(* MARK_DEBUG="true" *)reg        axi_wlast = 'd0;

always @(posedge axi_clk) begin
    if(cur_state == AW_CH )
        axi_wlast <= 'd0;
    else if(c0_ddr4_s_axi_wvalid && c0_ddr4_s_axi_wready && burst_cnt == 'd254)
        axi_wlast <= 'd1;
    else if(c0_ddr4_s_axi_wvalid && c0_ddr4_s_axi_wready && burst_cnt == 'd255)
        axi_wlast <= 'd0;
    else 
        axi_wlast <= axi_wlast;
end

reg    axi_wvalid = 'd0;
assign c0_ddr4_s_axi_wdata = {burst_num[21:0],{14{burst_num[20:0],burst_cnt,6'd0}}};
assign c0_ddr4_s_axi_wstrb = {64{1'b1}};
assign c0_ddr4_s_axi_wlast = axi_wlast;
assign c0_ddr4_s_axi_wvalid = axi_wvalid;

always @(posedge axi_clk) begin
    if(cur_state == W_CH && axi_wvalid && c0_ddr4_s_axi_wready && burst_cnt == 'd255)
        axi_wvalid <= 'd0;
    else if(cur_state == W_CH)
        axi_wvalid <= 'd1;
    else 
        axi_wvalid <= 'd0;
end
// B CH
reg     axi_bready = 'd0;
assign c0_ddr4_s_axi_bready = axi_bready;

always @(posedge axi_clk) begin
    if(cur_state == B_CH && axi_bready && c0_ddr4_s_axi_bvalid)
        axi_bready <= 'd0;
    else if (cur_state == B_CH) 
        axi_bready <= 'd1;
    else 
        axi_bready <= 'd0;
end

// AR CH
assign c0_ddr4_s_axi_arid = 'd0;

(* MARK_DEBUG="true" *)reg [34:0]  axi_araddr = 'd0;

always @(posedge axi_clk) begin
    axi_araddr[34:14] <= burst_num;
    axi_araddr[13:0] <= 'd0;
end

reg    s_axi_arvalid = 'd0;
assign c0_ddr4_s_axi_araddr = axi_araddr;
assign c0_ddr4_s_axi_arlen = 'd255;
assign c0_ddr4_s_axi_arsize = 'd6;
assign c0_ddr4_s_axi_arburst = 'b1;
assign c0_ddr4_s_axi_arlock  ='d0;
assign c0_ddr4_s_axi_arcache = 'd0;
assign c0_ddr4_s_axi_arprot = 'd0;
assign c0_ddr4_s_axi_arqos = 'd0;
assign c0_ddr4_s_axi_arqos  = 'd1;
assign c0_ddr4_s_axi_arvalid = s_axi_arvalid;
assign c0_ddr4_s_axi_rready = 'd1;

always @(posedge axi_clk) begin
    if(cur_state == AR_CH && c0_ddr4_s_axi_arready && c0_ddr4_s_axi_arvalid)
        s_axi_arvalid <= 'd0;
    else if(cur_state == AR_CH && c0_ddr4_s_axi_arready)
        s_axi_arvalid <= 'd1;
    else 
        s_axi_arvalid <= 'd0;
end

// RD_CH
(* MARK_DEBUG="true" *)reg [511:0] expect_data;
(* MARK_DEBUG="true" *)reg [7:0]  burst_cnt_r='d0;

always @(posedge axi_clk) begin
    if(cur_state == AR_CH)
        burst_cnt_r <= 'd0;
    else if(c0_ddr4_s_axi_rready && c0_ddr4_s_axi_rvalid)    
        burst_cnt_r <= burst_cnt_r + 1'd1;
    else 
        burst_cnt_r <= burst_cnt_r;
end
always @(posedge axi_clk) begin
    // expect_data_32[31:14] <= burst_num;
    // expect_data_32[13:6] <= burst_cnt_r;
    // expect_data_32[5:0] <= 'd0;
    expect_data <= {burst_num[21:0],{14{burst_num[20:0],burst_cnt_r,6'd0}}}; 
end

reg [511:0] c0_ddr4_s_axi_rdata_d1 = 'd0;
reg         c0_ddr4_s_axi_rvalid_d1 ='d0;

always @(posedge axi_clk) begin
    c0_ddr4_s_axi_rvalid_d1 <= c0_ddr4_s_axi_rvalid;
end
always @(posedge axi_clk) begin
    if(c0_ddr4_s_axi_rready && c0_ddr4_s_axi_rvalid)
        c0_ddr4_s_axi_rdata_d1 <= c0_ddr4_s_axi_rdata;
    else 
        c0_ddr4_s_axi_rdata_d1 <= c0_ddr4_s_axi_rdata_d1;
end

always @(posedge axi_clk)
begin
    if(c0_ddr4_s_axi_rvalid_d1)
        compare_err <=  (c0_ddr4_s_axi_rdata_d1 !=expect_data);
    else
        compare_err <= 'd0;
end

endmodule