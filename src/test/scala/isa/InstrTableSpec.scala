// See README.md for license details.
// Tests for InstrTable: internal invariants + exhaustive decode coverage.
//
//   * every InstrTable.def (and every cvtPair) must assemble to a legal word
//     that decodes to the family/op recorded in the table;
//   * every (opcode, funct3) NOT in the table must be illegal (neutral attrs).
//
// The exhaustive sweep pins Review Focus #1 (no unaliased legal encodings).

package isa

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec
import isa.micro_op._

class InstrTableSpec extends AnyFlatSpec {
  import NpuAssembler._

  private def peekEnum(dut: InstrDecoder, field: chisel3.Data): BigInt =
    field.peekValue().asBigInt

  // Assemble a neutral-attribute word for a table definition using its
  // declared format.  Neutral attrs: width=0, round=0, sat=0, dtype=0.
  private def instrFor(d: InstrDef): Int = d.fmt match {
    case Fmt.R     => encR(d.opcode, d.funct3, f7(), 0, 0, 0)
    case Fmt.I     => encI(d.opcode, d.funct3, 0, 0, 0)
    case Fmt.S     => encS(d.opcode, d.funct3, 0, 0, 0, 0, RNE)
    case Fmt.NoFmt => 0x00
  }

  // (opcode, funct3) pairs that are legal under neutral attributes:
  // all table entries, all funct3 for NOP (funct3 don't-care), and the CVT
  // pairs whose source format is the neutral code S8 (0).
  private val legalNeutral: Set[(Int, Int)] = {
    val fromDefs   = InstrTable.defs.map(d => (d.opcode, d.funct3)).toSet
    val nopAll     = (0 to 7).map(f3 => (0x00, f3)).toSet
    val cvtNeutral = InstrTable.cvtPairs.filter(_.src == S8).map(p => (0x14, p.dst)).toSet
    fromDefs ++ nopAll ++ cvtNeutral
  }

  // ==========================================================================
  // Invariants (pure Scala — no simulation)
  // ==========================================================================
  "InstrTable" should "be internally consistent" in {
    assert(InstrTable.byMnemonic.size == InstrTable.defs.size, "duplicate mnemonic")
    for (d <- InstrTable.defs)
      assert(d.opcode <= 0x7F && d.opcode >= 0 && d.funct3 >= 0 && d.funct3 <= 7,
        s"out-of-range ${d.mnemonic}: opcode=${d.opcode} funct3=${d.funct3}")
    assert(InstrTable.cvtPairs.map(p => (p.dst, p.src)).distinct.size == InstrTable.cvtPairs.size,
      "duplicate cvt pair")
    // byOpcode must cover exactly the expected opcode set (explicit literal,
    // so a missing/spurious family is caught), and each group must be well
    // formed: non-empty, all entries share the group opcode, no funct3 dupes.
    val expectedOpcodes = Set(0x00, 0x03, 0x07, 0x10, 0x11, 0x12, 0x13,
                              0x15, 0x16, 0x17, 0x18, 0x27)
    assert(InstrTable.byOpcode.keySet == expectedOpcodes,
      s"unexpected opcode set: ${InstrTable.byOpcode.keySet}")
    for ((op, ds) <- InstrTable.byOpcode) {
      assert(ds.nonEmpty && ds.forall(_.opcode == op), s"byOpcode(0x${op.toHexString}) group malformed")
      assert(ds.map(_.funct3).distinct.size == ds.size, s"byOpcode(0x${op.toHexString}) duplicate funct3")
    }
    assert(InstrTable.cvtPairs.size == 12, "expected 12 cvt pairs")
  }

  // ==========================================================================
  // Valid decode: every table entry + every cvt pair
  // ==========================================================================
  "an InstrDecoder driven from InstrTable" should "decode every table entry legally" in {
    simulate(new InstrDecoder) { dut =>
      for (d <- InstrTable.defs) {
        val w = instrFor(d)
        dut.io.instr.poke((w.toLong & 0xFFFFFFFFL).U)
        dut.clock.step(0)
        assert(!dut.io.illegal.peek().litToBoolean,
          s"${d.mnemonic} (0x${w.toHexString}) unexpectedly illegal")
        assert(peekEnum(dut, dut.io.decoded.family) == BigInt(d.opcode),
          s"${d.mnemonic}: family != 0x${d.opcode.toHexString}")
        assert(peekEnum(dut, dut.io.decoded.valu.op) == d.op.litValue,
          s"${d.mnemonic}: valu.op mismatch")
      }

      for (p <- InstrTable.cvtPairs) {
        val w = encR(0x14, p.dst, f7Cvt(p.src), 0, 0, 0)
        dut.io.instr.poke((w.toLong & 0xFFFFFFFFL).U)
        dut.clock.step(0)
        assert(!dut.io.illegal.peek().litToBoolean,
          s"cvt(dst=${p.dst},src=${p.src}) unexpectedly illegal")
        assert(peekEnum(dut, dut.io.decoded.family) == BigInt(0x14),
          s"cvt(dst=${p.dst},src=${p.src}): family != 0x14")
        assert(peekEnum(dut, dut.io.decoded.valu.op) == p.op.litValue,
          s"cvt(dst=${p.dst},src=${p.src}): valu.op mismatch")
      }
    }
  }

  // ==========================================================================
  // Exhaustive illegal sweep: every (opcode, funct3) not in the table is
  // illegal under neutral attributes.
  // ==========================================================================
  "InstrTable" should "classify every non-table (opcode,funct3) as illegal" in {
    simulate(new InstrDecoder) { dut =>
      for (op <- 0 to 0x7F; f3 <- 0 to 7) {
        val w = encR(op, f3, f7(), 0, 0, 0)
        dut.io.instr.poke((w.toLong & 0xFFFFFFFFL).U)
        dut.clock.step(0)
        val illegal = dut.io.illegal.peek().litToBoolean
        val expectLegal = legalNeutral.contains((op, f3))
        if (expectLegal)
          assert(!illegal, s"(0x${op.toHexString},$f3) should be LEGAL but was illegal")
        else
          assert(illegal, s"(0x${op.toHexString},$f3) should be ILLEGAL but was legal")
      }
    }
  }
}
