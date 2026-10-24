// See README.md for license details.
// -----------------------------------------------------------------------------
//  StreamedChainedSpec.scala — two back-to-back rounds with no inter-round
//  boundary stall.  In the pipelined engine an mma.last no longer inserts a
//  6K-1 idle/reset window in the default (per-feed capture) mode, so the
//  accumulator keeps running across the marker and every capture is a clean
//  running-sum column of the whole session (no cross-round residue).
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

  "NpuProgramEngine" should "chain two back-to-back session rounds without a boundary stall" in {
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

      // watch the feed timing
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
      // No inter-round stall: the next feed follows the mma.last feed closely
      // (the old boundary forced >= 4K-1 idle cycles).
      if (lastFeedTick >= 0)
        assert(gapAfterLast <= BigInt(4),
          s"round boundary stall: round-2 first feed $gapAfterLast ticks after round-1 mma.last")

      val out = StreamSpecUtil.readI32(dut, NpuSections.OUT, (n1 + n2) * k)
      // each capture = a running-sum column over the whole session (there is
      // no boundary reset between the two marker-separated rounds)
      for (m <- 0 until n1 + n2) {
        val g = out.slice(m * k, (m + 1) * k)
        val pe = Array.tabulate(k, k)((i, j) => (0 to m).map(t => aVals(t)(i) * bVals(t)(j)).sum)
        val cols = StreamSpecUtil.columnsOf(g, pe)
        assert(cols.nonEmpty, s"chained capture $m is not a running-sum column: ${g.toList}")
      }
      println(s"chained n1=$n1 n2=$n2: OK (gap=$gapAfterLast)")
    }
  }
}
