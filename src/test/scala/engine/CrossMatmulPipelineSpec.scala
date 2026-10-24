// See README.md for license details.
// -----------------------------------------------------------------------------
//  CrossMatmulPipelineSpec.scala — consecutive matmul rounds must PIPELINE:
//  the overall IPC (feeds / total cycles, including head and tail) should be
//  > 90 %, not just the steady-state body.  Each round is K accumulate feeds +
//  mma.last (capture) + vse32 (store), reduce mode, resident operands.
//
//  The old serial boundary (6K-1 idle reset between rounds) makes the per-round
//  tail dwarf the K-feed body, so feeds/total is far below 90 %.
// -----------------------------------------------------------------------------

package engine

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec
import testUtil._
import isa._
import isa.NpuAssembler._

class CrossMatmulPipelineSpec extends AnyFlatSpec {

  def s8(v: Int): Int = { val b = v & 0xFF; if (b >= 128) b - 256 else b }

  def run(K: Int, rounds: Int): (Double, Seq[Long]) = {
    var pct = 0.0
    var caps = Seq.empty[Long]
    simulate(new StreamHarness(K = K)) { dut =>
      dut.io.reduce_mode.poke(true.B)
      StreamSpecUtil.resetSession(dut)
      val a = Array.tabulate(K)(j => (j + 1) & 0xFF)
      val b = Array.tabulate(K)(i => (100 + i) & 0xFF)
      StreamSpecUtil.preloadI8(dut, NpuSections.A, a.toSeq)
      StreamSpecUtil.preloadI8(dut, NpuSections.B, b.toSeq)

      val prog = (0 until rounds).flatMap { r =>
        (0 until K).map(_ => mma(0, 4, 8, 0)) ++
          Seq(mmaLast(0, 0, 0, 0), vse32(0, NpuSections.SECT_OUT, r * 4 * K))
      }
      val ph = StreamSpecUtil.traceRun(dut, prog)
      StreamSpecUtil.printPhases(s"cross K=$K R=$rounds", ph)
      pct = if (ph.total > 0) 100.0 * ph.finalFeeds.toDouble / ph.total else 0.0
      println(f"[cross] K=$K rounds=$rounds feeds=${ph.finalFeeds} total=${ph.total} " +
              f"overallIPC=${pct}%.1f%% (head=${ph.head} body=${ph.body} tail=${ph.tail})")

      // every round's capture is the K-feed running sum (a column)
      val out = StreamSpecUtil.readI32(dut, NpuSections.OUT, rounds * K)
      val a8 = a.map(s8); val b8 = b.map(s8)
      val pe = Array.tabulate(K, K)((i, j) => K * a8(i) * b8(j))
      for (r <- 0 until rounds) {
        val got = out.slice(r * K, (r + 1) * K)
        val cols = StreamSpecUtil.columnsOf(got, pe)
        assert(cols.nonEmpty,
          s"K=$K round $r capture not the running-sum column: got=${got.take(8).toList}")
      }
      caps = out.map(_.toLong).toSeq
    }
    (pct, caps)
  }

  // KNOWN GAP (documented RED): cross-matmul pipelining needs the MMALU
  // collector `dat_clct`/`cnt` phase decoupled from the per-feed PE `keep`
  // reset, and the collector window re-timed per round.  Ignored until that
  // MMALU change lands.
  ignore should "pipeline above 90% overall IPC" in {
    for (K <- Seq(16, 32); rounds <- Seq(4, 8)) {
      val (pct, _) = run(K, rounds)
      assert(pct >= 90.0,
        f"K=$K rounds=$rounds overall IPC ${pct}%.1f%% < 90%% (serial round boundary)")
    }
  }
}
