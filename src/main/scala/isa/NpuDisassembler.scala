// See README.md for license details.
// -----------------------------------------------------------------------------
//  NpuDisassembler.scala — table-driven 32-bit instruction word → text.
//
//  Consumes InstrTable.byOpcode and InstrTable.cvtPairs (the same decode map
//  InstrDecoder and NpuAssembler are folded onto) and renders the matched row's
//  mnemonic plus the decoded operand fields.  Returns "illegal" for any word
//  whose (opcode, funct3) is not a legal row.
//
//  Legality mirrors InstrDecoder:
//    * NOP (0x00) is the sole funct3 don't-care opcode.
//    * CVT (0x14) legality is the correlated (dst=funct3, src=funct7[2:0]) pair.
//    * every other family is legal iff (opcode, funct3) appears in the table.
//
//  Format (instruction word):
//    R-type  [funct7(7) | rs2(5) | rs1(5) | funct3(3) | rd(5) | opcode(7)]
//    I-type  [    imm[11:0](12)  | rs1(5) | funct3(3) | rd(5) | opcode(7)]
//    S-type  [rs3(5)|rnd(2)| rs2(5) | rs1(5) | funct3(3) | rd(5) | opcode(7)]
//
//  Rendered tail: "<mnem> rd(<n>), rs1(<n>), rs2(<n>)" for R-type,
//  "... rs1(<n>), imm(<n>)" for I-type, "... rs3(<n>)" appended for S-type.
// -----------------------------------------------------------------------------

package isa

import isa.micro_op.VecOp

object NpuDisassembler {

  // VecOp singleton values do not expose their declared name directly; Chisel's
  // `.toString` renders "VecOp(<id>=<name>)".  Pair `VecOp.all` with the public
  // `allNames` (same declaration order) to recover the bare mnemonic.
  private val opNames: Map[BigInt, String] =
    VecOp.all.zip(VecOp.allNames).map { case (v, n) => v.litValue -> n }.toMap

  // (dst funct3, src funct7[2:0]) → vcvt mnemonic.
  private val cvtByPair: Map[(Int, Int), String] =
    InstrTable.cvtPairs.map(p => (p.dst, p.src) -> opNames(p.op.litValue)).toMap

  def apply(word: Int): String = {
    val opcode = word & 0x7F
    val funct3 = (word >> 12) & 0x7

    if (opcode == 0x14) {
      // CVT: legality is the correlated (dst, src) pair, src in funct7[2:0].
      val src = (word >> 25) & 0x7
      cvtByPair.get((funct3, src)) match {
        case Some(mnem) => withTail(mnem, s"rd(${rd(word)}), rs1(${rs1(word)})")
        case None       => "illegal"
      }
    } else if (opcode == 0x00) {
      // NOP: funct3 don't-care.
      "nop"
    } else {
      InstrTable.byOpcode.get(opcode).flatMap(_.find(_.funct3 == funct3)) match {
        case Some(d) => withTail(d.mnemonic, operandTail(d, word))
        case None    => "illegal"
      }
    }
  }

  private def rd(word: Int): Int    = (word >> 7) & 0x1F
  private def rs1(word: Int): Int   = (word >> 15) & 0x1F
  private def rs2(word: Int): Int   = (word >> 20) & 0x1F
  private def rs3(word: Int): Int   = (word >> 27) & 0x1F
  private def imm(word: Int): Int   = (word >> 20) & 0xFFF

  private def operandTail(d: InstrDef, word: Int): String = d.fmt match {
    case Fmt.R => s"rd(${rd(word)}), rs1(${rs1(word)}), rs2(${rs2(word)})"
    case Fmt.I => s"rd(${rd(word)}), rs1(${rs1(word)}), imm(${imm(word)})"
    case Fmt.S => s"rd(${rd(word)}), rs1(${rs1(word)}), rs2(${rs2(word)}), rs3(${rs3(word)})"
    case Fmt.NoFmt => ""
  }

  private def withTail(mnem: String, tail: String): String =
    if (tail.isEmpty) mnem else s"$mnem $tail"
}
