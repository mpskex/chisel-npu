// See README.md for license details.
// -----------------------------------------------------------------------------
//  NpuAssembler.scala — Scala-side assembler for NPU instruction words
//
//  Produces 32-bit UInt literals that can be poked directly into
//  NeuralCoreMicroOp.word in simulation.
//
//  Usage example (in a spec):
//    import isa.NpuAssembler._
//    val instr = vadd(rd=0, rs1=1, rs2=2, width=VX)
//    dut.io.micro_op.word.poke(instr)
//
//  All methods return a Scala Int (bit pattern) that can be converted to
//  UInt via .U in a Chisel context, or wrapped by the asUInt helper.
// -----------------------------------------------------------------------------

package isa

import chisel3._

object NpuAssembler {

  // ---- Constants -----------------------------------------------------------

  // Width selectors (funct7[1:0])
  val VX = 0  // N(bits)-wide lanes
  val VE = 1  // 2N-wide lanes
  val VR = 2  // 4N-wide lanes

  // Rounding modes (funct7[3:2])
  val RNE   = 0
  val RTZ   = 1
  val FLOOR = 2
  val CEIL  = 3

  // Dtype class (funct7[6:5])
  val INT = 0
  val FP  = 1
  val BF  = 2

  // Format codes for vcvt (funct3 = dst, funct7[2:0] = src)
  val S8   = 0
  val S16  = 1
  val S32  = 2
  val F32  = 3
  val BF16 = 4
  val BF8  = 5   // BF8 variant (E4M3 vs E5M2) from bf8E5M2 parameter

  // ---- Encoding helpers ----------------------------------------------------

  /** Encode funct7 for R-type vector ops. */
  def f7(width: Int = VX, round: Int = RNE, sat: Boolean = false, dtype: Int = INT): Int =
    (width & 3) | ((round & 3) << 2) | ((if (sat) 1 else 0) << 4) | ((dtype & 3) << 5)

  /** Encode funct7 for VALU_CVT. */
  def f7Cvt(srcFmt: Int, sat: Boolean = true, round: Int = RNE, bf8E5M2: Boolean = false): Int =
    (srcFmt & 7) | ((if (sat) 1 else 0) << 3) | ((round & 3) << 4) | ((if (bf8E5M2) 1 else 0) << 6)

  /** Build R-type instruction word (returns Long to avoid signed-int overflow at bit 31). */
  def encR(opcode: Int, funct3: Int, funct7: Int, rd: Int, rs1: Int, rs2: Int): Int = {
    val w = (opcode.toLong & 0x7F) |
            ((rd.toLong & 0x1F) << 7) |
            ((funct3.toLong & 0x7) << 12) |
            ((rs1.toLong & 0x1F) << 15) |
            ((rs2.toLong & 0x1F) << 20) |
            ((funct7.toLong & 0x7F) << 25)
    (w & 0xFFFFFFFFL).toInt  // keep 32 bits, return as (possibly signed) Int
  }

  /** Build I-type instruction word (imm is sign-extended 12-bit). */
  def encI(opcode: Int, funct3: Int, rd: Int, rs1: Int, imm: Int): Int = {
    val imm12 = imm.toLong & 0xFFF
    val w = (opcode.toLong & 0x7F) |
            ((rd.toLong & 0x1F) << 7) |
            ((funct3.toLong & 0x7) << 12) |
            ((rs1.toLong & 0x1F) << 15) |
            (imm12 << 20)
    (w & 0xFFFFFFFFL).toInt
  }

  /** Build S-type (FMA) instruction word. rs3 at [31:27], rnd at [26:25]. */
  def encS(opcode: Int, funct3: Int, rd: Int, rs1: Int, rs2: Int, rs3: Int, round: Int = RNE): Int = {
    val w = (opcode.toLong & 0x7F) |
            ((rd.toLong & 0x1F) << 7) |
            ((funct3.toLong & 0x7) << 12) |
            ((rs1.toLong & 0x1F) << 15) |
            ((rs2.toLong & 0x1F) << 20) |
            ((round.toLong & 0x3) << 25) |
            ((rs3.toLong & 0x1F) << 27)
    (w & 0xFFFFFFFFL).toInt
  }

