// See README.md for license details.
// -----------------------------------------------------------------------------
//  NpuCtrlBus.scala — transport-agnostic NPUCTL control bus.
//
//  A request carries a byte address, write data and byte strobes.  A response
//  carries 32-bit read data and an error flag.  The AXI4-Lite and APB adapters
//  are thin translators at the peripheral boundary.
// -----------------------------------------------------------------------------

package peripheral

import chisel3._
import chisel3.util._

class NpuCtrlReq extends Bundle {
  val write = Bool()
  val addr  = UInt(12.W)
  val wdata = UInt(32.W)
  val wstrb = UInt(4.W)
}

class NpuCtrlResp extends Bundle {
  val rdata = UInt(32.W)
  val error = Bool()
}

/** Master side of the control bus (drives requests, consumes responses). */
class NpuCtrlMaster extends Bundle {
  val req  = Decoupled(new NpuCtrlReq)
  val resp = Flipped(Decoupled(new NpuCtrlResp))
}

/** Slave side of the control bus (consumes requests, drives responses). */
class NpuCtrlSlave extends Bundle {
  val req  = Flipped(Decoupled(new NpuCtrlReq))
  val resp = Decoupled(new NpuCtrlResp)
}
