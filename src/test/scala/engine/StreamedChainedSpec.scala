// See README.md for license details.
// -----------------------------------------------------------------------------
//  StreamedChainedSpec.scala — two back-to-back reduce matmul rounds with no
//  inter-round boundary stall.
//
//  Each round is K accumulate feeds + mma.last (capture into VR[0]) + one
//  vse32 that reads VR[0] directly.  mma.last resets the PE wavefront for the
//  next round; consecutive rounds must still feed back-to-back (the old
//  boundary forced >= 4K-1 idle cycles).  Every round uses its own operand
//  rows, so a capture that picked up another round's accumulator cannot pass.
// -----------------------------------------------------------------------------

package engine

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec
import testUtil._
import isa._
import isa.NpuAssembler._

class StreamedChainedSpec extends AnyFlatSpec {

  def s8(v: Int): Int = { val b = v & 0xFF; if (b >= 128) b - 256 else b }

  "NpuProgramEngine" should "chain two back-to-back reduce rounds without a boundary stall" in {
    val k = 16
    val rounds = 2
    val aVals = Array.tabulate(rounds, k)((r, j) => s8(1 + ((r * 11 + j * 3) % 31)))
    val bVals = Array.tabulate(rounds, k)((r, i) => s8(1 + ((r * 7 + i * 5) % 29)))
    simulate(new StreamHarness(K = 16, readLatency = 100)) { dut =>
      StreamSpecUtil.preloadI8(dut, NpuSections.A, aVals.flatten.map(_ & 0xFF))
      StreamSpecUtil.preloadI8(dut, NpuSections.B, bVals.flatten.map(_ & 0xFF))
      StreamSpecUtil.resetSession(dut)

      def round(r: Int): Unit = {
        StreamSpecUtil.issue(dut, vle8(4, NpuSections.SECT_A, r * k))
        StreamSpecUtil.issue(dut, vle8(8, NpuSections.SECT_B, r * k))
        for (_ <- 0 until k) StreamSpecUtil.issue(dut, mma(0, 4, 8, 0))
        StreamSpecUtil.issue(dut, mmaLast(0, 0, 0, 0))
        StreamSpecUtil.issue(dut, vse32(0, NpuSections.SECT_OUT, r * 4 * k))
      }

      // round 1, then immediately round 2 (no NOPs between).  Inter-round
      // pipelining (no boundary stall) is measured by CrossMatmulPipelineSpec's
      // drain-inclusive IPC + maxBnd==0, so this spec only checks the captures.
      round(0)
      round(1)
      StreamSpecUtil.runToDrain(dut)

      val out = StreamSpecUtil.readI32(dut, NpuSections.OUT, rounds * k)
      // K identical feeds per round => the capture is K*a_r(i)*b_r(j).
      for (r <- 0 until rounds) {
        val g = out.slice(r * k, (r + 1) * k)
        val pe = Array.tabulate(k, k)((i, j) => k * aVals(r)(i) * bVals(r)(j))
        val cols = StreamSpecUtil.columnsOf(g, pe)
        assert(cols.nonEmpty, s"reduce round $r capture is not K*a*b: ${g.toList}")
      }
      println(s"chained reduce rounds=$rounds k=$k: OK")
    }
  }
}
