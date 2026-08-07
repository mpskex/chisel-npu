// See README.md for license details.
// Integration tests for NpuProgramEngineFrontend: programs staged in the CODE
// section, run via the ctrl_lite interface (start/PROG_LEN), verified through
// the L3 OUT section.

package engine

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec
import testUtil._
import isa.NpuAssembler._

class FrontendSpecHarness(val K: Int = 32) extends Module {
  val io = IO(new Bundle {
    val ctrl_addr  = Input(UInt(5.W))
    val ctrl_we    = Input(Bool())
    val ctrl_wdata = Input(UInt(32.W))
    val ctrl_rdata = Output(UInt(32.W))

    val dbg_rd_addr = Input(UInt(12.W))
    val dbg_rd_data = Output(UInt(128.W))
    val pre_wr_en   = Input(Bool())
    val pre_wr_addr = Input(UInt(12.W))
    val pre_wr_data = Input(UInt(128.W))

    val dbg_pc   = Output(UInt(16.W))
    val dbg_word = Output(UInt(32.W))
    val dbg_run  = Output(UInt(3.W))
    val dbg_startEdge = Output(Bool())
  })

  val fe = Module(new NpuProgramEngineFrontend(K, 8))
  val ram = Module(new AxiRamModel())

  fe.io.ctrl_addr  := io.ctrl_addr
  fe.io.ctrl_we    := io.ctrl_we
  fe.io.ctrl_wdata := io.ctrl_wdata
  io.ctrl_rdata    := fe.io.ctrl_rdata

  ram.io.s_axi_awaddr  := fe.io.m_axi_awaddr
  ram.io.s_axi_awlen   := fe.io.m_axi_awlen
  ram.io.s_axi_awsize  := fe.io.m_axi_awsize
  ram.io.s_axi_awburst := fe.io.m_axi_awburst
  ram.io.s_axi_awvalid := fe.io.m_axi_awvalid
  fe.io.m_axi_awready  := ram.io.s_axi_awready
  ram.io.s_axi_wdata   := fe.io.m_axi_wdata
  ram.io.s_axi_wstrb   := fe.io.m_axi_wstrb
  ram.io.s_axi_wlast   := fe.io.m_axi_wlast
  ram.io.s_axi_wvalid  := fe.io.m_axi_wvalid
  fe.io.m_axi_wready   := ram.io.s_axi_wready
  fe.io.m_axi_bvalid   := ram.io.s_axi_bvalid
  ram.io.s_axi_bready  := fe.io.m_axi_bready
  ram.io.s_axi_araddr  := fe.io.m_axi_araddr
  ram.io.s_axi_arlen   := fe.io.m_axi_arlen
  ram.io.s_axi_arsize  := fe.io.m_axi_arsize
  ram.io.s_axi_arburst := fe.io.m_axi_arburst
  ram.io.s_axi_arvalid := fe.io.m_axi_arvalid
  fe.io.m_axi_arready  := ram.io.s_axi_arready
  fe.io.m_axi_rdata    := ram.io.s_axi_rdata
  fe.io.m_axi_rlast    := ram.io.s_axi_rlast
  fe.io.m_axi_rvalid   := ram.io.s_axi_rvalid
  ram.io.s_axi_rready  := fe.io.m_axi_rready

  ram.io.dbg_rd_addr := io.dbg_rd_addr
  io.dbg_rd_data     := ram.io.dbg_rd_data
  ram.io.pre_wr_en   := io.pre_wr_en
  ram.io.pre_wr_addr := io.pre_wr_addr
  ram.io.pre_wr_data := io.pre_wr_data

  io.dbg_pc   := fe.io.dbg_pc
  io.dbg_word := fe.io.dbg_word
  io.dbg_run  := fe.io.dbg_run
  io.dbg_startEdge := fe.io.dbg_startEdge
}

class NpuProgramEngineFrontendSpec extends AnyFlatSpec {

  val K = 32

  // ---- RAM helpers ---------------------------------------------------------

