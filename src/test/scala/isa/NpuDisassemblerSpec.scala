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

  it should "reject illegal CVT pairs" in {
    // dst=BF16(4), src=S8(0) is not a legal pair (only src in {F32}).
    assert(NpuDisassembler(encR(0x14, BF16, f7Cvt(S8), 0, 0, 0)) == "illegal")
    // dst=S32(2), src=S32(2) (identity) is not a legal pair either.
    assert(NpuDisassembler(encR(0x14, S32, f7Cvt(S32), 0, 0, 0)) == "illegal")
  }
}
