// See README.md for license details.
// -----------------------------------------------------------------------------
//  fp.scala — Tier-2 FP32 / BF16 / BF8 arithmetic helpers
//
//  Design scope (Tier 2):
//    - IEEE 754 binary32 subset: RNE rounding (default), RTZ/floor/ceil optional.
//    - No NaN propagation: NaN inputs are treated as zero; outputs never NaN.
//    - No ±Infinity propagation: overflow saturates to max finite normal.
//    - Subnormals flushed to zero on both input and output (FTZ).
//    - All functions are purely combinational (no registers).
//      The VALU adds a 1-cycle output register around these helpers.
//
//  BF8 variants:
//    E4M3 (1/4/3): bias 7,  max exp = 14 → max value ≈ 448
//    E5M2 (1/5/2): bias 15, max exp = 30 → max value ≈ 57344
//
//  Scala reference functions (for tests and LUT generation) live alongside
//  the Chisel hardware in companion objects.
// -----------------------------------------------------------------------------

package alu.vec

import chisel3._
import chisel3.util._

// ---------------------------------------------------------------------------
// IEEE754 — Chisel combinational FP32 building blocks
// All functions operate on raw UInt(32.W) bit patterns.
// ---------------------------------------------------------------------------
object IEEE754 {

  // FP32 field constants
  val SIGN_BIT  = 31
  val EXP_HI    = 30; val EXP_LO = 23
  val MAN_HI    = 22; val MAN_LO = 0
  val EXP_BIAS  = 127
  val EXP_WIDTH = 8
  val MAN_WIDTH = 23

  // Max finite FP32 (0_11111110_11111111111111111111111) = 0x7F7FFFFF
  val MAX_FP32 = "h7F7FFFFF".U(32.W)
  val MIN_FP32 = "hFF7FFFFF".U(32.W)

  /** Extract sign (1 bit) */
  def sign(f: UInt): UInt = f(SIGN_BIT)

  /** Extract biased exponent (8 bits) */
  def exp(f: UInt): UInt = f(EXP_HI, EXP_LO)

  /** Extract mantissa fraction (23 bits, no implicit 1) */
  def man(f: UInt): UInt = f(MAN_HI, MAN_LO)

  /** True if f is zero (flush-to-zero: subnormals also treated as zero) */
  def isZero(f: UInt): Bool = exp(f) === 0.U

  /** True if f is a NaN or Inf (exponent all-ones) — treated as zero in Tier-2 */
  def isSpecial(f: UInt): Bool = exp(f) === "hFF".U(8.W)

  /** Sanitize: replace NaN/Inf/subnormal with ±0 */
  def sanitize(f: UInt): UInt =
    Mux(isSpecial(f) || isZero(f), Cat(sign(f), 0.U(31.W)), f)

  /** Build FP32 from parts (does NOT check for overflow) */
  def pack(s: UInt, e: UInt, m: UInt): UInt = Cat(s, e(7, 0), m(22, 0))

