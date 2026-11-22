// See README.md for license details.
// -----------------------------------------------------------------------------
//  NpuCtrlAxiLite.scala — AXI4-Lite slave -> NPUCTL control bus master.
//
//  AW and W are accepted independently and buffered; the request is issued
//  once both are held.  Reads are accepted one at a time.  The response path
//  maps the control-bus error flag to SLVERR.  The control bus is single-
//  ported, so a write and a read are serialized: a read is not accepted while
//  a write is in flight and vice versa.
// -----------------------------------------------------------------------------

package peripheral

import chisel3._
import chisel3.util._

class NpuCtrlAxiLite(val ADDR_W: Int = 12) extends Module {
  require(ADDR_W >= 12, "NpuCtrlAxiLite: ADDR_W must be at least 12")

  val io = IO(new Bundle {
    // ---- AXI4-Lite slave --------------------------------------------------
    val s_axil_awaddr  = Input(UInt(ADDR_W.W))
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
    val s_axil_araddr  = Input(UInt(ADDR_W.W))
    val s_axil_arprot  = Input(UInt(3.W))
    val s_axil_arvalid = Input(Bool())
    val s_axil_arready = Output(Bool())
    val s_axil_rdata   = Output(UInt(32.W))
    val s_axil_rresp   = Output(UInt(2.W))
    val s_axil_rvalid  = Output(Bool())
    val s_axil_rready  = Input(Bool())

    // ---- NPUCTL control bus ----------------------------------------------
    val ctrl = new NpuCtrlMaster
  })

  val OKAY   = 0.U(2.W)
  val SLVERR = 2.U(2.W)

  object WrState extends ChiselEnum {
    val IDLE, REQ, RESP = Value
  }
  object RdState extends ChiselEnum {
    val IDLE, REQ, RESP, HOLD = Value
  }

  val wrState = RegInit(WrState.IDLE)
  val rdState = RegInit(RdState.IDLE)

  val awHeld = RegInit(false.B)
  val awAddr = RegInit(0.U(ADDR_W.W))
  val wHeld  = RegInit(false.B)
  val wData  = RegInit(0.U(32.W))
  val wStrb  = RegInit(0.U(4.W))
  val bValid = RegInit(false.B)
  val bResp  = RegInit(OKAY)

  val arAddr = RegInit(0.U(ADDR_W.W))
  val rValid = RegInit(false.B)
  val rData  = RegInit(0.U(32.W))
  val rResp  = RegInit(OKAY)

  val writeIdle = wrState === WrState.IDLE
  val readIdle  = rdState === RdState.IDLE

  // ---- write side ---------------------------------------------------------
  io.s_axil_awready := writeIdle && !awHeld && readIdle
  io.s_axil_wready  := writeIdle && !wHeld && readIdle

  when (io.s_axil_awvalid && io.s_axil_awready) {
    awHeld := true.B
    awAddr := io.s_axil_awaddr
  }
  when (io.s_axil_wvalid && io.s_axil_wready) {
    wHeld := true.B
    wData := io.s_axil_wdata
    wStrb := io.s_axil_wstrb
  }

  // ---- read side ----------------------------------------------------------
  io.s_axil_arready := readIdle && writeIdle && !bValid

  when (io.s_axil_arvalid && io.s_axil_arready) {
    arAddr  := io.s_axil_araddr
    rdState := RdState.REQ
  }

  // ---- control-bus request mux -------------------------------------------
  val wrReq = wrState === WrState.REQ
  val rdReq = rdState === RdState.REQ
  io.ctrl.req.valid      := wrReq || rdReq
  io.ctrl.req.bits.write := !rdReq
  io.ctrl.req.bits.addr  := Mux(rdReq,
    Cat(arAddr(ADDR_W - 1, 2), 0.U(2.W)),
    Cat(awAddr(ADDR_W - 1, 2), 0.U(2.W)))
  io.ctrl.req.bits.wdata := wData
  io.ctrl.req.bits.wstrb := Mux(rdReq, 0.U, wStrb)
  io.ctrl.resp.ready     := (wrState === WrState.RESP) || (rdState === RdState.RESP)

  // ---- write FSM ----------------------------------------------------------
  switch (wrState) {
    is (WrState.IDLE) {
      when (awHeld && wHeld && readIdle) { wrState := WrState.REQ }
    }
    is (WrState.REQ) {
      when (io.ctrl.req.ready) { wrState := WrState.RESP }
    }
    is (WrState.RESP) {
      when (io.ctrl.resp.valid) {
        bValid  := true.B
        bResp   := Mux(io.ctrl.resp.bits.error, SLVERR, OKAY)
        wrState := WrState.IDLE
        awHeld  := false.B
        wHeld   := false.B
      }
    }
  }

  io.s_axil_bvalid := bValid
  io.s_axil_bresp  := bResp
  when (bValid && io.s_axil_bready) { bValid := false.B }

  // ---- read FSM -----------------------------------------------------------
  switch (rdState) {
    is (RdState.IDLE) { /* transition made by the AR handshake above */ }
    is (RdState.REQ) {
      when (io.ctrl.req.ready) { rdState := RdState.RESP }
    }
    is (RdState.RESP) {
      when (io.ctrl.resp.valid) {
        rData   := io.ctrl.resp.bits.rdata
        rResp   := Mux(io.ctrl.resp.bits.error, SLVERR, OKAY)
        rValid  := true.B
        rdState := RdState.HOLD
      }
    }
    is (RdState.HOLD) {
      when (io.s_axil_rready) {
        rValid  := false.B
        rdState := RdState.IDLE
      }
    }
  }

  io.s_axil_rvalid := rValid
  io.s_axil_rdata  := rData
  io.s_axil_rresp  := rResp
}
