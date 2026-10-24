// See README.md for license details.
// -----------------------------------------------------------------------------
//  ChainedCaptureSpec.scala — K-parameterized reduce matmul rounds through the
//  FRONTEND with a realistic AXI/DRAM latency model.
//
//  Each round is K accumulate feeds + mma.last (capture into VR[vd]) + one
//  vse32 that reads VR[vd] directly from the RF.  The engine-level chained
//  spec bypasses the frontend and the RAM models were fixed-latency /
//  immediate-B, which hid the hardware failure.  This spec runs the real
//  instruction path with variable read latency + jitter + delayed B, for
//  several K, so it is not tied to one configuration.
// -----------------------------------------------------------------------------

package engine

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec
import testUtil._
import isa._
import isa.NpuAssembler._

class ChainedCaptureSpec extends AnyFlatSpec {

  def s8(v: Int): Int = { val b = v & 0xFF; if (b >= 128) b - 256 else b }

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
  def preloadI8(dut: FrontendSpecHarness, base: Long, v: Seq[Int]): Unit =
    preload(dut, base, v.map(_ & 0xFF))
  def preloadI32(dut: FrontendSpecHarness, base: Long, v: Seq[Int]): Unit =
    preload(dut, base, v.flatMap(x =>
      Seq(x & 0xFF, (x >> 8) & 0xFF, (x >> 16) & 0xFF, (x >> 24) & 0xFF)))
  def preloadInstrs(dut: FrontendSpecHarness, instrs: Seq[Int]): Unit =
    preload(dut, NpuSections.CODE_BASE, instrs.flatMap(w =>
      Seq(w & 0xFF, (w >> 8) & 0xFF, (w >> 16) & 0xFF, (w >> 24) & 0xFF)))
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
    dut.io.ctrl_addr.poke(addr.U); dut.io.ctrl_we.poke(true.B)
    dut.io.ctrl_wdata.poke((data.toLong & 0xFFFFFFFFL).U)
    dut.clock.step(); dut.io.ctrl_we.poke(false.B)
  }
  def ctrlRead(dut: FrontendSpecHarness, addr: Int): Long = {
    dut.io.ctrl_addr.poke(addr.U); dut.io.ctrl_we.poke(false.B)
    dut.clock.step(0); dut.io.ctrl_rdata.peek().litValue.toLong
  }
  def waitDone(dut: FrontendSpecHarness, max: Int = 2000000): Unit = {
    var n = 0
    while (((ctrlRead(dut, 0x0) >> 1) & 1) == 0 && n < max) { dut.clock.step(); n += 1 }
    assert(((ctrlRead(dut, 0x0) >> 1) & 1) == 1, s"program did not finish within $max cycles")
  }

  /** One reduce round: vle8/vle8, K accumulate mmas, mma.last (capture into
    * VR[vd]), one vse32 of VR[vd].  Distinct per-round operand rows m. */
  def reduceRoundProg(k: Int, m: Int, vd: Int): Seq[Int] =
    (0 until m).flatMap { mm =>
      Seq(vle8(4, NpuSections.SECT_A, mm * k),
          vle8(8, NpuSections.SECT_B, mm * k)) ++
        (0 until k).map(_ => mma(vd, 4, 8, 0)) ++
        Seq(mmaLast(vd, 0, 0, 0),
            vse32(vd, NpuSections.SECT_OUT, mm * 4 * k))
    }

  /** Reduce-mode chained rounds through the frontend with a realistic memory
    * model.  Returns the capture columns. */
  def runChained(K: Int, lat: Int, jit: Int, bd: Int, m: Int = 3): Seq[Int] = {
    var cols = Seq.empty[Int]
    simulate(new FrontendSpecHarness(K = K, readLatency = lat,
                                     readJitter = jit, bDelay = bd)) { dut =>
      val k = K
      val aVals = Array.tabulate(m, k)((mm, j) => (1 + ((mm * 11 + j * 3) % 31)) & 0xFF)
      val bVals = Array.tabulate(m, k)((mm, i) => (1 + ((mm * 7 + i * 5) % 29)) & 0xFF)
      preloadI8(dut, NpuSections.A, aVals.flatten.toSeq)
      preloadI8(dut, NpuSections.B, bVals.flatten.toSeq)
      val prog = reduceRoundProg(k, m, vd = 0)
      preloadInstrs(dut, prog)

      preloadI32(dut, NpuSections.OUT, Array.fill(m * k)(0))
      ctrlWrite(dut, 0x14, prog.length)
      ctrlWrite(dut, 0x0, 1); ctrlWrite(dut, 0x0, 0)
      waitDone(dut)

      val out = readI32(dut, NpuSections.OUT, m * k)
      val a8 = aVals.map(_.map(s8)); val b8 = bVals.map(_.map(s8))
      cols = (0 until m).map { mm =>
        val got = out.slice(mm * k, (mm + 1) * k)
        val pe = Array.tabulate(k, k)((i, j) => k * a8(mm)(i) * b8(mm)(j))
        val c = (0 until k).filter(cc => (0 until k).forall(i => got(i) == pe(i)(cc)))
        assert(c.nonEmpty,
          s"K=$K lat=$lat jit=$jit bd=$bd round $mm wrong: " +
          s"got=${got.take(8).toList} exp0=${(0 until 8).map(pe(_)(0)).toList}")
        c.head
      }
    }
    cols
  }

  "NpuProgramEngineFrontend reduce rounds" should
    "match the K*a*b columns across K and DRAM latency" in {
    for (K <- Seq(16, 32);
         (lat, jit, bd) <- Seq((1, 0, 0), (32, 32, 8), (100, 100, 32), (1, 0, 300))) {
      val cols = runChained(K, lat, jit, bd)
      println(s"[chained] K=$K lat=$lat jit=$jit bd=$bd columns=$cols")
    }
  }

  it should "run a reduce session correctly after a one-shot reduce session" in {
    for (K <- Seq(16, 32)) {
      simulate(new FrontendSpecHarness(K = K, readLatency = 64,
                                       readJitter = 32, bDelay = 8)) { dut =>
        val k = K
        val m = 3
        val aVals = Array.tabulate(m, k)((mm, j) => (1 + ((mm * 11 + j * 3) % 31)) & 0xFF)
        val bVals = Array.tabulate(m, k)((mm, i) => (1 + ((mm * 7 + i * 5) % 29)) & 0xFF)

        def startAndWait(prog: Seq[Int]): Unit = {
          preloadInstrs(dut, prog)
          preloadI32(dut, NpuSections.OUT, Array.fill(4 * k)(0))
          ctrlWrite(dut, 0x14, prog.length)
          ctrlWrite(dut, 0x0, 1); ctrlWrite(dut, 0x0, 0)
          waitDone(dut)
        }

        preloadI8(dut, NpuSections.A, aVals.flatten.toSeq)
        preloadI8(dut, NpuSections.B, bVals.flatten.toSeq)

        // session 1: one-shot reduce round (vd=2), like the py suite runs first
        println("=== MARK ONE-SHOT ===")
        startAndWait(Seq(vle8(4, NpuSections.SECT_A, 0),
                         vle8(8, NpuSections.SECT_B, 0)) ++
                     (0 until k).map(_ => mma(2, 4, 8, 0)) ++
                     Seq(mmaLast(2, 0, 0, 0),
                         vse32(2, NpuSections.SECT_OUT, 0)))

        // session 2: chained reduce rounds (vd=0), distinct operands
        println("=== MARK CHAINED ===")
        startAndWait(reduceRoundProg(k, m, vd = 0))

        val out = readI32(dut, NpuSections.OUT, m * k)
        val a8 = aVals.map(_.map(s8)); val b8 = bVals.map(_.map(s8))
        for (mm <- 0 until m) {
          val got = out.slice(mm * k, (mm + 1) * k)
          val pe = Array.tabulate(k, k)((i, j) => k * a8(mm)(i) * b8(mm)(j))
          val c = (0 until k).filter(cc => (0 until k).forall(i => got(i) == pe(i)(cc)))
          assert(c.nonEmpty,
            s"K=$K after one-shot, round $mm wrong: got=${got.take(8).toList} " +
            s"exp0=${(0 until 8).map(pe(_)(0)).toList}")
        }
      }
    }
  }
}
