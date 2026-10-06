// See README.md for license details.
// Round-trip tests for NpuDisassembler: assemble every InstrTable row (and every
// CVT pair) through NpuAssembler, then check the disassembler reports the row's
// mnemonic.  Illegal encodings must disassemble to "illegal".
//
// NpuDisassembler is a pure Scala object — no Chisel simulation is required.

package isa

import org.scalatest.flatspec.AnyFlatSpec
import isa.micro_op.VecOp

class NpuDisassemblerSpec extends AnyFlatSpec {
  import NpuAssembler._

  // VecOp singleton values do not expose their declared name directly; Chisel's
  // `.toString` renders "VecOp(<id>=<name>)".  Pair `VecOp.all` with the public
  // `allNames` (same declaration order) to recover the bare mnemonic.
  private val opNames: Map[BigInt, String] =
    VecOp.all.zip(VecOp.allNames).map { case (v, n) => v.litValue -> n }.toMap

  private def cvtMnemonic(p: CvtPair): String = opNames(p.op.litValue)

  // ==========================================================================
  // Round-trip every non-CVT table entry.
  // ==========================================================================
  "NpuDisassembler" should "round-trip every InstrTable entry" in {
    for (d <- InstrTable.defs) {
      val w = encode(d, rd = 3, rs1 = 5, rs2 = 6, rs3 = 7,
                     width = VX, round = RNE, sat = false, dtype = INT)
      val got = NpuDisassembler(w)
      assert(got.startsWith(d.mnemonic),
        s"${d.mnemonic}: got '$got' from word 0x${w.toHexString}")
    }
  }

  // ==========================================================================
  // Round-trip every CVT (dst, src) pair.
  // ==========================================================================
  it should "round-trip every CVT pair" in {
    for (p <- InstrTable.cvtPairs) {
      val w = encR(0x14, p.dst, f7Cvt(p.src), 2, 1, 0)
      val m = cvtMnemonic(p)
      val got = NpuDisassembler(w)
      assert(got.startsWith(m),
        s"$m: got '$got' from word 0x${w.toHexString}")
    }
  }

  // ==========================================================================
  // Independent anchor encodings with hardcoded expected mnemonics.  This
  // breaks the tautology of the exhaustive loops above, whose expected value is
  // sourced from the same InstrTable/opNames data as the disassembler: a wrong
  // table string or mapping bug would still pass those loops.
  // ==========================================================================
  it should "decode hardcoded anchor encodings" in {
    val anchors: Seq[(Int, Int, String)] = Seq(
      (0x00, 0, "nop"),
      (0x03, 0, "mma"),
      (0x03, 1, "mma.last"),
      (0x03, 2, "mma.reset"),
      (0x07, 0, "vle8"),
      (0x10, 0, "vadd"),
      (0x10, 7, "vrsub"),
      (0x11, 6, "vor"),
      (0x12, 5, "vrxor"),
      (0x13, 0, "vlut.A"),
      (0x13, 5, "vsetlut.B"),
      (0x15, 0, "vbcast"),
      (0x15, 1, "vbcast.imm"),
      (0x16, 0, "vfadd"),
      (0x17, 3, "vnfms"),
      (0x18, 2, "vmovh"),
      (0x27, 2, "vse32"),
    )
    for ((opcode, funct3, expected) <- anchors) {
      val w = (opcode & 0x7F) | ((funct3 & 0x7) << 12)
      val got = NpuDisassembler(w)
      assert(got.startsWith(expected),
        s"anchor (0x${opcode.toHexString},$funct3): expected '$expected', got '$got'")
    }

    val cvtAnchors: Seq[(Int, Int, String)] = Seq(
      (S8,  F32, "vcvt_s8_f32"),
      (F32, BF8, "vcvt_f32_bf8"),
    )
    for ((dst, src, expected) <- cvtAnchors) {
      val w = encR(0x14, dst, f7Cvt(src), 0, 0, 0)
      val got = NpuDisassembler(w)
      assert(got.startsWith(expected),
        s"cvt anchor (dst=$dst,src=$src): expected '$expected', got '$got'")
    }
  }

