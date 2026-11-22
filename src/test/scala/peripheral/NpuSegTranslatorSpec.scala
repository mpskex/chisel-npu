// See README.md for license details.
// -----------------------------------------------------------------------------
//  NpuSegTranslatorSpec — 64-bit base translation and local error responses.
// -----------------------------------------------------------------------------

package peripheral

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec

class NpuSegTranslatorSpec extends AnyFlatSpec {
  def idle(dut: NpuSegTranslator): Unit = {
    dut.io.s_awaddr.poke(0.U)
    dut.io.s_awseg.poke(0.U)
    dut.io.s_awlen.poke(0.U)
    dut.io.s_awsize.poke(6.U)
    dut.io.s_awburst.poke(1.U)
    dut.io.s_awvalid.poke(false.B)
    dut.io.s_wdata.poke(0.U)
    dut.io.s_wstrb.poke(0.U)
    dut.io.s_wlast.poke(false.B)
    dut.io.s_wvalid.poke(false.B)
    dut.io.s_bready.poke(false.B)
    dut.io.s_araddr.poke(0.U)
    dut.io.s_arseg.poke(0.U)
    dut.io.s_arlen.poke(0.U)
    dut.io.s_arsize.poke(6.U)
    dut.io.s_arburst.poke(1.U)
    dut.io.s_arvalid.poke(false.B)
    dut.io.s_rready.poke(false.B)
    dut.io.m_awready.poke(true.B)
    dut.io.m_wready.poke(true.B)
    dut.io.m_bvalid.poke(false.B)
    dut.io.m_bresp.poke(0.U)
    dut.io.m_arready.poke(true.B)
    dut.io.m_rdata.poke(0.U)
    dut.io.m_rresp.poke(0.U)
    dut.io.m_rlast.poke(false.B)
    dut.io.m_rvalid.poke(false.B)
    for (i <- 0 until 5) {
      dut.io.seg_base(i).poke(0.U(64.W))
      dut.io.seg_limit(i).poke(0.U(32.W))
    }
  }

  def setA(dut: NpuSegTranslator, base: Long, limit: Int): Unit = {
    dut.io.seg_base(0).poke(base.U(64.W))
    dut.io.seg_limit(0).poke(limit.U(32.W))
  }

  "NpuSegTranslator" should "translate a valid 64-bit base and a valid read" in {
    simulate(new NpuSegTranslator) { dut =>
      idle(dut)
      setA(dut, 0x1_0000_0000L, 0x1000)
      dut.io.s_awaddr.poke(0.U)
      dut.io.s_awvalid.poke(true.B)
      dut.clock.step(0)
      assert(dut.io.m_awvalid.peek().litValue == 1)
      assert(dut.io.m_awaddr.peek().litValue == 0x1_0000_0000L)
      assert(dut.io.s_awready.peek().litValue == 1)
      assert(dut.io.bus_error.peek().litValue == 0)

      dut.io.s_awvalid.poke(false.B)
      dut.io.s_araddr.poke(0x40.U)
      dut.io.s_arvalid.poke(true.B)
      dut.clock.step(0)
      assert(dut.io.m_arvalid.peek().litValue == 1)
      assert(dut.io.m_araddr.peek().litValue == 0x1_0000_0040L)
      assert(dut.io.bus_error.peek().litValue == 0)
    }
  }

  it should "terminate an out-of-range write locally with a B response" in {
    simulate(new NpuSegTranslator) { dut =>
      idle(dut)
      setA(dut, 0x1000L, 0x100)
      dut.io.s_awaddr.poke(0x200.U)
      dut.io.s_awvalid.poke(true.B)
      dut.io.s_bready.poke(false.B)
      dut.clock.step(0)
      assert(dut.io.bus_error.peek().litValue == 1, "out-of-range AW must report bus_error")
      assert(dut.io.m_awvalid.peek().litValue == 0, "invalid AW must not reach the system bus")
      assert(dut.io.s_awready.peek().litValue == 1, "invalid AW is accepted by the responder")
      dut.clock.step()  // accept the invalid AW; awErrActive latches

      dut.io.s_awvalid.poke(false.B)
      dut.io.s_wdata.poke("hABCD".U)
      dut.io.s_wstrb.poke("hF".U)
      dut.io.s_wlast.poke(true.B)
      dut.io.s_wvalid.poke(true.B)
      dut.clock.step(0)
      assert(dut.io.s_wready.peek().litValue == 1, "invalid W must be accepted")
      assert(dut.io.m_wvalid.peek().litValue == 0, "invalid W must not reach the system bus")
      dut.clock.step()  // accept WLAST; bErrValid latches
      dut.io.s_wvalid.poke(false.B)
      dut.io.s_wlast.poke(false.B)
      assert(dut.io.s_bvalid.peek().litValue == 1, "invalid write must return B")
      assert(dut.io.m_bready.peek().litValue == 0, "invalid write must not reach B")
    }
  }

  it should "terminate an out-of-range read locally with R beats" in {
    simulate(new NpuSegTranslator) { dut =>
      idle(dut)
      setA(dut, 0x1000L, 0x100)
      dut.io.s_araddr.poke(0x200.U)
      dut.io.s_arlen.poke(1.U)
      dut.io.s_arvalid.poke(true.B)
      dut.io.s_rready.poke(true.B)
      dut.clock.step(0)
      assert(dut.io.bus_error.peek().litValue == 1)
      assert(dut.io.m_arvalid.peek().litValue == 0)
      dut.clock.step()  // accept the invalid AR; rErrValid latches
      dut.io.s_arvalid.poke(false.B)
      dut.clock.step(0)
      assert(dut.io.s_rvalid.peek().litValue == 1, "first error R beat missing")
      assert(dut.io.s_rlast.peek().litValue == 0)
      assert(dut.io.s_rdata.peek().litValue == 0)
      dut.clock.step()
      assert(dut.io.s_rvalid.peek().litValue == 1, "second error R beat missing")
      assert(dut.io.s_rlast.peek().litValue == 1, "last error R beat must set rlast")
    }
  }
}
