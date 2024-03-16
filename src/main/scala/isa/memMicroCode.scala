// See README.md for license details.

package isa
import chisel3._
import chisel3.util._


object OffsetPattern extends ChiselEnum {
    val not_def = Value(0x0.U)
    val sca_0d  = Value(0x1.U)
    val vec_1d  = Value(0x2.U)
    val mat_2d  = Value(0x3.U)
}

object AddressMode extends ChiselEnum {
    val immd        = Value(0x0.U)
    val addr        = Value(0x1.U)
    val addr_immd   = Value(0x2.U)
}


object MemLayout extends ChiselEnum {
    val bit8    = Value(0x0.U)
    val bit16   = Value(0x1.U)
    val bit32   = Value(0x2.U)
}

object DType extends ChiselEnum {
    val uint    = Value(0x0.U)
    val int     = Value(0x1.U)
    val fp      = Value(0x2.U)
    // no bfp32c0
    val bfp     = Value(0x3.U)
}

object MemChannel extends ChiselEnum {
    val ch0     = Value(0x1.U)
    // 16/32 bits will have no ch1
    val ch1     = Value(0x2.U)
    // 32 bits will have no ch2
    val ch2     = Value(0x4.U)
    // 16/32 bits will have no ch3
    val ch3     = Value(0x8.U)
}