  // ---- NOP / special -------------------------------------------------------

  val nop: Int = 0x00  // opcode=0x00, everything zero

  // ---- VALU_ARITH (opcode=0x10) --------------------------------------------

  def vadd (rd: Int, rs1: Int, rs2: Int, width: Int = VX, sat: Boolean = false): Int = {
    val d = InstrTable.byMnemonic("vadd")
    encR(d.opcode, d.funct3, f7(width, sat=sat), rd, rs1, rs2)
  }
  def vsub (rd: Int, rs1: Int, rs2: Int, width: Int = VX, sat: Boolean = false): Int = {
    val d = InstrTable.byMnemonic("vsub")
    encR(d.opcode, d.funct3, f7(width, sat=sat), rd, rs1, rs2)
  }
  def vmul (rd: Int, rs1: Int, rs2: Int, width: Int = VX, sat: Boolean = false): Int = {
    val d = InstrTable.byMnemonic("vmul")
    encR(d.opcode, d.funct3, f7(width, sat=sat), rd, rs1, rs2)
  }
  def vneg (rd: Int, rs1: Int, width: Int = VX, sat: Boolean = false): Int = {
    val d = InstrTable.byMnemonic("vneg")
    encR(d.opcode, d.funct3, f7(width, sat=sat), rd, rs1, 0)
  }
  def vabs (rd: Int, rs1: Int, width: Int = VX, sat: Boolean = false): Int = {
    val d = InstrTable.byMnemonic("vabs")
    encR(d.opcode, d.funct3, f7(width, sat=sat), rd, rs1, 0)
  }
  def vmax (rd: Int, rs1: Int, rs2: Int, width: Int = VX): Int = {
    val d = InstrTable.byMnemonic("vmax")
    encR(d.opcode, d.funct3, f7(width), rd, rs1, rs2)
  }
  def vmin (rd: Int, rs1: Int, rs2: Int, width: Int = VX): Int = {
    val d = InstrTable.byMnemonic("vmin")
    encR(d.opcode, d.funct3, f7(width), rd, rs1, rs2)
  }
  def vrsub(rd: Int, rs1: Int, rs2: Int, width: Int = VX, sat: Boolean = false): Int = {
    val d = InstrTable.byMnemonic("vrsub")
    encR(d.opcode, d.funct3, f7(width, sat=sat), rd, rs1, rs2)
  }

  // ---- VALU_LOGIC (opcode=0x11) --------------------------------------------

  def vsll(rd: Int, rs1: Int, rs2: Int, width: Int = VX): Int = {
    val d = InstrTable.byMnemonic("vsll")
    encR(d.opcode, d.funct3, f7(width), rd, rs1, rs2)
  }
  def vsrl(rd: Int, rs1: Int, rs2: Int, width: Int = VX): Int = {
    val d = InstrTable.byMnemonic("vsrl")
    encR(d.opcode, d.funct3, f7(width), rd, rs1, rs2)
  }
  def vsra(rd: Int, rs1: Int, rs2: Int, width: Int = VX): Int = {
    val d = InstrTable.byMnemonic("vsra")
    encR(d.opcode, d.funct3, f7(width), rd, rs1, rs2)
  }
  def vrol(rd: Int, rs1: Int, rs2: Int, width: Int = VX): Int = {
    val d = InstrTable.byMnemonic("vrol")
    encR(d.opcode, d.funct3, f7(width), rd, rs1, rs2)
  }
  def vxor(rd: Int, rs1: Int, rs2: Int, width: Int = VX): Int = {
    val d = InstrTable.byMnemonic("vxor")
    encR(d.opcode, d.funct3, f7(width), rd, rs1, rs2)
  }
  def vnot(rd: Int, rs1: Int, width: Int = VX): Int = {
    val d = InstrTable.byMnemonic("vnot")
    encR(d.opcode, d.funct3, f7(width), rd, rs1, 0)
  }
  def vor (rd: Int, rs1: Int, rs2: Int, width: Int = VX): Int = {
    val d = InstrTable.byMnemonic("vor")
    encR(d.opcode, d.funct3, f7(width), rd, rs1, rs2)
  }
  def vand(rd: Int, rs1: Int, rs2: Int, width: Int = VX): Int = {
    val d = InstrTable.byMnemonic("vand")
    encR(d.opcode, d.funct3, f7(width), rd, rs1, rs2)
  }