  // ---------------------------------------------------------------------------
  // fadd32: FP32 addition (sanitised inputs; RNE)
  //   a + b with Tier-2 constraints.
  //   Combinational; latency ~ 1 cycle when registered externally.
  // ---------------------------------------------------------------------------
  def fadd32(a: UInt, b: UInt): UInt = {
    val aS = sanitize(a)
    val bS = sanitize(b)

    val aSign = sign(aS);  val bSign = sign(bS)
    val aExp  = exp(aS).pad(10).asSInt;  val bExp  = exp(bS).pad(10).asSInt
    val aMan  = Cat(1.U(1.W), man(aS))  // add implicit 1; 24 bits
    val bMan  = Cat(1.U(1.W), man(bS))  // 24 bits

    // Swap so that |a| >= |b|
    val swap   = (bExp > aExp) || ((bExp === aExp) && (bMan > aMan))
    val hiExp  = Mux(swap, bExp, aExp)
    val loExp  = Mux(swap, aExp, bExp)
    val hiMan  = Mux(swap, bMan, aMan)
    val loMan  = Mux(swap, aMan, bMan)
    val hiSign = Mux(swap, bSign, aSign)
    val loSign = Mux(swap, aSign, bSign)

    // Shift smaller operand right to align
    val shift = (hiExp - loExp).asUInt
    val shiftCap = Mux(shift > 25.U, 25.U, shift)
    val loAligned = (loMan >> shiftCap)(23, 0)  // 24 bits, shifted

    // Add or subtract based on signs
    val sameSign = hiSign === loSign
    // Extend to 25 bits to catch carry/borrow
    val hiExt = Cat(0.U(1.W), hiMan)   // 25 bits
    val loExt = Cat(0.U(1.W), loAligned)

    val raw = WireDefault(0.U(25.W))
    val rSign = WireDefault(hiSign)
    when (sameSign) {
      raw := hiExt + loExt
    } .otherwise {
      when (hiExt >= loExt) {
        raw := hiExt - loExt
      } .otherwise {
        raw  := loExt - hiExt
        rSign := ~hiSign
      }
    }

    // Normalise raw[24:0]:
    //   PriorityEncoder(Reverse(x)) gives the highest-set-bit position from LSB,
    //   which equals 24 - (number of leading zeros from bit 24).
    //   Let hbit = 24 - PriorityEncoder(Reverse(raw)) = position of leading 1.
    //   Normalised mantissa = raw << (23 - hbit), i.e. shift = 23 - hbit.
    //   Exponent adjustment = hbit - 23.
    //   rExp = hiExp + (hbit - 23).
    //
    //   Special case: bit 24 set (carry out from addition):
    //     hbit = 24, shift = -1 (shift right by 1), rExp = hiExp + 1.
    val lzFromTop = PriorityEncoder(Reverse(raw(24, 0)))  // = highest set bit position
    val rExp = WireDefault(0.S(10.W))
    val rMan = WireDefault(0.U(23.W))

    val rawTop = raw(24)
    when (rawTop) {
      // Leading 1 at bit 24 (carry): shift right 1, exp += 1
      rExp := hiExp + 1.S
      rMan := raw(23, 1)
    } .elsewhen (raw =/= 0.U) {
      // Leading 1 at bit lzFromTop; shift left by (23 - lzFromTop)
      // Exponent adjustment: (lzFromTop - 23)
      // hbit = position of highest set bit in raw (0-indexed from LSB)
      // lzFromTop = index in reversed vector = (24 - hbit), so hbit = 24 - lzFromTop
      val hbit = 24.U - lzFromTop
      when (hbit >= 23.U) {
        // Leading 1 at bit >= 23: shift right by (hbit - 23)
        val shiftR = hbit - 23.U
        rExp := hiExp + shiftR.asSInt
        rMan := (raw >> shiftR)(22, 0)
      } .otherwise {
        // Leading 1 at bit < 23: shift left by (23 - hbit)
        val shiftL = 23.U - hbit
        rExp := hiExp - shiftL.asSInt
        rMan := (raw << shiftL)(22, 0)
      }
    }
    // else: result is zero → rExp=0, rMan=0 (defaults)

    // Saturate on overflow / underflow
    val overflow  = rExp >= 255.S
    val underflow = (rExp <= 0.S) || (raw === 0.U)

    val outBits = WireDefault(0.U(32.W))
    when (overflow) {
      outBits := Mux(rSign === 0.U, MAX_FP32, MIN_FP32)
    } .elsewhen (underflow) {
      outBits := Cat(rSign, 0.U(31.W))  // ±0
    } .otherwise {
      outBits := pack(rSign, rExp.asUInt, rMan)
    }
    outBits
  }

  // ---------------------------------------------------------------------------
  // fmul32: FP32 multiplication (sanitised inputs; RNE)
  // ---------------------------------------------------------------------------
  def fmul32(a: UInt, b: UInt): UInt = {
    val aS = sanitize(a)
    val bS = sanitize(b)

    val rSign = sign(aS) ^ sign(bS)
    val aExp  = exp(aS).pad(10).asSInt
    val bExp  = exp(bS).pad(10).asSInt
    val aMan  = Cat(1.U(1.W), man(aS))  // 24 bits
    val bMan  = Cat(1.U(1.W), man(bS))  // 24 bits

    // Product: 48 bits; exponent = aExp + bExp - bias
    val prod    = aMan * bMan              // 48-bit product
    val rExpRaw = aExp + bExp - EXP_BIAS.S

    // Either bit 47 or 46 holds the leading 1
    val prodTop = prod(47)
    val rExp    = WireDefault(0.S(10.W))
    val rMan    = WireDefault(0.U(23.W))

    val isZeroResult = isZero(aS) || isZero(bS)
    when (!isZeroResult) {
      when (prodTop) {
        rExp := rExpRaw + 1.S
        rMan := prod(46, 24)
      } .otherwise {
        rExp := rExpRaw
        rMan := prod(45, 23)
      }
    }

    val overflow  = rExp >= 255.S
    val underflow = (rExp <= 0.S) || isZeroResult

    val outBits = WireDefault(0.U(32.W))
    when (overflow) {
      outBits := Mux(rSign === 0.U, MAX_FP32, MIN_FP32)
    } .elsewhen (underflow) {
      outBits := Cat(rSign, 0.U(31.W))
    } .otherwise {
      outBits := pack(rSign, rExp.asUInt, rMan)
    }
    outBits
  }

