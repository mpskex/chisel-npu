// See README.md for license details.
// Integration tests for NpuProgramEngineFrontend: session programs staged in
// CODE, run via the ctrl_lite interface, verified through the OUT section.

package engine

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec
import testUtil._
import isa.NpuAssembler._

class FrontendSpecHarness(val K: Int = 32) extends Module {
  val io = IO(new Bundle {
    val ctrl_addr  = Input(UInt(7.W))
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
    val dbg_clct = Output(UInt(16.W))
    val dbg_wcount = Output(UInt(5.W))
    val dbg_acc    = Output(UInt(16.W))
    val dbg_clctc  = Output(UInt(16.W))
    val dbg_dma    = Output(UInt(16.W))
    val dbg_bnd    = Output(UInt(7.W))
    val dbg_drain  = Output(Bool())
    val dbg_mma_out0 = Output(UInt(32.W))
    val dbg_cnt    = Output(UInt(4.W))
    val dbg_in_a0  = Output(UInt(8.W))
    val dbg_vx_data = Output(Vec(K, UInt(8.W)))
    val dbg_feed_rs1 = Output(UInt(5.W))
    val dbg_feed_last = Output(Bool())
    val dbg_slot_rs1  = Output(UInt(5.W))
    val dbg_mma_pend = Output(Bool())
    val dbg_s2_valid = Output(Bool())
    val dbg_s2_done  = Output(Bool())
    val dbg_s2_unit  = Output(UInt(2.W))
    val dbg_s2_last  = Output(Bool())
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
  io.dbg_clct := fe.io.dbg_clct
  io.dbg_wcount := fe.io.dbg_wcount
  io.dbg_bnd    := fe.io.dbg_bnd
  io.dbg_drain  := fe.io.dbg_drain
  io.dbg_mma_out0 := fe.io.dbg_mma_out0
  io.dbg_cnt    := fe.io.dbg_cnt
  io.dbg_in_a0  := fe.io.dbg_in_a0
  io.dbg_vx_data := fe.io.dbg_vx_data
  io.dbg_feed_rs1 := fe.io.dbg_feed_rs1
  io.dbg_feed_last := fe.io.dbg_feed_last
  io.dbg_slot_rs1  := fe.io.dbg_slot_rs1
  io.dbg_mma_pend := fe.io.dbg_mma_pend
  io.dbg_s2_valid := fe.io.dbg_s2_valid
  io.dbg_s2_done  := fe.io.dbg_s2_done
  io.dbg_s2_unit  := fe.io.dbg_s2_unit
  io.dbg_s2_last  := fe.io.dbg_s2_last
  io.dbg_acc    := fe.io.dbg_mma_acc
  io.dbg_clctc  := fe.io.dbg_clct
  io.dbg_dma    := fe.io.dbg_dma_done
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
    Array.tabulate(n)(i => out(4 * i) | (out(4 * i + 1) << 8) |
      (out(4 * i + 2) << 16) | (out(4 * i + 3) << 24))
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
    var lastAcc = BigInt(-1)
    while (((ctrlRead(dut, 0x0) >> 1) & 1) == 0 && n < maxCycles) {
      val acc = dut.io.dbg_acc.peek().litValue
      if (acc != lastAcc) {
        ()
        lastAcc = acc
      }
      dut.clock.step(); n += 1
    }
    assert(((ctrlRead(dut, 0x0) >> 1) & 1) == 1, s"program did not finish within $maxCycles cycles")
  }

  def s8(v: Int): Int = { val b = v & 0xFF; if (b >= 128) b - 256 else b }

  // ==========================================================================

  "NpuProgramEngineFrontend" should "run a one-shot mma session end-to-end" in {
    simulate(new FrontendSpecHarness(K = 16)) { dut =>
      val k = 16
      val a = Array.tabulate(k)(j => j + 1)
      val b = Array.tabulate(k)(i => 100 + i)
      val c = Array.tabulate(k)(i => 7 + i)
      preloadI8(dut, NpuSections.A, a)
      preloadI8(dut, NpuSections.B, b)
      preloadI32(dut, NpuSections.ACCUM, c)
      // C in VR[3] (VX[12..15]); vd = VR[2] (VX[8..11]); A = VX[4], B = VX[8]
      val prog = Seq(
        vle8(rd = 4, sect = NpuSections.SECT_A, off = 0),
        vle8(rd = 8, sect = NpuSections.SECT_B, off = 0),
        vle32(rd = 3, sect = NpuSections.SECT_ACCUM, off = 0),
        mma(rd = 2, vs1 = 4, vs2 = 8, vs3 = 3),
        mmaLast(rd = 0, vs1 = 0, vs2 = 0, vs3 = 0),
        vse32(src = 2, sect = NpuSections.SECT_OUT, off = 0),
      )
      preloadInstrs(dut, prog)
      ctrlWrite(dut, 0x14, prog.length)
      start(dut)
      waitDone(dut)


      val out = readI32(dut, NpuSections.OUT, k)
      // streamed: the capture is a column of a·b + C — locate it by search
      val cols = (0 until k).filter(col =>
        (0 until k).forall(i => out(i) == a(i) * s8(b(col)) + c(i)))
      assert(cols.nonEmpty, s"one-shot capture is not a column of a·b+C: ${out.toList}")
      println(s"[frontend] one-shot capture column: ${cols.head}")
      val status = ctrlRead(dut, 0x8)
      assert(((status >> 31) & 1) == 0, "no illegal expected")
      assert((status & 0xFFFF) == prog.length - 1, s"pc should be the last word")
      assert(((status >> 16) & 0x7FFF) == 1, "frames_done should be 1")
    }
  }

  "NpuProgramEngineFrontend" should "run a streamed 3-mma session (running sums)" in {
    simulate(new FrontendSpecHarness(K = 16)) { dut =>
      val k = 16
      val aVals = Array.tabulate(3, k)((m, j) => s8(m * 7 + j + 1))
      val bVals = Array.tabulate(3, k)((m, i) => s8(100 + m * 5 + i))
      preload(dut, NpuSections.A, aVals.flatten.map(_ & 0xFF))
      preload(dut, NpuSections.B, bVals.flatten.map(_ & 0xFF))

      // streamed form: reused operands (VX[4]/VX[8]) + vd=0 (VR[0], disjoint)
      // + per-feed vse; captures = the running partial sums
      val prog = Seq(
        vle8(rd = 4, sect = NpuSections.SECT_A, off = 0),
        vle8(rd = 8, sect = NpuSections.SECT_B, off = 0),
        mma(rd = 0, vs1 = 4, vs2 = 8, vs3 = 0),
        vse32(src = 0, sect = NpuSections.SECT_OUT, off = 0),
        vle8(rd = 4, sect = NpuSections.SECT_A, off = 16),
        vle8(rd = 8, sect = NpuSections.SECT_B, off = 16),
        mma(rd = 0, vs1 = 4, vs2 = 8, vs3 = 0),
        vse32(src = 0, sect = NpuSections.SECT_OUT, off = 64),
        vle8(rd = 4, sect = NpuSections.SECT_A, off = 32),
        vle8(rd = 8, sect = NpuSections.SECT_B, off = 32),
        mma(rd = 0, vs1 = 4, vs2 = 8, vs3 = 0),
        vse32(src = 0, sect = NpuSections.SECT_OUT, off = 128),
        mmaLast(rd = 0, vs1 = 0, vs2 = 0, vs3 = 0),
      )
      preloadInstrs(dut, prog)
      ctrlWrite(dut, 0x14, prog.length)
      start(dut)
      waitDone(dut)

      val out = readI32(dut, NpuSections.OUT, 3 * k)
      for (m <- 0 until 3) {
        val g = out.slice(m * k, (m + 1) * k)
        val pe = Array.tabulate(k, k)((i, j) => (0 to m).map(t => aVals(t)(i) * bVals(t)(j)).sum)
        val found = (0 until k).filter(c => (0 until k).forall(i => g(i) == pe(i)(c)))
        assert(found.nonEmpty, s"capture $m is not a running-sum column: ${g.toList}")
      }
      val status = ctrlRead(dut, 0x8)
      assert(((status >> 31) & 1) == 0, "no illegal expected")
      assert(((status >> 16) & 0x7FFF) == 1, "frames_done should be 1")
    }
  }

  "NpuProgramEngineFrontend" should "expose the collector trajectory via ctrl 0x20..0x2C" in {
    simulate(new FrontendSpecHarness(K = 16)) { dut =>
      val k = 16
      val a = Array.tabulate(k)(j => j + 1)
      val b = Array.tabulate(k)(i => 100 + i)
      preloadI8(dut, NpuSections.A, a)
      preloadI8(dut, NpuSections.B, b)
      val prog = Seq(
        vle8(rd = 4, sect = NpuSections.SECT_A, off = 0),
        vle8(rd = 8, sect = NpuSections.SECT_B, off = 0),
        mma(rd = 2, vs1 = 4, vs2 = 8, vs3 = 0),
        mmaLast(rd = 0, vs1 = 0, vs2 = 0, vs3 = 0),
        vse32(src = 2, sect = NpuSections.SECT_OUT, off = 0),
      )
      preloadInstrs(dut, prog)
      ctrlWrite(dut, 0x14, prog.length)
      start(dut)
      waitDone(dut)

      def entries(addr: Int, first: Int): Seq[Int] =
        (0 until 5).map(i => ((ctrlRead(dut, addr) >> (26 - 6 * i)) & 0x3F).toInt)
      val e = entries(0x20, 0) ++ entries(0x24, 5) ++ entries(0x28, 10) ++
        Seq(((ctrlRead(dut, 0x2C) >> 26) & 0x3F).toInt)
      // The snapshot is frozen at the boundary pulse: the 16-tick window
      // BEFORE the 1-tick keep=0 boundary, so keep must be 1 and dat_clct
      // high for every entry, with the collector cnt counting (1..15,0).
      e.foreach { x =>
        assert(((x >> 5) & 1) == 1, s"keep must be 1 in the snapshot window (entry=$x)")
        assert(((x >> 4) & 1) == 1, s"dat_clct must be high in the snapshot window (entry=$x)")
      }
      val cnts = e.map(_ & 0xF)
      // The collector's cnt is a free-running mod-16 counter (dat_clct held
      // high by the keep=1 idle pattern), so 16 consecutive samples must be
      // a full rotation of 0..15.
      assert(cnts.distinct.length == 16,
        s"collector cnt should be a full 0..15 rotation across the window, got $cnts")
      // 0x20..0x2C must not alias the legacy registers (the 5-bit-address
      // DCE trap read STATUS/CTRL/FRAMES/ERR_INFO instead).
      assert((ctrlRead(dut, 0x2C) & 0x3FFFFFF) == 0, "0x2C low bits must be zero pad")
    }
  }

  "NpuProgramEngineFrontend" should "halt on an illegal instruction with ERR_INFO" in {
    simulate(new FrontendSpecHarness) { dut =>
      val bad = 0x03 | (0 << 7) | (2 << 12) | (1 << 15) | (2 << 20)
      val prog = Seq(
        vle8(rd = 0, sect = NpuSections.SECT_A, off = 0),
        bad,
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
      assert(err == (bad.toLong & 0xFFFFFFFFL), s"ERR_INFO should hold the faulting word")
    }
  }

  "NpuProgramEngineFrontend" should "re-fill after start invalidation with stable misses" in {
    simulate(new FrontendSpecHarness(K = 16)) { dut =>
      val k = 16
      val a = Array.tabulate(k)(j => j + 1)
      val b = Array.tabulate(k)(i => 100 + i)
      preloadI8(dut, NpuSections.A, a)
      preloadI8(dut, NpuSections.B, b)
      val prog = Seq(
        vle8(rd = 4, sect = NpuSections.SECT_A, off = 0),
        vle8(rd = 8, sect = NpuSections.SECT_B, off = 0),
        mma(rd = 1, vs1 = 4, vs2 = 8, vs3 = 0),
        mmaLast(rd = 0, vs1 = 0, vs2 = 0, vs3 = 0),
        vse32(src = 1, sect = NpuSections.SECT_OUT, off = 0),
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
      assert(misses2 > misses1,
        s"each run should re-demand-fill the program lines: run1=$misses1 run2=$misses2")
    }
  }
}
