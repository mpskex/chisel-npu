// See README.md for license details.
// -----------------------------------------------------------------------------
//  LateStoreCaptureSpec.scala — regression test for the pc=4 wedge.
//
//  The silicon wedge reproduced intermittently at the chained vse32: the mma
//  producer's clctD can retire the producer before the store word streams into
//  the window (the MIG's variable latency vs. the frontend fetch cadence).  The
//  old useCapData detection required a *pending prior* slot (`vxWrMask &
//  priorMask`), so a late store missed the capture: it read the RF instead of
//  popping the capQ, the mma's capture sat in capQ forever, and `drain` never
//  asserted.
//
//  This test issues the store only AFTER the mma has fully retired (clct
//  fired), which is exactly the late-entry scenario — the store must still be
//  recognized as a capture consumer and the engine must drain.
// -----------------------------------------------------------------------------

package engine

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec
import testUtil._
import isa._
import isa.NpuAssembler._

class LateStoreCaptureSpec extends AnyFlatSpec {

  def s8(v: Int): Int = { val b = v & 0xFF; if (b >= 128) b - 256 else b }

  "NpuProgramEngine" should "consume the mma capture when the store enters after the mma retires" in {
    val k = 16
    val aVals = Array.tabulate(k)(j => s8(j + 1))
    val bVals = Array.tabulate(k)(i => s8(100 + i))
    simulate(new StreamHarness(K = 16, readLatency = 50)) { dut =>
      StreamSpecUtil.preloadI8(dut, NpuSections.A, aVals.map(_ & 0xFF))
      StreamSpecUtil.preloadI8(dut, NpuSections.B, bVals.map(_ & 0xFF))

      // feed the mma and let it fully retire (clct fires, slot done)
      StreamSpecUtil.issue(dut, vle8(4, NpuSections.SECT_A, 0))
      StreamSpecUtil.issue(dut, vle8(8, NpuSections.SECT_B, 0))
      StreamSpecUtil.issue(dut, mma(0, 4, 8, 0))
      var prev = BigInt(0)
      for (t <- 0 until 10000) {
        val c = dut.io.dbg_clct.peek().litValue
        if (c != prev) { if (c == BigInt(1)) prev = c }
        if (dut.io.dbg_wcount.peek().litValue == BigInt(0)) { /* window empty */ }
        dut.clock.step()
      }
      // wait until the mma's window slot is gone (producer retired)
      var mmaGone = false
      for (t <- 0 until 10000) {
        if (dut.io.dbg_wcount.peek().litValue == BigInt(0) &&
            dut.io.dbg_dma_done.peek().litValue >= BigInt(2)) { mmaGone = true; }
        dut.clock.step()
      }
      assert(mmaGone, "mma producer never retired")

      // NOW issue the store — it enters the window after the mma retired.
      StreamSpecUtil.issue(dut, vse32(0, NpuSections.SECT_OUT, 0: Int))
      // let it drain (the core's drain needs the capQ to empty)
      var drained = false
      for (t <- 0 until 20000) {
        if (dut.io.dbg_drain.peek().litToBoolean) { drained = true; }
        dut.clock.step()
      }
      assert(drained, "engine did not drain after the late store (capQ stuck)")

      // the stored value must be the mma capture (a b-column), not garbage
      val out = StreamSpecUtil.readI32(dut, NpuSections.OUT, k)
      val pe = Array.tabulate(k, k)((i, j) => aVals(i) * bVals(j))
      val cols = StreamSpecUtil.columnsOf(out, pe)
      assert(cols.nonEmpty, s"late store did not store a capture column: ${out.toList}")
      println(s"late-store capture: column ${cols.head} OK")
    }
  }
}
