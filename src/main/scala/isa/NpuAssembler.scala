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

  def vadd (rd: Int, rs1: Int, rs2: Int, width: Int = VX, sat: Boolean = false): Int =
    encR(0x10, 0, f7(width, sat=sat), rd, rs1, rs2)
  def vsub (rd: Int, rs1: Int, rs2: Int, width: Int = VX, sat: Boolean = false): Int =
    encR(0x10, 1, f7(width, sat=sat), rd, rs1, rs2)
  def vmul (rd: Int, rs1: Int, rs2: Int, width: Int = VX, sat: Boolean = false): Int =
    encR(0x10, 2, f7(width, sat=sat), rd, rs1, rs2)
  def vneg (rd: Int, rs1: Int, width: Int = VX, sat: Boolean = false): Int =
    encR(0x10, 3, f7(width, sat=sat), rd, rs1, 0)
  def vabs (rd: Int, rs1: Int, width: Int = VX, sat: Boolean = false): Int =
    encR(0x10, 4, f7(width, sat=sat), rd, rs1, 0)
  def vmax (rd: Int, rs1: Int, rs2: Int, width: Int = VX): Int =
    encR(0x10, 5, f7(width), rd, rs1, rs2)
  def vmin (rd: Int, rs1: Int, rs2: Int, width: Int = VX): Int =
    encR(0x10, 6, f7(width), rd, rs1, rs2)
  def vrsub(rd: Int, rs1: Int, rs2: Int, width: Int = VX, sat: Boolean = false): Int =
    encR(0x10, 7, f7(width, sat=sat), rd, rs1, rs2)

  // ---- VALU_LOGIC (opcode=0x11) --------------------------------------------

  def vsll(rd: Int, rs1: Int, rs2: Int, width: Int = VX): Int = encR(0x11, 0, f7(width), rd, rs1, rs2)
  def vsrl(rd: Int, rs1: Int, rs2: Int, width: Int = VX): Int = encR(0x11, 1, f7(width), rd, rs1, rs2)
  def vsra(rd: Int, rs1: Int, rs2: Int, width: Int = VX): Int = encR(0x11, 2, f7(width), rd, rs1, rs2)
  def vrol(rd: Int, rs1: Int, rs2: Int, width: Int = VX): Int = encR(0x11, 3, f7(width), rd, rs1, rs2)
  def vxor(rd: Int, rs1: Int, rs2: Int, width: Int = VX): Int = encR(0x11, 4, f7(width), rd, rs1, rs2)
  def vnot(rd: Int, rs1: Int, width: Int = VX): Int            = encR(0x11, 5, f7(width), rd, rs1, 0)
  def vor (rd: Int, rs1: Int, rs2: Int, width: Int = VX): Int  = encR(0x11, 6, f7(width), rd, rs1, rs2)
  def vand(rd: Int, rs1: Int, rs2: Int, width: Int = VX): Int  = encR(0x11, 7, f7(width), rd, rs1, rs2)

  // ---- VALU_REDUCE (opcode=0x12) -------------------------------------------

  def vsum (rd: Int, rs1: Int, width: Int = VX): Int = encR(0x12, 0, f7(width), rd, rs1, 0)
  def vrmax(rd: Int, rs1: Int, width: Int = VX): Int = encR(0x12, 1, f7(width), rd, rs1, 0)
  def vrmin(rd: Int, rs1: Int, width: Int = VX): Int = encR(0x12, 2, f7(width), rd, rs1, 0)
  def vrand(rd: Int, rs1: Int, width: Int = VX): Int = encR(0x12, 3, f7(width), rd, rs1, 0)
  def vror (rd: Int, rs1: Int, width: Int = VX): Int = encR(0x12, 4, f7(width), rd, rs1, 0)
  def vrxor(rd: Int, rs1: Int, width: Int = VX): Int = encR(0x12, 5, f7(width), rd, rs1, 0)

  // ---- VALU_LUT (opcode=0x13) — programmable two-bank LUT -------------------
  // Bank select: 0=A (default), 1=B.

  /**
   * Per-lane lookup: out[i] = lut_bank[in_a_vx[i]].
   * bank=0 → bank A (funct3=0), bank=1 → bank B (funct3=1).
   */
  def vlut(rd: Int, rs1: Int, bank: Int = 0): Int =
    encR(0x13, bank & 1, f7(VX), rd, rs1, 0)

  /**
   * Write one K×4-byte segment from VR[rs1] into the selected LUT bank.
   * segment: which K×4-entry block (0-based) within the 256-entry table.
   * bank=0 → bank A (funct3=4), bank=1 → bank B (funct3=5).
   * I-type: rd=0 (no register-file destination); imm=segment.
   */
  def vsetlut(rs1: Int, segment: Int, bank: Int = 0): Int =
    encI(0x13, 4 + (bank & 1), 0, rs1, segment)

  // ---- VALU_CVT (opcode=0x14) ----------------------------------------------
  // funct3 = dst fmt code; f7 encodes src + sat + round + bf8 variant

  def vcvt(rd: Int, rs1: Int,
           dstFmt: Int, srcFmt: Int,
           sat: Boolean = true, round: Int = RNE,
           bf8E5M2: Boolean = false): Int =
    encR(0x14, dstFmt, f7Cvt(srcFmt, sat, round, bf8E5M2), rd, rs1, 0)

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
  def vbcast(rd: Int, rs1: Int, width: Int = VX): Int =
    encR(0x15, 0, f7(width), rd, rs1, 0)

  /** Broadcast sign-extended 12-bit immediate to all K lanes of rd (I-format). */
  def vbcastImm(rd: Int, imm: Int, width: Int = VX): Int =
    encI(0x15, 1, rd, 0, imm)

  // ---- VALU_FP (opcode=0x16) — FP32 on VR ---------------------------------

  def vfadd(rd: Int, rs1: Int, rs2: Int, round: Int = RNE): Int =
    encR(0x16, 0, f7(VR, round=round, dtype=FP), rd, rs1, rs2)
  def vfsub(rd: Int, rs1: Int, rs2: Int, round: Int = RNE): Int =
    encR(0x16, 1, f7(VR, round=round, dtype=FP), rd, rs1, rs2)
  def vfmul(rd: Int, rs1: Int, rs2: Int, round: Int = RNE): Int =
    encR(0x16, 2, f7(VR, round=round, dtype=FP), rd, rs1, rs2)
  def vfneg(rd: Int, rs1: Int): Int =
    encR(0x16, 3, f7(VR, dtype=FP), rd, rs1, 0)
  def vfabs(rd: Int, rs1: Int): Int =
    encR(0x16, 4, f7(VR, dtype=FP), rd, rs1, 0)
  def vfmax(rd: Int, rs1: Int, rs2: Int): Int =
    encR(0x16, 5, f7(VR, dtype=FP), rd, rs1, rs2)
  def vfmin(rd: Int, rs1: Int, rs2: Int): Int =
    encR(0x16, 6, f7(VR, dtype=FP), rd, rs1, rs2)

  // ---- VALU_FP_FMA (opcode=0x17) — S-format -------------------------------

  def vfma (rd: Int, rs1: Int, rs2: Int, rs3: Int, round: Int = RNE): Int =
    encS(0x17, 0, rd, rs1, rs2, rs3, round)
  def vfms (rd: Int, rs1: Int, rs2: Int, rs3: Int, round: Int = RNE): Int =
    encS(0x17, 1, rd, rs1, rs2, rs3, round)
  def vnfma(rd: Int, rs1: Int, rs2: Int, rs3: Int, round: Int = RNE): Int =
    encS(0x17, 2, rd, rs1, rs2, rs3, round)
  def vnfms(rd: Int, rs1: Int, rs2: Int, rs3: Int, round: Int = RNE): Int =
    encS(0x17, 3, rd, rs1, rs2, rs3, round)

  // ---- VALU_MOV (opcode=0x18) ----------------------------------------------

  def vmov (rd: Int, rs1: Int, width: Int = VX): Int =
    encR(0x18, 0, f7(width), rd, rs1, 0)
  def vmovi(rd: Int, imm: Int, width: Int = VX): Int =
    encI(0x18, 1, rd, 0, imm)
  def vmovh(rd: Int, imm: Int): Int =
    encI(0x18, 2, rd, 0, imm)

  // ---- MMA (opcode=0x03) ---------------------------------------------------

  /** mma rd=outVR, rs1=A_VX_base, rs2=B_VX_base; keep in funct7[4] (sat bit). */
  def mma(rd: Int, rs1: Int, rs2: Int, keep: Boolean = true): Int =
    encR(0x03, 0, f7(VR, sat=keep), rd, rs1, rs2)
  def mmaLast(rd: Int, rs1: Int, rs2: Int): Int =
    encR(0x03, 1, f7(VR), rd, rs1, rs2)
  def mmaReset(rd: Int, rs1: Int, rs2: Int): Int =
    encR(0x03, 2, f7(VR), rd, rs1, rs2)

  // ---- LD / ST (opcodes 0x01 / 0x02) — unified register-file access -------
  //
  //  The register file (MultiWidthRegisterBlock) is the sole storage tier.
  //  Instructions address it via:
  //    Contiguous LD (I-type):  RF row = rs1 (page base) + sext(imm[11:0])
  //    Contiguous ST (R-type):  RF row = rs1 (base); data from VX/VE/VR[rs2]
  //                             row offset = funct7[6:0] (0..127)
  //    ld.gather  (R-type):     VX[rd][k] = RF[ VX[rs1][k] ][ k ]  (diagonal)
  //    ld.tile    (R-type):     row = rs1 + tile_h*stride_h + tile_w*stride_w
  //    st.scatter (R-type):     RF[ VX[rs1][k] ][ k ] = VX[rs2][k]

  // ---- Contiguous LD (I-type) ----
  def ldVx(rd: Int, base: Int, offset: Int = 0): Int = encI(0x01, 3, rd, base, offset)
  def ldVe(rd: Int, base: Int, offset: Int = 0): Int = encI(0x01, 4, rd, base, offset)
  def ldVr(rd: Int, base: Int, offset: Int = 0): Int = encI(0x01, 5, rd, base, offset)

  // ---- Contiguous ST (R-type): rs1=base, rs2=source reg, funct7=row offset ----
  def stVx(rs2: Int, base: Int, offset: Int = 0): Int = encR(0x02, 3, offset & 0x7F, 0, base, rs2)
  def stVe(rs2: Int, base: Int, offset: Int = 0): Int = encR(0x02, 4, offset & 0x7F, 0, base, rs2)
  def stVr(rs2: Int, base: Int, offset: Int = 0): Int = encR(0x02, 5, offset & 0x7F, 0, base, rs2)

  // ---- ld.gather (opcode=0x01, funct3=6, funct7[USE_TILE_CNT]=0) ----------
  //
  //   R-type:  rd = dest VX,  rs1 = VX holding K row indices,  rs2 = 0
  //   Semantics: VX[rd][k] = RF[ VX[rs1][k] ][ k ]  (diagonal gather)
  //     → each lane k reads lane k from the row pointed to by VX[rs1][k].
  //   autoInc: pulse tile_w_inc after the gather (funct7[AUTO_INC]=1).

  def ldGather(rd: Int, rs1: Int, autoInc: Boolean = false): Int = {
    val f7 = (if (autoInc) 1 << Funct7Gather.AUTO_INC else 0)
    // USE_TILE_CNT=0: gather mode
    encR(0x01, 6, f7, rd, rs1, 0)
  }

  // ---- ld.tile (opcode=0x01, funct3=6, funct7[USE_TILE_CNT]=1) ------------
  //
  //   R-type:  rd = dest VX,  rs1 = RF base row,  rs2 = 0
  //   Address: row = rs1 + tile_h * stride_row_h + tile_w * stride_row_w
  //   Reads one contiguous VX row from the RF.
  //   autoInc: pulse tile_w_inc after the load (for tiling loops).
  //   zeroPad / transposed: flags forwarded to future PAG.

  def ldTile(rd: Int, rs1: Int,
             zeroPad:    Boolean = false,
             transposed: Boolean = false,
             autoInc:    Boolean = false): Int = {
    val f7 = ((if (zeroPad)    1 else 0) << Funct7Gather.ZERO_PAD)    |
             ((if (transposed) 1 else 0) << Funct7Gather.TRANSPOSED)  |
             (1                          << Funct7Gather.USE_TILE_CNT) |
             ((if (autoInc)    1 else 0) << Funct7Gather.AUTO_INC)
    encR(0x01, 6, f7, rd, rs1, 0)
  }

  // ---- st.scatter (opcode=0x02, funct3=6) ----------------------------------
  //
  //   R-type:  rs1 = VX holding K dest row indices,  rs2 = source VX,  rd=0
  //   Semantics: RF[ VX[rs1][k] ][ k ] = VX[rs2][k]  (diagonal scatter)
  //     → lane k of rs2 is written to lane k of the row at VX[rs1][k].

  def stScatter(rs1: Int, rs2: Int): Int = encR(0x02, 6, 0, 0, rs1, rs2)

  // ---- tile.cfg (opcode=0x01, funct3=7) — write config into .sreg ---------
  //
  //   I-type: rd=0, rs1 = VR register holding the 32-bit config word in lane 0
  //   imm[2:0] = wr_sel (TileCfgSel)

  /** Configure H_in and W_in. */
  def tileCfgHW(rs1: Int): Int = encI(0x01, 7, 0, rs1, 0)

  /** Configure C_in and C_out. */
  def tileCfgCh(rs1: Int): Int = encI(0x01, 7, 0, rs1, 1)

  /** Configure kernel shape: Kh, Kw, stride, dilation, pad_h, pad_w, mode. */
  def tileCfgKern(rs1: Int): Int = encI(0x01, 7, 0, rs1, 2)

  /** Set/reset tile position (tile_h, tile_w). */
  def tileCfgPos(rs1: Int): Int = encI(0x01, 7, 0, rs1, 3)

  /** Configure stride_row_h (RF rows per tile_h increment). */
  def tileCfgStrideH(rs1: Int): Int = encI(0x01, 7, 0, rs1, 4)

  /** Configure stride_row_w (RF rows per tile_w increment). */
  def tileCfgStrideW(rs1: Int): Int = encI(0x01, 7, 0, rs1, 5)

  // ---- Convenience: convert Scala Int to Chisel UInt -----------------------
  implicit class IntToUInt(val v: Int) {
    // Convert to UInt treating the int as an unsigned 32-bit bit pattern
    def asUInt: chisel3.UInt = (v.toLong & 0xFFFFFFFFL).U(32.W)
  }
}
