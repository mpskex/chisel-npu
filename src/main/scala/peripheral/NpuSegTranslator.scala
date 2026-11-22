// See README.md for license details.
// -----------------------------------------------------------------------------
//  NpuSegTranslator.scala — segmented core AXI -> system AXI.
//
//  The core emits a segment ID (0=A, 1=B, 2=ACCUM, 3=OUT, 4=CODE) plus the
//  flat local offset used by the instruction/test layout.  The translator
//  subtracts LOCAL_BASE[seg], checks the section-relative range against the
//  programmed limit, and adds the programmed 64-bit system base.
// -----------------------------------------------------------------------------

package peripheral

import chisel3._
import chisel3.util._

class NpuSegTranslator(val NUM_SEG: Int = 5) extends Module {
  require(NUM_SEG >= 5 && NUM_SEG <= 32)

  val ADDR_W = 64
  val DATA_W = 512

  val io = IO(new Bundle {
    // ---- core side (segmented AXI) ---------------------------------------
    val s_awaddr  = Input(UInt(32.W))
    val s_awseg   = Input(UInt(3.W))
    val s_awlen   = Input(UInt(8.W))
    val s_awsize  = Input(UInt(3.W))
    val s_awburst = Input(UInt(2.W))
    val s_awvalid = Input(Bool())
    val s_awready = Output(Bool())
    val s_wdata   = Input(UInt(DATA_W.W))
    val s_wstrb   = Input(UInt((DATA_W / 8).W))
    val s_wlast   = Input(Bool())
    val s_wvalid  = Input(Bool())
    val s_wready  = Output(Bool())
    val s_bvalid  = Output(Bool())
    val s_bready  = Input(Bool())
    val s_araddr  = Input(UInt(32.W))
    val s_arseg   = Input(UInt(3.W))
    val s_arlen   = Input(UInt(8.W))
    val s_arsize  = Input(UInt(3.W))
    val s_arburst = Input(UInt(2.W))
    val s_arvalid = Input(Bool())
    val s_arready = Output(Bool())
    val s_rdata   = Output(UInt(DATA_W.W))
    val s_rlast   = Output(Bool())
    val s_rvalid  = Output(Bool())
    val s_rready  = Input(Bool())

    // ---- system side (standard AXI4) -------------------------------------
    val m_awaddr  = Output(UInt(ADDR_W.W))
    val m_awlen   = Output(UInt(8.W))
    val m_awsize  = Output(UInt(3.W))
    val m_awburst = Output(UInt(2.W))
    val m_awvalid = Output(Bool())
    val m_awready = Input(Bool())
    val m_wdata   = Output(UInt(DATA_W.W))
    val m_wstrb   = Output(UInt((DATA_W / 8).W))
    val m_wlast   = Output(Bool())
    val m_wvalid  = Output(Bool())
    val m_wready  = Input(Bool())
    val m_bresp   = Input(UInt(2.W))
    val m_bvalid  = Input(Bool())
    val m_bready  = Output(Bool())
    val m_araddr  = Output(UInt(ADDR_W.W))
    val m_arlen   = Output(UInt(8.W))
    val m_arsize  = Output(UInt(3.W))
    val m_arburst = Output(UInt(2.W))
    val m_arvalid = Output(Bool())
    val m_arready = Input(Bool())
    val m_rdata   = Input(UInt(DATA_W.W))
    val m_rresp   = Input(UInt(2.W))
    val m_rlast   = Input(Bool())
    val m_rvalid  = Input(Bool())
    val m_rready  = Output(Bool())

    // ---- segment table ----------------------------------------------------
    val seg_base  = Input(Vec(NUM_SEG, UInt(64.W)))
    val seg_limit = Input(Vec(NUM_SEG, UInt(32.W)))

    // ---- error / guard ----------------------------------------------------
    val bus_error = Output(Bool())
    val err_seg   = Output(UInt(3.W))
    val err_addr  = Output(UInt(32.W))
  })

  // Flat local bases of the five sections (relative to the old DATA_BASE).
  val localBase = VecInit(Seq(
    0x0000L, 0x0400L, 0x0800L, 0x0880L, 0x4000L
  ).padTo(NUM_SEG, 0L).map(_.U(32.W)))

  def safeSeg(seg: UInt): UInt = Mux(seg < NUM_SEG.U, seg, 0.U)

  def xlate(addr: UInt, seg: UInt, len: UInt, size: UInt):
      (UInt, Bool, UInt, UInt) = {
    val s = safeSeg(seg)
    val base = localBase(s)
    val rel  = addr - base
    val bytes = ((len +& 1.U) << size).pad(32)
    val relEnd = rel +& bytes
    val fits = (seg < NUM_SEG.U) && (addr >= base) &&
               (relEnd <= Cat(0.U(1.W), io.seg_limit(s)))
    val sysAddr = io.seg_base(s) + rel
    (sysAddr, fits, s, rel)
  }

  val (awSys, awFits, awSegSafe, awRel) = xlate(io.s_awaddr, io.s_awseg, io.s_awlen, io.s_awsize)
  val (arSys, arFits, arSegSafe, arRel) = xlate(io.s_araddr, io.s_arseg, io.s_arlen, io.s_arsize)

  io.m_awaddr  := Mux(awFits, awSys, io.seg_base(awSegSafe))
  io.m_awlen   := io.s_awlen
  io.m_awsize  := io.s_awsize
  io.m_awburst := io.s_awburst
  io.m_awvalid := io.s_awvalid
  io.s_awready := io.m_awready

  io.m_wdata   := io.s_wdata
  io.m_wstrb   := io.s_wstrb
  io.m_wlast   := io.s_wlast
  io.m_wvalid  := io.s_wvalid
  io.s_wready  := io.m_wready
  io.s_bvalid  := io.m_bvalid
  io.m_bready  := io.s_bready

  io.m_araddr  := Mux(arFits, arSys, io.seg_base(arSegSafe))
  io.m_arlen   := io.s_arlen
  io.m_arsize  := io.s_arsize
  io.m_arburst := io.s_arburst
  io.m_arvalid := io.s_arvalid
  io.s_arready := io.m_arready

  io.m_rready  := io.s_rready
  io.s_rdata   := io.m_rdata
  io.s_rlast   := io.m_rlast
  io.s_rvalid  := io.m_rvalid

  val awError = io.s_awvalid && !awFits
  val arError = io.s_arvalid && !arFits
  io.bus_error := awError || arError
  io.err_seg   := Mux(awError, awSegSafe, arSegSafe)
  io.err_addr  := Mux(awError, awRel, arRel)
}
