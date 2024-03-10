// See README.md for license details.

package isa.mmc
import chisel3._
import chisel3.util._


object OffsetPattern extends ChiselEnum {
    val not_def = Value(0x0.U(2.W))
    val sca_0d  = Value(0x1.U(2.W))
    val vec_1d  = Value(0x2.U(2.W))
    val mat_2d  = Value(0x3.U(2.W))
}

object AddressMode extends ChiselEnum {
    val immd        = Value(0x0.U(2.W))
    val addr        = Value(0x1.U(2.W))
    val addr_immd   = Value(0x2.U(2.W))
}

object MemLayout extends ChiselEnum {
    // Unsigned Integer 8 (4 channels)
    val uint8c1     = Value(0x00.U(8.W))
    val uint8c2     = Value(0x01.U(8.W))
    val uint8c3     = Value(0x02.U(8.W))
    val uint8c4     = Value(0x03.U(8.W))
    // Signed Integer 8 (4 channels)
    val int8c1      = Value(0x04.U(8.W))
    val int8c2      = Value(0x05.U(8.W))
    val int8c3      = Value(0x06.U(8.W))
    val int8c4      = Value(0x07.U(8.W))
    // Floating Point 8 (4 channels)
    val fp8c1       = Value(0x08.U(8.W))
    val fp8c2       = Value(0x09.U(8.W))
    val fp8c3       = Value(0x0a.U(8.W))
    val fp8c4       = Value(0x0b.U(8.W))
    // Binary Floating Point 8 (4 channels)
    val bfp8c1      = Value(0x0c.U(8.W))
    val bfp8c2      = Value(0x0d.U(8.W))
    val bfp8c3      = Value(0x0e.U(8.W))
    val bfp8c4      = Value(0x0f.U(8.W))
    // Unsigned Integer 16 (2 channels)
    val uint16c1    = Value(0x10.U(8.W))
    val uint16c3    = Value(0x12.U(8.W))
    // Signed Integer 16 (2 channels)
    val int16c1     = Value(0x14.U(8.W))
    val int16c3     = Value(0x16.U(8.W))
    // Floating Point 16 (2 channels)
    val fp16c1      = Value(0x18.U(8.W))
    val fp16c3      = Value(0x1a.U(8.W))
    // Binary Floating Point 16 (2 channels)
    val bfp16c1     = Value(0x1c.U(8.W))
    val bfp16c3     = Value(0x1e.U(8.W))
    // Unsigned Integer 32 (1 channel)
    val uint32c1    = Value(0x21.U(8.W))
    // Signed Integer 32 (1 channel)
    val int32c1     = Value(0x24.U(8.W))
    // Floating Point 32 (1 channel)
    val fp32c1      = Value(0x28.U(8.W))
}