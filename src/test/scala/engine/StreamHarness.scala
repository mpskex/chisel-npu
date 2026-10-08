// See README.md for license details.
// -----------------------------------------------------------------------------
//  StreamHarness.scala — the streamed-matmul engine harness shared by the
//  streamed specs.  Wraps NpuProgramEngine with a latency-configurable RAM
//  (the silicon DDR is ~100-200 cycle reads) and exposes the debug counters.
// -----------------------------------------------------------------------------

package engine

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import testUtil._
import isa._
import isa.NpuAssembler._

/** Axi RAM with a configurable read latency (DDR-like). */
class LatencyRamModel(val readLatency: Int = 1) extends Module {
  require(readLatency >= 1)
  val io = IO(new Bundle {
    val s_axi_awaddr  = Input(UInt(32.W))
    val s_axi_awlen   = Input(UInt(8.W))
    val s_axi_awsize  = Input(UInt(3.W))
    val s_axi_awburst = Input(UInt(2.W))
    val s_axi_awvalid = Input(Bool())
    val s_axi_awready = Output(Bool())
    val s_axi_wdata   = Input(UInt(128.W))
    val s_axi_wstrb   = Input(UInt(16.W))
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
    val s_axi_rdata   = Output(UInt(128.W))
    val s_axi_rlast   = Output(Bool())
    val s_axi_rvalid  = Output(Bool())
    val s_axi_rready  = Input(Bool())
    val dbg_rd_addr = Input(UInt(12.W))
    val dbg_rd_data = Output(UInt(128.W))
    val pre_wr_en   = Input(Bool())
    val pre_wr_addr = Input(UInt(12.W))
    val pre_wr_data = Input(UInt(128.W))
  })

  val mem = Mem(4096, UInt(128.W))
  io.dbg_rd_data := mem(io.dbg_rd_addr)
  when (io.pre_wr_en) { mem(io.pre_wr_addr) := io.pre_wr_data }

  val latCnt   = RegInit(0.U(16.W))
  val arAddr   = RegInit(0.U(32.W))
  val arLen    = RegInit(0.U(8.W))
  val rCnt     = RegInit(0.U(8.W))
  val rValid   = RegInit(false.B)
  val busy     = RegInit(false.B)

  io.s_axi_arready := !busy
  io.s_axi_rvalid  := rValid
  io.s_axi_rdata   := mem((arAddr >> 4.U) + rCnt)
  io.s_axi_rlast   := rValid && rCnt === arLen

  when (io.s_axi_arvalid && !busy) {
    busy   := true.B
    arAddr := io.s_axi_araddr
    arLen  := io.s_axi_arlen
    latCnt := 0.U
  }
  when (busy) {
    latCnt := latCnt + 1.U
    when (latCnt === (readLatency - 1).U) {
      busy   := false.B
      rValid := true.B
      rCnt   := 0.U
    }
  }
  when (rValid && io.s_axi_rready) {
    when (rCnt === arLen) { rValid := false.B }
    .otherwise            { rCnt := rCnt + 1.U }
  }

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
    mem((awAddr >> 4.U) + wCnt) := io.s_axi_wdata
    when (io.s_axi_wlast) { bValid := true.B }
    .otherwise            { wCnt := wCnt + 1.U }
  }
  when (bValid && io.s_axi_bready) {
    bValid    := false.B
    awPending := false.B
  }
}

class StreamHarness(val K: Int = 16, val readLatency: Int = 1) extends Module {
  val io = IO(new Bundle {
    val instr       = Input(UInt(32.W))
    val instr_valid = Input(Bool())
    val instr_ready = Output(Bool())
    val illegal_out = Output(Bool())
    val session_reset = Input(Bool())

    val dbg_rd_addr = Input(UInt(12.W))
    val dbg_rd_data = Output(UInt(128.W))
    val pre_wr_en   = Input(Bool())
    val pre_wr_addr = Input(UInt(12.W))
    val pre_wr_data = Input(UInt(128.W))

    val dbg_vx_addr = Input(UInt(5.W))
    val dbg_vx_data = Output(Vec(K, UInt(8.W)))

    val dbg_mma_acc = Output(UInt(16.W))
    val dbg_clct    = Output(UInt(16.W))
    val dbg_dma_done = Output(UInt(16.W))
    val dbg_wcount   = Output(UInt(5.W))
    val dbg_bnd      = Output(UInt(7.W))
    val dbg_drain    = Output(Bool())
  })

  val eng = Module(new NpuProgramEngine(K, 8))
  val ram = Module(new LatencyRamModel(readLatency))

  eng.io.instr       := io.instr
  eng.io.instr_valid := io.instr_valid
  io.instr_ready     := eng.io.instr_ready
  io.illegal_out     := eng.io.illegal_out
  eng.io.session_reset := io.session_reset

  eng.io.dbg_vx_addr := io.dbg_vx_addr
  io.dbg_vx_data     := eng.io.dbg_vx_data
  io.dbg_mma_acc     := eng.io.dbg_mma_acc
  io.dbg_clct        := eng.io.dbg_clct
  io.dbg_dma_done    := eng.io.dbg_dma_done
  io.dbg_wcount      := eng.io.dbg_wcount
  io.dbg_bnd         := eng.io.dbg_bnd
  io.dbg_drain       := eng.io.drain

