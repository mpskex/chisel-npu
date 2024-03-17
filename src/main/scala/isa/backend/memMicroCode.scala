// See README.md for license details.

package isa.backend
import chisel3._
import chisel3.util._

object MemLayout extends ChiselEnum {
    val bit8    = Value(0x0.U)
    val bit16   = Value(0x1.U)
    val bit32   = Value(0x2.U)
}

object MemChannel extends ChiselEnum {
    val ch0     = Value(0x0.U)
    // 16/32 bits will have no ch1
    val ch1     = Value(0x1.U)
    // 32 bits will have no ch2
    val ch2     = Value(0x2.U)
    // 16/32 bits will have no ch3
    val ch3     = Value(0x3.U)
}
