package engine

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec
import isa._
import isa.NpuAssembler._

class DecodeDispatchPolicySpec extends AnyFlatSpec {
  "engine decode policy" should "reject out-of-range MMA operands" in {
    simulate(new StreamHarness(K = 16)) { dut =>
      dut.io.session_reset.poke(false.B)
      // Warm past the simulator's initial reset cycle.  StreamSpecUtil.issue
      // deasserts instr_valid after a single clock step, so without this the
      // very first word would be dropped on the reset edge and never reach
      // the window (the shared helper assumes a pre-stepped harness).
      dut.clock.step()
      def expectIllegal(instr: Int): Unit = {
        StreamSpecUtil.issue(dut, instr)
        var n = 0
        while (!dut.io.illegal_out.peek().litToBoolean && n < 50) { dut.clock.step(); n += 1 }
        assert(dut.io.illegal_out.peek().litToBoolean, f"0x$instr%08x should be illegal")
        dut.io.session_reset.poke(true.B); dut.clock.step()
        dut.io.session_reset.poke(false.B)
      }
      expectIllegal(mma(rd = 4, vs1 = 4, vs2 = 8, vs3 = 0))    // rd  >= L/4
      expectIllegal(mma(rd = 0, vs1 = 16, vs2 = 8, vs3 = 0))   // rs1 >= L
      expectIllegal(mma(rd = 0, vs1 = 4, vs2 = 16, vs3 = 0))   // rs2 >= L
      expectIllegal(mma(rd = 0, vs1 = 4, vs2 = 8, vs3 = 4))    // vs3 >= L/4
    }
  }

  it should "reject out-of-range memory operands and unsupported families" in {
    simulate(new StreamHarness(K = 16)) { dut =>
      dut.io.session_reset.poke(false.B)
      dut.clock.step()   // warm past the simulator's initial reset cycle (see above)
      def expectIllegal(instr: Int): Unit = {
        StreamSpecUtil.issue(dut, instr)
        var n = 0
        while (!dut.io.illegal_out.peek().litToBoolean && n < 50) { dut.clock.step(); n += 1 }
        assert(dut.io.illegal_out.peek().litToBoolean, f"0x$instr%08x should be illegal")
        dut.io.session_reset.poke(true.B); dut.clock.step()
        dut.io.session_reset.poke(false.B)
      }
      expectIllegal(vle8(rd = 16, sect = NpuSections.SECT_A, off = 0))   // VX >= L
      expectIllegal(vse16(src = 8, sect = NpuSections.SECT_OUT, off = 0)) // VE >= L/2
      expectIllegal(vse32(src = 4, sect = NpuSections.SECT_OUT, off = 0)) // VR >= L/4
      expectIllegal(vadd(rd = 0, rs1 = 1, rs2 = 2, width = NpuAssembler.VX)) // unsupported family
    }
  }

  it should "still accept and drain an in-range one-shot session" in {
    simulate(new StreamHarness(K = 16)) { dut =>
      dut.io.session_reset.poke(false.B)
      StreamSpecUtil.preloadI8(dut, NpuSections.A, Seq.tabulate(16)(i => i + 1))
      StreamSpecUtil.preloadI8(dut, NpuSections.B, Seq.tabulate(16)(i => 2))
      for (w <- Seq(vle8(4, NpuSections.SECT_A, 0), vle8(8, NpuSections.SECT_B, 0),
                     mma(2, 4, 8, 0), mmaLast(0, 0, 0, 0), vse32(2, NpuSections.SECT_OUT, 0)))
        StreamSpecUtil.issue(dut, w)
      var n = 0
      while (!dut.io.dbg_drain.peek().litToBoolean && n < 100000) { dut.clock.step(); n += 1 }
      assert(dut.io.dbg_drain.peek().litToBoolean, "session did not drain")
      assert(!dut.io.illegal_out.peek().litToBoolean, "in-range session flagged illegal")
    }
  }
}
