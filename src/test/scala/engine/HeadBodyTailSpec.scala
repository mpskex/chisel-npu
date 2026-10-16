// See README.md for license details.
// -----------------------------------------------------------------------------
//  HeadBodyTailSpec.scala — measure the theoretical-bound decomposition:
//    head = first feed -> first capture   (systolic fill, ~3n-1)
//    body = first capture -> last feed    (MUST be 1 feed/cycle = peak)
//    tail = last feed -> drain            (flush + boundary + stores)
//
//  H1 sweeps the body length (resident operands, per-mma vse) and must show
//  bodyUtil independent of length.  H2 runs back-to-back rounds so one
//  round's tail overlaps the next round's head.
// -----------------------------------------------------------------------------

package engine

import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec
import isa._
import isa.NpuAssembler._

class HeadBodyTailSpec extends AnyFlatSpec {

  val K = 32

  /** vle once, then R x (mma + vse) over a `vd` ring; one trailing mma.last.
    * The ring must avoid the operand registers VX[4]/VX[8]. */
  def residentRound(R: Int, ring: Seq[Int]): Seq[Int] =
    Seq(vle8(4, NpuSections.SECT_A, 0), vle8(8, NpuSections.SECT_B, 0)) ++
      (0 until R).flatMap { i =>
        val v = ring(i % ring.length)
        Seq(mma(v, 4, 8, 0), vse32(v, NpuSections.SECT_OUT, (i % 8) * 4 * K))
      } :+ mmaLast(0, 0, 0, 0)

  /** N back-to-back rounds of R feeds, each closed by mma.last, so round i's
    * tail (boundary + drain) meets round i+1's head (fill). */
  def interleaved(rounds: Int, R: Int, ring: Seq[Int]): Seq[Int] =
    Seq(vle8(4, NpuSections.SECT_A, 0), vle8(8, NpuSections.SECT_B, 0)) ++
      (0 until rounds).flatMap { _ =>
        (0 until R).flatMap { i =>
          val v = ring(i % ring.length)
          Seq(mma(v, 4, 8, 0), vse32(v, NpuSections.SECT_OUT, (i % 8) * 4 * K))
        } :+ mmaLast(0, 0, 0, 0)
      }

  def prep(dut: StreamHarness): Unit = {
    StreamSpecUtil.resetSession(dut)
    StreamSpecUtil.preloadI8(dut, NpuSections.A, Seq.fill(K)(3))
    StreamSpecUtil.preloadI8(dut, NpuSections.B, Seq.fill(K)(5))
  }

  "Array-pipeline phases (H1)" should "hold body utilization across body lengths" in {
    for (ring <- Seq(Seq(0), Seq(0, 3, 4, 5)); R <- Seq(128, 512)) {
      simulate(new StreamHarness(K = K)) { dut =>
        prep(dut)
        val ph = StreamSpecUtil.traceRun(dut, residentRound(R, ring))
        StreamSpecUtil.printPhases(s"H1 R=$R Vd=${ring.length}", ph)
        println(f"[phase]   ideal(head+feeds+tail)=${ph.idealTotal}%7d " +
          f"total=${ph.total}%7d overhead=${ph.total - ph.idealTotal}%6d")
      }
    }
  }

  "Interleaved head and tail (H2)" should "measure utilization across back-to-back rounds" in {
    for ((rounds, r) <- Seq((2, 64), (4, 32))) {
      simulate(new StreamHarness(K = K)) { dut =>
        prep(dut)
        val ph = StreamSpecUtil.traceRun(dut, interleaved(rounds, r, Seq(0, 3, 4, 5)))
        StreamSpecUtil.printPhases(s"H2 rounds=$rounds R=$r", ph)
      }
    }
  }
}
