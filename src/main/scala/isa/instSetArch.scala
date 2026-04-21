// See README.md for license details.
// -----------------------------------------------------------------------------
//  instSetArch.scala — NPU opcode families and per-family funct3 enums
//
//  Encoding model: RISC-V-inspired 32-bit instruction word.
//    opcode (7b) selects a functional *family*.
//    funct3 (3b) selects the sub-operation within the family.
//    funct7 (7b) carries attributes: width, round, saturate, dtype class.
//    rd, rs1, rs2 (5b each) index the register file.
//
//  See instrFormat.scala for bit-position constants and attribute enums.
//  See NpuAssembler.scala for a Scala-side assembler that builds instruction words.
//
//  Notation :
//    N(bits) — base lane width in bits (default 8). MMALU's nbits.
//    L       — number of VX base registers (default 32, must be div-by-4).
//    K       — SIMD lane count per register (8 for tests, 64 at top).
//              Equals MMALU's array-side n at the backend boundary.
//    VX[0..L-1]   — L  registers of K × N    bits
//    VE[0..L/2-1] — 16 registers of K × 2N   bits (alias VX[2i..2i+1])
//    VR[0..L/4-1] — 8  registers of K × 4N   bits (alias VX[4i..4i+3])
// -----------------------------------------------------------------------------

package isa

import chisel3._
import chisel3.util._

// ---------------------------------------------------------------------------
// Opcode families (7-bit primary decode field)
// ---------------------------------------------------------------------------
object OpFamily extends ChiselEnum {
  val NOP          = Value(0x00.U(7.W))
  val LD           = Value(0x01.U(7.W))
  val ST           = Value(0x02.U(7.W))
  val MMA          = Value(0x03.U(7.W))
  val VALU_ARITH   = Value(0x10.U(7.W))
  val VALU_LOGIC   = Value(0x11.U(7.W))
  val VALU_REDUCE  = Value(0x12.U(7.W))
  val VALU_LUT     = Value(0x13.U(7.W))
  val VALU_CVT     = Value(0x14.U(7.W))
  val VALU_BCAST   = Value(0x15.U(7.W))
  val VALU_FP      = Value(0x16.U(7.W))
  val VALU_FP_FMA  = Value(0x17.U(7.W))
  val VALU_MOV     = Value(0x18.U(7.W))
}

// ---------------------------------------------------------------------------
// funct3 encodings per family
// ---------------------------------------------------------------------------

// VALU_ARITH (opcode=0x10): elementwise arithmetic on VX/VE/VR
// Width from funct7[1:0]; saturate from funct7[4].
// RISC-V aligned where possible.
object Funct3Arith {
  val ADD  = 0.U(3.W)   // rd = rs1 + rs2
  val SUB  = 1.U(3.W)   // rd = rs1 - rs2
  val MUL  = 2.U(3.W)   // rd = rs1 * rs2  (narrow sat; wide on out_wide)
  val NEG  = 3.U(3.W)   // rd = -rs1  (rs2 ignored)
  val ABS  = 4.U(3.W)   // rd = |rs1|  (rs2 ignored)
  val MAX  = 5.U(3.W)   // rd = max(rs1, rs2)
  val MIN  = 6.U(3.W)   // rd = min(rs1, rs2)
  val RSUB = 7.U(3.W)   // rd = rs2 - rs1  (reverse subtract)
}

// VALU_LOGIC (opcode=0x11): bitwise and shift operations on VX/VE/VR
// RISC-V aligned: SLL=001, SRL/SRA=101, XOR=100, OR=110, AND=111.
object Funct3Logic {
  val SLL = 0.U(3.W)   // logical left shift
  val SRL = 1.U(3.W)   // logical right shift
  val SRA = 2.U(3.W)   // arithmetic right shift (sign extending)
  val ROL = 3.U(3.W)   // rotate left
  val XOR = 4.U(3.W)   // RV xor (100)
  val NOT = 5.U(3.W)   // bitwise NOT (rs2 ignored)
  val OR  = 6.U(3.W)   // RV or (110)
  val AND = 7.U(3.W)   // RV and (111)
}

// VALU_REDUCE (opcode=0x12): horizontal reductions, result broadcast to all lanes
object Funct3Reduce {
  val SUM  = 0.U(3.W)  // Σ lanes → out_wide broadcast
  val RMAX = 1.U(3.W)  // max over lanes → broadcast
  val RMIN = 2.U(3.W)  // min over lanes → broadcast
  val RAND = 3.U(3.W)  // AND over lanes → broadcast
  val ROR  = 4.U(3.W)  // OR  over lanes → broadcast
  val RXOR = 5.U(3.W)  // XOR over lanes → broadcast
  // 6, 7 reserved
}