  // ---- VALU_REDUCE (opcode=0x12) -------------------------------------------

  def vsum (rd: Int, rs1: Int, width: Int = VX): Int = {
    val d = InstrTable.byMnemonic("vsum")
    encR(d.opcode, d.funct3, f7(width), rd, rs1, 0)
  }
  def vrmax(rd: Int, rs1: Int, width: Int = VX): Int = {
    val d = InstrTable.byMnemonic("vrmax")
    encR(d.opcode, d.funct3, f7(width), rd, rs1, 0)
  }
  def vrmin(rd: Int, rs1: Int, width: Int = VX): Int = {
    val d = InstrTable.byMnemonic("vrmin")
    encR(d.opcode, d.funct3, f7(width), rd, rs1, 0)
  }
  def vrand(rd: Int, rs1: Int, width: Int = VX): Int = {
    val d = InstrTable.byMnemonic("vrand")
    encR(d.opcode, d.funct3, f7(width), rd, rs1, 0)
  }
  def vror (rd: Int, rs1: Int, width: Int = VX): Int = {
    val d = InstrTable.byMnemonic("vror")
    encR(d.opcode, d.funct3, f7(width), rd, rs1, 0)
  }
  def vrxor(rd: Int, rs1: Int, width: Int = VX): Int = {
    val d = InstrTable.byMnemonic("vrxor")
    encR(d.opcode, d.funct3, f7(width), rd, rs1, 0)
  }

  // ---- VALU_LUT (opcode=0x13) — programmable two-bank LUT -------------------
  // Bank select: 0=A (default), 1=B.

  /**
   * Per-lane lookup: out[i] = lut_bank[in_a_vx[i]].
   * bank=0 → bank A (funct3=0), bank=1 → bank B (funct3=1).
   */
  def vlut(rd: Int, rs1: Int, bank: Int = 0): Int = {
    val d = InstrTable.byMnemonic(if ((bank & 1) == 0) "vlut.A" else "vlut.B")
    encR(d.opcode, d.funct3, f7(VX), rd, rs1, 0)
  }

  /**
   * Write one K×4-byte segment from VR[rs1] into the selected LUT bank.
   * segment: which K×4-entry block (0-based) within the 256-entry table.
   * bank=0 → bank A (funct3=4), bank=1 → bank B (funct3=5).
   * I-type: rd=0 (no register-file destination); imm=segment.
   */
  def vsetlut(rs1: Int, segment: Int, bank: Int = 0): Int = {
    val d = InstrTable.byMnemonic(if ((bank & 1) == 0) "vsetlut.A" else "vsetlut.B")
    encI(d.opcode, d.funct3, 0, rs1, segment)
  }

  // ---- VALU_CVT (opcode=0x14) ----------------------------------------------
  // funct3 = dst fmt code; f7 encodes src + sat + round + bf8 variant

  def vcvt(rd: Int, rs1: Int,
           dstFmt: Int, srcFmt: Int,
           sat: Boolean = true, round: Int = RNE,
           bf8E5M2: Boolean = false): Int = {
    require(InstrTable.cvtPairs.exists(p => p.dst == dstFmt && p.src == srcFmt),
      s"vcvt: no legal CVT pair (dst=$dstFmt, src=$srcFmt)")
    encR(0x14, dstFmt, f7Cvt(srcFmt, sat, round, bf8E5M2), rd, rs1, 0)
  }

