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

// VALU_LUT (opcode=0x13): programmable two-bank LUT, raw byte-in/byte-out
//
//  vlut   (funct3=0/1) — per-lane 256-entry lookup from bank A (funct3=0)
//                        or bank B (funct3=1).  R-type; rd=VX dst, rs1=VX src.
//                        round[0] in the decoded bundle = bank select (0=A, 1=B).
//
//  vsetlut (funct3=4/5) — write one K×4-byte segment of the LUT bank from a
//                          VR source register.  I-type; rs1=VR src, imm=segment.
//                          funct3=4 → bank A, funct3=5 → bank B.
//                          No register-file write (side-effect on VALU-internal state only).
//
//  Segment packing: VR[rs1] holds K lanes × 4 bytes = K×4 consecutive LUT
//  entries.  Segment index s maps to LUT entries [s×K×4 .. (s+1)×K×4 − 1].
//  At K=8  : 8 vsetlut calls fill the full 256-byte bank.
//  At K=64 : 1 vsetlut call fills the full 256-byte bank.
//
//  The Qfmt object (vec.scala) remains available as a Scala-only compiler and
//  test utility for generating table data; it is no longer synthesised as hardware.
object Funct3Lut {
  val VLUT_A    = 0.U(3.W)  // per-lane lookup from bank A (R-type)
  val VLUT_B    = 1.U(3.W)  // per-lane lookup from bank B (R-type)
  // 2..3 reserved
  val VSETLUT_A = 4.U(3.W)  // write K×4-byte segment into bank A (I-type)
  val VSETLUT_B = 5.U(3.W)  // write K×4-byte segment into bank B (I-type)
  // 6..7 reserved
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

// LD (opcode 0x01) / ST (opcode 0x02): register-file access
//
//   funct3  LD meaning          ST meaning
//   ------  ------------------  ------------------
//   0..2    scalar (future)     scalar (future)
//   3       ld.VX               st.VX
//   4       ld.VE               st.VE
//   5       ld.VR               st.VR
//   6       ld.gather / ld.tile st.scatter          ← unified via funct7
//   7       tile.cfg            (reserved)
//
// funct3=6 is the "indexed" memory op.  funct7 selects the sub-mode:
//
//   Funct7Gather.USE_TILE_CNT (funct7[2]):
//     0 = ld.gather / st.scatter  — addresses from VX[rs1] lanes (LLM/embedding)
//     1 = ld.tile                 — address from SREG tile counters (conv/strided)
//
//   Other funct7 bits (tile mode only):
//     funct7[0] = zero_pad   (ld.tile only)
//     funct7[1] = transposed (ld.tile only)
//     funct7[3] = auto_inc   (ld.tile: pulse tile_w_inc after load)
object Funct3Mem {
  val BYTE      = 0.U(3.W)  // N-bit lane scalar       — future
  val HALF      = 1.U(3.W)  // 2N-bit                  — future
  val WORD      = 2.U(3.W)  // 4N-bit                  — future
  val VX_VEC    = 3.U(3.W)  // full K-lane VX vector
  val VE_VEC    = 4.U(3.W)  // full K-lane VE vector
  val VR_VEC    = 5.U(3.W)  // full K-lane VR vector
  val GATHER    = 6.U(3.W)  // ld.gather/ld.tile (LD) | st.scatter (ST)
  val TILE_CFG  = 7.U(3.W)  // tile.cfg — write conv/stride params to .sreg
}

// funct7 bit layout for funct3=6 (GATHER) instructions
object Funct7Gather {
  val ZERO_PAD      = 0  // bit 0: zero-pad enable     (ld.tile only)
  val TRANSPOSED    = 1  // bit 1: transposed-mode      (ld.tile only)
  val USE_TILE_CNT  = 2  // bit 2: 0=gather, 1=tile     (selects sub-mode)
  val AUTO_INC      = 3  // bit 3: auto-increment tile_w after completion
}

// tile.cfg wr_sel values (encoded in imm[2:0] of the tile.cfg instruction)
object TileCfgSel {
  val HW       = 0.U(3.W)  // wr_data = {W_in[31:16],  H_in[15:0]}
  val CH       = 1.U(3.W)  // wr_data = {C_out[31:16], C_in[15:0]}
  val KERN     = 2.U(3.W)  // wr_data = {mode[25:24],pad_w[23:20],pad_h[19:16],dil[15:12],stride[11:8],Kw[7:4],Kh[3:0]}
  val POS      = 3.U(3.W)  // wr_data = {tile_w[31:16], tile_h[15:0]}  (reset/seed)
  val STRIDE_H = 4.U(3.W)  // wr_data[15:0] = stride_row_h (RF rows per tile_h step)
  val STRIDE_W = 5.U(3.W)  // wr_data[15:0] = stride_row_w (RF rows per tile_w step)
}

// ---------------------------------------------------------------------------
// Backwards-compatible NeuralCoreMicroOp: wraps the raw 32-bit instruction
// word.  The InstrDecoder module unpacks it into DecodedMicroOp.
// ---------------------------------------------------------------------------
class NeuralCoreMicroOp extends Bundle {
  val word = UInt(32.W)
}
