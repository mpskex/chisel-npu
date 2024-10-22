// See README.md for license details.

package isa.dtype
import chisel3._
import chisel3.util._

class U8C4Input() extends Bundle {
    val u8c0 = UInt(8.W)
    val u8c1 = UInt(8.W)
    val u8c2 = UInt(8.W)
    val u8c3 = UInt(8.W)
}

class S8C4Input() extends Bundle {
    val s8c0 = SInt(8.W)
    val s8c1 = SInt(8.W)
    val s8c2 = SInt(8.W)
    val s8c3 = SInt(8.W)
}

class U16C2Input() extends Bundle {
    val u16c0 = UInt(16.W)
    val u16c1 = UInt(16.W)
}

class S16C2Input() extends Bundle {
    val s16c0 = UInt(16.W)
    val s16c1 = UInt(16.W)
}

class FP16C2Input() extends Bundle {
    val fp16c0 = UInt(16.W)
    val fp16c1 = UInt(16.W)
}

class BF16C2Input() extends Bundle {
    val bf16c0 = UInt(16.W)
    val bf16c1 = UInt(16.W)
}

class U32C1Input() extends Bundle {
    val u32c0 = UInt(32.W)
}

class S32C1Input() extends Bundle {
    val u32c0 = SInt(32.W)
}

class FP32C1Input() extends Bundle {
    val u32c0 = UInt(32.W)
}