  // Convenience aliases
  def vcvt_s8_s32 (rd: Int, rs1: Int, sat: Boolean = true,  round: Int = RNE): Int = vcvt(rd, rs1, S8,   S32, sat, round)
  def vcvt_s32_s8 (rd: Int, rs1: Int): Int = vcvt(rd, rs1, S32, S8)
  def vcvt_s32_f32(rd: Int, rs1: Int, round: Int = RNE): Int = vcvt(rd, rs1, S32, F32, round=round)
  def vcvt_f32_s32(rd: Int, rs1: Int): Int = vcvt(rd, rs1, F32, S32, sat=false)
  def vcvt_f32_s8 (rd: Int, rs1: Int): Int = vcvt(rd, rs1, F32, S8,  sat=false)
  def vcvt_s8_f32 (rd: Int, rs1: Int, sat: Boolean = true, round: Int = RNE): Int = vcvt(rd, rs1, S8,   F32, sat, round)
  def vcvt_f32_bf16(rd: Int, rs1: Int): Int = vcvt(rd, rs1, F32, BF16, sat=false)
  def vcvt_bf16_f32(rd: Int, rs1: Int): Int = vcvt(rd, rs1, BF16, F32, sat=false)
  def vcvt_f32_bf8 (rd: Int, rs1: Int, e5m2: Boolean = false): Int = vcvt(rd, rs1, F32, BF8, sat=false, bf8E5M2=e5m2)
  def vcvt_bf8_f32 (rd: Int, rs1: Int, e5m2: Boolean = false): Int = vcvt(rd, rs1, BF8, F32, sat=false, bf8E5M2=e5m2)
  def vcvt_s16_s32 (rd: Int, rs1: Int, sat: Boolean = true): Int   = vcvt(rd, rs1, S16, S32, sat)
  def vcvt_s32_s16 (rd: Int, rs1: Int): Int = vcvt(rd, rs1, S32, S16, sat=false)

  // ---- VALU_BCAST (opcode=0x15) --------------------------------------------

  /** Broadcast lane 0 of rs1 to all K lanes of rd (R-format). */
  def vbcast(rd: Int, rs1: Int, width: Int = VX): Int = {
    val d = InstrTable.byMnemonic("vbcast")
    encR(d.opcode, d.funct3, f7(width), rd, rs1, 0)
  }

  /** Broadcast sign-extended 12-bit immediate to all K lanes of rd (I-format). */
  def vbcastImm(rd: Int, imm: Int, width: Int = VX): Int = {
    val d = InstrTable.byMnemonic("vbcast.imm")
    encI(d.opcode, d.funct3, rd, 0, imm)
  }

  // ---- VALU_FP (opcode=0x16) — FP32 on VR ---------------------------------

  def vfadd(rd: Int, rs1: Int, rs2: Int, round: Int = RNE): Int = {
    val d = InstrTable.byMnemonic("vfadd")
    encR(d.opcode, d.funct3, f7(VR, round=round, dtype=FP), rd, rs1, rs2)
  }
  def vfsub(rd: Int, rs1: Int, rs2: Int, round: Int = RNE): Int = {
    val d = InstrTable.byMnemonic("vfsub")
    encR(d.opcode, d.funct3, f7(VR, round=round, dtype=FP), rd, rs1, rs2)
  }
  def vfmul(rd: Int, rs1: Int, rs2: Int, round: Int = RNE): Int = {
    val d = InstrTable.byMnemonic("vfmul")
    encR(d.opcode, d.funct3, f7(VR, round=round, dtype=FP), rd, rs1, rs2)
  }
  def vfneg(rd: Int, rs1: Int): Int = {
    val d = InstrTable.byMnemonic("vfneg")
    encR(d.opcode, d.funct3, f7(VR, dtype=FP), rd, rs1, 0)
  }
  def vfabs(rd: Int, rs1: Int): Int = {
    val d = InstrTable.byMnemonic("vfabs")
    encR(d.opcode, d.funct3, f7(VR, dtype=FP), rd, rs1, 0)
  }
  def vfmax(rd: Int, rs1: Int, rs2: Int): Int = {
    val d = InstrTable.byMnemonic("vfmax")
    encR(d.opcode, d.funct3, f7(VR, dtype=FP), rd, rs1, rs2)
  }
  def vfmin(rd: Int, rs1: Int, rs2: Int): Int = {
    val d = InstrTable.byMnemonic("vfmin")
    encR(d.opcode, d.funct3, f7(VR, dtype=FP), rd, rs1, rs2)
  }

