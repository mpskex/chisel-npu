package engine

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec
import isa._
import isa.NpuAssembler._

class EnginePerfCounterSpec extends AnyFlatSpec {
  "Engine perf counters" should "count cycles and MACs for a resident long round" in {
    val K = 32
    simulate(new StreamHarness(K = K)) { dut =>
      StreamSpecUtil.resetSession(dut)
      val R = 8
      // One vse per mma drains capQ (else the session never drains); the
      // trailing mma.last is a ZERO feed, so it counts in dbg_mma_acc (R+1)
      // but NOT in dbg_mac (which counts only useful K² MACs).
      val prog = Seq(vle8(4, NpuSections.SECT_A, 0), vle8(8, NpuSections.SECT_B, 0)) ++
        (0 until R).flatMap(i => Seq(mma(0, 4, 8, 0), vse32(0, NpuSections.SECT_OUT, (i % 8) * 4 * K))) :+
        mmaLast(0, 0, 0, 0)
      prog.foreach(StreamSpecUtil.issue(dut, _))
      StreamSpecUtil.runToDrain(dut)
      val feeds = dut.io.dbg_mma_acc.peek().litValue
      val macs  = dut.io.dbg_mac.peek().litValue
      val cyc   = dut.io.dbg_cycles.peek().litValue
      assert(feeds == BigInt(R + 1), s"expected ${R + 1} feeds (incl. mma.last), got $feeds")
      assert(macs == BigInt(R * K * K), s"expected ${R * K * K} MACs, got $macs")
      assert(cyc > 0, "cycle counter must have advanced")
      println(s"[perf] R=$R feeds=$feeds macs=$macs cycles=$cyc macs/cyc=${macs.toDouble / cyc.toDouble}")
    }
  }

  it should "reset counters at session_reset" in {
    simulate(new StreamHarness(K = 32)) { dut =>
      StreamSpecUtil.resetSession(dut)
      // One useful feed plus the mma.last zero feed, then a vse so the
      // session drains.  The feed counter (0x1C DBG_MMA) must be per-session
      // like the cycle/MAC counters, otherwise the driver's perf readback
      // accumulates across runs.
      val prog = Seq(
        vle8(4, NpuSections.SECT_A, 0),
        vle8(8, NpuSections.SECT_B, 0),
        mma(0, 4, 8, 0),
        mmaLast(0, 0, 0, 0),
        vse32(0, NpuSections.SECT_OUT, 0))
      prog.foreach(StreamSpecUtil.issue(dut, _))
      StreamSpecUtil.runToDrain(dut)
      val c1 = dut.io.dbg_cycles.peek().litValue
      val m1 = dut.io.dbg_mac.peek().litValue
      val f1 = dut.io.dbg_mma_acc.peek().litValue
      StreamSpecUtil.resetSession(dut)
      val c2 = dut.io.dbg_cycles.peek().litValue
      val m2 = dut.io.dbg_mac.peek().litValue
      val f2 = dut.io.dbg_mma_acc.peek().litValue
      assert(c1 > 0 && c2 == 0, s"cycle counter must reset on session_reset (c1=$c1 c2=$c2)")
      assert(m2 == 0, s"MAC counter must reset on session_reset (c1=$c1 m1=$m1 m2=$m2)")
      assert(f1 == BigInt(2), s"expected 2 feeds (mma + mma.last), got $f1")
      assert(f2 == 0, s"feed counter must reset on session_reset (f1=$f1 f2=$f2)")
    }
  }
}