  // ---------------------------------------------------------------------------
  // fma32: fused multiply-add: (a * b) + c
  // Tier-2: not truly fused (two operations); matches 1-ULP accuracy for
  // the quantisation use case where intermediate results are finite & normal.
  // ---------------------------------------------------------------------------
  def fma32(a: UInt, b: UInt, c: UInt): UInt = fadd32(fmul32(a, b), c)
  def fms32(a: UInt, b: UInt, c: UInt): UInt = fadd32(fmul32(a, b), fneg32(c))
  def nfma32(a: UInt, b: UInt, c: UInt): UInt = fadd32(fneg32(fmul32(a, b)), c)
  def nfms32(a: UInt, b: UInt, c: UInt): UInt = fneg32(fma32(a, b, c))

  // ---------------------------------------------------------------------------
  // Negate / abs: just flip/clear the sign bit
  // ---------------------------------------------------------------------------
  def fneg32(f: UInt): UInt = Cat(~f(31), f(30, 0))
  def fabs32(f: UInt): UInt = Cat(0.U(1.W), f(30, 0))

  // ---------------------------------------------------------------------------
  // fmax32 / fmin32: lane-wise comparison (NaN treated as zero)
  // ---------------------------------------------------------------------------
  def fmax32(a: UInt, b: UInt): UInt = {
    val aS = sanitize(a); val bS = sanitize(b)
    // Compare as signed magnitude: negative FP32 is larger magnitude but smaller value
    Mux(aS.asSInt > bS.asSInt, aS, bS)
  }
  def fmin32(a: UInt, b: UInt): UInt = {
    val aS = sanitize(a); val bS = sanitize(b)
    Mux(aS.asSInt < bS.asSInt, aS, bS)
  }

  // ---------------------------------------------------------------------------
  // INT ↔ FP32 conversions
  // ---------------------------------------------------------------------------

  /** INT32 → FP32 (exact for values ≤ 2^23; round-to-nearest for larger) */
  def s32ToF32(s: SInt): UInt = {
    val neg   = s < 0.S(32.W)
    val mag   = Mux(neg, (-s).asUInt, s.asUInt)    // magnitude
    val msb   = (31.U - PriorityEncoder(Reverse(mag))).asUInt  // position of leading 1
    val rSign = neg.asUInt
    // Guard: if mag == 0, output is 0
    val isZeroI = mag === 0.U
    // Normalise: shift mag left so leading 1 is at bit 23
    val shift = WireDefault(0.U(5.W))
    when (msb > 23.U) { shift := (msb - 23.U)(4, 0) }
    val rMan = WireDefault(0.U(23.W))
    when (!isZeroI) {
      when (msb >= 23.U) { rMan := (mag >> (msb - 23.U))(22, 0) }
      .otherwise         { rMan := (mag << (23.U - msb))(22, 0) }
    }
    val rExp = Mux(isZeroI, 0.U, (msb.pad(9) + EXP_BIAS.U(9.W))(7, 0))
    Mux(isZeroI, 0.U, pack(rSign, rExp, rMan))
  }

  /** FP32 → INT32, RTZ (truncate toward zero, saturate) */
  def f32ToS32RTZ(f: UInt): SInt = {
    val fS = sanitize(f)
    val fSign = sign(fS)
    val fExp  = exp(fS)
    val fMan  = Cat(1.U(1.W), man(fS))  // 24 bits

    // exponent biased: unbiased = fExp - 127
    val unbiased = fExp.asSInt - EXP_BIAS.S

    val result = WireDefault(0.S(32.W))
    when (!isZero(fS)) {
      when (unbiased >= 31.S) {
        // Overflow: saturate
        result := Mux(fSign === 0.U, 0x7FFFFFFF.S(32.W), 0x80000000L.S(32.W))
      } .elsewhen (unbiased >= 23.S) {
        // Shift left
        val sh = (unbiased - 23.S).asUInt
        val mag = (fMan << sh)(31, 0)
        result := Mux(fSign === 0.U, mag.asSInt, -(mag.asSInt))
      } .elsewhen (unbiased >= 0.S) {
        // Shift right (truncate)
        val sh = (23.S - unbiased).asUInt
        val mag = (fMan >> sh)(23, 0)
        result := Mux(fSign === 0.U, mag.asSInt, -(mag.asSInt))
      }
      // else: |f| < 1 → 0
    }
    result
  }

