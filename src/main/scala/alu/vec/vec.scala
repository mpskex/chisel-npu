// See README.md for license details.
// -----------------------------------------------------------------------------
//  vec.scala — Vector ALU (VALU) and Q-format LUT tables
//
//  Parameters:
//    K    — SIMD lane count per register (8 for tests, 64 at top)
//    N    — base lane width in bits / N(bits) (default 8)
//
//  Register-class widths used by VALU datapaths:
//    VX: K lanes of N   bits (SInt(N.W))
//    VE: K lanes of 2N  bits (SInt((2*N).W))
//    VR: K lanes of 4N  bits (SInt((4*N).W) for INT; UInt for FP32)
//
//  I/O:
//    in_a_vx / in_a_ve / in_a_vr : input operand A (three widths)
//    in_b_vx / in_b_ve / in_b_vr : input operand B (three widths)
//    in_c_vr                      : third operand C (FMA, VR only)
//    ctrl                         : NCoreVALUBundle (decoded; includes VecOp, VecWidth, etc.)
//    out_vx / out_ve / out_vr     : registered outputs (one clock latency)
//
//  Only ONE output port carries valid data per cycle (selected by ctrl.width).
//  All three output ports are registered; unused ports output 0.
//
//  See fp.scala for FP32/BF16/BF8 helpers.
//  See instrFormat.scala / instSetArch.scala for ISA encoding.
// -----------------------------------------------------------------------------

package alu.vec

import chisel3._
import chisel3.util._
import isa.micro_op._

// ---------------------------------------------------------------------------
// Q-format reference tables (shared with test specs for bit-exact checks)
// ---------------------------------------------------------------------------
object Qfmt {
  val FRAC_BITS = 6
  val IN_SCALE  = 1 << FRAC_BITS  // 64
  val EXP_SCALE = 256              // UQ0.8 for vexp output
  val OUT_SCALE = IN_SCALE

  def sq16ToDouble(raw: Int): Double = {
    val signed = if (raw >= 128) raw - 256 else raw
    signed.toDouble / IN_SCALE
  }

  def doubleToSq16(v: Double): Int = {
    val scaled = math.round(v * IN_SCALE).toInt
    math.max(-128, math.min(127, scaled))
  }

  def doubleToUq08(v: Double): Int = {
    val scaled = math.round(v * EXP_SCALE).toInt
    math.max(0, math.min(255, scaled))
  }

  // vexp: SQ1.6 → UQ0.8 stored as SInt(8.W) two's-complement
  val lutExp: Seq[Int] = Seq.tabulate(256) { raw =>
    val x = sq16ToDouble(raw)
    val e = math.exp(x)
    val u = doubleToUq08(math.min(e, 255.0 / EXP_SCALE))
    if (u > 127) u - 256 else u
  }

  val lutRecip: Seq[Int] = Seq.tabulate(256) { raw =>
    val x = sq16ToDouble(raw)
    if (x == 0.0) 127 else doubleToSq16(1.0 / x)
  }

  val lutTanh: Seq[Int] = Seq.tabulate(256) { raw =>
    doubleToSq16(math.tanh(sq16ToDouble(raw)))
  }

  val lutErf: Seq[Int] = Seq.tabulate(256) { raw =>
    doubleToSq16(erfApprox(sq16ToDouble(raw)))
  }

  private def erfApprox(x: Double): Double = {
    val sign = if (x < 0) -1.0 else 1.0
    val t = 1.0 / (1.0 + 0.3275911 * math.abs(x))
    val poly = t * (0.254829592 +
      t * (-0.284496736 +
      t * (1.421413741 +
      t * (-1.453152027 +
      t * 1.061405429))))
    sign * (1.0 - poly * math.exp(-x * x))
  }
}

// ---------------------------------------------------------------------------
// VALU — K-lane vector ALU, multi-width
// ---------------------------------------------------------------------------
class VALU(val K: Int = 8, val N: Int = 8) extends Module {

  val N2 = 2 * N
  val N4 = 4 * N

