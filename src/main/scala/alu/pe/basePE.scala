// See README.md for license details.

package alu.pe

import chisel3._
import isa.micro_op._

/**
  * processing element unit in npu design. 
  * This is the core compute unit .
  */
class BasePE(val nbits: Int = 8, val accum_nbits: Int = 32) extends Module {
  val io = IO(
    new Bundle {
      val ctrl        = Input(new NCoreMMALUCtrlBundle())
      val in_a        = Input(SInt(nbits.W))
      val in_b        = Input(SInt(nbits.W))
      //  The register bandwith is optimized for large transformer 
      //  The lower bound of max cap matrix size is:
      //    2^12 x 2^12 = (4096 x 4096)
      val out         = Output(SInt(accum_nbits.W))
  })

  val res = RegInit(0.S(accum_nbits.W))

  when (io.ctrl.keep) {
    res := res + (io.in_a * io.in_b)
  } .otherwise {
    res := (io.in_a * io.in_b)
  }
  io.out := res
}