  // ---- VALU_FP_FMA (opcode=0x17) — S-format -------------------------------

  def vfma (rd: Int, rs1: Int, rs2: Int, rs3: Int, round: Int = RNE): Int = {
    val d = InstrTable.byMnemonic("vfma")
    encS(d.opcode, d.funct3, rd, rs1, rs2, rs3, round)
  }
  def vfms (rd: Int, rs1: Int, rs2: Int, rs3: Int, round: Int = RNE): Int = {
    val d = InstrTable.byMnemonic("vfms")
    encS(d.opcode, d.funct3, rd, rs1, rs2, rs3, round)
  }
  def vnfma(rd: Int, rs1: Int, rs2: Int, rs3: Int, round: Int = RNE): Int = {
    val d = InstrTable.byMnemonic("vnfma")
    encS(d.opcode, d.funct3, rd, rs1, rs2, rs3, round)
  }
  def vnfms(rd: Int, rs1: Int, rs2: Int, rs3: Int, round: Int = RNE): Int = {
    val d = InstrTable.byMnemonic("vnfms")
    encS(d.opcode, d.funct3, rd, rs1, rs2, rs3, round)
  }

  // ---- VALU_MOV (opcode=0x18) ----------------------------------------------

  def vmov (rd: Int, rs1: Int, width: Int = VX): Int = {
    val d = InstrTable.byMnemonic("vmov")
    encR(d.opcode, d.funct3, f7(width), rd, rs1, 0)
  }
  def vmovi(rd: Int, imm: Int, width: Int = VX): Int = {
    val d = InstrTable.byMnemonic("vmovi")
    encI(d.opcode, d.funct3, rd, 0, imm)
  }
  def vmovh(rd: Int, imm: Int): Int = {
    val d = InstrTable.byMnemonic("vmovh")
    encI(d.opcode, d.funct3, rd, 0, imm)
  }

  // ---- MMA (opcode=0x03) ---------------------------------------------------
  // S-format register-level MAC:  vd = A·B + C  (one K×1 collect per mma)
  //   vs1 = A (VX, K×1 column), vs2 = B (VX, K×1 row),
  //   vs3 = C (VR accumulator; x0 = no C), rd = vd (VR).
  // Accumulation across instructions is software C-chaining (vs3 = previous
  // output); each mma's feed resets the PEs (keep=0).  mma.last is the group
  // marker (drain + collect) — the engine executes both identically.

  /** mma vd, vs1, vs2, vs3 — vd = A·B + C. */
  def mma(rd: Int, vs1: Int, vs2: Int, vs3: Int): Int = {
    val d = InstrTable.byMnemonic("mma")
    encS(d.opcode, d.funct3, rd, vs1, vs2, vs3)
  }

  /** mma.last vd, vs1, vs2, vs3 — last of the group: collect + drain marker. */
  def mmaLast(rd: Int, vs1: Int, vs2: Int, vs3: Int): Int = {
    val d = InstrTable.byMnemonic("mma.last")
    encS(d.opcode, d.funct3, rd, vs1, vs2, vs3)
  }

  /** Legacy mma.reset (R-format) — reserved by the new model; decodes but the
    * engine flags it illegal.  Kept for decoder coverage only. */
  def mmaReset(rd: Int, rs1: Int, rs2: Int): Int = {
    val d = InstrTable.byMnemonic("mma.reset")
    encR(d.opcode, d.funct3, f7(VR), rd, rs1, rs2)
  }