  val io = IO(new Bundle {
    // Three-width input ports; backend muxes the correct one for each op
    val in_a_vx = Input(Vec(K, UInt(N.W)))
    val in_a_ve = Input(Vec(K, UInt(N2.W)))
    val in_a_vr = Input(Vec(K, UInt(N4.W)))
    val in_b_vx = Input(Vec(K, UInt(N.W)))
    val in_b_ve = Input(Vec(K, UInt(N2.W)))
    val in_b_vr = Input(Vec(K, UInt(N4.W)))
    val in_c_vr = Input(Vec(K, UInt(N4.W)))  // FMA third operand

    val ctrl = Input(new NCoreVALUBundle)

    // Three-width registered outputs (1-cycle latency)
    val out_vx = Output(Vec(K, UInt(N.W)))
    val out_ve = Output(Vec(K, UInt(N2.W)))
    val out_vr = Output(Vec(K, UInt(N4.W)))
  })

  // ---- LUT ROMs (256 entries, 8-bit signed stored as SInt) ----
  val expLut   = VecInit(Qfmt.lutExp.map(_.S(N.W).asUInt))
  val recipLut = VecInit(Qfmt.lutRecip.map(_.S(N.W).asUInt))
  val tanhLut  = VecInit(Qfmt.lutTanh.map(_.S(N.W).asUInt))
  val erfLut   = VecInit(Qfmt.lutErf.map(_.S(N.W).asUInt))

  // ---- Saturation helpers ----
  def satN(v: SInt, w: Int): SInt = {
    val maxV =  ((1 << (w-1)) - 1).S(w.W)
    val minV = (-(1 << (w-1))).S(w.W)
    Mux(v > maxV, maxV, Mux(v < minV, minV, v))
  }

  def satOrTrunc(v: SInt, doSat: Bool, outW: Int): SInt =
    Mux(doSat, satN(v, outW), v(outW-1, 0).asSInt)

  // ---- Wire up raw output buses ----
  val rawVX = Wire(Vec(K, UInt(N.W)))
  val rawVE = Wire(Vec(K, UInt(N2.W)))
  val rawVR = Wire(Vec(K, UInt(N4.W)))
  for (lane <- 0 until K) {
    rawVX(lane) := 0.U
    rawVE(lane) := 0.U
    rawVR(lane) := 0.U
  }

  val op  = io.ctrl.op
  val sat = io.ctrl.saturate
  val wid = io.ctrl.regCls

  // ---- Horizontal reductions (tree over all K lanes) ----
  // Operate on VX (N-bit); result widened to 4N for out_vr broadcast
  // VX lanes are UInt(N.W); sign-extend for signed arithmetic (S8C4 dtype).
  val aVXsigned = VecInit(io.in_a_vx.map(_.asSInt))
  val sumVX : SInt = aVXsigned.map(_.asTypeOf(SInt(N4.W))).reduce(_ + _)
  val rmaxVX: SInt = aVXsigned.reduce { (a, b) => Mux(a > b, a, b) }.asTypeOf(SInt(N4.W))

  val aVEsigned = VecInit(io.in_a_ve.map(_.asSInt))
  val sumVE : SInt = aVEsigned.reduce { (a, b) => (a.asTypeOf(SInt(N4.W)) + b.asTypeOf(SInt(N4.W)))(N4-1, 0).asSInt }
  val rmaxVE: SInt = aVEsigned.reduce { (a, b) => Mux(a > b, a, b) }.asTypeOf(SInt(N4.W))

  val aVRsigned = VecInit(io.in_a_vr.map(_.asSInt))
  val sumVR : SInt = aVRsigned.reduce { (a, b) => (a.asTypeOf(SInt(N4.W)) + b.asTypeOf(SInt(N4.W)))(N4-1, 0).asSInt }
  val rmaxVR: SInt = aVRsigned.reduce { (a, b) => Mux(a > b, a, b) }

