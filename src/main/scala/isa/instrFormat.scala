// See README.md for license details.
// -----------------------------------------------------------------------------
//  instrFormat.scala — RISC-V-inspired 32-bit instruction word layout
//
//  Parameters used throughout the ISA:
//    N(bits) — base lane width (default 8).  Always spelled N(bits) in prose.
//    L       — number of base VX registers (default 32, divisible by 4).
//    K       — SIMD lane count per register (default 8 for tests, 64 at top).
//              Equals MMALU's array-side parameter n at the backend boundary.
//
//  Instruction formats (32-bit word):
//
//  R-type  [funct7(7) | rs2(5) | rs1(5) | funct3(3) | rd(5) | opcode(7)]
//  I-type  [    imm[11:0](12)  | rs1(5) | funct3(3) | rd(5) | opcode(7)]
//  S-type  [rs3(5)|rnd(2)| rs2(5) | rs1(5) | funct3(3) | rd(5) | opcode(7)]
//
//  funct7 attribute layout (R-type):
//    [1:0] width  : 00=VX (N bits)  01=VE (2N bits)  10=VR (4N bits)  11=reserved
//    [3:2] round  : 00=RNE  01=RTZ  10=floor  11=ceil
//    [4]   sat    : 0=wrap  1=saturate
//    [6:5] dtype  : 00=INT  01=FP   10=BF     11=reserved
//
//  vcvt (VALU_CVT family) uses funct7 differently:
//    [2:0] src format code  (see FmtCode enum)
//    [3]   saturate
//    [5:4] round mode
//    [6]   BF8 variant      0=E4M3  1=E5M2
//
//  funct3 meanings are family-specific; see instSetArch.scala.
//  rd, rs1, rs2 index VX[0..L-1].  VE uses rd[3:0]; VR uses rd[2:0].
// -----------------------------------------------------------------------------

package isa

import chisel3._
import chisel3.util._

// ---------------------------------------------------------------------------
// Bit-position constants (field boundaries in the 32-bit word)
// ---------------------------------------------------------------------------
object InstrBits {
  val OPCODE_LO  =  0;  val OPCODE_HI  =  6   // 7 bits
  val RD_LO      =  7;  val RD_HI      = 11   // 5 bits
  val FUNCT3_LO  = 12;  val FUNCT3_HI  = 14   // 3 bits
  val RS1_LO     = 15;  val RS1_HI     = 19   // 5 bits
  val RS2_LO     = 20;  val RS2_HI     = 24   // 5 bits
  val FUNCT7_LO  = 25;  val FUNCT7_HI  = 31   // 7 bits

  // I-type immediate: bits[31:20]
  val IMM_I_LO   = 20;  val IMM_I_HI   = 31   // 12 bits (sign-extended)

  // S-type (FMA): rs3 at [31:27], round at [26:25]
  val RS3_LO     = 27;  val RS3_HI     = 31   // 5 bits
  val RND_S_LO   = 25;  val RND_S_HI   = 26   // 2 bits

  // funct7 attribute sub-fields
  val F7_WIDTH_LO = 0;  val F7_WIDTH_HI = 1   // [1:0] in funct7
  val F7_ROUND_LO = 2;  val F7_ROUND_HI = 3   // [3:2] in funct7
  val F7_SAT      = 4                          // [4]   in funct7
  val F7_DTYPE_LO = 5;  val F7_DTYPE_HI = 6   // [6:5] in funct7

  // vcvt funct7 sub-fields
  val F7_CVT_SRC_LO = 0; val F7_CVT_SRC_HI = 2  // [2:0]
  val F7_CVT_SAT    = 3                           // [3]
  val F7_CVT_RND_LO = 4; val F7_CVT_RND_HI = 5  // [5:4]
  val F7_CVT_BF8    = 6                           // [6] BF8 variant
}

// ---------------------------------------------------------------------------
// Width class: which register class the instruction operates on
// ---------------------------------------------------------------------------
object VecWidth extends ChiselEnum {
  val VX = Value(0.U(2.W))   // N(bits)-wide lanes
  val VE = Value(1.U(2.W))   // 2N-wide lanes
  val VR = Value(2.U(2.W))   // 4N-wide lanes
  val VW_RSV = Value(3.U(2.W)) // reserved
}

// ---------------------------------------------------------------------------
// Rounding mode
// ---------------------------------------------------------------------------
object VecRound extends ChiselEnum {
  val RNE   = Value(0.U(2.W))  // round to nearest, ties to even (IEEE default)
  val RTZ   = Value(1.U(2.W))  // round toward zero (truncate)
  val FLOOR = Value(2.U(2.W))  // round toward −∞
  val CEIL  = Value(3.U(2.W))  // round toward +∞
}

// ---------------------------------------------------------------------------
// Dtype class (high-level family; see FmtCode for precise per-op format)
// ---------------------------------------------------------------------------
object VecDtypeCls extends ChiselEnum {
  val INT = Value(0.U(2.W))
  val FP  = Value(1.U(2.W))
  val BF  = Value(2.U(2.W))
  val DC_RSV = Value(3.U(2.W)) // reserved
}

// ---------------------------------------------------------------------------
// Format codes used by vcvt (3-bit src and dst selectors)
// ---------------------------------------------------------------------------
object FmtCode {
  val S8   = 0.U(3.W)
  val S16  = 1.U(3.W)
  val S32  = 2.U(3.W)
  val F32  = 3.U(3.W)
  val BF16 = 4.U(3.W)
  val BF8  = 5.U(3.W)   // variant (E4M3/E5M2) comes from funct7[6]
  val RSV6 = 6.U(3.W)
  val RSV7 = 7.U(3.W)
}

// ---------------------------------------------------------------------------
// Scala-level decoded representation of the funct7 attribute field.
// Used in tests and in the assembler; not a Chisel bundle.
// ---------------------------------------------------------------------------
case class Funct7Attrs(
  width:  Int = 0,   // VecWidth value
  round:  Int = 0,   // VecRound value
  sat:    Boolean = false,
  dtype:  Int = 0,   // VecDtypeCls value
) {
  def encode: Int =
    (width & 3) | ((round & 3) << 2) | ((if (sat) 1 else 0) << 4) | ((dtype & 3) << 5)
}

case class CvtFunct7(
  srcFmt: Int = 0,
  sat:    Boolean = true,
  round:  Int = 0,
  bf8E5M2: Boolean = false,
) {
  def encode: Int =
    (srcFmt & 7) | ((if (sat) 1 else 0) << 3) | ((round & 3) << 4) | ((if (bf8E5M2) 1 else 0) << 6)
}
