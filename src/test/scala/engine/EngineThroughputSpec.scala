package engine

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec
import alu.mma._
import alu.pe._
import isa._
import isa.NpuAssembler._

class EngineThroughputSpec extends AnyFlatSpec {
  val K = 32

  "Array ceiling (A1)" should "reach 1 feed/cycle with a continuous keep=1 feed" in {
    simulate(new MMALU(new MMPE(8, 32), K, 8, 32)) { dut =>
      val M = 2 * K
      for (m <- 0 until M) {
        dut.io.ctrl.keep.poke(true.B); dut.io.ctrl.busy.poke(true.B)
        for (i <- 0 until K) {
          dut.io.in_a(i).poke(1.S(8.W))
          dut.io.in_b(i).poke(1.S(8.W))
          dut.io.in_accum(i).poke(0.S(32.W))
        }
        dut.io.ctrl.use_accum.poke(false.B)
        dut.clock.step()
      }
      dut.io.ctrl.busy.poke(false.B); dut.io.ctrl.keep.poke(false.B)
      // settling steps (not counted): let the systolic pipeline drain so the
      // final feeds retire before we stop the clock.
      for (_ <- 0 until 4 * K) { dut.clock.step() }
      // Feed-phase rate only: the array is fed every feed cycle (1 feed/cyc =
      // 100% peak). The 4*K settling steps above are drain, not feed, so they
      // are excluded from the reported `cycles`.
      val p = StreamSpecUtil.Perf(M.toLong, M.toLong, M.toLong * K * K)
      StreamSpecUtil.printPerf(s"A1 MMALU M=$M", K, p)
      assert(p.pctPeak(K) > 99.0)
    }
  }

  val VdRings: Seq[Seq[Int]] = Seq(Seq(0), Seq(0, 3, 4, 5))

  def residentDrain(R: Int, ring: Seq[Int]): Seq[Int] =
    Seq(vle8(4, NpuSections.SECT_A, 0), vle8(8, NpuSections.SECT_B, 0)) ++
      (0 until R).flatMap { i =>
        val v = ring(i % ring.length)
        Seq(mma(v, 4, 8, 0), vse32(v, NpuSections.SECT_OUT, (i % 8) * 4 * K))
      } :+ mmaLast(0, 0, 0, 0)

  def shortRounds(N: Int): Seq[Int] =
    Seq(vle8(4, NpuSections.SECT_A, 0), vle8(8, NpuSections.SECT_B, 0)) ++
      (0 until N).flatMap(_ => Seq(
        mma(0, 4, 8, 0), mmaLast(0, 0, 0, 0), vse32(0, NpuSections.SECT_OUT, 0)))

  "Window / vd-ring cap (B1)" should "report MACs/cycle for resident drained rounds" in {
    for (ring <- VdRings; R <- Seq(32, 128, 512)) {
      simulate(new StreamHarness(K = K)) { dut =>
        StreamSpecUtil.resetSession(dut)
        residentDrain(R, ring).foreach(StreamSpecUtil.issue(dut, _))
        StreamSpecUtil.runToDrain(dut)
        StreamSpecUtil.printPerf(s"B1 resident R=$R Vd=${ring.length}", K, StreamSpecUtil.perf(dut, K))
      }
    }
  }

  "Boundary overhead (B2)" should "report MACs/cycle for short rounds" in {
    for (N <- Seq(4, 16, 64)) {
      simulate(new StreamHarness(K = K)) { dut =>
        StreamSpecUtil.resetSession(dut)
        shortRounds(N).foreach(StreamSpecUtil.issue(dut, _))
        StreamSpecUtil.runToDrain(dut)
        StreamSpecUtil.printPerf(s"B2 short N=$N", K, StreamSpecUtil.perf(dut, K))
      }
    }
  }
}
