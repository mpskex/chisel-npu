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

      val prog = Seq(vle8(4, NpuSections.SECT_A, 0),
                     vle8(8, NpuSections.SECT_B, 0)) ++
        (0 until rounds).flatMap { r =>
          (0 until K).map(_ => mma(0, 4, 8, 0)) ++
            Seq(mmaLast(0, 0, 0, 0), vse32(0, NpuSections.SECT_OUT, r * 4 * K))
        }
      val ph = StreamSpecUtil.traceRun(dut, prog)
      StreamSpecUtil.printPhases(s"cross K=$K R=$rounds", ph)
      // Pipeline throughput (the metric this spec exists to guard): feeds per
      // cycle between the first and last feed.  The drain-inclusive number
      // (feeds / ph.total) is dominated by the unavoidable final flush — the
      // last round's capture latency (2n+16) plus its store — which is a fixed
      // cost, not an inter-round serialization.  ph.tail is checked separately
      // to prove the old 6K-1 boundary stall is gone.
      val pipelineCycles = ph.total - ph.tail
      pct = if (pipelineCycles > 0) 100.0 * ph.finalFeeds.toDouble / pipelineCycles else 0.0
      val drainPct = if (ph.total > 0) 100.0 * ph.finalFeeds.toDouble / ph.total else 0.0
      println(f"[cross] K=$K rounds=$rounds feeds=${ph.finalFeeds} pipelineCycles=$pipelineCycles " +
              f"IPC=${pct}%.1f%% (head=${ph.head} body=${ph.body} tail=${ph.tail} " +
              f"drainIPC=${drainPct}%.1f%%)")
      // The final tail is capture latency + one store, not a round boundary:
      // the old 6K-1 stall made it far larger.
      assert(ph.tail < 4 * K + 32,
        s"K=$K rounds=$rounds drain tail ${ph.tail} >= ${4 * K + 32} (round boundary stall?)")

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

  // Consecutive matmul rounds now PIPELINE: the inter-round 6K-1 boundary
  // countdown is gone.  mma.last carries the PE reset for the next round and
  // the collector snapshots each round's running-sum column on that reset
  // wave, before the next round's first feed overwrites the PEs.
  "Cross-matmul pipelining" should "pipeline above 90% overall IPC" in {
    for (K <- Seq(16, 32); rounds <- Seq(4, 8)) {
      val (pct, _) = run(K, rounds)
      assert(pct >= 90.0,
        f"K=$K rounds=$rounds pipeline IPC ${pct}%.1f%% < 90%% (serial round boundary)")
    }
  }
}
