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
      var clctSeen = false
      var datClctSeen = false
      def observe(): Unit = {
        if (dut.io.clct.peek().litToBoolean) clctSeen = true
        if (dut.io.dbg_dat_clct.peek().litToBoolean) datClctSeen = true
      }
      for (m <- 0 until M) {
        dut.io.ctrl.keep.poke(true.B); dut.io.ctrl.busy.poke(true.B)
        for (i <- 0 until K) {
          dut.io.in_a(i).poke(1.S(8.W))
          dut.io.in_b(i).poke(1.S(8.W))
          dut.io.in_accum(i).poke(0.S(32.W))
        }
        dut.io.ctrl.use_accum.poke(false.B)
        dut.clock.step()
        observe()
      }
      dut.io.ctrl.busy.poke(false.B); dut.io.ctrl.keep.poke(false.B)
      // Drain/settling steps (not counted in the nominal feed-phase rate):
      // the array latency is ~3n-1, so the collection pulses only land after
      // the feed loop ends. Keep observing so we can assert the array ran.
      for (_ <- 0 until 4 * K) { dut.clock.step(); observe() }
      // Nominal driver-side feed-phase rate (the array is fed every feed
      // cycle => 1 feed/cyc = 100%). This is a driver-side nominal figure, not
      // a validated measured ceiling.
      val p = StreamSpecUtil.Perf(M.toLong, M.toLong, M.toLong * K * K)
      StreamSpecUtil.printPerf(s"A1 MMALU feed-phase (nominal) M=$M", K, p)
      assert(clctSeen || datClctSeen,
        "no MMALU collection pulse observed: the systolic array did not run")
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

  "DMA-bound cadence (B3)" should "sweep DDR read latency for reloaded operands" in {
    for (lat <- Seq(1, 100, 200)) {
      simulate(new StreamHarness(K = K, readLatency = lat)) { dut =>
        StreamSpecUtil.resetSession(dut)
        val prog = Seq(vle8(4, NpuSections.SECT_A, 0), vle8(8, NpuSections.SECT_B, 0)) ++
          (0 until 16).flatMap(m => Seq(
            vle8(4, NpuSections.SECT_A, m * K), vle8(8, NpuSections.SECT_B, m * K),
            mma(0, 4, 8, 0), vse32(0, NpuSections.SECT_OUT, (m % 8) * 4 * K))) :+ mmaLast(0, 0, 0, 0)
        prog.foreach(StreamSpecUtil.issue(dut, _))
        StreamSpecUtil.runToDrain(dut)
        StreamSpecUtil.printPerf(s"B3 reload lat=$lat", K, StreamSpecUtil.perf(dut, K))
      }
    }
  }

  "RAW serialization (B4)" should "report MACs/cycle for a chained C reduction" in {
    for (n <- Seq(8, 32)) {
      simulate(new StreamHarness(K = K)) { dut =>
        StreamSpecUtil.resetSession(dut)
        // rd=3, vs3=3: VR[3]=VX[12..15] is disjoint from rs1=4/rs2=8; each mma
        // reads the previous mma's VR[3] via C -> RAW chain. One vse per mma
        // drains capQ (single vd, so the vse->mma WAR also serializes).
        val prog = Seq(vle8(4, NpuSections.SECT_A, 0), vle8(8, NpuSections.SECT_B, 0)) ++
          (0 until n).flatMap(i => Seq(
            mma(3, 4, 8, 3), vse32(3, NpuSections.SECT_OUT, (i % 8) * 4 * K))) :+ mmaLast(0, 0, 0, 0)
        prog.foreach(StreamSpecUtil.issue(dut, _))
        StreamSpecUtil.runToDrain(dut)
        StreamSpecUtil.printPerf(s"B4 chain n=$n", K, StreamSpecUtil.perf(dut, K))
      }
    }
  }

  def s8(v: Int): Int = { val b = v & 0xFF; if (b >= 128) b - 256 else b }

  "Realistic tile (B5)" should "report MACs/cycle for vle/mma/vse tiles" in {
    val T = 16
    val aVals = Array.tabulate(T, K)((m, i) => s8(m * 3 + i + 1))
    val bVals = Array.tabulate(K)(j => s8(50 + j))
    simulate(new StreamHarness(K = K, readLatency = 100)) { dut =>
      StreamSpecUtil.resetSession(dut)
      StreamSpecUtil.preloadI8(dut, NpuSections.A, aVals.flatten.map(_ & 0xFF))
      StreamSpecUtil.preloadI8(dut, NpuSections.B, bVals.map(_ & 0xFF))
      val prog = Seq(vle8(8, NpuSections.SECT_B, 0)) ++
        (0 until T).flatMap(m => Seq(
          vle8(4, NpuSections.SECT_A, m * K),
          mma(0, 4, 8, 0),
          vse32(0, NpuSections.SECT_OUT, m * 4 * K))) :+ mmaLast(0, 0, 0, 0)
      prog.foreach(StreamSpecUtil.issue(dut, _))
      StreamSpecUtil.runToDrain(dut)
      StreamSpecUtil.printPerf(s"B5 tile T=$T", K, StreamSpecUtil.perf(dut, K))
      // the LAST stored capture must be a column of the running sum Σ_{t≤T-1} a_t·b
      val out = StreamSpecUtil.readI32(dut, NpuSections.OUT, T * K)
      val g = out.slice((T - 1) * K, T * K)
      val pe = Array.tabulate(K, K)((i, j) => (0 until T).map(t => aVals(t)(i) * bVals(j)).sum)
      assert(StreamSpecUtil.columnsOf(g, pe).nonEmpty,
        s"B5 final capture is not a running-sum column: ${g.toList}")
    }
  }
}
