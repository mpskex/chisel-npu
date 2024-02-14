// See README.md for license details.

package procElem

import chisel3._

/**
  * processing element unit in npu design. 
  * This is the core compute unit .
  */
class PE(val nbits: Int = 8) extends Module {
  val io = IO(
    new Bundle {
      val accum       = Input(Bool())
      val top_in      = Input(UInt(nbits.W))
      val left_in     = Input(UInt(nbits.W))
      val bottom_out  = Output(UInt((nbits).W))
      val right_out   = Output(UInt((nbits).W))
      //  The register bandwith is optimized for large transformer 
      //  The lower bound of max cap matrix size is:
      //    2^12 x 2^12 = (4096 x 4096)
      val out         = Output(UInt((nbits * 2 + 12).W))
  })

  val res = RegInit(0.U((nbits*2 + 12).W))
  val reg_h = RegInit(0.U(nbits.W))
  val reg_v = RegInit(0.U(nbits.W))

  when (io.accum) {
    res := res + (io.top_in * io.left_in)
  } .otherwise {
    res := (io.top_in * io.left_in)
  }

  reg_v := io.top_in
  reg_h := io.left_in

  io.bottom_out := reg_v
  io.right_out := reg_h
  io.out := res
}