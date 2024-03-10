// See README.md for license details.

package isa
import chisel3._

object NeuralISA extends ChiselEnum {
    val ld = Value(0x1.U(4.W))
    val st = Value(0x2.U(4.W))
    val mma = Value(0x3.U(4.W))
    val ip = Value (0x4.U(4.W))
}