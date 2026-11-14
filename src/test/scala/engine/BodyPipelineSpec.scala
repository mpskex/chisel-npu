package engine
import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec
import isa._
import isa.NpuAssembler._

class BodyPipelineSpec extends AnyFlatSpec {
  val K = 32
  def round(R: Int): Seq[Int] =
    Seq(vle8(4, NpuSections.SECT_A, 0), vle8(8, NpuSections.SECT_B, 0)) ++
      (0 until R).map(_ => mma(0, 4, 8, 0)) ++
      Seq(mmaLast(0, 0, 0, 0), vse32(0, NpuSections.SECT_OUT, 0))

  // Incremental lock-in: pipelined MMA issue lifts the body off the ~1%
  // one-feed-per-clctD floor and (critically) no longer wedges on a long
  // program.  The full peak target (bodyUtil >= 0.5) requires the §6 L1
  // rework (move the writer tag to the completion FIFO so a slot retires at
  // issue, not at clctD) plus a capture buffer >= R; see the Phase-2 plan.
  "Pipelined MMA issue" should "lift the body without wedging" in {
    simulate(new StreamHarness(K = K)) { dut =>
      StreamSpecUtil.resetSession(dut)
      StreamSpecUtil.preloadI8(dut, NpuSections.A, Seq.fill(K)(3))
      StreamSpecUtil.preloadI8(dut, NpuSections.B, Seq.fill(K)(5))
      val ph = StreamSpecUtil.traceRun(dut, round(128))
      StreamSpecUtil.printPhases("reduce R=128", ph)
      assert(ph.bodyUtil >= 0.05, f"body at ${ph.bodyUtil * 100}%.1f%% of peak, want >=5%%")
    }
  }
}