  // ---- Per-lane compute ----
  for (lane <- 0 until K) {
    val aVX = io.in_a_vx(lane).asSInt
    val bVX = io.in_b_vx(lane).asSInt
    val aVE = io.in_a_ve(lane).asSInt
    val bVE = io.in_b_ve(lane).asSInt
    val aVR = io.in_a_vr(lane)  // UInt for FP32; cast as needed
    val bVR = io.in_b_vr(lane)
    val cVR = io.in_c_vr(lane)

    // ---- VX arithmetic (N-bit) ----
    val aVXw = aVX.asTypeOf(SInt(N4.W))
    val bVXw = bVX.asTypeOf(SInt(N4.W))

    val vxAdd  = satOrTrunc(aVXw + bVXw, sat, N)
    val vxSub  = satOrTrunc(aVXw - bVXw, sat, N)
    val vxMul  = satOrTrunc(aVXw * bVXw, sat, N)
    val vxRsub = satOrTrunc(bVXw - aVXw, sat, N)
    val vxNeg  = satOrTrunc(-aVXw, sat, N)
    val vxAbs  = satOrTrunc(Mux(aVXw < 0.S(N4.W), -aVXw, aVXw), sat, N)
    val vxMax  = Mux(aVX > bVX, aVX, bVX)
    val vxMin  = Mux(aVX < bVX, aVX, bVX)

    // Logic / shift (treat as UInt)
    val aU = io.in_a_vx(lane); val bU = io.in_b_vx(lane)
    val shAmt = bU(log2Ceil(N) - 1, 0)
    val vxAnd = aU & bU
    val vxOr  = aU | bU
    val vxXor = aU ^ bU
    val vxNot = ~aU
    val vxSll = (aU << shAmt)(N-1, 0)
    val vxSrl = aU >> shAmt
    val vxSra = (aVX >> shAmt).asUInt(N-1, 0)
    val vxRol = Cat(aU(N-2, 0), aU(N-1))   // rotate by 1 for simplicity; full rot needs shAmt mux
    // Full rotate left by shAmt:
    val aU32  = aU.pad(N*2)
    val vxRolFull = ((aU32 << shAmt) | (aU32 >> (N.U - shAmt)))(N-1, 0)

    // LUT ops (always VX)
    val lutIdx = aU
    val vxExp   = expLut(lutIdx)
    val vxRecip = recipLut(lutIdx)
    val vxTanh  = tanhLut(lutIdx)
    val vxErf   = erfLut(lutIdx)

    // ---- VE arithmetic (2N-bit) ----
    val aVEw = aVE.asTypeOf(SInt(N4.W))
    val bVEw = bVE.asTypeOf(SInt(N4.W))

    val veAdd  = satOrTrunc(aVEw + bVEw, sat, N2)
    val veSub  = satOrTrunc(aVEw - bVEw, sat, N2)
    val veMul  = satOrTrunc(aVEw * bVEw, sat, N2)
    val veNeg  = satOrTrunc(-aVEw, sat, N2)
    val veAbs  = satOrTrunc(Mux(aVEw < 0.S(N4.W), -aVEw, aVEw), sat, N2)
    val veMax  = Mux(aVE.asSInt > bVE.asSInt, aVE.asSInt, bVE.asSInt).asUInt(N2-1, 0)
    val veMin  = Mux(aVE.asSInt < bVE.asSInt, aVE.asSInt, bVE.asSInt).asUInt(N2-1, 0)
    val veRsub = satOrTrunc(bVEw - aVEw, sat, N2)
    val veShAmt = io.in_b_ve(lane)(log2Ceil(N2)-1, 0)
    val veAnd  = io.in_a_ve(lane) & io.in_b_ve(lane)
    val veOr   = io.in_a_ve(lane) | io.in_b_ve(lane)
    val veXor  = io.in_a_ve(lane) ^ io.in_b_ve(lane)
    val veNot  = ~io.in_a_ve(lane)
    val veSll  = (io.in_a_ve(lane) << veShAmt)(N2-1, 0)
    val veSrl  = io.in_a_ve(lane) >> veShAmt
    val veSra  = (aVE >> veShAmt).asUInt(N2-1, 0)

    // ---- VR integer arithmetic (4N-bit signed) ----
    val aVRs = aVR.asSInt
    val bVRs = bVR.asSInt
    val vrAdd  = satOrTrunc(aVRs + bVRs, sat, N4)
    val vrSub  = satOrTrunc(aVRs - bVRs, sat, N4)
    val vrMul  = satOrTrunc(aVRs * bVRs, sat, N4)
    val vrNeg  = (-aVRs)(N4-1, 0)
    val vrAbs  = Mux(aVRs < 0.S(N4.W), -aVRs, aVRs)(N4-1, 0)
    val vrMax  = Mux(aVRs > bVRs, aVR, bVR)
    val vrMin  = Mux(aVRs < bVRs, aVR, bVR)
    val vrRsub = satOrTrunc(bVRs - aVRs, sat, N4)
    val vrShAmt= io.in_b_vr(lane)(log2Ceil(N4)-1, 0)
    val vrSra  = (aVRs >> vrShAmt).asUInt(N4-1, 0)

    // ---- FP32 arithmetic (VR, treated as UInt(32.W)) ----
    // Only active when N4 == 32 (N=8); for other N, FP32 is not connected
    val fpA = aVR(31, 0)
    val fpB = bVR(31, 0)
    val fpC = cVR(31, 0)
    val fpAdd  = IEEE754.fadd32(fpA, fpB)
    val fpSub  = IEEE754.fadd32(fpA, IEEE754.fneg32(fpB))
    val fpMul  = IEEE754.fmul32(fpA, fpB)
    val fpNeg  = IEEE754.fneg32(fpA)
    val fpAbs  = IEEE754.fabs32(fpA)
    val fpMax  = IEEE754.fmax32(fpA, fpB)
    val fpMin  = IEEE754.fmin32(fpA, fpB)
    val fpFma  = IEEE754.fma32(fpA, fpB, fpC)
    val fpFms  = IEEE754.fms32(fpA, fpB, fpC)
    val fpNFma = IEEE754.nfma32(fpA, fpB, fpC)
    val fpNFms = IEEE754.nfms32(fpA, fpB, fpC)

    // ---- Conversion ops ----
    val cvtS8S32  = aVR(N-1, 0).asSInt.asTypeOf(SInt(N4.W)).asUInt     // sign-extend
    val cvtS32S8  = satOrTrunc(aVRs, sat, N).asUInt.pad(N4)
    val cvtS32F32 = IEEE754.s32ToF32(aVRs).pad(N4)
    val cvtF32S32 = IEEE754.f32ToS32(fpA, io.ctrl.round).asUInt(N4-1, 0)
    val cvtF32S8  = IEEE754.f32ToS8 (fpA, io.ctrl.round).asUInt.pad(N4)
    val cvtS8F32  = IEEE754.s8ToF32(aVX).pad(N4)
    val cvtF32Bf16 = IEEE754.f32ToBf16(fpA).pad(N4)
    val cvtBf16F32 = IEEE754.bf16ToF32(aVR(N2-1, 0)).pad(N4)
    val isBf8E5M2  = io.ctrl.dtype === VecDType.BF8E5M2
    val cvtF32Bf8  = IEEE754.f32ToBf8(fpA, isBf8E5M2).pad(N4)
    val cvtBf8F32  = IEEE754.bf8ToF32(aVR(7, 0), isBf8E5M2).pad(N4)
    val cvtS16S32  = aVR(N2-1, 0).asSInt.asTypeOf(SInt(N4.W)).asUInt
    val cvtS32S16  = IEEE754.s32ToS16(aVRs).asUInt.pad(N4)

    // ---- Broadcast ops ----
    // vbcast_reg: lane 0 of in_a → all lanes (applied outside the per-lane loop; use lane0 value)
    val a0VX = io.in_a_vx(0)
    val a0VE = io.in_a_ve(0)
    val a0VR = io.in_a_vr(0)
    val immV = io.ctrl.imm

    // ---- VX result mux ----
    val selVX = MuxLookup(op.asUInt, 0.U(N.W))(Seq(
      // ARITH
      VecOp.vadd.asUInt  -> vxAdd.asUInt(N-1, 0),
      VecOp.vsub.asUInt  -> vxSub.asUInt(N-1, 0),
      VecOp.vmul.asUInt  -> vxMul.asUInt(N-1, 0),
      VecOp.vneg.asUInt  -> vxNeg.asUInt(N-1, 0),
      VecOp.vabs.asUInt  -> vxAbs.asUInt(N-1, 0),
      VecOp.vmax.asUInt  -> vxMax.asUInt(N-1, 0),
      VecOp.vmin.asUInt  -> vxMin.asUInt(N-1, 0),
      VecOp.vrsub.asUInt -> vxRsub.asUInt(N-1, 0),
      // LOGIC
      VecOp.vsll.asUInt  -> vxSll,
      VecOp.vsrl.asUInt  -> vxSrl(N-1, 0),
      VecOp.vsra.asUInt  -> vxSra,
      VecOp.vrol.asUInt  -> vxRolFull,
      VecOp.vxor.asUInt  -> vxXor,
      VecOp.vnot.asUInt  -> vxNot,
      VecOp.vor.asUInt   -> vxOr,
      VecOp.vand.asUInt  -> vxAnd,
      // REDUCE (broadcast reduced scalar; same value for all lanes)
      VecOp.vsum.asUInt  -> satOrTrunc(sumVX, sat, N).asUInt(N-1, 0),
      VecOp.vrmax.asUInt -> satOrTrunc(rmaxVX, false.B, N).asUInt(N-1, 0),
      VecOp.vrmin.asUInt -> 0.U,  // TODO: add vrmin reduction
      VecOp.vrand.asUInt -> 0.U,
      VecOp.vror.asUInt  -> 0.U,
      VecOp.vrxor.asUInt -> 0.U,
      // LUT
      VecOp.vexp.asUInt   -> vxExp,
      VecOp.vrecip.asUInt -> vxRecip,
      VecOp.vtanh.asUInt  -> vxTanh,
      VecOp.verf.asUInt   -> vxErf,
      // CVT → VX output (s32_s8, f32_s8, s8_f32 narrow side)
      VecOp.vcvt_s8_s32.asUInt   -> cvtS8S32(N-1, 0),   // sign-extend s8 to 8-bit slice
      VecOp.vcvt_f32_s8.asUInt   -> cvtF32S8(N-1, 0),   // FP32 → INT8 (narrow output)
      // BCAST → VX
      VecOp.vbcast_reg.asUInt -> a0VX,
      VecOp.vbcast_imm.asUInt -> immV(N-1, 0).asUInt,
      // MOV
      VecOp.vmov.asUInt  -> io.in_a_vx(lane),
      VecOp.vmovi.asUInt -> Mux(lane.U === 0.U, immV(N-1, 0).asUInt, io.out_vx(lane)),
    ))

    // ---- VE result mux ----
    val selVE = MuxLookup(op.asUInt, 0.U(N2.W))(Seq(
      VecOp.vadd.asUInt  -> veAdd.asUInt(N2-1, 0),
      VecOp.vsub.asUInt  -> veSub.asUInt(N2-1, 0),
      VecOp.vmul.asUInt  -> veMul.asUInt(N2-1, 0),
      VecOp.vneg.asUInt  -> veNeg.asUInt(N2-1, 0),
      VecOp.vabs.asUInt  -> veAbs.asUInt(N2-1, 0),
      VecOp.vmax.asUInt  -> veMax,
      VecOp.vmin.asUInt  -> veMin,
      VecOp.vrsub.asUInt -> veRsub.asUInt(N2-1, 0),
      VecOp.vsll.asUInt  -> veSll,
      VecOp.vsrl.asUInt  -> veSrl(N2-1, 0),
      VecOp.vsra.asUInt  -> veSra,
      VecOp.vxor.asUInt  -> veXor,
      VecOp.vnot.asUInt  -> veNot,
      VecOp.vor.asUInt   -> veOr,
      VecOp.vand.asUInt  -> veAnd,
      VecOp.vsum.asUInt  -> satOrTrunc(sumVE, sat, N2).asUInt(N2-1, 0),
      VecOp.vrmax.asUInt -> rmaxVE(N2-1, 0).asUInt,
      VecOp.vcvt_bf16_f32.asUInt -> cvtBf16F32(N2-1, 0),
      VecOp.vcvt_f32_bf16.asUInt -> cvtF32Bf16(N2-1, 0),
      VecOp.vcvt_s16_s32.asUInt  -> cvtS16S32(N2-1, 0),
      VecOp.vcvt_s32_s16.asUInt  -> cvtS32S16(N2-1, 0),
      VecOp.vbcast_reg.asUInt -> a0VE,
      VecOp.vbcast_imm.asUInt -> immV.asTypeOf(SInt(N2.W)).asUInt,
      VecOp.vmov.asUInt  -> io.in_a_ve(lane),
    ))

    // ---- VR result mux ----
    val selVR = MuxLookup(op.asUInt, 0.U(N4.W))(Seq(
      // INT32 arith
      VecOp.vadd.asUInt  -> vrAdd.asUInt(N4-1, 0),
      VecOp.vsub.asUInt  -> vrSub.asUInt(N4-1, 0),
      VecOp.vmul.asUInt  -> vrMul.asUInt(N4-1, 0),
      VecOp.vneg.asUInt  -> vrNeg,
      VecOp.vabs.asUInt  -> vrAbs,
      VecOp.vmax.asUInt  -> vrMax,
      VecOp.vmin.asUInt  -> vrMin,
      VecOp.vrsub.asUInt -> vrRsub.asUInt(N4-1, 0),
      VecOp.vsra.asUInt  -> vrSra,
      // reductions: pick the right accumulated value based on active width
      VecOp.vsum.asUInt  -> Mux(wid === 1.U, sumVE(N4-1, 0).asUInt,
                              Mux(wid === 2.U, sumVR(N4-1, 0).asUInt,
                                sumVX.asTypeOf(SInt(N4.W)).asUInt)),
      VecOp.vrmax.asUInt -> Mux(wid === 1.U, rmaxVE(N4-1, 0).asUInt,
                              Mux(wid === 2.U, rmaxVR(N4-1, 0).asUInt,
                                rmaxVX.asUInt)),
      // FP32 arith
      VecOp.vfadd.asUInt -> fpAdd.pad(N4),
      VecOp.vfsub.asUInt -> fpSub.pad(N4),
      VecOp.vfmul.asUInt -> fpMul.pad(N4),
      VecOp.vfneg.asUInt -> fpNeg.pad(N4),
      VecOp.vfabs.asUInt -> fpAbs.pad(N4),
      VecOp.vfmax.asUInt -> fpMax.pad(N4),
      VecOp.vfmin.asUInt -> fpMin.pad(N4),
      // FMA
      VecOp.vfma.asUInt  -> fpFma.pad(N4),
      VecOp.vfms.asUInt  -> fpFms.pad(N4),
      VecOp.vnfma.asUInt -> fpNFma.pad(N4),
      VecOp.vnfms.asUInt -> fpNFms.pad(N4),
      // Cvt → VR (widening or same-width)
      VecOp.vcvt_s8_s32.asUInt   -> cvtS8S32,
      VecOp.vcvt_s32_s8.asUInt   -> cvtS32S8,
      VecOp.vcvt_s32_f32.asUInt  -> cvtS32F32,
      VecOp.vcvt_f32_s32.asUInt  -> cvtF32S32,
      VecOp.vcvt_f32_s8.asUInt   -> cvtF32S8,
      VecOp.vcvt_s8_f32.asUInt   -> cvtS8F32,
      VecOp.vcvt_f32_bf16.asUInt -> cvtF32Bf16,
      VecOp.vcvt_bf16_f32.asUInt -> cvtBf16F32,
      VecOp.vcvt_f32_bf8.asUInt  -> cvtF32Bf8,
      VecOp.vcvt_bf8_f32.asUInt  -> cvtBf8F32,
      VecOp.vcvt_s16_s32.asUInt  -> cvtS16S32,
      VecOp.vcvt_s32_s16.asUInt  -> cvtS32S16,
      // Bcast
      VecOp.vbcast_reg.asUInt -> a0VR,
      VecOp.vbcast_imm.asUInt -> immV.asTypeOf(SInt(N4.W)).asUInt,
      VecOp.vmov.asUInt  -> io.in_a_vr(lane),
    ))

    // ---- Width-gated output assignment ----
    // width: 0=VX, 1=VE, 2=VR (raw UInt matching VecWidth enum values)
    // Narrow CVT ops (s8 output: vcvt_f32_s8) always write to VX regardless of regCls
    rawVX(lane) := Mux(wid === 0.U ||
      op === VecOp.vcvt_s8_s32 ||   // s8 sign-extend slice
      op === VecOp.vcvt_f32_s8,     // FP32 → INT8
      selVX, 0.U)
    rawVE(lane) := Mux(wid === 1.U, selVE, 0.U)
    rawVR(lane) := Mux(
      wid === 2.U ||
      op === VecOp.vsum  || op === VecOp.vrmax || op === VecOp.vrmin ||
      op === VecOp.vcvt_s32_f32 ||
      op === VecOp.vcvt_f32_s32 ||
      op === VecOp.vcvt_s8_f32  ||  // INT8→FP32: wide output
      op === VecOp.vcvt_f32_bf8  ||
      op === VecOp.vcvt_bf8_f32  ||
      op === VecOp.vcvt_f32_bf16 ||
      op === VecOp.vcvt_bf16_f32 ||
      op === VecOp.vfadd || op === VecOp.vfsub || op === VecOp.vfmul ||
      op === VecOp.vfneg || op === VecOp.vfabs || op === VecOp.vfmax ||
      op === VecOp.vfmin ||
      op === VecOp.vfma  || op === VecOp.vfms  ||
      op === VecOp.vnfma || op === VecOp.vnfms,
      selVR,
      0.U
    )
  }

  // ---- Register outputs (1-cycle latency) ----
  io.out_vx := RegNext(rawVX)
  io.out_ve := RegNext(rawVE)
  io.out_vr := RegNext(rawVR)
}