  def preload(dut: FrontendSpecHarness, base: Long, bytes: Seq[Int]): Unit = {
    for ((b, i) <- bytes.grouped(16).zipWithIndex) {
      var w = BigInt(0)
      for ((v, j) <- b.zipWithIndex) w |= (BigInt(v & 0xFF) << (8 * j))
      dut.io.pre_wr_addr.poke((((base + i * 16) >> 4) & 0xFFF).U)
      dut.io.pre_wr_data.poke(w.U)
      dut.io.pre_wr_en.poke(true.B)
      dut.clock.step()
      dut.io.pre_wr_en.poke(false.B)
    }
  }

  def preloadI8(dut: FrontendSpecHarness, base: Long, values: Seq[Int]): Unit =
    preload(dut, base, values.map(_ & 0xFF))

  def preloadI32(dut: FrontendSpecHarness, base: Long, values: Seq[Int]): Unit = {
    val bytes = values.flatMap(v => Seq(v & 0xFF, (v >> 8) & 0xFF, (v >> 16) & 0xFF, (v >> 24) & 0xFF))
    preload(dut, base, bytes)
  }

  def preloadInstrs(dut: FrontendSpecHarness, instrs: Seq[Int]): Unit = {
    val bytes = instrs.flatMap(w => Seq(w & 0xFF, (w >> 8) & 0xFF, (w >> 16) & 0xFF, (w >> 24) & 0xFF))
    preload(dut, NpuSections.CODE_BASE, bytes)
  }

  def readI32(dut: FrontendSpecHarness, base: Long, n: Int): Array[Int] = {
    val out = Array.fill(4 * n)(0)
    for (w <- 0 until 4 * n / 16) {
      dut.io.dbg_rd_addr.poke((((base + 16 * w) >> 4) & 0xFFF).U)
      dut.clock.step(0)
      val got = dut.io.dbg_rd_data.peek().litValue
      for (b <- 0 until 16) out(16 * w + b) = ((got >> (8 * b)) & 0xFF).toInt
    }
    Array.tabulate(n)(i => out(4 * i) | (out(4 * i + 1) << 8) | (out(4 * i + 2) << 16) | (out(4 * i + 3) << 24))
  }

  // ---- ctrl helpers --------------------------------------------------------

  def ctrlWrite(dut: FrontendSpecHarness, addr: Int, data: Int): Unit = {
    dut.io.ctrl_addr.poke(addr.U)
    dut.io.ctrl_we.poke(true.B)
    dut.io.ctrl_wdata.poke((data.toLong & 0xFFFFFFFFL).U)
    dut.clock.step()
    dut.io.ctrl_we.poke(false.B)
  }

  def ctrlRead(dut: FrontendSpecHarness, addr: Int): Long = {
    dut.io.ctrl_addr.poke(addr.U)
    dut.io.ctrl_we.poke(false.B)
    dut.clock.step(0)
    dut.io.ctrl_rdata.peek().litValue.toLong
  }

  def start(dut: FrontendSpecHarness): Unit = {
    ctrlWrite(dut, 0x0, 1)
    ctrlWrite(dut, 0x0, 0)
  }

  def waitDone(dut: FrontendSpecHarness, maxCycles: Int = 500000): Unit = {
    var n = 0
    while (((ctrlRead(dut, 0x0) >> 1) & 1) == 0 && n < maxCycles) {
      dut.clock.step(); n += 1
    }
    assert(((ctrlRead(dut, 0x0) >> 1) & 1) == 1, s"program did not finish within $maxCycles cycles")
  }

  def s8(v: Int): Int = { val b = v & 0xFF; if (b >= 128) b - 256 else b }

  // ==========================================================================

  "NpuProgramEngineFrontend" should "run the one-shot MMA program end-to-end" in {
    simulate(new FrontendSpecHarness) { dut =>
      val a = Array.tabulate(K)(i => i + 1)
      val b = Array.tabulate(K)(i => i + 1)
      val acc = Array.tabulate(K)(i => 1000 * i + 7)
      preloadI8(dut, NpuSections.A, a)
      preloadI8(dut, NpuSections.B, b)
      preloadI32(dut, NpuSections.ACCUM, acc)
      preloadInstrs(dut, Seq(
        vle8(rd = 0, sect = NpuSections.SECT_A, off = 0),
        vle8(rd = 1, sect = NpuSections.SECT_B, off = 0),
        mmaLast(rd = 4, rs1 = 0, rs2 = 1, keep = false),
        vse32(src = 4, sect = NpuSections.SECT_OUT, off = 0),
      ))
      ctrlWrite(dut, 0x14, 4)   // PROG_LEN
      start(dut)
      waitDone(dut)

      val out = readI32(dut, NpuSections.OUT, K)
      for (i <- 0 until K) {
        assert(out(i) == a(i) * b(K - 1) + acc(i), s"lane $i: got ${out(i)}")
      }
      val status = ctrlRead(dut, 0x8)
      assert(((status >> 31) & 1) == 0, "no illegal expected")
      assert((status & 0xFFFF) == 3, s"pc should be 3, got ${status & 0xFFFF}")
      assert(((status >> 16) & 0x7FFF) == 1, "frames_done should be 1")
    }
  }

