// See README.md for license details.

package isa
import chisel3._

object _OpCode extends ChiselEnum {
    val ld = Value(0x1.U(4.W))
    val st = Value(0x2.U(4.W))
    val mma = Value(0x3.U(4.W))
    val ip = Value (0x4.U(4.W))
}

object _Dtype extends ChiselEnum {
    val uint    = Value(0x0.U)
    val int     = Value(0x1.U)
    val fp      = Value(0x2.U)
    // no bfp32c0
    val bfp     = Value(0x3.U)
}

class NeuralCoreMicroOp extends Bundle {
    val opcode  = _OpCode()
    val dtype   = _Dtype()

}
