// See README.md for license details.
// -----------------------------------------------------------------------------
//  VrStoreWARSpec.scala — regression for the VR capture-store WAR hazard.
//
//  A reduce round writes its accumulated column to VR[vd]; an immediately
//  following vse32 stores that VR.  If the store let the DMA re-read VR[vd]
//  combinationally at the slow W-data phase, a later round's mma.last reusing
//  the SAME vd would overwrite it before the DMA sampled it (no WAR reader is
//  registered).  The engine therefore latches VR[vd] into the dmaQ entry at
//  the store's issue (after its RAW gate) and the DMA writes that snapshot.
//
//  This test runs back-to-back reduce chunks that all reuse vd=0 with
//  resident operands (no intervening operand load to serialize the hazard),
//  under deliberate W-channel backpressure (wDelay).  Every chunk's stored
//  column must be its own K*a_r*b_r.
// -----------------------------------------------------------------------------

package engine

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec
import testUtil._
import isa._
import isa.NpuAssembler._

class VrStoreWARSpec extends AnyFlatSpec {

  def s8(v: Int): Int = { val b = v & 0xFF; if (b >= 128) b - 256 else b }

  "NpuProgramEngine" should "snapshot VR[vd] at vse issue (WAR under write backpressure)" in {
    val k = 16
    val rounds = 4
    val aVals = Array.tabulate(rounds, k)((r, j) => s8(1 + ((r * 13 + j * 3) % 31)))
    val bVals = Array.tabulate(rounds, k)((r, i) => s8(1 + ((r * 7 + i * 5) % 29)))

    simulate(new StreamHarness(K = k, readLatency = 50, wDelay = 80)) { dut =>
      StreamSpecUtil.resetSession(dut)
      StreamSpecUtil.preloadI8(dut, NpuSections.A, aVals.flatten.map(_ & 0xFF))
      StreamSpecUtil.preloadI8(dut, NpuSections.B, bVals.flatten.map(_ & 0xFF))

      // Resident distinct operands: round r uses VX[4+2r]/VX[5+2r].  No loads
      // occur between the rounds, so nothing serializes the store behind the
      // next capture.
      for (r <- 0 until rounds) {
        StreamSpecUtil.issue(dut, vle8(4 + 2 * r, NpuSections.SECT_A, r * k))
        StreamSpecUtil.issue(dut, vle8(5 + 2 * r, NpuSections.SECT_B, r * k))
      }

      // Back-to-back reduce chunks, all reusing capture vd = VR[0], each with
      // an immediate vse right after its mma.last.
      for (r <- 0 until rounds) {
        for (_ <- 0 until k)
          StreamSpecUtil.issue(dut, mma(0, 4 + 2 * r, 5 + 2 * r, 0))
        StreamSpecUtil.issue(dut, mmaLast(0, 0, 0, 0))
        StreamSpecUtil.issue(dut, vse32(0, NpuSections.SECT_OUT, r * 4 * k))
      }

      StreamSpecUtil.runToDrain(dut)
      val out = StreamSpecUtil.readI32(dut, NpuSections.OUT, rounds * k)
      for (r <- 0 until rounds) {
        val got = out.slice(r * k, (r + 1) * k)
        val pe = Array.tabulate(k, k)((i, j) => k * aVals(r)(i) * bVals(r)(j))
        val cols = StreamSpecUtil.columnsOf(got, pe)
        assert(cols.nonEmpty,
          s"round $r store is not its own K*a*b column (VR[0] overwritten " +
          s"before the DMA sampled it): ${got.take(8).toList}")
      }
    }
  }
}
