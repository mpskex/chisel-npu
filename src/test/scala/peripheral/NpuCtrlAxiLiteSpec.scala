// See README.md for license details.
// -----------------------------------------------------------------------------
//  NpuCtrlAxiLiteSpec — AXI4-Lite slave -> NPUCTL control-bus adapter.
// -----------------------------------------------------------------------------

package peripheral

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec

/** Adapter plus a one-outstanding-request stub responder.
  * Read data is `addr + 1`; address 0x30 returns an error.
  */
class CtrlAdapterHarness extends Module {
  val io = IO(new Bundle {
    val s_axil_awaddr  = Input(UInt(12.W))
    val s_axil_awprot  = Input(UInt(3.W))
    val s_axil_awvalid = Input(Bool())
    val s_axil_awready = Output(Bool())
    val s_axil_wdata   = Input(UInt(32.W))
    val s_axil_wstrb   = Input(UInt(4.W))
    val s_axil_wvalid  = Input(Bool())
    val s_axil_wready  = Output(Bool())
    val s_axil_bresp   = Output(UInt(2.W))
    val s_axil_bvalid  = Output(Bool())
    val s_axil_bready  = Input(Bool())
    val s_axil_araddr  = Input(UInt(12.W))
    val s_axil_arprot  = Input(UInt(3.W))
    val s_axil_arvalid = Input(Bool())
    val s_axil_arready = Output(Bool())
    val s_axil_rdata   = Output(UInt(32.W))
    val s_axil_rresp   = Output(UInt(2.W))
    val s_axil_rvalid  = Output(Bool())
    val s_axil_rready  = Input(Bool())

    val seen_addr  = Output(UInt(12.W))
    val seen_write = Output(Bool())
    val seen_wdata = Output(UInt(32.W))
    val seen_wstrb = Output(UInt(4.W))
    val seen_count = Output(UInt(8.W))
  })

  val axil = Module(new NpuCtrlAxiLite)

  axil.io.s_axil_awaddr  := io.s_axil_awaddr
  axil.io.s_axil_awprot  := io.s_axil_awprot
  axil.io.s_axil_awvalid := io.s_axil_awvalid
  io.s_axil_awready      := axil.io.s_axil_awready
  axil.io.s_axil_wdata   := io.s_axil_wdata
  axil.io.s_axil_wstrb   := io.s_axil_wstrb
  axil.io.s_axil_wvalid  := io.s_axil_wvalid
  io.s_axil_wready       := axil.io.s_axil_wready
  io.s_axil_bresp        := axil.io.s_axil_bresp
  io.s_axil_bvalid       := axil.io.s_axil_bvalid
  axil.io.s_axil_bready  := io.s_axil_bready
  axil.io.s_axil_araddr  := io.s_axil_araddr
  axil.io.s_axil_arprot  := io.s_axil_arprot
  axil.io.s_axil_arvalid := io.s_axil_arvalid
  io.s_axil_arready      := axil.io.s_axil_arready
  io.s_axil_rdata        := axil.io.s_axil_rdata
  io.s_axil_rresp        := axil.io.s_axil_rresp
  io.s_axil_rvalid       := axil.io.s_axil_rvalid
  axil.io.s_axil_rready  := io.s_axil_rready

  val reqValid  = RegInit(false.B)
  val respValid = RegInit(false.B)
  val reqWrite  = RegInit(false.B)
  val reqAddr   = RegInit(0.U(12.W))
  val reqWdata  = RegInit(0.U(32.W))
  val reqWstrb  = RegInit(0.U(4.W))
  val reqCount  = RegInit(0.U(8.W))

  axil.io.ctrl.req.ready       := !reqValid
  axil.io.ctrl.resp.valid      := respValid
  axil.io.ctrl.resp.bits.rdata := reqAddr + 1.U
  axil.io.ctrl.resp.bits.error := reqAddr === 0x30.U

  when (axil.io.ctrl.req.fire) {
    reqValid  := true.B
    reqWrite  := axil.io.ctrl.req.bits.write
    reqAddr   := axil.io.ctrl.req.bits.addr
    reqWdata  := axil.io.ctrl.req.bits.wdata
    reqWstrb  := axil.io.ctrl.req.bits.wstrb
    respValid := true.B
    reqCount  := reqCount + 1.U
  }
  when (axil.io.ctrl.resp.fire) {
    reqValid  := false.B
    respValid := false.B
  }

  io.seen_addr  := reqAddr
  io.seen_write := reqWrite
  io.seen_wdata := reqWdata
  io.seen_wstrb := reqWstrb
  io.seen_count := reqCount
}

class NpuCtrlAxiLiteSpec extends AnyFlatSpec {
  def idle(dut: CtrlAdapterHarness): Unit = {
    dut.io.s_axil_awprot.poke(0.U)
    dut.io.s_axil_arprot.poke(0.U)
    dut.io.s_axil_awvalid.poke(false.B)
    dut.io.s_axil_wvalid.poke(false.B)
    dut.io.s_axil_arvalid.poke(false.B)
  }

