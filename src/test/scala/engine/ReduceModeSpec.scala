// See README.md for license details.
// -----------------------------------------------------------------------------
//  ReduceModeSpec.scala — store-sparse reduce mode (the only mode).
//
//  A reduction round feeds R mmas with NO per-mma capture/store; the regular
//  mmas accumulate in the array (the MMALU holds keep=1) and only the round's
//  FINAL (mma.last) feed commits the accumulated capture to VR[vd].  The
//  software then issues a single trailing vse32 that reads VR[vd] directly.
//
//  This removes the 128 B-per-feed store roofline from the array-pipeline
//  body, so the body is limited only by the dispatch window / in-flight cap.
// -----------------------------------------------------------------------------

package engine

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec
import testUtil._
import isa._
import isa.NpuAssembler._

class ReduceModeSpec extends AnyFlatSpec {

  val K = 32

  /** One reduction round: resident operands, R accumulating mmas (no per-mma
    * store), one mma.last, one vse storing the final accumulated capture. */
  def program(R: Int): Seq[Int] =
    Seq(vle8(4, NpuSections.SECT_A, 0), vle8(8, NpuSections.SECT_B, 0)) ++
      (0 until R).map(_ => mma(0, 4, 8, 0)) ++
      Seq(mmaLast(0, 0, 0, 0), vse32(0, NpuSections.SECT_OUT, 0))

  def check(W: Int): Unit = {
    val R = 128
    simulate(new StreamHarness(K = K, W = W)) { dut =>
      StreamSpecUtil.resetSession(dut)
      StreamSpecUtil.preloadI8(dut, NpuSections.A, Seq.fill(K)(3))
      StreamSpecUtil.preloadI8(dut, NpuSections.B, Seq.fill(K)(5))

      val ph = StreamSpecUtil.traceRun(dut, program(R))
      StreamSpecUtil.printPhases(s"reduce W=$W R=$R", ph)

      val out = StreamSpecUtil.readI32(dut, NpuSections.OUT, K)
      // The final running sum after R useful feeds (mma.last injects zeros):
      //   PE(i,j) = Σ_m A_m,i·B_m,j = R · a_i · b_j = R · 3 · 5
      // The stored 128 B capture is one column of that matrix.
      val pe = Array.tabulate(K, K)((_, _) => R * 3 * 5)
      val cols = StreamSpecUtil.columnsOf(out, pe)
      assert(cols.nonEmpty,
        s"reduce capture is not the final running-sum column: ${out.toList}")

      println(f"[reduce W=$W] bodyUtil=${ph.bodyUtil * 100.0}%.1f%% body=${ph.body} feeds=${ph.bodyFeeds}")
      assert(ph.bodyUtil >= 0.955, f"bodyUtil ${ph.bodyUtil * 100.0}%.1f%% < 95.5%%")
    }
  }

  "Store-sparse reduce mode" should "capture only the round's final sum and drain" in check(W = 16)

  it should "keep the body saturated at W=8 (L1 decouples throughput from W)" in check(W = 8)
}
