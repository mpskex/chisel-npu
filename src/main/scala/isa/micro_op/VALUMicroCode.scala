// See README.md for license details.
// -----------------------------------------------------------------------------
//  VALUMicroCode.scala — internal VALU control bundle and sub-op enums
//
//  The NCoreVALUBundle is the *decoded* view of a VALU instruction,
//  produced by InstrDecoder and consumed directly by the VALU module.
//  It is NOT the raw instruction word; see instrFormat.scala for that.
//
//  Notation: N(bits), L, K — see instSetArch.scala header.
// -----------------------------------------------------------------------------

package isa.micro_op

import chisel3._
import chisel3.util._

// ---------------------------------------------------------------------------
// VecDType — data-type packing layout (legacy; still used for test helpers
// and to carry the BF8 sub-format selector in the decoded bundle).
// ---------------------------------------------------------------------------
object VecDType extends ChiselEnum {
  val U8C4    = Value(0x1.U(4.W))
  val S8C4    = Value(0x2.U(4.W))
  val U16C2   = Value(0x3.U(4.W))
  val S16C2   = Value(0x4.U(4.W))
  val FP16C2  = Value(0x5.U(4.W))
  val BF16C2  = Value(0x6.U(4.W))
  val U32C1   = Value(0x7.U(4.W))
  val S32C1   = Value(0x8.U(4.W))
  val FP32C1  = Value(0x9.U(4.W))
  // FP8 formats (OCP / NVIDIA naming)
  val BF8E4M3 = Value(0xA.U(4.W))  // 1/4/3 bits; activation-side
  val BF8E5M2 = Value(0xB.U(4.W))  // 1/5/2 bits; weight/gradient-side
}

// ---------------------------------------------------------------------------
// VecOp — internal operation code decoded from opcode+funct3.
// The VALU module dispatches on this enum; it never sees raw instruction bits.
// Values are compact (5-bit) for use in MuxLookup.
// ---------------------------------------------------------------------------
object VecOp extends ChiselEnum {
  // -- ARITH (VALU_ARITH family) --
  val vadd  = Value(0x00.U(7.W))
  val vsub  = Value(0x01.U(7.W))
  val vmul  = Value(0x02.U(7.W))
  val vneg  = Value(0x03.U(7.W))
  val vabs  = Value(0x04.U(7.W))
  val vmax  = Value(0x05.U(7.W))
  val vmin  = Value(0x06.U(7.W))
  val vrsub = Value(0x07.U(7.W))

  // -- LOGIC (VALU_LOGIC family) --
  val vsll  = Value(0x08.U(7.W))
  val vsrl  = Value(0x09.U(7.W))
  val vsra  = Value(0x0A.U(7.W))
  val vrol  = Value(0x0B.U(7.W))
  val vxor  = Value(0x0C.U(7.W))
  val vnot  = Value(0x0D.U(7.W))
  val vor   = Value(0x0E.U(7.W))
  val vand  = Value(0x0F.U(7.W))

  // -- REDUCE (VALU_REDUCE family) --
  val vsum  = Value(0x10.U(7.W))
  val vrmax = Value(0x11.U(7.W))
  val vrmin = Value(0x12.U(7.W))
  val vrand = Value(0x13.U(7.W))
  val vror  = Value(0x14.U(7.W))
  val vrxor = Value(0x15.U(7.W))

  // -- LUT (VALU_LUT family) --
  val vexp   = Value(0x18.U(7.W))
  val vrecip = Value(0x19.U(7.W))
  val vtanh  = Value(0x1A.U(7.W))
  val verf   = Value(0x1B.U(7.W))

  // -- CVT (VALU_CVT family) — one entry per (dst_fmt, src_fmt) pair actually used --
  val vcvt_s8_s32   = Value(0x20.U(7.W))
  val vcvt_s32_s8   = Value(0x21.U(7.W))
  val vcvt_s32_f32  = Value(0x22.U(7.W))
  val vcvt_f32_s32  = Value(0x23.U(7.W))
  val vcvt_f32_s8   = Value(0x24.U(7.W))
  val vcvt_s8_f32   = Value(0x25.U(7.W))
  val vcvt_f32_bf16 = Value(0x26.U(7.W))
  val vcvt_bf16_f32 = Value(0x27.U(7.W))
  val vcvt_f32_bf8  = Value(0x28.U(7.W))
  val vcvt_bf8_f32  = Value(0x29.U(7.W))
  val vcvt_s16_s32  = Value(0x2A.U(7.W))
  val vcvt_s32_s16  = Value(0x2B.U(7.W))

  // -- BCAST (VALU_BCAST family) --
  val vbcast_reg = Value(0x30.U(7.W))
  val vbcast_imm = Value(0x31.U(7.W))

  // -- FP ARITH (VALU_FP family) --
  val vfadd = Value(0x38.U(7.W))
  val vfsub = Value(0x39.U(7.W))
  val vfmul = Value(0x3A.U(7.W))
  val vfneg = Value(0x3B.U(7.W))
  val vfabs = Value(0x3C.U(7.W))
  val vfmax = Value(0x3D.U(7.W))
  val vfmin = Value(0x3E.U(7.W))

  // -- FP FMA (VALU_FP_FMA family) --
  val vfma  = Value(0x3F.U(7.W))
  val vfms  = Value(0x40.U(7.W))
  val vnfma = Value(0x41.U(7.W))
  val vnfms = Value(0x42.U(7.W))

  // -- MOV (VALU_MOV family) --
  val vmov  = Value(0x43.U(7.W))
  val vmovi = Value(0x44.U(7.W))
  val vmovh = Value(0x45.U(7.W))
}

// ---------------------------------------------------------------------------
// NCoreVALUBundle — decoded control bundle presented to the VALU module.
// Populated by InstrDecoder; consumed by VALU.io.ctrl.
//
//   op       — internal VecOp (decoded from opcode+funct3)
//   width    — register class (VX/VE/VR), from funct7[1:0]
//   dtype    — data type / BF8 variant selector, from VecDType
//   saturate — from funct7[4]
//   round    — from funct7[3:2] (or S-type rnd field for FMA)
//   rs3_idx  — third source register (S-type only; used by FMA)
//   imm      — sign-extended 12-bit immediate (I-type only; used by bcast.imm / movi)
// ---------------------------------------------------------------------------
// NCoreVALUBundle — decoded control bundle for VALU.
//   op      : internal VecOp (decoded from opcode+funct3)
//   dtype   : data type / BF8 variant selector
//   saturate: from funct7[4]
//   round   : from funct7[3:2]; 00=RNE, 01=RTZ, 10=floor, 11=ceil
//   regCls  : register class; 0=VX (N bits), 1=VE (2N bits), 2=VR (4N bits)
//   rs3_idx : third source register (S-type / FMA)
//   imm     : sign-extended 12-bit immediate (I-type)
class NCoreVALUBundle extends Bundle {
  val op       = VecOp()
  val dtype    = VecDType()
  val saturate = Bool()
  val round    = UInt(2.W)
  val regCls   = UInt(2.W)  // 0=VX, 1=VE, 2=VR  (avoids name conflict with chisel3 Width)
  val rs3_idx  = UInt(5.W)
  val imm      = SInt(12.W)
}

// (Legacy alias — NCoreMMALUCtrlBundle stays in MMALUMicroCode.scala)
