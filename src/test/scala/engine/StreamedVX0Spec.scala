// See README.md for license details.
// -----------------------------------------------------------------------------
//  StreamedVX0Spec.scala — regression for the mma_last VX[0] feed bug.
//  mma_last is a session terminator and must inject ZEROS into the PEs; the
//  old engine fed VX[0] (vs1=vs2=0), adding a VX0[i]·VX0[c] residue on
//  silicon (sim only stayed clean because VX[0] resets to 0).  Load VX[0]
//  with garbage and verify the streamed captures stay clean.
// -----------------------------------------------------------------------------

package engine

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec
import testUtil._
import isa._
import isa.NpuAssembler._

class StreamedVX0Spec extends AnyFlatSpec {

  def s8(v: Int): Int = { val b = v & 0xFF; if (b >= 128) b - 256 else b }

  "NpuProgramEngine" should "not let stale VX[0] leak into captures via the mma_last feed" in {
    val k = 16
    val n = 4
    val aVals = Array.tabulate(n, k)((m, j) => s8(m * 7 + j + 1))
    val bVals = Array.tabulate(n, k)((m, i) => s8(100 + m * 5 + i))
    simulate(new StreamHarness(K = 16, readLatency = 100)) { dut =>
      // A section: [a data at 0..n*k)][garbage ramp at n*k..]
      val aBytes = aVals.flatten.map(_ & 0xFF) ++ Array.tabulate(16)(i => (i + 1))
      StreamSpecUtil.preloadI8(dut, NpuSections.A, aBytes)
      StreamSpecUtil.preloadI8(dut, NpuSections.B, bVals.flatten.map(_ & 0xFF))
      // load VX[0] with a garbage ramp (the would-be stale mma_last operand)
      StreamSpecUtil.issue(dut, vle8(0, NpuSections.SECT_A, n * k))   // VX[0] <- ramp 1..16
      for (m <- 0 until n) {
        StreamSpecUtil.issue(dut, vle8(4, NpuSections.SECT_A, m * k))
        StreamSpecUtil.issue(dut, vle8(8, NpuSections.SECT_B, m * k))
        StreamSpecUtil.issue(dut, mma(0, 4, 8, 0))
        StreamSpecUtil.issue(dut, vse32(0, NpuSections.SECT_OUT, m * 4 * k))
      }
      StreamSpecUtil.issue(dut, mmaLast(0, 0, 0, 0))
      for (t <- 0 until 10000) { dut.clock.step() }
      val out = StreamSpecUtil.readI32(dut, NpuSections.OUT, n * k)
      for (m <- 0 until n) {
        val g = out.slice(m * k, (m + 1) * k)
        val pe = Array.tabulate(k, k)((i, j) => (0 to m).map(t => aVals(t)(i) * bVals(t)(j)).sum)
        val cols = StreamSpecUtil.columnsOf(g, pe)
        assert(cols.nonEmpty,
          s"stale VX[0] leaked into capture $m: ${g.toList}")
      }
      println(s"vx0-contam streamed: OK")
    }
  }
}