  "NpuProgramEngineFrontend" should "run a K×K program with prefetch (LRU cache)" in {
    simulate(new FrontendSpecHarness) { dut =>
      val a = Array.tabulate(K)(i => i + 1)
      val b = Array.tabulate(K * K)(idx => (idx / K) * 100 + (idx % K))
      val acc = Array.fill(K)(0)
      preloadI8(dut, NpuSections.A, a)
      preloadI8(dut, NpuSections.B, b)
      preloadI32(dut, NpuSections.ACCUM, acc)

      val cols = 4
      val prog = Seq(vle8(rd = 0, sect = NpuSections.SECT_A, off = 0)) ++
        (0 until cols).flatMap(j => Seq(
          vle8(rd = 1, sect = NpuSections.SECT_B, off = j * K),
          mmaLast(rd = 4 + j, rs1 = 0, rs2 = 1, keep = false),
          vse32(src = 4 + j, sect = NpuSections.SECT_OUT, off = j * 128)))
      preloadInstrs(dut, prog)
      ctrlWrite(dut, 0x14, prog.length)
      start(dut)
      waitDone(dut)

      val out = readI32(dut, NpuSections.OUT, K * cols)
      for (j <- 0 until cols) {
        val bLast = s8(j * 100 + 31)
        for (i <- 0 until K) {
          assert(out(j * K + i) == a(i) * bLast + acc(i),
            s"col $j lane $i: got ${out(j * K + i)} want ${a(i) * bLast}")
        }
      }
      val status = ctrlRead(dut, 0x8)
      assert(((status >> 16) & 0x7FFF) == cols, "frames_done should equal cols")
      val stats = ctrlRead(dut, 0x10)
      val misses = stats & 0xFFFF
      val prefetches = (stats >> 16) & 0xFFFF
      assert(misses <= (prog.length + 3) / 4, s"misses $misses too high for ${prog.length} instrs")
      assert(prefetches > 0, "expected at least one prefetch")
    }
  }

  "NpuProgramEngineFrontend" should "halt on an illegal instruction with ERR_INFO" in {
    simulate(new FrontendSpecHarness) { dut =>
      val prog = Seq(
        vle8(rd = 0, sect = NpuSections.SECT_A, off = 0),
        vadd(rd = 0, rs1 = 1, rs2 = 2),   // VALU: unsupported → illegal
        vse8(src = 0, sect = NpuSections.SECT_OUT, off = 0),
      )
      preloadInstrs(dut, prog)
      ctrlWrite(dut, 0x14, prog.length)
      start(dut)
      waitDone(dut)

      val status = ctrlRead(dut, 0x8)
      assert(((status >> 31) & 1) == 1, "illegal flag must be set")
      assert((status & 0xFFFF) == 1, s"pc should stop at 1, got ${status & 0xFFFF}")
      val err = ctrlRead(dut, 0xC)
      assert(err == (vadd(rd = 0, rs1 = 1, rs2 = 2).toLong & 0xFFFFFFFFL),
        s"ERR_INFO should hold the faulting word, got 0x${err.toHexString}")
    }
  }

