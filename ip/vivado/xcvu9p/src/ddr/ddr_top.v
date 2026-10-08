module ddr_top
(
    input           axi_clk,
    input           axi_rst,

    input           m_axi_awready,
    output  [3 : 0] m_axi_awid,
    output  [63 : 0]m_axi_awaddr,
    output  [31 : 0]m_axi_awuser,
    output  [7 : 0] m_axi_awlen,
    output  [2 : 0] m_axi_awsize,
    output  [1 : 0] m_axi_awburst,
    output  [2 : 0] m_axi_awprot,
    output          m_axi_awvalid,
    output          m_axi_awlock,
    output  [3 : 0] m_axi_awcache,

    input           m_axi_wready,
    output  [511:0] m_axi_wdata,
    output  [63 :0] m_axi_wuser,
    output  [63 :0] m_axi_wstrb,
    output          m_axi_wlast,
    output          m_axi_wvalid,

    input  [3 : 0]  m_axi_bid,
    input  [1 : 0]  m_axi_bresp,
    input           m_axi_bvalid,
    output          m_axi_bready,

    input           m_axi_arready,
    output  [3 : 0] m_axi_arid,
    output  [63 : 0]m_axi_araddr,
    output  [31 : 0]m_axi_aruser,
    output  [7 : 0] m_axi_arlen,
    output  [2 : 0] m_axi_arsize,
    output  [1 : 0] m_axi_arburst,
    output  [2 : 0] m_axi_arprot,
    output          m_axi_arvalid,
    output          m_axi_arlock,
    output  [3 : 0] m_axi_arcache, 

    input  [3 : 0]  m_axi_rid,
    input  [511:0]  m_axi_rdata,
    input  [1 : 0]  m_axi_rresp,
    input           m_axi_rlast,
    input           m_axi_rvalid,
    output          m_axi_rready,

//  user 

    output           m_axi_user_awready,
    input  [3 : 0]   m_axi_user_awid,
    input  [63 : 0]  m_axi_user_awaddr,
    input  [31 : 0]  m_axi_user_awuser,
    input  [7 : 0]   m_axi_user_awlen,
    input  [2 : 0]   m_axi_user_awsize,
    input  [1 : 0]   m_axi_user_awburst,
    input  [2 : 0]   m_axi_user_awprot,
    input            m_axi_user_awvalid,
    input            m_axi_user_awlock,
    input  [3 : 0]   m_axi_user_awcache,

    output           m_axi_user_wready,
    input  [511:0]   m_axi_user_wdata,
    input  [63 :0]   m_axi_user_wuser,
    input  [63 :0]   m_axi_user_wstrb,
    input            m_axi_user_wlast,
    input            m_axi_user_wvalid,

    output  [3 : 0]  m_axi_user_bid,
    output  [1 : 0]  m_axi_user_bresp,
    output           m_axi_user_bvalid,
    input            m_axi_user_bready,

    output           m_axi_user_arready,
    input  [3 : 0]   m_axi_user_arid,
    input  [63 : 0]  m_axi_user_araddr,
    input  [31 : 0]  m_axi_user_aruser,
    input  [7 : 0]   m_axi_user_arlen,
    input  [2 : 0]   m_axi_user_arsize,
    input  [1 : 0]   m_axi_user_arburst,
    input  [2 : 0]   m_axi_user_arprot,
    input            m_axi_user_arvalid,
    input            m_axi_user_arlock,
    input  [3 : 0]   m_axi_user_arcache, 

    output  [3 : 0]  m_axi_user_rid,
    output  [511:0]  m_axi_user_rdata,
    output  [1 : 0]  m_axi_user_rresp,
    output           m_axi_user_rlast,
    output           m_axi_user_rvalid,
    input            m_axi_user_rready,

    input           c0_sys_clk_p,
    input           c0_sys_clk_n,
    output          c0_ddr4_act_n,
    output [16:0]   c0_ddr4_adr,
    output [1:0]    c0_ddr4_ba,
    output [1:0]    c0_ddr4_bg,
    output [0:0]    c0_ddr4_cke,
    output [0:0]    c0_ddr4_odt,
    output [0:0]    c0_ddr4_cs_n,
    output [0:0]    c0_ddr4_ck_t,
    output [0:0]    c0_ddr4_ck_c,
    output          c0_ddr4_reset_n,
    inout  [8:0]    c0_ddr4_dm_dbi_n,
    inout  [71:0]   c0_ddr4_dq,
    inout  [8:0]    c0_ddr4_dqs_c,
    inout  [8:0]    c0_ddr4_dqs_t,

    input           c1_sys_clk_p,
    input           c1_sys_clk_n,
    output          c1_ddr4_act_n,
    output [16:0]   c1_ddr4_adr,
    output [1:0]    c1_ddr4_ba,
    output [1:0]    c1_ddr4_bg,
    output [0:0]    c1_ddr4_cke,
    output [0:0]    c1_ddr4_odt,
    output [0:0]    c1_ddr4_cs_n,
    output [0:0]    c1_ddr4_ck_t,
    output [0:0]    c1_ddr4_ck_c,
    output          c1_ddr4_reset_n,
    inout  [8:0]    c1_ddr4_dm_dbi_n,
    inout  [71:0]   c1_ddr4_dq,
    inout  [8:0]    c1_ddr4_dqs_c,
    inout  [8:0]    c1_ddr4_dqs_t,

    input           c2_sys_clk_p,
    input           c2_sys_clk_n,
    output          c2_ddr4_act_n,
    output [16:0]   c2_ddr4_adr,
    output [1:0]    c2_ddr4_ba,
    output [1:0]    c2_ddr4_bg,
    output [0:0]    c2_ddr4_cke,
    output [0:0]    c2_ddr4_odt,
    output [0:0]    c2_ddr4_cs_n,
    output [0:0]    c2_ddr4_ck_t,
    output [0:0]    c2_ddr4_ck_c,
    output          c2_ddr4_reset_n,
    inout  [8:0]    c2_ddr4_dm_dbi_n,
    inout  [71:0]   c2_ddr4_dq,
    inout  [8:0]    c2_ddr4_dqs_c,
    inout  [8:0]    c2_ddr4_dqs_t,

    input           c3_sys_clk_p,
    input           c3_sys_clk_n,
    output          c3_ddr4_act_n,
    output [16:0]   c3_ddr4_adr,
    output [1:0]    c3_ddr4_ba,
    output [1:0]    c3_ddr4_bg,
    output [0:0]    c3_ddr4_cke,
    output [0:0]    c3_ddr4_odt,
    output [0:0]    c3_ddr4_cs_n,
    output [0:0]    c3_ddr4_ck_t,
    output [0:0]    c3_ddr4_ck_c,
    output          c3_ddr4_reset_n,
    inout  [8:0]    c3_ddr4_dm_dbi_n,
    inout  [71:0]   c3_ddr4_dq,
    inout  [8:0]    c3_ddr4_dqs_c,
    inout  [8:0]    c3_ddr4_dqs_t
);

    wire  [3:0]     c0_ddr4_s_axi_awid  ;
    wire  [31:0]    c0_ddr4_s_axi_awaddr;
    wire  [7:0]     c0_ddr4_s_axi_awlen ;
    wire  [2:0]     c0_ddr4_s_axi_awsize;
    wire  [1:0]     c0_ddr4_s_axi_awburst;
    wire  [0:0]     c0_ddr4_s_axi_awlock;
    wire  [3:0]     c0_ddr4_s_axi_awcache;
    wire  [2:0]     c0_ddr4_s_axi_awprot;
    wire  [3:0]     c0_ddr4_s_axi_awqos;
    wire            c0_ddr4_s_axi_awvalid;
    wire            c0_ddr4_s_axi_awready;

    wire  [511:0]   c0_ddr4_s_axi_wdata;
    wire  [63:0]    c0_ddr4_s_axi_wstrb;
    wire            c0_ddr4_s_axi_wlast;
    wire            c0_ddr4_s_axi_wvalid;
    wire            c0_ddr4_s_axi_wready;

    wire            c0_ddr4_s_axi_bready;
    wire [3:0]      c0_ddr4_s_axi_bid;
    wire [1:0]      c0_ddr4_s_axi_bresp;
    wire            c0_ddr4_s_axi_bvalid;

   wire  [3:0]      c0_ddr4_s_axi_arid;
   wire  [31:0]     c0_ddr4_s_axi_araddr;
   wire  [7:0]      c0_ddr4_s_axi_arlen;
   wire  [2:0]      c0_ddr4_s_axi_arsize;
   wire  [1:0]      c0_ddr4_s_axi_arburst;
   wire  [0:0]      c0_ddr4_s_axi_arlock;
   wire  [3:0]      c0_ddr4_s_axi_arcache;
   wire  [2:0]      c0_ddr4_s_axi_arprot;
   wire  [3:0]      c0_ddr4_s_axi_arqos;
   wire             c0_ddr4_s_axi_arvalid;
   wire             c0_ddr4_s_axi_arready;

   wire             c0_ddr4_s_axi_rready;
   wire [3:0]       c0_ddr4_s_axi_rid;
   wire [511:0]     c0_ddr4_s_axi_rdata;
   wire [1:0]       c0_ddr4_s_axi_rresp;
   wire             c0_ddr4_s_axi_rlast;
   wire             c0_ddr4_s_axi_rvalid;