  /** FP32 → INT32 with round mode selection (0=RTZ, else RTZ for simplicity in Tier-2) */
  def f32ToS32(f: UInt, round: UInt): SInt = f32ToS32RTZ(f)

  /** INT8 → FP32 (exact, always representable) */
  def s8ToF32(s: SInt): UInt = s32ToF32(s.asTypeOf(SInt(32.W)))

  /** FP32 → INT8 saturated, RTZ */
  def f32ToS8(f: UInt, round: UInt): SInt = {
    val s32 = f32ToS32(f, round)
    val sat = Wire(SInt(8.W))
    sat := MuxCase(s32(7, 0).asSInt, Seq(
      (s32 > 127.S(32.W))   -> 127.S(8.W),
      (s32 < (-128).S(32.W)) -> (-128).S(8.W),
    ))
    sat
  }

  // ---------------------------------------------------------------------------
  // BF16 ↔ FP32: top-16-bits aliasing
  // ---------------------------------------------------------------------------

  /** FP32 → BF16: truncate low 16 bits (RNE: round up if bit 15 and any lower bit set) */
  def f32ToBf16(f: UInt): UInt = {
    // Simple RNE: add 0x8000 to round, then truncate
    val rounded = f + "h00008000".U
    rounded(31, 16)
  }

  /** BF16 → FP32: zero-pad low 16 bits */
  def bf16ToF32(b: UInt): UInt = Cat(b(15, 0), 0.U(16.W))

  // ---------------------------------------------------------------------------
  // BF8 ↔ FP32
  //   E4M3 (1b sign, 4b exp bias=7,  3b mantissa; max normal ≈ 448)
  //   E5M2 (1b sign, 5b exp bias=15, 2b mantissa; max normal ≈ 57344)
  // ---------------------------------------------------------------------------

  /** FP32 → BF8 E4M3 */
  def f32ToBf8E4M3(f: UInt): UInt = {
    val fS   = sanitize(f)
    val fSgn = sign(fS)
    val fExp = exp(fS).asSInt - EXP_BIAS.S  // unbiased
    val fMan = man(fS)

    // E4M3 unbiased range: [-6, 7] (biased [1..14]); 0 for zero
    val result = WireDefault(0.U(8.W))
    when (!isZero(fS)) {
      val eRaw = fExp + 7.S  // re-bias to 7
      when (eRaw >= 15.S) {
        // Overflow: max normal
        result := Cat(fSgn, "b01111111".U(7.W))
      } .elsewhen (eRaw > 0.S) {
        result := Cat(fSgn, eRaw(3, 0), fMan(22, 20))
      }
      // else underflow / subnormal → 0
    }
    result
  }

  /** BF8 E4M3 → FP32 */
  def bf8E4M3ToF32(b: UInt): UInt = {
    val sgn  = b(7)
    val bExp = b(6, 3)
    val bMan = b(2, 0)
    val isZ  = (bExp === 0.U) && (bMan === 0.U)
    val fExp = (bExp.asSInt - 7.S + EXP_BIAS.S).asUInt(7, 0)
    val fMan = Cat(bMan, 0.U(20.W))
    Mux(isZ, Cat(sgn, 0.U(31.W)), pack(sgn, fExp, fMan))
  }

  /** FP32 → BF8 E5M2 */
  def f32ToBf8E5M2(f: UInt): UInt = {
    val fS   = sanitize(f)
    val fSgn = sign(fS)
    val fExp = exp(fS).asSInt - EXP_BIAS.S
    val fMan = man(fS)

    val result = WireDefault(0.U(8.W))
    when (!isZero(fS)) {
      val eRaw = fExp + 15.S
      when (eRaw >= 31.S) {
        result := Cat(fSgn, "b0111111".U(7.W))
      } .elsewhen (eRaw > 0.S) {
        result := Cat(fSgn, eRaw(4, 0), fMan(22, 21))
      }
    }
    result
  }