  eng.io.fetch_fill_req  := false.B
  eng.io.fetch_fill_addr := 0.U

  ram.io.s_axi_awaddr  := eng.io.m_axi_awaddr
  ram.io.s_axi_awlen   := eng.io.m_axi_awlen
  ram.io.s_axi_awsize  := eng.io.m_axi_awsize
  ram.io.s_axi_awburst := eng.io.m_axi_awburst
  ram.io.s_axi_awvalid := eng.io.m_axi_awvalid
  eng.io.m_axi_awready := ram.io.s_axi_awready
  ram.io.s_axi_wdata   := eng.io.m_axi_wdata
  ram.io.s_axi_wstrb   := eng.io.m_axi_wstrb
  ram.io.s_axi_wlast   := eng.io.m_axi_wlast
  ram.io.s_axi_wvalid  := eng.io.m_axi_wvalid
  eng.io.m_axi_wready  := ram.io.s_axi_wready
  eng.io.m_axi_bvalid  := ram.io.s_axi_bvalid
  ram.io.s_axi_bready  := eng.io.m_axi_bready
  ram.io.s_axi_araddr  := eng.io.m_axi_araddr
  ram.io.s_axi_arlen   := eng.io.m_axi_arlen
  ram.io.s_axi_arsize  := eng.io.m_axi_arsize
  ram.io.s_axi_arburst := eng.io.m_axi_arburst
  ram.io.s_axi_arvalid := eng.io.m_axi_arvalid
  eng.io.m_axi_arready := ram.io.s_axi_arready
  eng.io.m_axi_rdata   := ram.io.s_axi_rdata
  eng.io.m_axi_rlast   := ram.io.s_axi_rlast
  eng.io.m_axi_rvalid  := ram.io.s_axi_rvalid
  ram.io.s_axi_rready  := eng.io.m_axi_rready

  ram.io.dbg_rd_addr := io.dbg_rd_addr
  io.dbg_rd_data     := ram.io.dbg_rd_data
  ram.io.pre_wr_en   := io.pre_wr_en
  ram.io.pre_wr_addr := io.pre_wr_addr
  ram.io.pre_wr_data := io.pre_wr_data
}

object StreamSpecUtil {

  def preload(dut: StreamHarness, base: Long, bytes: Seq[Int]): Unit = {
    for ((bb, i) <- bytes.grouped(16).zipWithIndex) {
      var w = BigInt(0)
      for ((v, j) <- bb.zipWithIndex) w |= (BigInt(v & 0xFF) << (8 * j))
      dut.io.pre_wr_addr.poke((((base + i * 16) >> 4) & 0xFFF).U)
      dut.io.pre_wr_data.poke(w.U)
      dut.io.pre_wr_en.poke(true.B)
      dut.clock.step()
      dut.io.pre_wr_en.poke(false.B)
    }
  }

  def preloadI8(dut: StreamHarness, base: Long, values: Seq[Int]): Unit =
    preload(dut, base, values.map(_ & 0xFF))

  def preloadI32(dut: StreamHarness, base: Long, values: Seq[Int]): Unit = {
    val bytes = values.flatMap(v => Seq(v & 0xFF, (v >> 8) & 0xFF, (v >> 16) & 0xFF, (v >> 24) & 0xFF))
    preload(dut, base, bytes)
  }

  /** Issue one word, waiting for acceptance then completion (the frontend's
    * instr handshake).  Returns immediately after the engine accepts it. */
  def issue(dut: StreamHarness, instr: Int): Unit = {
    dut.io.instr.poke((instr.toLong & 0xFFFFFFFFL).U)
    dut.io.instr_valid.poke(true.B)
    var n = 0
    while (!dut.io.instr_ready.peek().litToBoolean && n < 1000000) dut.clock.step()
    assert(dut.io.instr_ready.peek().litToBoolean, s"engine never accepted 0x${instr.toHexString}")
    dut.clock.step()
    dut.io.instr_valid.poke(false.B)
  }

  def readI32(dut: StreamHarness, base: Long, n: Int): Array[Int] = {
    val out = Array.fill(4 * n)(0)
    for (w <- 0 until 4 * n / 16) {
      dut.io.dbg_rd_addr.poke((((base + 16 * w) >> 4) & 0xFFF).U)
      dut.clock.step(0)
      val got = dut.io.dbg_rd_data.peek().litValue
      for (b <- 0 until 16) out(16 * w + b) = ((got >> (8 * b)) & 0xFF).toInt
    }
    Array.tabulate(n)(i => out(4 * i) | (out(4 * i + 1) << 8) |
      (out(4 * i + 2) << 16) | (out(4 * i + 3) << 24))
  }

  /** The columns of `pe` that match `g` exactly (the capture search). */
  def columnsOf(g: Array[Int], pe: Array[Array[Int]]): Seq[Int] =
    (0 until pe(0).length).filter(c => (0 until g.length).forall(i => g(i) == pe(i)(c)))
}
