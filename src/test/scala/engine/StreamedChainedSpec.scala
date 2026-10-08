// See README.md for license details.
// -----------------------------------------------------------------------------
//  StreamedChainedSpec.scala — back-to-back session rounds.  Round 1's
//  mma.last starts the boundary countdown; round 2's first feed must land
//  ≥ 4K−1 ticks later (the reset wave completes), with zero software padding
//  between the rounds.  Every capture must be a clean running-sum column of
//  its own round — no cross-round residue.
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

  "NpuProgramEngine" should "chain two back-to-back session rounds with the boundary gate" in {
    val k = 16
    val n1 = 3
    val n2 = 3
    val aVals = Array.tabulate(n1 + n2, k)((m, j) => s8(m * 7 + j + 1))
    val bVals = Array.tabulate(n1 + n2, k)((m, i) => s8(100 + m * 5 + i))
    simulate(new StreamHarness(K = 16, readLatency = 100)) { dut =>
      StreamSpecUtil.preloadI8(dut, NpuSections.A, aVals.flatten.map(_ & 0xFF))
      StreamSpecUtil.preloadI8(dut, NpuSections.B, bVals.flatten.map(_ & 0xFF))

      def round(n: Int, aBase: Int, outOff: Int): Unit = {
        for (m <- 0 until n) {
          StreamSpecUtil.issue(dut, vle8(4, NpuSections.SECT_A, (aBase + m) * k))
          StreamSpecUtil.issue(dut, vle8(8, NpuSections.SECT_B, (aBase + m) * k))
          StreamSpecUtil.issue(dut, mma(0, 4, 8, 0))
          StreamSpecUtil.issue(dut, vse32(0, NpuSections.SECT_OUT, (outOff + m) * 4 * k))
        }
        StreamSpecUtil.issue(dut, mmaLast(0, 0, 0, 0))
      }

      // round 1, then immediately round 2 (no NOPs between)
      round(n1, 0, 0)
      round(n2, n1, n1)

      // watch the feed timing to verify the boundary gate
      var lastFeedTick = BigInt(-1); var prevAcc = BigInt(0)
      var gapAfterLast = BigInt(-1)
      for (t <- 0 until 20000) {
        val acc = dut.io.dbg_mma_acc.peek().litValue
        if (acc != prevAcc) {
          if (acc == BigInt(n1 + 1)) lastFeedTick = BigInt(t)  // round-1 mma.last feed
          if (acc == BigInt(n1 + 2)) gapAfterLast = BigInt(t) - lastFeedTick
          prevAcc = acc
        }
        dut.clock.step()
      }
      // round-2's first feed must be ≥ 4K−1 after round-1's mma.last
      assert(gapAfterLast >= BigInt(4 * 16 - 1),
        s"boundary gate: round-2 first feed only $gapAfterLast ticks after round-1 mma.last")

      val out = StreamSpecUtil.readI32(dut, NpuSections.OUT, (n1 + n2) * k)
      // each capture = a running-sum column of its OWN round's feeds (the
      // boundary resets the PEs between rounds, so round-2 starts fresh)
      val roundStart = Array(0, n1)
      for (m <- 0 until n1 + n2) {
        val g = out.slice(m * k, (m + 1) * k)
        val rs = roundStart(if (m < n1) 0 else 1)
        val pe = Array.tabulate(k, k)((i, j) => (rs to m).map(t => aVals(t)(i) * bVals(t)(j)).sum)
        val cols = StreamSpecUtil.columnsOf(g, pe)
        assert(cols.nonEmpty, s"chained capture $m is not a round running-sum column: ${g.toList}")
      }
      println(s"chained n1=$n1 n2=$n2: OK (gate gap=$gapAfterLast)")
    }
  }
}