  "NpuProgramEngineFrontend" should "support a PROG_LEN that is not a multiple of 4" in {
    simulate(new FrontendSpecHarness) { dut =>
      val a = Array.tabulate(K)(i => i + 1)
      val b = Array.tabulate(K)(i => i + 1)
      val acc = Array.fill(K)(0)
      preloadI8(dut, NpuSections.A, a)
      preloadI8(dut, NpuSections.B, b)
      preloadI32(dut, NpuSections.ACCUM, acc)
      // 5 instructions: last line (words 4..7) holds 4 words, only word 4 runs
      val prog = Seq(
        vle8(rd = 0, sect = NpuSections.SECT_A, off = 0),
        vle8(rd = 1, sect = NpuSections.SECT_B, off = 0),
        mmaLast(rd = 4, rs1 = 0, rs2 = 1, keep = false),
        vse32(src = 4, sect = NpuSections.SECT_OUT, off = 0),
        nop,   // never executed
      )
      preloadInstrs(dut, prog)
      ctrlWrite(dut, 0x14, prog.length)
      start(dut)
      waitDone(dut)

      val out = readI32(dut, NpuSections.OUT, K)
      for (i <- 0 until K) assert(out(i) == a(i) * b(K - 1) + acc(i), s"lane $i")
      assert(((ctrlRead(dut, 0x8) >> 31) & 1) == 0, "no illegal expected")
    }
  }

  "NpuProgramEngineFrontend" should "re-fill after start invalidation with stable misses" in {
    simulate(new FrontendSpecHarness) { dut =>
      val a = Array.tabulate(K)(i => i + 1)
      val b = Array.tabulate(K)(i => i + 1)
      val acc = Array.fill(K)(0)
      preloadI8(dut, NpuSections.A, a)
      preloadI8(dut, NpuSections.B, b)
      preloadI32(dut, NpuSections.ACCUM, acc)
      val prog = Seq(
        vle8(rd = 0, sect = NpuSections.SECT_A, off = 0),
        vle8(rd = 1, sect = NpuSections.SECT_B, off = 0),
        mmaLast(rd = 4, rs1 = 0, rs2 = 1, keep = false),
        vse32(src = 4, sect = NpuSections.SECT_OUT, off = 0),
      )
      preloadInstrs(dut, prog)
      ctrlWrite(dut, 0x14, prog.length)
      start(dut)
      waitDone(dut)
      val stats1 = ctrlRead(dut, 0x10)

      start(dut)   // start invalidates the cache (program may have changed)
      waitDone(dut)
      val stats2 = ctrlRead(dut, 0x10)
      val misses1 = stats1 & 0xFFFF
      val misses2 = stats2 & 0xFFFF
      // the miss counter is cumulative across runs; each start invalidates the
      // cache, so run2 must add exactly one more demand miss (its 1 line)
      assert(misses2 == misses1 + 1,
        s"each run should re-demand-fill the 1 program line: run1=$misses1 run2=$misses2")
      // results identical on both runs
      val out = readI32(dut, NpuSections.OUT, K)
      for (i <- 0 until K) assert(out(i) == a(i) * b(K - 1) + acc(i), s"lane $i")
    }
  }

  "NpuProgramEngineFrontend" should "run the one-shot program end-to-end at K=16" in {
    simulate(new FrontendSpecHarness(K = 16)) { dut =>
      val k = 16
      val a = Array.tabulate(k)(i => i + 1)
      val b = Array.tabulate(k)(i => (i * 5 + 2) & 0xFF)
      val acc = Array.fill(k)(0)
      def s8(v: Int): Int = { val x = v & 0xFF; if (x >= 128) x - 256 else x }
      preloadI8(dut, NpuSections.A, a)
      preloadI8(dut, NpuSections.B, b)
      preloadI32(dut, NpuSections.ACCUM, acc)
      val prog = Seq(
        vle8(rd = 0, sect = NpuSections.SECT_A, off = 0),
        vle8(rd = 1, sect = NpuSections.SECT_B, off = 0),
        mmaLast(rd = 4, rs1 = 0, rs2 = 1, keep = false),
        vse32(src = 4, sect = NpuSections.SECT_OUT, off = 0),
      )
      preloadInstrs(dut, prog)
      ctrlWrite(dut, 0x14, prog.length)
      start(dut)
      waitDone(dut)

      val out = readI32(dut, NpuSections.OUT, k)
      for (i <- 0 until k) {
        assert(out(i) == a(i) * s8(b(k - 1)) + acc(i), s"K16 lane $i: got ${out(i)}")
      }
      val status = ctrlRead(dut, 0x8)
      assert(((status >> 31) & 1) == 0, "no illegal expected")
      assert((status & 0xFFFF) == 3, s"pc should be 3")
      assert(((status >> 16) & 0x7FFF) == 1, "frames_done should be 1")
    }
  }
}