  // ---- LD / ST (opcodes 0x07 / 0x27, RISC-V V-aligned) --------------------
  // Unit-stride whole-register vector transfers between the L3 DATA section
  // and VX/VE/VR.  I-format: rs1 = section selector (0=A, 1=B, 2=ACCUM,
  // 3=OUT), imm = unsigned byte offset within the section.
  // Load:  rd  = destination register.  Store: rd  = source register.

  val SECT_A     = 0  // DATA section: A     (K×K int8,  1 KiB)
  val SECT_B     = 1  // DATA section: B     (K×K int8,  1 KiB)
  val SECT_ACCUM = 2  // DATA section: ACCUM (K×1 int32, 128 B)
  val SECT_OUT   = 3  // DATA section: OUT   (K×K int32, 4 KiB)

  def vle8 (rd: Int, sect: Int, off: Int = 0): Int = {  // vle8.v  → VX
    val d = InstrTable.byMnemonic("vle8")
    encI(d.opcode, d.funct3, rd, sect, off)
  }
  def vle16(rd: Int, sect: Int, off: Int = 0): Int = {  // vle16.v → VE
    val d = InstrTable.byMnemonic("vle16")
    encI(d.opcode, d.funct3, rd, sect, off)
  }
  def vle32(rd: Int, sect: Int, off: Int = 0): Int = {  // vle32.v → VR
    val d = InstrTable.byMnemonic("vle32")
    encI(d.opcode, d.funct3, rd, sect, off)
  }
  def vse8 (src: Int, sect: Int, off: Int = 0): Int = {  // vse8.v  ← VX
    val d = InstrTable.byMnemonic("vse8")
    encI(d.opcode, d.funct3, src, sect, off)
  }
  def vse16(src: Int, sect: Int, off: Int = 0): Int = {  // vse16.v ← VE
    val d = InstrTable.byMnemonic("vse16")
    encI(d.opcode, d.funct3, src, sect, off)
  }
  def vse32(src: Int, sect: Int, off: Int = 0): Int = {  // vse32.v ← VR
    val d = InstrTable.byMnemonic("vse32")
    encI(d.opcode, d.funct3, src, sect, off)
  }

  // ---- Generic table-driven encoding (used by the disassembler round-trip) --

  /**
   * Assemble a 32-bit word for any `InstrTable` entry using the format recorded
   * in `d`.  Parameter meaning depends on `d.fmt`:
   *   - R: `rd`, `rs1`, `rs2` are register indices; funct7 is built from
   *        `width`, `round`, `sat` and `dtype`.
   *   - I: `rd` is the destination, `rs1` is the I-type rs1 field, and `rs2`
   *        carries the 12-bit immediate.
   *   - S: `rd`, `rs1`, `rs2`, `rs3` are register indices; `round` is the mode.
   *   - NoFmt: no operands — returns 0x00.
   */
  def encode(d: InstrDef, rd: Int, rs1: Int = 0, rs2: Int = 0, rs3: Int = 0,
             width: Int = VX, round: Int = RNE, sat: Boolean = false, dtype: Int = INT): Int =
    d.fmt match {
      case Fmt.R     => encR(d.opcode, d.funct3, f7(width, round, sat, dtype), rd, rs1, rs2)
      case Fmt.I     => encI(d.opcode, d.funct3, rd, rs1, rs2)
      case Fmt.S     => encS(d.opcode, d.funct3, rd, rs1, rs2, rs3, round)
      case Fmt.NoFmt => 0x00
    }

  // ---- Convenience: convert Scala Int to Chisel UInt -----------------------
  implicit class IntToUInt(val v: Int) {
    // Convert to UInt treating the int as an unsigned 32-bit bit pattern
    def asUInt: chisel3.UInt = (v.toLong & 0xFFFFFFFFL).U(32.W)
  }
}