wire    c0_ddr4_ui_clk;
wire    c0_ddr4_ui_clk_sync_rst;
ddr4_0 ddr4_c0
(
  .sys_rst                  ('d0                    ),
  .c0_sys_clk_p             (c0_sys_clk_p           ),
  .c0_sys_clk_n             (c0_sys_clk_n           ),
  .c0_ddr4_act_n            (c0_ddr4_act_n          ),
  .c0_ddr4_adr              (c0_ddr4_adr            ),
  .c0_ddr4_ba               (c0_ddr4_ba             ),
  .c0_ddr4_bg               (c0_ddr4_bg             ),
  .c0_ddr4_cke              (c0_ddr4_cke            ),
  .c0_ddr4_odt              (c0_ddr4_odt            ),
  .c0_ddr4_cs_n             (c0_ddr4_cs_n           ),
  .c0_ddr4_ck_t             (c0_ddr4_ck_t           ),
  .c0_ddr4_ck_c             (c0_ddr4_ck_c           ),
  .c0_ddr4_reset_n          (c0_ddr4_reset_n        ),
  .c0_ddr4_dm_dbi_n         (c0_ddr4_dm_dbi_n       ),
  .c0_ddr4_dq               (c0_ddr4_dq             ),
  .c0_ddr4_dqs_c            (c0_ddr4_dqs_c          ),
  .c0_ddr4_dqs_t            (c0_ddr4_dqs_t          ),

  .c0_ddr4_s_axi_ctrl_awvalid(1'b0),
  .c0_ddr4_s_axi_ctrl_awready(),
  .c0_ddr4_s_axi_ctrl_awaddr (32'd0),
  .c0_ddr4_s_axi_ctrl_wvalid(1'b0),
  .c0_ddr4_s_axi_ctrl_wready(),
  .c0_ddr4_s_axi_ctrl_wdata(32'd0),
  .c0_ddr4_s_axi_ctrl_bvalid(),
  .c0_ddr4_s_axi_ctrl_bready(1'b1),
  .c0_ddr4_s_axi_ctrl_bresp(),
  .c0_ddr4_s_axi_ctrl_arvalid(1'b0),
  .c0_ddr4_s_axi_ctrl_arready(),
  .c0_ddr4_s_axi_ctrl_araddr(31'd0),
  .c0_ddr4_s_axi_ctrl_rvalid(),
  .c0_ddr4_s_axi_ctrl_rready(1'b1),
  .c0_ddr4_s_axi_ctrl_rdata(),
  .c0_ddr4_s_axi_ctrl_rresp(),


  .c0_init_calib_complete   (                           ),
  .c0_ddr4_ui_clk           (c0_ddr4_ui_clk             ),
  .c0_ddr4_ui_clk_sync_rst  (c0_ddr4_ui_clk_sync_rst    ),
  .dbg_clk                  (),
  
  .c0_ddr4_interrupt        (),
  .c0_ddr4_aresetn          (~c0_ddr4_ui_clk_sync_rst   ),
  .c0_ddr4_s_axi_awid       (c0_ddr4_s_axi_awid         ),
  .c0_ddr4_s_axi_awaddr     (c0_ddr4_s_axi_awaddr       ),
  .c0_ddr4_s_axi_awlen      (c0_ddr4_s_axi_awlen        ),
  .c0_ddr4_s_axi_awsize     (c0_ddr4_s_axi_awsize       ),
  .c0_ddr4_s_axi_awburst    (c0_ddr4_s_axi_awburst      ),
  .c0_ddr4_s_axi_awlock     (c0_ddr4_s_axi_awlock       ),
  .c0_ddr4_s_axi_awcache    (c0_ddr4_s_axi_awcache      ),
  .c0_ddr4_s_axi_awprot     (c0_ddr4_s_axi_awprot       ),
  .c0_ddr4_s_axi_awqos      (c0_ddr4_s_axi_awqos        ),
  .c0_ddr4_s_axi_awvalid    (c0_ddr4_s_axi_awvalid      ),
  .c0_ddr4_s_axi_awready    (c0_ddr4_s_axi_awready      ),
  .c0_ddr4_s_axi_wdata      (c0_ddr4_s_axi_wdata        ),
  .c0_ddr4_s_axi_wstrb      (c0_ddr4_s_axi_wstrb        ),
  .c0_ddr4_s_axi_wlast      (c0_ddr4_s_axi_wlast        ),
  .c0_ddr4_s_axi_wvalid     (c0_ddr4_s_axi_wvalid       ),
  .c0_ddr4_s_axi_wready     (c0_ddr4_s_axi_wready       ),
   // Slave Interface Write Response Ports
  .c0_ddr4_s_axi_bready     (c0_ddr4_s_axi_bready       ),
  .c0_ddr4_s_axi_bid        (c0_ddr4_s_axi_bid          ),
  .c0_ddr4_s_axi_bresp      (c0_ddr4_s_axi_bresp        ),
  .c0_ddr4_s_axi_bvalid     (c0_ddr4_s_axi_bvalid       ),

  .c0_ddr4_s_axi_arid       (c0_ddr4_s_axi_arid         ),
  .c0_ddr4_s_axi_araddr     (c0_ddr4_s_axi_araddr       ),
  .c0_ddr4_s_axi_arlen      (c0_ddr4_s_axi_arlen        ),
  .c0_ddr4_s_axi_arsize     (c0_ddr4_s_axi_arsize       ),
  .c0_ddr4_s_axi_arburst    (c0_ddr4_s_axi_arburst      ),
  .c0_ddr4_s_axi_arlock     (c0_ddr4_s_axi_arlock       ),
  .c0_ddr4_s_axi_arcache    (c0_ddr4_s_axi_arcache      ),
  .c0_ddr4_s_axi_arprot     (c0_ddr4_s_axi_arprot       ),
  .c0_ddr4_s_axi_arqos      (c0_ddr4_s_axi_arqos        ),
  .c0_ddr4_s_axi_arvalid    (c0_ddr4_s_axi_arvalid      ),
  .c0_ddr4_s_axi_arready    (c0_ddr4_s_axi_arready      ),
   // Slave Interface Read Data Ports
  .c0_ddr4_s_axi_rready     (c0_ddr4_s_axi_rready       ),
  .c0_ddr4_s_axi_rid        (c0_ddr4_s_axi_rid          ),
  .c0_ddr4_s_axi_rdata      (c0_ddr4_s_axi_rdata        ),
  .c0_ddr4_s_axi_rresp      (c0_ddr4_s_axi_rresp        ),
  .c0_ddr4_s_axi_rlast      (c0_ddr4_s_axi_rlast        ),
  .c0_ddr4_s_axi_rvalid     (c0_ddr4_s_axi_rvalid       ),
  .dbg_bus                  (                           )
);

// c1

wire  [3:0]     c1_ddr4_s_axi_awid  ;
wire  [31:0]    c1_ddr4_s_axi_awaddr;
wire  [7:0]     c1_ddr4_s_axi_awlen ;
wire  [2:0]     c1_ddr4_s_axi_awsize;
wire  [1:0]     c1_ddr4_s_axi_awburst;
wire  [0:0]     c1_ddr4_s_axi_awlock;
wire  [3:0]     c1_ddr4_s_axi_awcache;
wire  [2:0]     c1_ddr4_s_axi_awprot;
wire  [3:0]     c1_ddr4_s_axi_awqos;
wire            c1_ddr4_s_axi_awvalid;
wire            c1_ddr4_s_axi_awready;

wire  [511:0]   c1_ddr4_s_axi_wdata;
wire  [63:0]    c1_ddr4_s_axi_wstrb;
wire            c1_ddr4_s_axi_wlast;
wire            c1_ddr4_s_axi_wvalid;
wire            c1_ddr4_s_axi_wready;

wire            c1_ddr4_s_axi_bready;
wire [3:0]      c1_ddr4_s_axi_bid;
wire [1:0]      c1_ddr4_s_axi_bresp;
wire            c1_ddr4_s_axi_bvalid;

wire  [3:0]     c1_ddr4_s_axi_arid;
wire  [31:0]    c1_ddr4_s_axi_araddr;
wire  [7:0]     c1_ddr4_s_axi_arlen;
wire  [2:0]     c1_ddr4_s_axi_arsize;
wire  [1:0]     c1_ddr4_s_axi_arburst;
wire  [0:0]     c1_ddr4_s_axi_arlock;
wire  [3:0]     c1_ddr4_s_axi_arcache;
wire  [2:0]     c1_ddr4_s_axi_arprot;
wire  [3:0]     c1_ddr4_s_axi_arqos;
wire            c1_ddr4_s_axi_arvalid;
wire            c1_ddr4_s_axi_arready;

wire            c1_ddr4_s_axi_rready;
wire [3:0]      c1_ddr4_s_axi_rid;
wire [511:0]    c1_ddr4_s_axi_rdata;
wire [1:0]      c1_ddr4_s_axi_rresp;
wire            c1_ddr4_s_axi_rlast;
wire            c1_ddr4_s_axi_rvalid;

wire    c1_ddr4_ui_clk;
wire    c1_ddr4_ui_clk_sync_rst;
ddr4_0 ddr4_c1
(
  .sys_rst                  ('d0                      ),
  .c0_sys_clk_p             (c1_sys_clk_p             ),
  .c0_sys_clk_n             (c1_sys_clk_n             ),
  .c0_ddr4_act_n            (c1_ddr4_act_n            ),
  .c0_ddr4_adr              (c1_ddr4_adr              ),
  .c0_ddr4_ba               (c1_ddr4_ba               ),
  .c0_ddr4_bg               (c1_ddr4_bg               ),
  .c0_ddr4_cke              (c1_ddr4_cke              ),
  .c0_ddr4_odt              (c1_ddr4_odt              ),
  .c0_ddr4_cs_n             (c1_ddr4_cs_n             ),
  .c0_ddr4_ck_t             (c1_ddr4_ck_t             ),
  .c0_ddr4_ck_c             (c1_ddr4_ck_c             ),
  .c0_ddr4_reset_n          (c1_ddr4_reset_n          ),
  .c0_ddr4_dm_dbi_n         (c1_ddr4_dm_dbi_n         ),
  .c0_ddr4_dq               (c1_ddr4_dq               ),
  .c0_ddr4_dqs_c            (c1_ddr4_dqs_c            ),
  .c0_ddr4_dqs_t            (c1_ddr4_dqs_t            ),

  .c0_ddr4_s_axi_ctrl_awvalid(1'b0),
  .c0_ddr4_s_axi_ctrl_awready(),
  .c0_ddr4_s_axi_ctrl_awaddr (32'd0),
  .c0_ddr4_s_axi_ctrl_wvalid(1'b0),
  .c0_ddr4_s_axi_ctrl_wready(),
  .c0_ddr4_s_axi_ctrl_wdata(32'd0),
  .c0_ddr4_s_axi_ctrl_bvalid(),
  .c0_ddr4_s_axi_ctrl_bready(1'b1),
  .c0_ddr4_s_axi_ctrl_bresp(),
  .c0_ddr4_s_axi_ctrl_arvalid(1'b0),
  .c0_ddr4_s_axi_ctrl_arready(),
  .c0_ddr4_s_axi_ctrl_araddr(31'd0),
  .c0_ddr4_s_axi_ctrl_rvalid(),
  .c0_ddr4_s_axi_ctrl_rready(1'b1),
  .c0_ddr4_s_axi_ctrl_rdata(),
  .c0_ddr4_s_axi_ctrl_rresp(),

  .c0_init_calib_complete   (),
  .c0_ddr4_ui_clk           (c1_ddr4_ui_clk           ),
  .c0_ddr4_ui_clk_sync_rst  (c1_ddr4_ui_clk_sync_rst  ),
  .dbg_clk                  (),
  
  .c0_ddr4_interrupt        (),
  .c0_ddr4_aresetn          (~c1_ddr4_ui_clk_sync_rst ),
  .c0_ddr4_s_axi_awid       (c1_ddr4_s_axi_awid       ),
  .c0_ddr4_s_axi_awaddr     (c1_ddr4_s_axi_awaddr     ),
  .c0_ddr4_s_axi_awlen      (c1_ddr4_s_axi_awlen      ),
  .c0_ddr4_s_axi_awsize     (c1_ddr4_s_axi_awsize     ),
  .c0_ddr4_s_axi_awburst    (c1_ddr4_s_axi_awburst    ),
  .c0_ddr4_s_axi_awlock     (c1_ddr4_s_axi_awlock     ),
  .c0_ddr4_s_axi_awcache    (c1_ddr4_s_axi_awcache    ),
  .c0_ddr4_s_axi_awprot     (c1_ddr4_s_axi_awprot     ),
  .c0_ddr4_s_axi_awqos      (c1_ddr4_s_axi_awqos      ),
  .c0_ddr4_s_axi_awvalid    (c1_ddr4_s_axi_awvalid    ),
  .c0_ddr4_s_axi_awready    (c1_ddr4_s_axi_awready    ),
  .c0_ddr4_s_axi_wdata      (c1_ddr4_s_axi_wdata      ),
  .c0_ddr4_s_axi_wstrb      (c1_ddr4_s_axi_wstrb      ),
  .c0_ddr4_s_axi_wlast      (c1_ddr4_s_axi_wlast      ),
  .c0_ddr4_s_axi_wvalid     (c1_ddr4_s_axi_wvalid     ),
  .c0_ddr4_s_axi_wready     (c1_ddr4_s_axi_wready     ),
   // Slave Interface Write Response Ports
  .c0_ddr4_s_axi_bready     (c1_ddr4_s_axi_bready     ),
  .c0_ddr4_s_axi_bid        (c1_ddr4_s_axi_bid        ),
  .c0_ddr4_s_axi_bresp      (c1_ddr4_s_axi_bresp      ),
  .c0_ddr4_s_axi_bvalid     (c1_ddr4_s_axi_bvalid     ),

  .c0_ddr4_s_axi_arid       (c1_ddr4_s_axi_arid       ),
  .c0_ddr4_s_axi_araddr     (c1_ddr4_s_axi_araddr     ),
  .c0_ddr4_s_axi_arlen      (c1_ddr4_s_axi_arlen      ),
  .c0_ddr4_s_axi_arsize     (c1_ddr4_s_axi_arsize     ),
  .c0_ddr4_s_axi_arburst    (c1_ddr4_s_axi_arburst    ),
  .c0_ddr4_s_axi_arlock     (c1_ddr4_s_axi_arlock     ),
  .c0_ddr4_s_axi_arcache    (c1_ddr4_s_axi_arcache    ),
  .c0_ddr4_s_axi_arprot     (c1_ddr4_s_axi_arprot     ),
  .c0_ddr4_s_axi_arqos      (c1_ddr4_s_axi_arqos      ),
  .c0_ddr4_s_axi_arvalid    (c1_ddr4_s_axi_arvalid    ),
  .c0_ddr4_s_axi_arready    (c1_ddr4_s_axi_arready    ),
   // Slave Interface Read Data Ports
  .c0_ddr4_s_axi_rready     (c1_ddr4_s_axi_rready     ),
  .c0_ddr4_s_axi_rid        (c1_ddr4_s_axi_rid        ),
  .c0_ddr4_s_axi_rdata      (c1_ddr4_s_axi_rdata      ),
  .c0_ddr4_s_axi_rresp      (c1_ddr4_s_axi_rresp      ),
  .c0_ddr4_s_axi_rlast      (c1_ddr4_s_axi_rlast      ),
  .c0_ddr4_s_axi_rvalid     (c1_ddr4_s_axi_rvalid     ),
  .dbg_bus                  ()
);

// c2
wire  [3:0]     c2_ddr4_s_axi_awid  ;
wire  [31:0]    c2_ddr4_s_axi_awaddr;
wire  [7:0]     c2_ddr4_s_axi_awlen ;
wire  [2:0]     c2_ddr4_s_axi_awsize;
wire  [1:0]     c2_ddr4_s_axi_awburst;
wire  [0:0]     c2_ddr4_s_axi_awlock;
wire  [3:0]     c2_ddr4_s_axi_awcache;
wire  [2:0]     c2_ddr4_s_axi_awprot;
wire  [3:0]     c2_ddr4_s_axi_awqos;
wire            c2_ddr4_s_axi_awvalid;
wire            c2_ddr4_s_axi_awready;

wire  [511:0]   c2_ddr4_s_axi_wdata;
wire  [63:0]    c2_ddr4_s_axi_wstrb;
wire            c2_ddr4_s_axi_wlast;
wire            c2_ddr4_s_axi_wvalid;
wire            c2_ddr4_s_axi_wready;

wire            c2_ddr4_s_axi_bready;
wire [3:0]      c2_ddr4_s_axi_bid;
wire [1:0]      c2_ddr4_s_axi_bresp;
wire            c2_ddr4_s_axi_bvalid;

wire  [3:0]     c2_ddr4_s_axi_arid;
wire  [31:0]    c2_ddr4_s_axi_araddr;
wire  [7:0]     c2_ddr4_s_axi_arlen;
wire  [2:0]     c2_ddr4_s_axi_arsize;
wire  [1:0]     c2_ddr4_s_axi_arburst;
wire  [0:0]     c2_ddr4_s_axi_arlock;
wire  [3:0]     c2_ddr4_s_axi_arcache;
wire  [2:0]     c2_ddr4_s_axi_arprot;
wire  [3:0]     c2_ddr4_s_axi_arqos;
wire            c2_ddr4_s_axi_arvalid;
wire            c2_ddr4_s_axi_arready;

wire            c2_ddr4_s_axi_rready;
wire [3:0]      c2_ddr4_s_axi_rid;
wire [511:0]    c2_ddr4_s_axi_rdata;
wire [1:0]      c2_ddr4_s_axi_rresp;
wire            c2_ddr4_s_axi_rlast;
wire            c2_ddr4_s_axi_rvalid;

wire    c2_ddr4_ui_clk;
wire    c2_ddr4_ui_clk_sync_rst;
ddr4_0 ddr4_c2
(
  .sys_rst                  ('d0                      ),
  .c0_sys_clk_p             (c2_sys_clk_p             ),
  .c0_sys_clk_n             (c2_sys_clk_n             ),
  .c0_ddr4_act_n            (c2_ddr4_act_n            ),
  .c0_ddr4_adr              (c2_ddr4_adr              ),
  .c0_ddr4_ba               (c2_ddr4_ba               ),
  .c0_ddr4_bg               (c2_ddr4_bg               ),
  .c0_ddr4_cke              (c2_ddr4_cke              ),
  .c0_ddr4_odt              (c2_ddr4_odt              ),
  .c0_ddr4_cs_n             (c2_ddr4_cs_n             ),
  .c0_ddr4_ck_t             (c2_ddr4_ck_t             ),
  .c0_ddr4_ck_c             (c2_ddr4_ck_c             ),
  .c0_ddr4_reset_n          (c2_ddr4_reset_n          ),
  .c0_ddr4_dm_dbi_n         (c2_ddr4_dm_dbi_n         ),
  .c0_ddr4_dq               (c2_ddr4_dq               ),
  .c0_ddr4_dqs_c            (c2_ddr4_dqs_c            ),
  .c0_ddr4_dqs_t            (c2_ddr4_dqs_t            ),

  .c0_init_calib_complete   (),
  .c0_ddr4_ui_clk           (c2_ddr4_ui_clk           ),
  .c0_ddr4_ui_clk_sync_rst  (c2_ddr4_ui_clk_sync_rst  ),
  .dbg_clk                  (),
  
  .c0_ddr4_s_axi_ctrl_awvalid(1'b0),
  .c0_ddr4_s_axi_ctrl_awready(),
  .c0_ddr4_s_axi_ctrl_awaddr (32'd0),
  .c0_ddr4_s_axi_ctrl_wvalid(1'b0),
  .c0_ddr4_s_axi_ctrl_wready(),
  .c0_ddr4_s_axi_ctrl_wdata(32'd0),
  .c0_ddr4_s_axi_ctrl_bvalid(),
  .c0_ddr4_s_axi_ctrl_bready(1'b1),
  .c0_ddr4_s_axi_ctrl_bresp(),
  .c0_ddr4_s_axi_ctrl_arvalid(1'b0),
  .c0_ddr4_s_axi_ctrl_arready(),
  .c0_ddr4_s_axi_ctrl_araddr(31'd0),
  .c0_ddr4_s_axi_ctrl_rvalid(),
  .c0_ddr4_s_axi_ctrl_rready(1'b1),
  .c0_ddr4_s_axi_ctrl_rdata(),
  .c0_ddr4_s_axi_ctrl_rresp(),

  .c0_ddr4_interrupt        (),
  .c0_ddr4_aresetn          (~c2_ddr4_ui_clk_sync_rst),
  .c0_ddr4_s_axi_awid       (c2_ddr4_s_axi_awid      ),
  .c0_ddr4_s_axi_awaddr     (c2_ddr4_s_axi_awaddr    ),
  .c0_ddr4_s_axi_awlen      (c2_ddr4_s_axi_awlen     ),
  .c0_ddr4_s_axi_awsize     (c2_ddr4_s_axi_awsize    ),
  .c0_ddr4_s_axi_awburst    (c2_ddr4_s_axi_awburst   ),
  .c0_ddr4_s_axi_awlock     (c2_ddr4_s_axi_awlock    ),
  .c0_ddr4_s_axi_awcache    (c2_ddr4_s_axi_awcache   ),
  .c0_ddr4_s_axi_awprot     (c2_ddr4_s_axi_awprot    ),
  .c0_ddr4_s_axi_awqos      (c2_ddr4_s_axi_awqos     ),
  .c0_ddr4_s_axi_awvalid    (c2_ddr4_s_axi_awvalid   ),
  .c0_ddr4_s_axi_awready    (c2_ddr4_s_axi_awready   ),
  .c0_ddr4_s_axi_wdata      (c2_ddr4_s_axi_wdata     ),
  .c0_ddr4_s_axi_wstrb      (c2_ddr4_s_axi_wstrb     ),
  .c0_ddr4_s_axi_wlast      (c2_ddr4_s_axi_wlast     ),
  .c0_ddr4_s_axi_wvalid     (c2_ddr4_s_axi_wvalid    ),
  .c0_ddr4_s_axi_wready     (c2_ddr4_s_axi_wready    ),
   // Slave Interface Write Response Ports
  .c0_ddr4_s_axi_bready     (c2_ddr4_s_axi_bready    ),
  .c0_ddr4_s_axi_bid        (c2_ddr4_s_axi_bid       ),
  .c0_ddr4_s_axi_bresp      (c2_ddr4_s_axi_bresp     ),
  .c0_ddr4_s_axi_bvalid     (c2_ddr4_s_axi_bvalid    ),

  .c0_ddr4_s_axi_arid       (c2_ddr4_s_axi_arid      ),
  .c0_ddr4_s_axi_araddr     (c2_ddr4_s_axi_araddr    ),
  .c0_ddr4_s_axi_arlen      (c2_ddr4_s_axi_arlen     ),
  .c0_ddr4_s_axi_arsize     (c2_ddr4_s_axi_arsize    ),
  .c0_ddr4_s_axi_arburst    (c2_ddr4_s_axi_arburst   ),
  .c0_ddr4_s_axi_arlock     (c2_ddr4_s_axi_arlock    ),
  .c0_ddr4_s_axi_arcache    (c2_ddr4_s_axi_arcache   ),
  .c0_ddr4_s_axi_arprot     (c2_ddr4_s_axi_arprot    ),
  .c0_ddr4_s_axi_arqos      (c2_ddr4_s_axi_arqos     ),
  .c0_ddr4_s_axi_arvalid    (c2_ddr4_s_axi_arvalid   ),
  .c0_ddr4_s_axi_arready    (c2_ddr4_s_axi_arready   ),
   // Slave Interface Read Data Ports
  .c0_ddr4_s_axi_rready     (c2_ddr4_s_axi_rready    ),
  .c0_ddr4_s_axi_rid        (c2_ddr4_s_axi_rid       ),
  .c0_ddr4_s_axi_rdata      (c2_ddr4_s_axi_rdata     ),
  .c0_ddr4_s_axi_rresp      (c2_ddr4_s_axi_rresp     ),
  .c0_ddr4_s_axi_rlast      (c2_ddr4_s_axi_rlast     ),
  .c0_ddr4_s_axi_rvalid     (c2_ddr4_s_axi_rvalid    ),
  .dbg_bus                  ()
);

// C3

wire  [3:0]     c3_ddr4_s_axi_awid  ;
wire  [31:0]    c3_ddr4_s_axi_awaddr;
wire  [7:0]     c3_ddr4_s_axi_awlen ;
wire  [2:0]     c3_ddr4_s_axi_awsize;
wire  [1:0]     c3_ddr4_s_axi_awburst;
wire  [0:0]     c3_ddr4_s_axi_awlock;
wire  [3:0]     c3_ddr4_s_axi_awcache;
wire  [2:0]     c3_ddr4_s_axi_awprot;
wire  [3:0]     c3_ddr4_s_axi_awqos;
wire            c3_ddr4_s_axi_awvalid;
wire            c3_ddr4_s_axi_awready;

wire  [511:0]   c3_ddr4_s_axi_wdata;
wire  [63:0]    c3_ddr4_s_axi_wstrb;
wire            c3_ddr4_s_axi_wlast;
wire            c3_ddr4_s_axi_wvalid;
wire            c3_ddr4_s_axi_wready;

wire            c3_ddr4_s_axi_bready;
wire [3:0]      c3_ddr4_s_axi_bid;
wire [1:0]      c3_ddr4_s_axi_bresp;
wire            c3_ddr4_s_axi_bvalid;

wire  [3:0]     c3_ddr4_s_axi_arid;
wire  [31:0]    c3_ddr4_s_axi_araddr;
wire  [7:0]     c3_ddr4_s_axi_arlen;
wire  [2:0]     c3_ddr4_s_axi_arsize;
wire  [1:0]     c3_ddr4_s_axi_arburst;
wire  [0:0]     c3_ddr4_s_axi_arlock;
wire  [3:0]     c3_ddr4_s_axi_arcache;
wire  [2:0]     c3_ddr4_s_axi_arprot;
wire  [3:0]     c3_ddr4_s_axi_arqos;
wire            c3_ddr4_s_axi_arvalid;
wire            c3_ddr4_s_axi_arready;

wire            c3_ddr4_s_axi_rready;
wire [3:0]      c3_ddr4_s_axi_rid;
wire [511:0]    c3_ddr4_s_axi_rdata;
wire [1:0]      c3_ddr4_s_axi_rresp;
wire            c3_ddr4_s_axi_rlast;
wire            c3_ddr4_s_axi_rvalid;

wire    c3_ddr4_ui_clk;
wire    c3_ddr4_ui_clk_sync_rst;
ddr4_0 ddr4_c3
(
  .sys_rst                  ('d0             ),
  .c0_sys_clk_p             (c3_sys_clk_p    ),
  .c0_sys_clk_n             (c3_sys_clk_n    ),
  .c0_ddr4_act_n            (c3_ddr4_act_n   ),
  .c0_ddr4_adr              (c3_ddr4_adr     ),
  .c0_ddr4_ba               (c3_ddr4_ba      ),
  .c0_ddr4_bg               (c3_ddr4_bg      ),
  .c0_ddr4_cke              (c3_ddr4_cke     ),
  .c0_ddr4_odt              (c3_ddr4_odt     ),
  .c0_ddr4_cs_n             (c3_ddr4_cs_n    ),
  .c0_ddr4_ck_t             (c3_ddr4_ck_t    ),
  .c0_ddr4_ck_c             (c3_ddr4_ck_c    ),
  .c0_ddr4_reset_n          (c3_ddr4_reset_n ),
  .c0_ddr4_dm_dbi_n         (c3_ddr4_dm_dbi_n),
  .c0_ddr4_dq               (c3_ddr4_dq      ),
  .c0_ddr4_dqs_c            (c3_ddr4_dqs_c   ),
  .c0_ddr4_dqs_t            (c3_ddr4_dqs_t   ),

  .c0_init_calib_complete   (),
  .c0_ddr4_ui_clk           (c3_ddr4_ui_clk           ),
  .c0_ddr4_ui_clk_sync_rst  (c3_ddr4_ui_clk_sync_rst  ),
  .dbg_clk                  (),
  
  .c0_ddr4_s_axi_ctrl_awvalid(1'b0),
  .c0_ddr4_s_axi_ctrl_awready(),
  .c0_ddr4_s_axi_ctrl_awaddr (32'd0),
  .c0_ddr4_s_axi_ctrl_wvalid(1'b0),
  .c0_ddr4_s_axi_ctrl_wready(),
  .c0_ddr4_s_axi_ctrl_wdata(32'd0),
  .c0_ddr4_s_axi_ctrl_bvalid(),
  .c0_ddr4_s_axi_ctrl_bready(1'b1),
  .c0_ddr4_s_axi_ctrl_bresp(),
  .c0_ddr4_s_axi_ctrl_arvalid(1'b0),
  .c0_ddr4_s_axi_ctrl_arready(),
  .c0_ddr4_s_axi_ctrl_araddr(31'd0),
  .c0_ddr4_s_axi_ctrl_rvalid(),
  .c0_ddr4_s_axi_ctrl_rready(1'b1),
  .c0_ddr4_s_axi_ctrl_rdata(),
  .c0_ddr4_s_axi_ctrl_rresp(),

  .c0_ddr4_interrupt        (),
  .c0_ddr4_aresetn          (~c3_ddr4_ui_clk_sync_rst),
  .c0_ddr4_s_axi_awid       (c3_ddr4_s_axi_awid      ),
  .c0_ddr4_s_axi_awaddr     (c3_ddr4_s_axi_awaddr    ),
  .c0_ddr4_s_axi_awlen      (c3_ddr4_s_axi_awlen     ),
  .c0_ddr4_s_axi_awsize     (c3_ddr4_s_axi_awsize    ),
  .c0_ddr4_s_axi_awburst    (c3_ddr4_s_axi_awburst   ),
  .c0_ddr4_s_axi_awlock     (c3_ddr4_s_axi_awlock    ),
  .c0_ddr4_s_axi_awcache    (c3_ddr4_s_axi_awcache   ),
  .c0_ddr4_s_axi_awprot     (c3_ddr4_s_axi_awprot    ),
  .c0_ddr4_s_axi_awqos      (c3_ddr4_s_axi_awqos     ),
  .c0_ddr4_s_axi_awvalid    (c3_ddr4_s_axi_awvalid   ),
  .c0_ddr4_s_axi_awready    (c3_ddr4_s_axi_awready   ),
  .c0_ddr4_s_axi_wdata      (c3_ddr4_s_axi_wdata     ),
  .c0_ddr4_s_axi_wstrb      (c3_ddr4_s_axi_wstrb     ),
  .c0_ddr4_s_axi_wlast      (c3_ddr4_s_axi_wlast     ),
  .c0_ddr4_s_axi_wvalid     (c3_ddr4_s_axi_wvalid    ),
  .c0_ddr4_s_axi_wready     (c3_ddr4_s_axi_wready    ),
   // Slave Interface Write Response Ports
  .c0_ddr4_s_axi_bready     (c3_ddr4_s_axi_bready    ),
  .c0_ddr4_s_axi_bid        (c3_ddr4_s_axi_bid       ),
  .c0_ddr4_s_axi_bresp      (c3_ddr4_s_axi_bresp     ),
  .c0_ddr4_s_axi_bvalid     (c3_ddr4_s_axi_bvalid    ),

  .c0_ddr4_s_axi_arid       (c3_ddr4_s_axi_arid      ),
  .c0_ddr4_s_axi_araddr     (c3_ddr4_s_axi_araddr    ),
  .c0_ddr4_s_axi_arlen      (c3_ddr4_s_axi_arlen     ),
  .c0_ddr4_s_axi_arsize     (c3_ddr4_s_axi_arsize    ),
  .c0_ddr4_s_axi_arburst    (c3_ddr4_s_axi_arburst   ),
  .c0_ddr4_s_axi_arlock     (c3_ddr4_s_axi_arlock    ),
  .c0_ddr4_s_axi_arcache    (c3_ddr4_s_axi_arcache   ),
  .c0_ddr4_s_axi_arprot     (c3_ddr4_s_axi_arprot    ),
  .c0_ddr4_s_axi_arqos      (c3_ddr4_s_axi_arqos     ),
  .c0_ddr4_s_axi_arvalid    (c3_ddr4_s_axi_arvalid   ),
  .c0_ddr4_s_axi_arready    (c3_ddr4_s_axi_arready   ),
   // Slave Interface Read Data Ports
  .c0_ddr4_s_axi_rready     (c3_ddr4_s_axi_rready    ),
  .c0_ddr4_s_axi_rid        (c3_ddr4_s_axi_rid       ),
  .c0_ddr4_s_axi_rdata      (c3_ddr4_s_axi_rdata     ),
  .c0_ddr4_s_axi_rresp      (c3_ddr4_s_axi_rresp     ),
  .c0_ddr4_s_axi_rlast      (c3_ddr4_s_axi_rlast     ),
  .c0_ddr4_s_axi_rvalid     (c3_ddr4_s_axi_rvalid    ),
  .dbg_bus                  ()
);

axi_interconnect axi_interconnect_i
(
    .ACLK               (axi_clk                    ),
    .ARESETN            (~axi_rst                   ),

    .M00_ACLK           (c0_ddr4_ui_clk             ),
    .M00_ARESETN        (~c0_ddr4_ui_clk_sync_rst   ),
    .M00_AXI_araddr     (c0_ddr4_s_axi_araddr       ),
    .M00_AXI_arburst    (c0_ddr4_s_axi_arburst      ),
    .M00_AXI_arcache    (c0_ddr4_s_axi_arcache      ),
    .M00_AXI_arid       (c0_ddr4_s_axi_arid         ),
    .M00_AXI_arlen      (c0_ddr4_s_axi_arlen        ),
    .M00_AXI_arlock     (c0_ddr4_s_axi_arlock       ),
    .M00_AXI_arprot     (c0_ddr4_s_axi_arprot       ),
    .M00_AXI_arqos      (c0_ddr4_s_axi_arqos        ),
    .M00_AXI_arready    (c0_ddr4_s_axi_arready      ),
    .M00_AXI_arregion   (c0_ddr4_s_axi_arregion     ),
    .M00_AXI_arsize     (c0_ddr4_s_axi_arsize       ),
    .M00_AXI_arvalid    (c0_ddr4_s_axi_arvalid      ),
    .M00_AXI_awaddr     (c0_ddr4_s_axi_awaddr       ),
    .M00_AXI_awburst    (c0_ddr4_s_axi_awburst      ),
    .M00_AXI_awcache    (c0_ddr4_s_axi_awcache      ),
    .M00_AXI_awid       (c0_ddr4_s_axi_awid         ),
    .M00_AXI_awlen      (c0_ddr4_s_axi_awlen        ),
    .M00_AXI_awlock     (c0_ddr4_s_axi_awlock       ),
    .M00_AXI_awprot     (c0_ddr4_s_axi_awprot       ),
    .M00_AXI_awqos      (c0_ddr4_s_axi_awqos        ),
    .M00_AXI_awready    (c0_ddr4_s_axi_awready      ),
    .M00_AXI_awregion   (c0_ddr4_s_axi_awregion     ),
    .M00_AXI_awsize     (c0_ddr4_s_axi_awsize       ),
    .M00_AXI_awvalid    (c0_ddr4_s_axi_awvalid      ),
    .M00_AXI_bid        (c0_ddr4_s_axi_bid          ),
    .M00_AXI_bready     (c0_ddr4_s_axi_bready       ),
    .M00_AXI_bresp      (c0_ddr4_s_axi_bresp        ),
    .M00_AXI_bvalid     (c0_ddr4_s_axi_bvalid       ),
    .M00_AXI_rdata      (c0_ddr4_s_axi_rdata        ),
    .M00_AXI_rid        (c0_ddr4_s_axi_rid          ),
    .M00_AXI_rlast      (c0_ddr4_s_axi_rlast        ),
    .M00_AXI_rready     (c0_ddr4_s_axi_rready       ),
    .M00_AXI_rresp      (c0_ddr4_s_axi_rresp        ),
    .M00_AXI_rvalid     (c0_ddr4_s_axi_rvalid       ),
    .M00_AXI_wdata      (c0_ddr4_s_axi_wdata        ),
    .M00_AXI_wlast      (c0_ddr4_s_axi_wlast        ),
    .M00_AXI_wready     (c0_ddr4_s_axi_wready       ),
    .M00_AXI_wstrb      (c0_ddr4_s_axi_wstrb        ),
    .M00_AXI_wvalid     (c0_ddr4_s_axi_wvalid       ),
    
    .M01_ACLK           (c1_ddr4_ui_clk             ),
    .M01_ARESETN        (c1_ddr4_ui_clk_sync_rst    ),
    .M01_AXI_araddr     (c1_ddr4_s_axi_araddr       ),
    .M01_AXI_arburst    (c1_ddr4_s_axi_arburst      ),
    .M01_AXI_arcache    (c1_ddr4_s_axi_arcache      ),
    .M01_AXI_arid       (c1_ddr4_s_axi_arid         ),
    .M01_AXI_arlen      (c1_ddr4_s_axi_arlen        ),
    .M01_AXI_arlock     (c1_ddr4_s_axi_arlock       ),
    .M01_AXI_arprot     (c1_ddr4_s_axi_arprot       ),
    .M01_AXI_arqos      (c1_ddr4_s_axi_arqos        ),
    .M01_AXI_arready    (c1_ddr4_s_axi_arready      ),
    .M01_AXI_arregion   (c1_ddr4_s_axi_arregion     ),
    .M01_AXI_arsize     (c1_ddr4_s_axi_arsize       ),
    .M01_AXI_arvalid    (c1_ddr4_s_axi_arvalid      ),
    .M01_AXI_awaddr     (c1_ddr4_s_axi_awaddr       ),
    .M01_AXI_awburst    (c1_ddr4_s_axi_awburst      ),
    .M01_AXI_awcache    (c1_ddr4_s_axi_awcache      ),
    .M01_AXI_awid       (c1_ddr4_s_axi_awid         ),
    .M01_AXI_awlen      (c1_ddr4_s_axi_awlen        ),
    .M01_AXI_awlock     (c1_ddr4_s_axi_awlock       ),
    .M01_AXI_awprot     (c1_ddr4_s_axi_awprot       ),
    .M01_AXI_awqos      (c1_ddr4_s_axi_awqos        ),
    .M01_AXI_awready    (c1_ddr4_s_axi_awready      ),
    .M01_AXI_awregion   (c1_ddr4_s_axi_awregion     ),
    .M01_AXI_awsize     (c1_ddr4_s_axi_awsize       ),
    .M01_AXI_awvalid    (c1_ddr4_s_axi_awvalid      ),
    .M01_AXI_bid        (c1_ddr4_s_axi_bid          ),
    .M01_AXI_bready     (c1_ddr4_s_axi_bready       ),
    .M01_AXI_bresp      (c1_ddr4_s_axi_bresp        ),
    .M01_AXI_bvalid     (c1_ddr4_s_axi_bvalid       ),
    .M01_AXI_rdata      (c1_ddr4_s_axi_rdata        ),
    .M01_AXI_rid        (c1_ddr4_s_axi_rid          ),
    .M01_AXI_rlast      (c1_ddr4_s_axi_rlast        ),
    .M01_AXI_rready     (c1_ddr4_s_axi_rready       ),
    .M01_AXI_rresp      (c1_ddr4_s_axi_rresp        ),
    .M01_AXI_rvalid     (c1_ddr4_s_axi_rvalid       ),
    .M01_AXI_wdata      (c1_ddr4_s_axi_wdata        ),
    .M01_AXI_wlast      (c1_ddr4_s_axi_wlast        ),
    .M01_AXI_wready     (c1_ddr4_s_axi_wready       ),
    .M01_AXI_wstrb      (c1_ddr4_s_axi_wstrb        ),
    .M01_AXI_wvalid     (c1_ddr4_s_axi_wvalid       ),

    .M02_ACLK           (c2_ddr4_ui_clk             ),
    .M02_ARESETN        (c2_ddr4_ui_clk_sync_rst    ),
    .M02_AXI_araddr     (c2_ddr4_s_axi_araddr       ),
    .M02_AXI_arburst    (c2_ddr4_s_axi_arburst      ),
    .M02_AXI_arcache    (c2_ddr4_s_axi_arcache      ),
    .M02_AXI_arid       (c2_ddr4_s_axi_arid         ),
    .M02_AXI_arlen      (c2_ddr4_s_axi_arlen        ),
    .M02_AXI_arlock     (c2_ddr4_s_axi_arlock       ),
    .M02_AXI_arprot     (c2_ddr4_s_axi_arprot       ),
    .M02_AXI_arqos      (c2_ddr4_s_axi_arqos        ),
    .M02_AXI_arready    (c2_ddr4_s_axi_arready      ),
    .M02_AXI_arregion   (c2_ddr4_s_axi_arregion     ),
    .M02_AXI_arsize     (c2_ddr4_s_axi_arsize       ),
    .M02_AXI_arvalid    (c2_ddr4_s_axi_arvalid      ),
    .M02_AXI_awaddr     (c2_ddr4_s_axi_awaddr       ),
    .M02_AXI_awburst    (c2_ddr4_s_axi_awburst      ),
    .M02_AXI_awcache    (c2_ddr4_s_axi_awcache      ),
    .M02_AXI_awid       (c2_ddr4_s_axi_awid         ),
    .M02_AXI_awlen      (c2_ddr4_s_axi_awlen        ),
    .M02_AXI_awlock     (c2_ddr4_s_axi_awlock       ),
    .M02_AXI_awprot     (c2_ddr4_s_axi_awprot       ),
    .M02_AXI_awqos      (c2_ddr4_s_axi_awqos        ),
    .M02_AXI_awready    (c2_ddr4_s_axi_awready      ),
    .M02_AXI_awregion   (c2_ddr4_s_axi_awregion     ),
    .M02_AXI_awsize     (c2_ddr4_s_axi_awsize       ),
    .M02_AXI_awvalid    (c2_ddr4_s_axi_awvalid      ),
    .M02_AXI_bid        (c2_ddr4_s_axi_bid          ),
    .M02_AXI_bready     (c2_ddr4_s_axi_bready       ),
    .M02_AXI_bresp      (c2_ddr4_s_axi_bresp        ),
    .M02_AXI_bvalid     (c2_ddr4_s_axi_bvalid       ),
    .M02_AXI_rdata      (c2_ddr4_s_axi_rdata        ),
    .M02_AXI_rid        (c2_ddr4_s_axi_rid          ),
    .M02_AXI_rlast      (c2_ddr4_s_axi_rlast        ),
    .M02_AXI_rready     (c2_ddr4_s_axi_rready       ),
    .M02_AXI_rresp      (c2_ddr4_s_axi_rresp        ),
    .M02_AXI_rvalid     (c2_ddr4_s_axi_rvalid       ),
    .M02_AXI_wdata      (c2_ddr4_s_axi_wdata        ),
    .M02_AXI_wlast      (c2_ddr4_s_axi_wlast        ),
    .M02_AXI_wready     (c2_ddr4_s_axi_wready       ),
    .M02_AXI_wstrb      (c2_ddr4_s_axi_wstrb        ),
    .M02_AXI_wvalid     (c2_ddr4_s_axi_wvalid       ),

    .M03_ACLK           (c3_ddr4_ui_clk             ),
    .M03_ARESETN        (c3_ddr4_ui_clk_sync_rst    ),
    .M03_AXI_araddr     (c3_ddr4_s_axi_araddr       ),
    .M03_AXI_arburst    (c3_ddr4_s_axi_arburst      ),
    .M03_AXI_arcache    (c3_ddr4_s_axi_arcache      ),
    .M03_AXI_arid       (c3_ddr4_s_axi_arid         ),
    .M03_AXI_arlen      (c3_ddr4_s_axi_arlen        ),
    .M03_AXI_arlock     (c3_ddr4_s_axi_arlock       ),
    .M03_AXI_arprot     (c3_ddr4_s_axi_arprot       ),
    .M03_AXI_arqos      (c3_ddr4_s_axi_arqos        ),
    .M03_AXI_arready    (c3_ddr4_s_axi_arready      ),
    .M03_AXI_arregion   (c3_ddr4_s_axi_arregion     ),
    .M03_AXI_arsize     (c3_ddr4_s_axi_arsize       ),
    .M03_AXI_arvalid    (c3_ddr4_s_axi_arvalid      ),
    .M03_AXI_awaddr     (c3_ddr4_s_axi_awaddr       ),
    .M03_AXI_awburst    (c3_ddr4_s_axi_awburst      ),
    .M03_AXI_awcache    (c3_ddr4_s_axi_awcache      ),
    .M03_AXI_awid       (c3_ddr4_s_axi_awid         ),
    .M03_AXI_awlen      (c3_ddr4_s_axi_awlen        ),
    .M03_AXI_awlock     (c3_ddr4_s_axi_awlock       ),
    .M03_AXI_awprot     (c3_ddr4_s_axi_awprot       ),
    .M03_AXI_awqos      (c3_ddr4_s_axi_awqos        ),
    .M03_AXI_awready    (c3_ddr4_s_axi_awready      ),
    .M03_AXI_awregion   (c3_ddr4_s_axi_awregion     ),
    .M03_AXI_awsize     (c3_ddr4_s_axi_awsize       ),
    .M03_AXI_awvalid    (c3_ddr4_s_axi_awvalid      ),
    .M03_AXI_bid        (c3_ddr4_s_axi_bid          ),
    .M03_AXI_bready     (c3_ddr4_s_axi_bready       ),
    .M03_AXI_bresp      (c3_ddr4_s_axi_bresp        ),
    .M03_AXI_bvalid     (c3_ddr4_s_axi_bvalid       ),
    .M03_AXI_rdata      (c3_ddr4_s_axi_rdata        ),
    .M03_AXI_rid        (c3_ddr4_s_axi_rid          ),
    .M03_AXI_rlast      (c3_ddr4_s_axi_rlast        ),
    .M03_AXI_rready     (c3_ddr4_s_axi_rready       ),
    .M03_AXI_rresp      (c3_ddr4_s_axi_rresp        ),
    .M03_AXI_rvalid     (c3_ddr4_s_axi_rvalid       ),
    .M03_AXI_wdata      (c3_ddr4_s_axi_wdata        ),
    .M03_AXI_wlast      (c3_ddr4_s_axi_wlast        ),
    .M03_AXI_wready     (c3_ddr4_s_axi_wready       ),
    .M03_AXI_wstrb      (c3_ddr4_s_axi_wstrb        ),
    .M03_AXI_wvalid     (c3_ddr4_s_axi_wvalid       ),

    .S00_ACLK           (axi_clk                    ),
    .S00_AXI_araddr     (m_axi_araddr               ),
    .S00_AXI_arburst    (m_axi_arburst              ),
    .S00_AXI_arcache    (m_axi_arcache              ),
    .S00_AXI_arlen      (m_axi_arlen                ),
    .S00_AXI_arlock     (m_axi_arlock               ),
    .S00_AXI_arprot     (m_axi_arprot               ),
    .S00_AXI_arqos      (m_axi_arqos                ),
    .S00_AXI_arready    (m_axi_arready              ),
    .S00_AXI_arregion   (m_axi_arregion             ),
    .S00_AXI_arsize     (m_axi_arsize               ),
    .S00_AXI_arvalid    (m_axi_arvalid              ),
    .S00_AXI_awaddr     (m_axi_awaddr               ),
    .S00_AXI_awburst    (m_axi_awburst              ),
    .S00_AXI_awcache    (m_axi_awcache              ),
    .S00_AXI_awlen      (m_axi_awlen                ),
    .S00_AXI_awlock     (m_axi_awlock               ),
    .S00_AXI_awprot     (m_axi_awprot               ),
    .S00_AXI_awqos      (m_axi_awqos                ),
    .S00_AXI_awready    (m_axi_awready              ),
    .S00_AXI_awregion   (m_axi_awregion             ),
    .S00_AXI_awsize     (m_axi_awsize               ),
    .S00_AXI_awvalid    (m_axi_awvalid              ),
    .S00_AXI_bready     (m_axi_bready               ),
    .S00_AXI_bresp      (m_axi_bresp                ),
    .S00_AXI_bvalid     (m_axi_bvalid               ),
    .S00_AXI_rdata      (m_axi_rdata                ),
    .S00_AXI_rlast      (m_axi_rlast                ),
    .S00_AXI_rready     (m_axi_rready               ),
    .S00_AXI_rresp      (m_axi_rresp                ),
    .S00_AXI_rvalid     (m_axi_rvalid               ),
    .S00_AXI_wdata      (m_axi_wdata                ),
    .S00_AXI_wlast      (m_axi_wlast                ),
    .S00_AXI_wready     (m_axi_wready               ),
    .S00_AXI_wstrb      (m_axi_wstrb                ),
    .S00_AXI_wvalid     (m_axi_wvalid               ),

    .S01_ACLK           (axi_clk                    ),
    .S01_ARESETN        (~axi_rst                   ),
    .S01_AXI_araddr     (m_axi_user_araddr          ),
    .S01_AXI_arburst    (m_axi_user_arburst         ),
    .S01_AXI_arcache    (m_axi_user_arcache         ),
    .S01_AXI_arlen      (m_axi_user_arlen           ),
    .S01_AXI_arlock     (m_axi_user_arlock          ),
    .S01_AXI_arprot     (m_axi_user_arprot          ),
    .S01_AXI_arqos      (m_axi_user_arqos           ),
    .S01_AXI_arready    (m_axi_user_arready         ),
    .S01_AXI_arregion   (m_axi_user_arregion        ),
    .S01_AXI_arsize     (m_axi_user_arsize          ),
    .S01_AXI_arvalid    (m_axi_user_arvalid         ),
    .S01_AXI_awaddr     (m_axi_user_awaddr          ),
    .S01_AXI_awburst    (m_axi_user_awburst         ),
    .S01_AXI_awcache    (m_axi_user_awcache         ),
    .S01_AXI_awlen      (m_axi_user_awlen           ),
    .S01_AXI_awlock     (m_axi_user_awlock          ),
    .S01_AXI_awprot     (m_axi_user_awprot          ),
    .S01_AXI_awqos      (m_axi_user_awqos           ),
    .S01_AXI_awready    (m_axi_user_awready         ),
    .S01_AXI_awregion   (m_axi_user_awregion        ),
    .S01_AXI_awsize     (m_axi_user_awsize          ),
    .S01_AXI_awvalid    (m_axi_user_awvalid         ),
    .S01_AXI_bready     (m_axi_user_bready          ),
    .S01_AXI_bresp      (m_axi_user_bresp           ),
    .S01_AXI_bvalid     (m_axi_user_bvalid          ),
    .S01_AXI_rdata      (m_axi_user_rdata           ),
    .S01_AXI_rlast      (m_axi_user_rlast           ),
    .S01_AXI_rready     (m_axi_user_rready          ),
    .S01_AXI_rresp      (m_axi_user_rresp           ),
    .S01_AXI_rvalid     (m_axi_user_rvalid          ),
    .S01_AXI_wdata      (m_axi_user_wdata           ),
    .S01_AXI_wlast      (m_axi_user_wlast           ),
    .S01_AXI_wready     (m_axi_user_wready          ),
    .S01_AXI_wstrb      (m_axi_user_wstrb           ),
    .S01_AXI_wvalid     (m_axi_user_wvalid          )
);
endmodule