// See README.md for license details.
// -----------------------------------------------------------------------------
//  CaptureDeterminismSpec.scala — reproduce the intermittent wrong captures.
//
//  On silicon ~12% of one-shot sessions produce a wrong capture column (the
//  OUT is a·R with R outside the staged b vector).  This test runs the exact
//  program back-to-back many times (like the silicon loop) and asserts every
//  capture is a valid b-column.  If it fails here, the wrongness is a
//  deterministic RTL bug reproducible in simulation.
// -----------------------------------------------------------------------------

package engine

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec
import testUtil._
import isa._
import isa.NpuAssembler._

class CaptureDeterminismSpec extends AnyFlatSpec {

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
    while (((ctrlRead(dut, 0x0) >> 1) & 1) == 0 && n < maxCycles) { dut.clock.step(); n += 1 }
    assert(((ctrlRead(dut, 0x0) >> 1) & 1) == 1, s"program did not finish within $maxCycles cycles")
  }

  "NpuProgramEngineFrontend" should "produce a valid capture column on every back-to-back run" in {
    simulate(new FrontendSpecHarness(K = 16)) { dut =>
      val k = 16
      val a = Array.tabulate(k)(j => j + 1)
      val b = Array.tabulate(k)(i => 100 + i)
      preloadI8(dut, NpuSections.A, a)
      preloadI8(dut, NpuSections.B, b)
      val prog = Seq(
        vle8(4, NpuSections.SECT_A, 0),
        vle8(8, NpuSections.SECT_B, 0),
        mma(2, 4, 8, 0),
        mmaLast(0, 0, 0, 0),
        vse32(2, NpuSections.SECT_OUT, 0),
      )
      preloadInstrs(dut, prog)

      var wrong = 0
      for (iter <- 0 until 200) {
        preloadI32(dut, NpuSections.OUT, Array.fill(k)(0))
        ctrlWrite(dut, 0x14, prog.length) // PROG_LEN
        start(dut)
        waitDone(dut, 500000)
        val out = readI32(dut, NpuSections.OUT, k)
        val cols = (0 until k).filter(c => out.indices.forall(i => out(i) == a(i) * b(c)))
        if (cols.isEmpty) {
          wrong += 1
          if (wrong <= 3) {
            val r = if (a(1) != 0) out(1) / a(1) else -999
            val bnd = (ctrlRead(dut, 0x30) >> 24) & 0x7F
            val wc  = (ctrlRead(dut, 0x30) >> 19) & 0x1F
            val drn = (ctrlRead(dut, 0x30) >> 31) & 1
            println(s"iter $iter WRONG out[:4]=${out.take(4).toList} ratio=$r " +
              s"drain=$drn bnd=$bnd wcount=$wc clct=${dut.io.dbg_clct.peek().litValue} " +
              s"mma=${dut.io.dbg_acc.peek().litValue} dma=${dut.io.dbg_dma.peek().litValue}")
          }
        }
      }
      assert(wrong == 0, s"$wrong/200 captures were wrong (non-deterministic capture)")
      println(s"all 200 captures correct")
    }
  }
}