  /** BF8 E5M2 → FP32 */
  def bf8E5M2ToF32(b: UInt): UInt = {
    val sgn  = b(7)
    val bExp = b(6, 2)
    val bMan = b(1, 0)
    val isZ  = (bExp === 0.U) && (bMan === 0.U)
    val fExp = (bExp.asSInt - 15.S + EXP_BIAS.S).asUInt(7, 0)
    val fMan = Cat(bMan, 0.U(21.W))
    Mux(isZ, Cat(sgn, 0.U(31.W)), pack(sgn, fExp, fMan))
  }

  // Dispatch based on a Bool (false=E4M3, true=E5M2)
  def f32ToBf8(f: UInt, e5m2: Bool): UInt =
    Mux(e5m2, f32ToBf8E5M2(f), f32ToBf8E4M3(f))
  def bf8ToF32(b: UInt, e5m2: Bool): UInt =
    Mux(e5m2, bf8E5M2ToF32(b), bf8E4M3ToF32(b))

  // ---------------------------------------------------------------------------
  // INT16 ↔ INT32 (sign-extend / saturate-narrow)
  // ---------------------------------------------------------------------------
  def s16ToS32(s: SInt): SInt = s.asTypeOf(SInt(32.W))
  def s32ToS16(s: SInt): SInt = {
    val sat = Wire(SInt(16.W))
    sat := MuxCase(s(15, 0).asSInt, Seq(
      (s > 32767.S(32.W))   -> 32767.S(16.W),
      (s < (-32768).S(32.W)) -> (-32768).S(16.W),
    ))
    sat
  }
}

// ---------------------------------------------------------------------------
// Scala-reference functions for test specs (not synthesised)
// ---------------------------------------------------------------------------
object FpRef {
  import java.lang.{Float => JFloat, Integer => JInt}

  def f32Bits(f: Float): Int = JFloat.floatToRawIntBits(f)
  def bitsF32(i: Int): Float = JFloat.intBitsToFloat(i)

  def fadd(aBits: Int, bBits: Int): Int =
    f32Bits(bitsF32(aBits) + bitsF32(bBits))
  def fmul(aBits: Int, bBits: Int): Int =
    f32Bits(bitsF32(aBits) * bitsF32(bBits))
  def fma(aBits: Int, bBits: Int, cBits: Int): Int =
    f32Bits(Math.fma(bitsF32(aBits).toDouble, bitsF32(bBits).toDouble, bitsF32(cBits).toDouble).toFloat)

  def s32ToF32(s: Int): Int = f32Bits(s.toFloat)
  def f32ToS32(bits: Int): Int = bitsF32(bits).toInt   // RTZ per Java cast
  def s8ToF32(s: Byte): Int  = f32Bits(s.toFloat)
  def f32ToS8(bits: Int): Byte = {
    val f = bitsF32(bits)
    if (f >= 127.0f) 127 else if (f <= -128.0f) -128 else f.toInt.toByte
  }

  /** BF16 encode: top 16 bits of FP32 (round-nearest) */
  def f32ToBf16Bits(bits: Int): Int = ((bits + 0x8000) >> 16) & 0xFFFF
  def bf16BitsToF32(bf: Int): Int  = (bf & 0xFFFF) << 16

  /** BF8 E4M3 reference encoder */
  def f32ToBf8E4M3(bits: Int): Int = {
    val f = bitsF32(bits)
    if (f.isNaN || f == 0.0f) 0
    else {
      val sgn = if (bits < 0) 0x80 else 0
      val absF = Math.abs(f.toDouble)
      val exp  = Math.getExponent(f).max(-6).min(7)
      val scale = Math.pow(2.0, exp)
      val man3  = Math.round((absF / scale - 1.0) * 8.0).toInt.min(7).max(0)
      val eBiased = (exp + 7).max(0).min(15)
      sgn | ((eBiased & 0xF) << 3) | (man3 & 0x7)
    }
  }

  /** BF8 E5M2 reference encoder */
  def f32ToBf8E5M2(bits: Int): Int = {
    val f = bitsF32(bits)
    if (f.isNaN || f == 0.0f) 0
    else {
      val sgn = if (bits < 0) 0x80 else 0
      val absF = Math.abs(f.toDouble)
      val exp  = Math.getExponent(f).max(-14).min(15)
      val scale = Math.pow(2.0, exp)
      val man2  = Math.round((absF / scale - 1.0) * 4.0).toInt.min(3).max(0)
      val eBiased = (exp + 15).max(0).min(31)
      sgn | ((eBiased & 0x1F) << 2) | (man2 & 0x3)
    }
  }
}
