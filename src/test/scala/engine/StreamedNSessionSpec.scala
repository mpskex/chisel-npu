// See README.md for license details.
// -----------------------------------------------------------------------------
//  StreamedNSessionSpec.scala — streamed n-mma sessions.  The program is the
//  pure stream form (vle, vle, mma, vse, …) with zero NOP padding; the
//  software reuses the operand registers (VX[4]/VX[8]) and the capture slot
//  (VR[0]) per feed, and the chained vse drains each capture.  Every stored
//  capture must equal a column of the running partial sum Σ_{j≤m} a_j·b_j.
// -----------------------------------------------------------------------------

package engine

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec
import testUtil._
import isa._
import isa.NpuAssembler._

class StreamedNSessionSpec extends AnyFlatSpec {

  def s8(v: Int): Int = { val b = v & 0xFF; if (b >= 128) b - 256 else b }

  def checkStreamed(n: Int, latency: Int): Unit = {
    val k = 16
    // int8 signed: values >= 128 are negative (0x80..0xFF).  The operands
    // are stored as int8 bytes; the PE multiplies SIGNED — expectations use
    // the s8 interpretation, exactly like the hardware tests.
    val aVals = Array.tabulate(n, k)((m, j) => s8(m * 7 + j + 1))
    val bVals = Array.tabulate(n, k)((m, i) => s8(100 + m * 5 + i))
    simulate(new StreamHarness(K = 16, readLatency = latency)) { dut =>
      StreamSpecUtil.preloadI8(dut, NpuSections.A, aVals.flatten.map(_ & 0xFF))
      StreamSpecUtil.preloadI8(dut, NpuSections.B, bVals.flatten.map(_ & 0xFF))
      for (m <- 0 until n) {
        StreamSpecUtil.issue(dut, vle8(4, NpuSections.SECT_A, m * k))
        StreamSpecUtil.issue(dut, vle8(8, NpuSections.SECT_B, m * k))
        StreamSpecUtil.issue(dut, mma(0, 4, 8, 0))
        StreamSpecUtil.issue(dut, vse32(0, NpuSections.SECT_OUT, m * 4 * k))
      }
      StreamSpecUtil.issue(dut, mmaLast(0, 0, 0, 0))
      for (t <- 0 until 10000) { dut.clock.step() }
      val out = StreamSpecUtil.readI32(dut, NpuSections.OUT, n * k)
      // capture m must be a column of the running sum Σ_{j≤m} (signed int8)
      for (m <- 0 until n) {
        val g = out.slice(m * k, (m + 1) * k)
        val pe = Array.tabulate(k, k)((i, j) => (0 to m).map(t => aVals(t)(i) * bVals(t)(j)).sum)
        val cols = StreamSpecUtil.columnsOf(g, pe)
        assert(cols.nonEmpty,
          s"n=$n lat=$latency capture $m is not a running-sum column: ${g.toList}")
      }
      println(s"streamed n=$n latency=$latency: OK")
    }
  }

  "NpuProgramEngine" should "stream a 3-mma session (running sums) at realistic latencies" in {
    for (lat <- Seq(1, 50, 100, 200)) checkStreamed(3, lat)
  }

  "NpuProgramEngine" should "read back a vle-loaded VX register" in {
    simulate(new StreamHarness(K = 16, readLatency = 100)) { dut =>
      StreamSpecUtil.preloadI8(dut, NpuSections.A, Array.tabulate(16)(i => 100 + i))
      StreamSpecUtil.issue(dut, vle8(4, NpuSections.SECT_A, 0))
      for (t <- 0 until 500) { dut.clock.step() }
      dut.io.dbg_vx_addr.poke(4.U)
      dut.clock.step(0)
      val vx4 = (0 until 4).map(i => dut.io.dbg_vx_data(i).peek().litValue.toInt).toList
      println(s"VX[4] after vle: $vx4 (want List(100, 101, 102, 103))")
      assert(vx4 == List(100, 101, 102, 103))
    }
  }

  "NpuProgramEngine" should "stream 8/16/32-mma sessions (software reuse) at latency 100" in {
    for (n <- Seq(8, 16, 32)) checkStreamed(n, 100)
  }

  "NpuProgramEngine" should "add the C operand to a streamed one-shot capture" in {
    val k = 16
    val a = Array.tabulate(k)(j => j + 1)
    val b = Array.tabulate(k)(i => 100 + i)
    val c = Array.tabulate(k)(i => 7 + i)
    simulate(new StreamHarness(K = 16, readLatency = 100)) { dut =>
      StreamSpecUtil.preloadI8(dut, NpuSections.A, a.map(_ & 0xFF))
      StreamSpecUtil.preloadI8(dut, NpuSections.B, b.map(_ & 0xFF))
      StreamSpecUtil.preloadI32(dut, NpuSections.ACCUM, c)
      StreamSpecUtil.issue(dut, vle8(4, NpuSections.SECT_A, 0))
      StreamSpecUtil.issue(dut, vle8(8, NpuSections.SECT_B, 0))
      StreamSpecUtil.issue(dut, vle32(3, NpuSections.SECT_ACCUM, 0))
      StreamSpecUtil.issue(dut, mma(2, 4, 8, 3))
      StreamSpecUtil.issue(dut, mmaLast(0, 0, 0, 0))
      StreamSpecUtil.issue(dut, vse32(2, NpuSections.SECT_OUT, 0))
      for (t <- 0 until 300) { dut.clock.step() }
      for (t <- 0 until 10000) { dut.clock.step() }
      val out = StreamSpecUtil.readI32(dut, NpuSections.OUT, k)
      val cols = (0 until k).filter(col => (0 until k).forall(i => out(i) == a(i) * b(col) + c(i)))
      assert(cols.nonEmpty, s"C not added: ${out.toList}")
    }
  }
}
