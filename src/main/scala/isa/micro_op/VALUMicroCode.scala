// See README.md for license details.

package isa.micro_op
import chisel3._
import chisel3.util._
import isa.dtype._

object VecDType extends ChiselEnum {
    val U8C4 = Value(0x1.U(4.W))
    val S8C4 = Value(0x2.U(4.W))
    val U16C2 = Value(0x3.U(4.W))
    val S16C2 = Value(0x4.U(4.W))
    val FP16C2 = Value(0x5.U(4.W))
    val BF16C2 = Value(0x6.U(4.W))
    val U32C1 = Value(0x7.U(4.W))
    val S32C1 = Value(0x8.U(4.W))
    val FP32C1 = Value(0x9.U(4.W))
}

class NCoreVALUBundle() extends Bundle {
    val accum = Bool()
    val dat_collect = Bool()
    val op_code = VecDType()
}