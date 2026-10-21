package engine

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec
import isa._

import testUtil._
import isa.NpuAssembler._

/** Mirrors the hardware `test_session_captures_columns` program at K=32:
  * three chained (vle8,vle8,mma, vse32) rounds then mma.last, each capture a
  * column of the running partial sum.  The Scala chained spec only runs K=16.
  */
class CaptureColumnsK32Spec extends AnyFlatSpec {

  def s8(v: Int): Int = { val b = v & 0xFF; if (b >= 128) b - 256 else b }

  "K=32 chained per-feed captures" should "match the running-sum columns" in {
    val k = 32
    val m = 3
    val aVals = Array.tabulate(m, k)((mm, j) => (mm * 7 + j + 1) & 0xFF)
    val bVals = Array.tabulate(m, k)((mm, i) => (100 + mm * 5 + i) & 0xFF)

    simulate(new StreamHarness(K = k)) { dut =>
      StreamSpecUtil.resetSession(dut)
      StreamSpecUtil.preloadI8(dut, NpuSections.A, aVals.flatten.map(_ & 0xFF))
      StreamSpecUtil.preloadI8(dut, NpuSections.B, bVals.flatten.map(_ & 0xFF))

      val words = (0 until m).flatMap { mm =>
        Seq(vle8(4, NpuSections.SECT_A, mm * k),
            vle8(8, NpuSections.SECT_B, mm * k),
            mma(0, 4, 8, 0),
            vse32(0, NpuSections.SECT_OUT, mm * 4 * k))
      } :+ mmaLast(0, 0, 0, 0)
      words.foreach(w => StreamSpecUtil.issue(dut, w))
      StreamSpecUtil.runToDrain(dut)

      val out = StreamSpecUtil.readI32(dut, NpuSections.OUT, m * k)
      val a8 = aVals.map(_.map(s8))
      val b8 = bVals.map(_.map(s8))

      for (mm <- 0 until m) {
        val got = out.slice(mm * k, (mm + 1) * k)
        val pe = Array.tabulate(k, k) { (i, j) =>
          (0 to mm).map(t => a8(t)(i) * b8(t)(j)).sum
        }
        val cols = (0 until k).filter(c => (0 until k).forall(i => got(i) == pe(i)(c)))
        assert(cols.nonEmpty,
          s"capture $mm is not a running-sum column: got=${got.take(8).toList} " +
          s"exp0=${(0 until 8).map(pe(_)(0)).toList}")
      }
    }
  }
}
