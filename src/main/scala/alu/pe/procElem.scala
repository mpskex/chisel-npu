// See README.md for license details.

package alu.pe

import chisel3._
import isa.micro_op._

/**
  * processing element unit in npu design. 
  * This is the core compute unit .
  */
class MMPE(nbits: Int = 8, accum_nbits: Int = 32) extends BasePE(nbits, accum_nbits) {
  when (io.ctrl.keep) {
    res := res + (io.in_a * io.in_b)
  } .otherwise {
    res := (io.in_a * io.in_b)
  }
  io.out := res
}