  // ==========================================================================
  // NOP is the sole funct3 don't-care opcode.
  // ==========================================================================
  it should "treat NOP funct3 as don't-care" in {
    for (f3 <- 1 to 7) {
      val w = (0x00 & 0x7F) | ((f3 & 0x7) << 12)
      assert(NpuDisassembler(w) == "nop",
        s"nop with funct3=$f3 should decode to 'nop', got '${NpuDisassembler(w)}'")
    }
  }

  // ==========================================================================
  // Guard against a vacuous table making the exhaustive loops pass trivially.
  // ==========================================================================
  it should "exercise a non-empty table" in {
    assert(InstrTable.defs.nonEmpty, "InstrTable.defs is empty — round-trip would be vacuous")
    assert(InstrTable.cvtPairs.nonEmpty, "InstrTable.cvtPairs is empty — round-trip would be vacuous")
  }

  // ==========================================================================
  // Illegal encodings.
  // ==========================================================================
  it should "reject reserved opcodes" in {
    assert(NpuDisassembler(0x7F) == "illegal")
    assert(NpuDisassembler(encR(0x01, 0, f7(), 0, 0, 0)) == "illegal")
  }

  it should "reject reserved funct3 within a known family" in {
    // REDUCE (0x12) funct3=6,7 are reserved.
    assert(NpuDisassembler(encR(0x12, 6, f7(), 0, 0, 0)) == "illegal")
    assert(NpuDisassembler(encR(0x12, 7, f7(), 0, 0, 0)) == "illegal")
    // ARITH (0x10) has all 8 funct3 legal, so a lower reserved family check:
    // LUT (0x13) funct3=2,3 reserved.
    assert(NpuDisassembler(encR(0x13, 2, f7(), 0, 0, 0)) == "illegal")
  }

  // ==========================================================================
  // Attribute legality mirrors InstrDecoder: reserved width (funct7[1:0]=3) and
  // reserved dtype (funct7[6:5]=3) must disassemble to "illegal".
  // ==========================================================================
  it should "reject reserved width (funct7[1:0]=3)" in {
    assert(NpuDisassembler(encR(0x10, 0, f7(width = 3), 0, 1, 2)) == "illegal")
    assert(NpuDisassembler(encR(0x12, 0, f7(width = 3), 0, 1, 2)) == "illegal")
  }

  it should "reject reserved dtype (funct7[6:5]=3)" in {
    assert(NpuDisassembler(encR(0x10, 0, f7(dtype = 3), 0, 1, 2)) == "illegal")
    assert(NpuDisassembler(encR(0x11, 0, f7(dtype = 3), 0, 1, 2)) == "illegal")
  }

  it should "accept legal attribute words and exempt families" in {
    // Negative control: a normal VALU word still disassembles.
    assert(NpuDisassembler(encR(0x10, 0, f7(VX), 0, 1, 2)).startsWith("vadd"))
    // FP width bits are don't-care (mirrors InstrDecoder).
    assert(NpuDisassembler(encR(0x16, 0, f7(width = 3, dtype = FP), 0, 1, 2))
      .startsWith("vfadd"))
    // I-format immediate high bits alias funct7 and must not be rejected.
    assert(NpuDisassembler(vmovi(rd = 1, imm = (-1))).startsWith("vmovi"))
  }

  it should "reject illegal CVT pairs" in {
    // dst=BF16(4), src=S8(0) is not a legal pair (only src in {F32}).
    assert(NpuDisassembler(encR(0x14, BF16, f7Cvt(S8), 0, 0, 0)) == "illegal")
    // dst=S32(2), src=S32(2) (identity) is not a legal pair either.
    assert(NpuDisassembler(encR(0x14, S32, f7Cvt(S32), 0, 0, 0)) == "illegal")
  }
}