// VALU_LUT (opcode=0x13): 256-entry ROM transcendentals, SQ1.6 input, VX only
object Funct3Lut {
  val EXP   = 0.U(3.W)  // exp(x)   SQ1.6 → UQ0.8 (stored as SInt signed byte)
  val RECIP = 1.U(3.W)  // 1/x      SQ1.6 → SQ1.6; x=0 → sentinel 127
  val TANH  = 2.U(3.W)  // tanh(x)  SQ1.6 → SQ1.6
  val ERF   = 3.U(3.W)  // erf(x)   SQ1.6 → SQ1.6  (GELU helper)
  // 4..7 reserved for future LUT ops
}

// VALU_CVT (opcode=0x14): type conversions
// funct3 = destination format code (FmtCode object in instrFormat.scala)
// funct7[2:0] = source format code; funct7[3]=sat; funct7[5:4]=round; funct7[6]=BF8 variant
// Width is determined by src/dst format (e.g. s32 implies VR; s8 implies VX).

// VALU_BCAST (opcode=0x15): scalar broadcast to all K lanes
object Funct3Bcast {
  val REG = 0.U(3.W)   // R-format: rd[i] = rs1[0] for all i; width from funct7[1:0]
  val IMM = 1.U(3.W)   // I-format: rd[i] = sext(imm[11:0]); width from funct7[1:0]
  // 2..7 reserved
}

// VALU_FP (opcode=0x16): FP32 arithmetic on VR lanes; width and dtype implicit (VR, FP)
// funct7[3:2] carries round mode; funct7[6:5] reserved-must-be-01 (FP dtype).
object Funct3Fp {
  val FADD = 0.U(3.W)
  val FSUB = 1.U(3.W)
  val FMUL = 2.U(3.W)
  val FNEG = 3.U(3.W)  // rs2 ignored
  val FABS = 4.U(3.W)  // rs2 ignored
  val FMAX = 5.U(3.W)
  val FMIN = 6.U(3.W)
  // 7 reserved
}

// VALU_FP_FMA (opcode=0x17): fused multiply-add, S-format (rd, rs1, rs2, rs3)
// rd = rs1 * rs2 + rs3  (FMA), etc.
object Funct3Fma {
  val FMA  = 0.U(3.W)   // rd =  (rs1 * rs2) + rs3
  val FMS  = 1.U(3.W)   // rd =  (rs1 * rs2) - rs3
  val NFMA = 2.U(3.W)   // rd = -(rs1 * rs2) + rs3
  val NFMS = 3.U(3.W)   // rd = -(rs1 * rs2) - rs3
  // 4..7 reserved
}

// VALU_MOV (opcode=0x18): register move and immediate load
object Funct3Mov {
  val MOV  = 0.U(3.W)  // R-format: rd = rs1 (copy), width from funct7[1:0]
  val MOVI = 1.U(3.W)  // I-format: rd[0] = sext(imm); other lanes unchanged
  val MOVH = 2.U(3.W)  // I-format: rd[0][31:16] = imm[15:0]; low 16 unchanged
  // 3..7 reserved
}

// MMA (opcode=0x03): systolic array matrix multiply-accumulate
object Funct3Mma {
  val MMA       = 0.U(3.W)   // normal accumulate (keep from funct7[4])
  val MMA_LAST  = 1.U(3.W)   // assert clct signal; finalize result
  val MMA_RESET = 2.U(3.W)   // clear accumulator
  // 3..7 reserved
}

// LD / ST (opcodes 0x01/0x02): memory access; funct3 encodes transfer width
object Funct3Mem {
  val BYTE  = 0.U(3.W)  // N-bit lane (byte)
  val HALF  = 1.U(3.W)  // 2N-bit
  val WORD  = 2.U(3.W)  // 4N-bit
  val VX_VEC = 3.U(3.W) // full K-lane VX vector
  val VE_VEC = 4.U(3.W) // full K-lane VE vector
  val VR_VEC = 5.U(3.W) // full K-lane VR vector
  // 6..7 reserved
}

// ---------------------------------------------------------------------------
// Backwards-compatible NeuralCoreMicroOp: wraps the raw 32-bit instruction
// word.  The InstrDecoder module unpacks it into DecodedMicroOp.
// ---------------------------------------------------------------------------
class NeuralCoreMicroOp extends Bundle {
  val word = UInt(32.W)
}
