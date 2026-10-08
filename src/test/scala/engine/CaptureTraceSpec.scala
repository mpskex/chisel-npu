// See README.md for license details.
// -----------------------------------------------------------------------------
//  CaptureTraceSpec.scala — trace the boundary/capture timing on the wrong run.
// -----------------------------------------------------------------------------

package engine

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec
import testUtil._
import isa._
import isa.NpuAssembler._

class CaptureTraceSpec extends AnyFlatSpec {

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
  def preloadI32(dut: FrontendSpecHarness, base: Long, values: Seq[Int]): Unit =
    preload(dut, base, values.flatMap(v => Seq(v & 0xFF, (v >> 8) & 0xFF, (v >> 16) & 0xFF, (v >> 24) & 0xFF)))
  def preloadInstrs(dut: FrontendSpecHarness, instrs: Seq[Int]): Unit =
    preload(dut, NpuSections.CODE_BASE, instrs.flatMap(w => Seq(w & 0xFF, (w >> 8) & 0xFF, (w >> 16) & 0xFF, (w >> 24) & 0xFF)))
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
  def start(dut: FrontendSpecHarness): Unit = { ctrlWrite(dut, 0x0, 1); ctrlWrite(dut, 0x0, 0) }

  "trace" should "show the boundary/capture timing on the wrong run" in {
    simulate(new FrontendSpecHarness(K = 16)) { dut =>
      val k = 16
      val a = Array.tabulate(k)(j => j + 1)
      val b = Array.tabulate(k)(i => 100 + i)
      preloadI8(dut, NpuSections.A, a)
      preloadI8(dut, NpuSections.B, b)
      val prog = Seq(vle8(4, NpuSections.SECT_A, 0), vle8(8, NpuSections.SECT_B, 0),
        mma(2, 4, 8, 0), mmaLast(0, 0, 0, 0), vse32(2, NpuSections.SECT_OUT, 0))
      preloadInstrs(dut, prog)

      var wrong = 0
      for (iter <- 0 until 10) {
        preloadI32(dut, NpuSections.OUT, Array.fill(k)(0))
        ctrlWrite(dut, 0x14, prog.length)
        start(dut)
        // trace the run: record bnd, clct, mma, drain, pc per cycle
        var done = false; var n = 0
        var trace = scala.collection.mutable.ArrayBuffer[String]()
        while (!done && n < 500000) {
          val bnd = dut.io.dbg_bnd.peek().litValue.toLong
          val cl = dut.io.dbg_clct.peek().litValue.toLong
          val mma = dut.io.dbg_acc.peek().litValue.toLong
          val drn = dut.io.dbg_drain.peek().litValue.toLong
          val run = dut.io.dbg_run.peek().litValue.toLong
          val mo0 = dut.io.dbg_mma_out0.peek().litValue.toLong
          val cnt = dut.io.dbg_cnt.peek().litValue.toLong
          val ia0 = dut.io.dbg_in_a0.peek().litValue.toLong
          val dma = dut.io.dbg_dma.peek().litValue.toLong
          val vx4 = dut.io.dbg_vx_data(0).peek().litValue.toLong
          val frs1 = dut.io.dbg_feed_rs1.peek().litValue.toLong
          val word = dut.io.dbg_word.peek().litValue.toLong
          val flast = dut.io.dbg_feed_last.peek().litValue.toLong
          val srs1 = dut.io.dbg_slot_rs1.peek().litValue.toLong
          val wc = dut.io.dbg_wcount.peek().litValue.toLong
          val mp = dut.io.dbg_mma_pend.peek().litValue.toLong
          val s2v = dut.io.dbg_s2_valid.peek().litValue.toLong
          val s2d = dut.io.dbg_s2_done.peek().litValue.toLong
          val s2u = dut.io.dbg_s2_unit.peek().litValue.toLong
          val s2l = dut.io.dbg_s2_last.peek().litValue.toLong
          trace += s"$n bnd=$bnd clct=$cl mma=$mma drn=$drn run=$run mo0=$mo0 cnt=$cnt ia0=$ia0 dma=$dma vx4=$vx4 frs1=$frs1 w=${word.toHexString} last=$flast srs1=$srs1 wc=$wc mp=$mp s2(v,d,u,l)=$s2v,$s2d,$s2u,$s2l"
          dut.clock.step(); n += 1
          if ((ctrlRead(dut, 0x0) >> 1 & 1) == 1) done = true
        }
        val out = readI32(dut, NpuSections.OUT, k)
        val cols = (0 until k).filter(c => out.indices.forall(i => out(i) == a(i) * b(c)))
        if (cols.isEmpty) {
          wrong += 1
          println(s"=== iter $iter WRONG out=${out.take(4).toList} ===")
          // mma is cumulative; the run's feeds are mmaPrev->mmaPrev+2
          val mmaPrev = 2 * iter.toLong
          // print the trace from the mma feed (mmaPrev+1) for ~130 cycles
          val feedIdx = trace.indexWhere(_.split(" ").find(_.startsWith("mma=")).get.split("=")(1).toLong == mmaPrev + 1)
          if (feedIdx >= 0) {
            println("--- FULL trace 0..%d ---" format (trace.length))
            trace.foreach(println)
          } else {
            println("trace len=" + trace.length)
          }
        }
      }
      assert(wrong == 0, s"$wrong wrong captures")
    }
  }
}
