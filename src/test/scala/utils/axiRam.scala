// See README.md for license details.
// -----------------------------------------------------------------------------
//  axiRam.scala — minimal AXI4 slave RAM model for unit tests.
//
//  AXI data width is `DW` (default 512).  Storage and the `pre_wr`/`dbg_rd`
//  hooks stay 128-bit: one DW-wide beat occupies WORDS = DW/128 consecutive
//  128-bit memory words (byte strobes gate the write per word).
//
//  Behaviour:
//    - Reads: araddr is registered, rvalid asserted one cycle later; INCR
//      bursts of arbitrary len supported.  `dropReadRequests` > 0 makes the
//      model ignore that many AR handshakes entirely (rvalid never asserted).
//    - Writes: AW/W/B with independent handshakes, one beat per cycle, INCR;
//      wdata written on wready, gated by wstrb.
//  Memory: 4096 entries x 128 bits = 64 KiB.
// -----------------------------------------------------------------------------

package testUtil

import chisel3._
import chisel3.util._

class AxiRamModel(val dropReadRequests: Int = 0, val DW: Int = 512) extends Module {
  require(DW % 128 == 0, s"AxiRamModel: DW=$DW must be a multiple of 128")
  val WORDS = DW / 128
  val STRB = DW / 8

  val io = IO(new Bundle {
    val s_axi_awaddr  = Input(UInt(32.W))
    val s_axi_awlen   = Input(UInt(8.W))
    val s_axi_awsize  = Input(UInt(3.W))
    val s_axi_awburst = Input(UInt(2.W))
    val s_axi_awvalid = Input(Bool())
    val s_axi_awready = Output(Bool())
    val s_axi_wdata   = Input(UInt(DW.W))
    val s_axi_wstrb   = Input(UInt((DW / 8).W))
    val s_axi_wlast   = Input(Bool())
    val s_axi_wvalid  = Input(Bool())
    val s_axi_wready  = Output(Bool())
    val s_axi_bvalid  = Output(Bool())
    val s_axi_bready  = Input(Bool())
    val s_axi_araddr  = Input(UInt(32.W))
    val s_axi_arlen   = Input(UInt(8.W))
    val s_axi_arsize  = Input(UInt(3.W))
    val s_axi_arburst = Input(UInt(2.W))
    val s_axi_arvalid = Input(Bool())
    val s_axi_arready = Output(Bool())
    val s_axi_rdata   = Output(UInt(DW.W))
    val s_axi_rlast   = Output(Bool())
    val s_axi_rvalid  = Output(Bool())
    val s_axi_rready  = Input(Bool())

    // Test hooks (128-bit)
    val dbg_rd_addr = Input(UInt(12.W))
    val dbg_rd_data = Output(UInt(128.W))
    val pre_wr_en   = Input(Bool())
    val pre_wr_addr = Input(UInt(12.W))   // 16-byte word index
    val pre_wr_data = Input(UInt(128.W))
  })

  require(dropReadRequests >= 0)

  val mem = Mem(4096, UInt(128.W))

  io.dbg_rd_data := mem(io.dbg_rd_addr)
  when (io.pre_wr_en) { mem(io.pre_wr_addr) := io.pre_wr_data }

  // ---- Read channel ----
  val arPending = RegInit(false.B)
  val arAddr    = RegInit(0.U(32.W))
  val arLen     = RegInit(0.U(8.W))
  val rCnt      = RegInit(0.U(8.W))
  val rValid    = RegInit(false.B)
  val dropCnt   = RegInit(dropReadRequests.U(8.W))

  val rBase = (arAddr >> 4.U) + (WORDS.U * rCnt)
  io.s_axi_arready := !arPending && !rValid
  io.s_axi_rvalid  := rValid
  io.s_axi_rdata   := Cat((0 until WORDS).reverse.map(w => mem(rBase + w.U)))
  io.s_axi_rlast   := rValid && rCnt === arLen

  when (io.s_axi_arvalid && !arPending && !rValid) {
    when (dropCnt > 0.U) {
      dropCnt := dropCnt - 1.U
    } .otherwise {
      arPending := true.B
      arAddr    := io.s_axi_araddr
      arLen     := io.s_axi_arlen
      rCnt      := 0.U
    }
  }
  when (arPending) {
    arPending := false.B
    rValid    := true.B
  }
  when (rValid && io.s_axi_rready) {
    when (rCnt === arLen) { rValid := false.B }
    .otherwise            { rCnt := rCnt + 1.U }
  }

  // ---- Write channel ----
  val awPending = RegInit(false.B)
  val awAddr    = RegInit(0.U(32.W))
  val awLen     = RegInit(0.U(8.W))
  val wCnt      = RegInit(0.U(8.W))
  val bValid    = RegInit(false.B)

  io.s_axi_awready := !awPending
  io.s_axi_wready  := awPending && !bValid
  io.s_axi_bvalid  := bValid

  when (io.s_axi_awvalid && !awPending) {
    awPending := true.B
    awAddr    := io.s_axi_awaddr
    awLen     := io.s_axi_awlen
    wCnt      := 0.U
  }

  when (io.s_axi_wvalid && io.s_axi_wready) {
    val wBase = (awAddr >> 4.U) + (WORDS.U * wCnt)
    for (w <- 0 until WORDS) {
      when (io.s_axi_wstrb(16 * w + 15, 16 * w).orR) {
        mem(wBase + w.U) := io.s_axi_wdata(128 * w + 127, 128 * w)
      }
    }
    when (io.s_axi_wlast) {
      bValid := true.B
    } .otherwise {
      wCnt := wCnt + 1.U
    }
  }

  when (bValid && io.s_axi_bready) {
    bValid    := false.B
    awPending := false.B
  }
}