  "NpuCtrlAxiLite" should "accept an AW/W write and return OKAY" in {
    simulate(new CtrlAdapterHarness) { dut =>
      idle(dut)
      dut.io.s_axil_bready.poke(true.B)
      dut.io.s_axil_rready.poke(true.B)

      dut.io.s_axil_awaddr.poke(0x10.U)
      dut.io.s_axil_awvalid.poke(true.B)
      dut.io.s_axil_wdata.poke("h12345678".U)
      dut.io.s_axil_wstrb.poke("hF".U)
      dut.io.s_axil_wvalid.poke(true.B)

      dut.clock.step(3)
      dut.io.s_axil_awvalid.poke(false.B)
      dut.io.s_axil_wvalid.poke(false.B)

      var n = 0
      while (dut.io.s_axil_bvalid.peek().litValue == 0 && n < 50) {
        dut.clock.step(); n += 1
      }
      assert(dut.io.s_axil_bvalid.peek().litValue == 1, "write response missing")
      assert(dut.io.s_axil_bresp.peek().litValue == 0, "write response must be OKAY")
      assert(dut.io.seen_addr.peek().litValue == 0x10)
      assert(dut.io.seen_write.peek().litValue == 1)
      assert(dut.io.seen_wdata.peek().litValue == BigInt("12345678", 16))
      assert(dut.io.seen_wstrb.peek().litValue == 0xF)
      assert(dut.io.seen_count.peek().litValue == 1)
    }
  }

  it should "return read data and an error response" in {
    simulate(new CtrlAdapterHarness) { dut =>
      idle(dut)
      dut.io.s_axil_bready.poke(true.B)
      dut.io.s_axil_rready.poke(true.B)

      dut.io.s_axil_araddr.poke(0x20.U)
      dut.io.s_axil_arvalid.poke(true.B)
      dut.clock.step(3)
      dut.io.s_axil_arvalid.poke(false.B)

      var n = 0
      while (dut.io.s_axil_rvalid.peek().litValue == 0 && n < 50) {
        dut.clock.step(); n += 1
      }
      assert(dut.io.s_axil_rvalid.peek().litValue == 1, "read response missing")
      assert(dut.io.s_axil_rdata.peek().litValue == 0x21)
      assert(dut.io.s_axil_rresp.peek().litValue == 0)

      dut.clock.step(2)

      dut.io.s_axil_araddr.poke(0x30.U)
      dut.io.s_axil_arvalid.poke(true.B)
      dut.clock.step(3)
      dut.io.s_axil_arvalid.poke(false.B)
      n = 0
      while (dut.io.s_axil_rvalid.peek().litValue == 0 && n < 50) {
        dut.clock.step(); n += 1
      }
      assert(dut.io.s_axil_rvalid.peek().litValue == 1)
      assert(dut.io.s_axil_rresp.peek().litValue == 2, "0x30 must return SLVERR")
    }
  }
  it should "not accept a second write while B is pending" in {
    simulate(new CtrlAdapterHarness) { dut =>
      idle(dut)
      dut.io.s_axil_bready.poke(false.B)
      dut.io.s_axil_rready.poke(true.B)

      dut.io.s_axil_awaddr.poke(0x10.U)
      dut.io.s_axil_awvalid.poke(true.B)
      dut.io.s_axil_wdata.poke("h11111111".U)
      dut.io.s_axil_wstrb.poke("hF".U)
      dut.io.s_axil_wvalid.poke(true.B)
      var n = 0
      while (dut.io.s_axil_bvalid.peek().litValue == 0 && n < 50) {
        dut.clock.step(); n += 1
      }
      assert(dut.io.s_axil_bvalid.peek().litValue == 1)
      dut.io.s_axil_awvalid.poke(false.B)
      dut.io.s_axil_wvalid.poke(false.B)

      // Second write while the first B is still pending.
      dut.io.s_axil_awaddr.poke(0x14.U)
      dut.io.s_axil_awvalid.poke(true.B)
      dut.io.s_axil_wdata.poke("h22222222".U)
      dut.io.s_axil_wvalid.poke(true.B)
      dut.clock.step(2)
      assert(dut.io.s_axil_awready.peek().litValue == 0,
        "AW must not be accepted while B is pending")
      assert(dut.io.s_axil_wready.peek().litValue == 0,
        "W must not be accepted while B is pending")

      dut.io.s_axil_bready.poke(true.B)
      dut.clock.step()
      n = 0
      while (dut.io.s_axil_bvalid.peek().litValue == 0 && n < 50) {
        dut.clock.step(); n += 1
      }
      assert(dut.io.s_axil_bvalid.peek().litValue == 1, "second write response missing")
      assert(dut.io.seen_count.peek().litValue == 2)
      assert(dut.io.seen_addr.peek().litValue == 0x14)
      assert(dut.io.seen_wdata.peek().litValue == BigInt("22222222", 16))
    }
  }

}
