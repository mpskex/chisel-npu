// See README.md for license details.

package alu.pe

import chisel3._
import isa.micro_op._

/**
  * processing element unit in npu design. 
  * This is the core compute unit .
  */
class MMPE(val nbits: Int = 8) extends Module {
  val io = IO(
    new Bundle {
      val ctrl        = Input(new NCoreMMALUBundle())
      val in_a        = Input(SInt(nbits.W))
      val in_b        = Input(SInt(nbits.W))
      //  The register bandwith is optimized for large transformer 
      //  The lower bound of max cap matrix size is:
      //    2^12 x 2^12 = (4096 x 4096)
      val out         = Output(SInt((nbits * 2).W))
  })

  val res = RegInit(0.S((nbits * 2).W))

  when (io.ctrl.accum) {
    res := res + (io.in_a * io.in_b)
  } .otherwise {
    res := (io.in_a * io.in_b)
  }
  io.out := res
}