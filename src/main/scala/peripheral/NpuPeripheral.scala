// See README.md for license details.
// -----------------------------------------------------------------------------
//  NpuPeripheral.scala — the board-agnostic NPU release shell.
//
//  NPUCTL AXI4-Lite control -> NpuControlHub -> NpuProgramEngineFrontend.
//  The core's segmented AXI master is translated to a standard 64-bit system
//  AXI4 master.  IRQ and trace are exposed directly.  No DDR/PCIe/clock/pin
//  logic lives here.
// -----------------------------------------------------------------------------

package peripheral

import chisel3._
import chisel3.util._
import engine._

class NpuPeripheral(val K: Int = 32, val N: Int = 8, val W: Int = 8,
                    val NUM_SEG: Int = 5, val TRACE_DEPTH: Int = 256) extends Module {

  val ADDR_W = 64
  val DATA_W = 512

  val io = IO(new Bundle {
    // ---- NPUCTL control plane (AXI4-Lite slave) ---------------------------
    val s_axil_awaddr  = Input(UInt(12.W))
    val s_axil_awprot  = Input(UInt(3.W))
    val s_axil_awvalid = Input(Bool())
    val s_axil_awready = Output(Bool())
    val s_axil_wdata   = Input(UInt(32.W))
    val s_axil_wstrb   = Input(UInt(4.W))
    val s_axil_wvalid  = Input(Bool())
    val s_axil_wready  = Output(Bool())
    val s_axil_bresp   = Output(UInt(2.W))
    val s_axil_bvalid  = Output(Bool())
    val s_axil_bready  = Input(Bool())
    val s_axil_araddr  = Input(UInt(12.W))
    val s_axil_arprot  = Input(UInt(3.W))
    val s_axil_arvalid = Input(Bool())
    val s_axil_arready = Output(Bool())
    val s_axil_rdata   = Output(UInt(32.W))
    val s_axil_rresp   = Output(UInt(2.W))
    val s_axil_rvalid  = Output(Bool())
    val s_axil_rready  = Input(Bool())

    // ---- System data plane (AXI4 master) ---------------------------------
    val m_axi_awaddr  = Output(UInt(ADDR_W.W))
    val m_axi_awlen   = Output(UInt(8.W))
    val m_axi_awsize  = Output(UInt(3.W))
    val m_axi_awburst = Output(UInt(2.W))
    val m_axi_awvalid = Output(Bool())
    val m_axi_awready = Input(Bool())
    val m_axi_wdata   = Output(UInt(DATA_W.W))
    val m_axi_wstrb   = Output(UInt((DATA_W / 8).W))
    val m_axi_wlast   = Output(Bool())
    val m_axi_wvalid  = Output(Bool())
    val m_axi_wready  = Input(Bool())
    val m_axi_bresp   = Input(UInt(2.W))
    val m_axi_bvalid  = Input(Bool())
    val m_axi_bready  = Output(Bool())
    val m_axi_araddr  = Output(UInt(ADDR_W.W))
    val m_axi_arlen   = Output(UInt(8.W))
    val m_axi_arsize  = Output(UInt(3.W))
    val m_axi_arburst = Output(UInt(2.W))
    val m_axi_arvalid = Output(Bool())
    val m_axi_arready = Input(Bool())
    val m_axi_rdata   = Input(UInt(DATA_W.W))
    val m_axi_rresp   = Input(UInt(2.W))
    val m_axi_rlast   = Input(Bool())
    val m_axi_rvalid  = Input(Bool())
    val m_axi_rready  = Output(Bool())

    // ---- Interrupt and trace ---------------------------------------------
    val irq         = Output(Bool())
    val trace_valid = Output(Bool())
    val trace_ready = Input(Bool())
    val trace_data  = Output(UInt(64.W))
  })

  val axil  = Module(new NpuCtrlAxiLite(12))
  val hub   = Module(new NpuControlHub(K, N, K, W, NUM_SEG, TRACE_DEPTH))
  val core  = Module(new NpuProgramEngineFrontend(K, N, W))
  val xlate = Module(new NpuSegTranslator(NUM_SEG))

  // ---- AXI4-Lite -> NPUCTL ------------------------------------------------
  axil.io.s_axil_awaddr  := io.s_axil_awaddr
  axil.io.s_axil_awprot  := io.s_axil_awprot
  axil.io.s_axil_awvalid := io.s_axil_awvalid
  io.s_axil_awready      := axil.io.s_axil_awready
  axil.io.s_axil_wdata   := io.s_axil_wdata
  axil.io.s_axil_wstrb   := io.s_axil_wstrb
  axil.io.s_axil_wvalid  := io.s_axil_wvalid
  io.s_axil_wready       := axil.io.s_axil_wready
  io.s_axil_bresp        := axil.io.s_axil_bresp
  io.s_axil_bvalid       := axil.io.s_axil_bvalid
  axil.io.s_axil_bready  := io.s_axil_bready
  axil.io.s_axil_araddr  := io.s_axil_araddr
  axil.io.s_axil_arprot  := io.s_axil_arprot
  axil.io.s_axil_arvalid := io.s_axil_arvalid
  io.s_axil_arready      := axil.io.s_axil_arready
  io.s_axil_rdata        := axil.io.s_axil_rdata
  io.s_axil_rresp        := axil.io.s_axil_rresp
  io.s_axil_rvalid       := axil.io.s_axil_rvalid
  axil.io.s_axil_rready  := io.s_axil_rready

  // ---- NPUCTL master -> hub ----------------------------------------------
  hub.io.ctrl.req.valid      := axil.io.ctrl.req.valid
  hub.io.ctrl.req.bits       := axil.io.ctrl.req.bits
  axil.io.ctrl.req.ready     := hub.io.ctrl.req.ready
  axil.io.ctrl.resp.valid    := hub.io.ctrl.resp.valid
  axil.io.ctrl.resp.bits     := hub.io.ctrl.resp.bits
  hub.io.ctrl.resp.ready     := axil.io.ctrl.resp.ready

  // ---- Hub -> core control -----------------------------------------------
  core.io.native_ctrl   := true.B
  core.io.core_start    := hub.io.core_start
  core.io.core_abort    := hub.io.core_abort
  core.io.core_prog_len := hub.io.core_prog_len
  core.io.core_entry_pc := hub.io.core_entry_pc
  core.io.seg_limit     := hub.io.core_seg_limit

  // The core's legacy ctrl port is unused in the peripheral.
  core.io.ctrl_addr  := 0.U
  core.io.ctrl_we    := false.B
  core.io.ctrl_wdata := 0.U

  // ---- Core -> hub status ------------------------------------------------
  hub.io.core_busy        := core.io.core_busy
  hub.io.core_done        := core.io.core_done
  hub.io.core_illegal     := core.io.core_illegal
  hub.io.core_mem_fault   := core.io.core_mem_fault
  hub.io.core_fault_seg   := core.io.core_fault_seg
  hub.io.core_fault_addr  := core.io.core_fault_addr
  hub.io.core_pc          := core.io.core_pc
  hub.io.core_err_instr   := core.io.core_err_instr
  hub.io.core_cycles      := core.io.core_cycles
  hub.io.core_macs        := core.io.core_macs
  hub.io.core_fetch_stats := core.io.core_fetch_stats
  hub.io.core_trace_retire := core.io.trace_retire
  hub.io.core_trace_pc     := core.io.trace_pc
  hub.io.bus_error         := xlate.io.bus_error

  hub.io.trace_ready := io.trace_ready
  io.trace_valid     := hub.io.trace_valid
  io.trace_data      := hub.io.trace_data
  io.irq             := hub.io.irq

  // ---- Segmented core AXI -> system AXI ----------------------------------
  xlate.io.seg_base  := hub.io.seg_base
  xlate.io.seg_limit := hub.io.seg_limit

  xlate.io.s_awaddr  := core.io.m_axi_awaddr
  xlate.io.s_awseg   := core.io.m_axi_awseg
  xlate.io.s_awlen   := core.io.m_axi_awlen
  xlate.io.s_awsize  := core.io.m_axi_awsize
  xlate.io.s_awburst := core.io.m_axi_awburst
  xlate.io.s_awvalid := core.io.m_axi_awvalid
  core.io.m_axi_awready := xlate.io.s_awready
  xlate.io.s_wdata   := core.io.m_axi_wdata
  xlate.io.s_wstrb   := core.io.m_axi_wstrb
  xlate.io.s_wlast   := core.io.m_axi_wlast
  xlate.io.s_wvalid  := core.io.m_axi_wvalid
  core.io.m_axi_wready := xlate.io.s_wready
  core.io.m_axi_bvalid := xlate.io.s_bvalid
  xlate.io.s_bready  := core.io.m_axi_bready

  xlate.io.s_araddr  := core.io.m_axi_araddr
  xlate.io.s_arseg   := core.io.m_axi_arseg
  xlate.io.s_arlen   := core.io.m_axi_arlen
  xlate.io.s_arsize  := core.io.m_axi_arsize
  xlate.io.s_arburst := core.io.m_axi_arburst
  xlate.io.s_arvalid := core.io.m_axi_arvalid
  core.io.m_axi_arready := xlate.io.s_arready
  core.io.m_axi_rdata  := xlate.io.s_rdata
  core.io.m_axi_rlast  := xlate.io.s_rlast
  core.io.m_axi_rvalid := xlate.io.s_rvalid
  xlate.io.s_rready  := core.io.m_axi_rready

  io.m_axi_awaddr  := xlate.io.m_awaddr
  io.m_axi_awlen   := xlate.io.m_awlen
  io.m_axi_awsize  := xlate.io.m_awsize
  io.m_axi_awburst := xlate.io.m_awburst
  io.m_axi_awvalid := xlate.io.m_awvalid
  xlate.io.m_awready := io.m_axi_awready
  io.m_axi_wdata   := xlate.io.m_wdata
  io.m_axi_wstrb   := xlate.io.m_wstrb
  io.m_axi_wlast   := xlate.io.m_wlast
  io.m_axi_wvalid  := xlate.io.m_wvalid
  xlate.io.m_wready := io.m_axi_wready
  xlate.io.m_bresp  := io.m_axi_bresp
  xlate.io.m_bvalid := io.m_axi_bvalid
  io.m_axi_bready   := xlate.io.m_bready
  io.m_axi_araddr  := xlate.io.m_araddr
  io.m_axi_arlen   := xlate.io.m_arlen
  io.m_axi_arsize  := xlate.io.m_arsize
  io.m_axi_arburst := xlate.io.m_arburst
  io.m_axi_arvalid := xlate.io.m_arvalid
  xlate.io.m_arready := io.m_axi_arready
  xlate.io.m_rdata  := io.m_axi_rdata
  xlate.io.m_rresp  := io.m_axi_rresp
  xlate.io.m_rlast  := io.m_axi_rlast
  xlate.io.m_rvalid := io.m_axi_rvalid
  io.m_axi_rready   := xlate.io.m_rready
}
