// See README.md for license details.
// -----------------------------------------------------------------------------
//  InstrTable.scala — authoritative Scala-level ISA decode map.
//
//  Single source of truth for the (opcode, funct3) → mnemonic + VecOp mapping,
//  plus the instruction format class and the attribute class.  InstrDecoder and
//  NpuAssembler are folded onto this table; the disassembler consumes it.
//
//  CVT (opcode 0x14) is special: its legality is a correlated (dst, src) format
//  pair rather than funct3 membership alone, and InstrDef carries only funct3.
//  It is therefore represented by `cvtPairs`, not by `defs`.
//
//  See instSetArch.scala for the opcode/funct3 encodings and instrFormat.scala
//  for the funt7 attribute layout.
// -----------------------------------------------------------------------------

package isa

import isa.micro_op.VecOp

// ---------------------------------------------------------------------------
// Fmt — which 32-bit field layout the instruction word uses.
// ---------------------------------------------------------------------------
sealed trait Fmt

object Fmt {
  case object R     extends Fmt   // funct7 | rs2 | rs1 | funct3 | rd | opcode
  case object I     extends Fmt   // imm[11:0] | rs1 | funct3 | rd | opcode
  case object S     extends Fmt   // rs3 | rnd | rs2 | rs1 | funct3 | rd | opcode
  case object NoFmt extends Fmt   // no operands (NOP)
}

// ---------------------------------------------------------------------------
// Attr — which funct7 sub-fields are meaningful for the instruction.
// ---------------------------------------------------------------------------
sealed trait Attr

case object AttrStd  extends Attr   // R/I VALU: width/round/sat/dtype
case object AttrCvt  extends Attr   // CVT: src/sat/round/bf8 (see cvtPairs)
case object AttrMem  extends Attr   // LD/ST: funct7 overlaps the immediate
case object AttrNone extends Attr   // NOP: no attributes

// ---------------------------------------------------------------------------
// InstrDef — one legal (opcode, funct3) decode target.
//
// `op` is the VecOp produced by InstrDecoder for the entry.  Non-VALU families
// (NOP/MMA/LD/ST) use VecOp.vadd, matching the decoder's default output, so the
// exhaustive test can assert `valu.op == d.op` uniformly.
// ---------------------------------------------------------------------------
case class InstrDef(
  opcode:   Int,
  funct3:   Int,
  mnemonic: String,
  fmt:      Fmt,
  op:       VecOp.Type,
  attr:     Attr = AttrStd,
)

// ---------------------------------------------------------------------------
// CvtPair — one legal CVT (dst, src) format pair.  funct3 carries the dst
// format code; funct7[2:0] carries the src format code.
// ---------------------------------------------------------------------------
case class CvtPair(dst: Int, src: Int, op: VecOp.Type)

// ---------------------------------------------------------------------------
// InstrTable — the decode map.
// ---------------------------------------------------------------------------
object InstrTable {
  import Fmt._
  import NpuAssembler.{S8, S16, S32, F32, BF16, BF8}

  // -------------------------------------------------------------------------
  // defs — every legal (opcode, funct3) entry (including NOP).
  // -------------------------------------------------------------------------
  val defs: Seq[InstrDef] = Seq(
    // -- NOP (0x00) — sole funct3 don't-care opcode; single entry at funct3=0 --
    InstrDef(0x00, 0, "nop", NoFmt, VecOp.vadd, AttrNone),

    // -- MMA (0x03) — S-format; VecOp is don't-care (default vadd) --
    InstrDef(0x03, 0, "mma",       S, VecOp.vadd),
    InstrDef(0x03, 1, "mma.last",  S, VecOp.vadd),
    InstrDef(0x03, 2, "mma.reset", S, VecOp.vadd),

    // -- LD (0x07) — I-format --
    InstrDef(0x07, 0, "vle8",  I, VecOp.vadd, AttrMem),
    InstrDef(0x07, 1, "vle16", I, VecOp.vadd, AttrMem),
    InstrDef(0x07, 2, "vle32", I, VecOp.vadd, AttrMem),

    // -- ARITH (0x10) --
    InstrDef(0x10, 0, "vadd",  R, VecOp.vadd),
    InstrDef(0x10, 1, "vsub",  R, VecOp.vsub),
    InstrDef(0x10, 2, "vmul",  R, VecOp.vmul),
    InstrDef(0x10, 3, "vneg",  R, VecOp.vneg),
    InstrDef(0x10, 4, "vabs",  R, VecOp.vabs),
    InstrDef(0x10, 5, "vmax",  R, VecOp.vmax),
    InstrDef(0x10, 6, "vmin",  R, VecOp.vmin),
    InstrDef(0x10, 7, "vrsub", R, VecOp.vrsub),

    // -- LOGIC (0x11) --
    InstrDef(0x11, 0, "vsll", R, VecOp.vsll),
    InstrDef(0x11, 1, "vsrl", R, VecOp.vsrl),
    InstrDef(0x11, 2, "vsra", R, VecOp.vsra),
    InstrDef(0x11, 3, "vrol", R, VecOp.vrol),
    InstrDef(0x11, 4, "vxor", R, VecOp.vxor),
    InstrDef(0x11, 5, "vnot", R, VecOp.vnot),
    InstrDef(0x11, 6, "vor",  R, VecOp.vor),
    InstrDef(0x11, 7, "vand", R, VecOp.vand),

    // -- REDUCE (0x12) — funct3 6,7 reserved (not in table) --
    InstrDef(0x12, 0, "vsum",  R, VecOp.vsum),
    InstrDef(0x12, 1, "vrmax", R, VecOp.vrmax),
    InstrDef(0x12, 2, "vrmin", R, VecOp.vrmin),
    InstrDef(0x12, 3, "vrand", R, VecOp.vrand),
    InstrDef(0x12, 4, "vror",  R, VecOp.vror),
    InstrDef(0x12, 5, "vrxor", R, VecOp.vrxor),

    // -- LUT (0x13) — bank-suffixed mnemonics; funct3 2,3,6,7 reserved --
    InstrDef(0x13, 0, "vlut.A",    R, VecOp.vlut),
    InstrDef(0x13, 1, "vlut.B",    R, VecOp.vlut),
    InstrDef(0x13, 4, "vsetlut.A", I, VecOp.vsetlut),
    InstrDef(0x13, 5, "vsetlut.B", I, VecOp.vsetlut),

    // -- CVT (0x14) is in cvtPairs, not here. --

    // -- BCAST (0x15) — funct3 2..7 reserved --
    InstrDef(0x15, 0, "vbcast",     R, VecOp.vbcast_reg),
    InstrDef(0x15, 1, "vbcast.imm", I, VecOp.vbcast_imm),

    // -- FP (0x16) — funct3 7 reserved --
    InstrDef(0x16, 0, "vfadd", R, VecOp.vfadd),
    InstrDef(0x16, 1, "vfsub", R, VecOp.vfsub),
    InstrDef(0x16, 2, "vfmul", R, VecOp.vfmul),
    InstrDef(0x16, 3, "vfneg", R, VecOp.vfneg),
    InstrDef(0x16, 4, "vfabs", R, VecOp.vfabs),
    InstrDef(0x16, 5, "vfmax", R, VecOp.vfmax),
    InstrDef(0x16, 6, "vfmin", R, VecOp.vfmin),

    // -- FP_FMA (0x17) — S-format; funct3 4..7 reserved --
    InstrDef(0x17, 0, "vfma",  S, VecOp.vfma),
    InstrDef(0x17, 1, "vfms",  S, VecOp.vfms),
    InstrDef(0x17, 2, "vnfma", S, VecOp.vnfma),
    InstrDef(0x17, 3, "vnfms", S, VecOp.vnfms),

    // -- MOV (0x18) — funct3 3..7 reserved --
    InstrDef(0x18, 0, "vmov",  R, VecOp.vmov),
    InstrDef(0x18, 1, "vmovi", I, VecOp.vmovi),
    InstrDef(0x18, 2, "vmovh", I, VecOp.vmovh),

    // -- ST (0x27) — I-format --
    InstrDef(0x27, 0, "vse8",  I, VecOp.vadd, AttrMem),
    InstrDef(0x27, 1, "vse16", I, VecOp.vadd, AttrMem),
    InstrDef(0x27, 2, "vse32", I, VecOp.vadd, AttrMem),
  )

  // -------------------------------------------------------------------------
  // cvtPairs — the 12 legal CVT (dst, src) format pairs.
  // Format codes: S8=0, S16=1, S32=2, F32=3, BF16=4, BF8=5.
  // -------------------------------------------------------------------------
  val cvtPairs: Seq[CvtPair] = Seq(
    CvtPair(S8,  S32,  VecOp.vcvt_s8_s32),
    CvtPair(S32, S8,   VecOp.vcvt_s32_s8),
    CvtPair(S32, F32,  VecOp.vcvt_s32_f32),
    CvtPair(F32, S32,  VecOp.vcvt_f32_s32),
    CvtPair(F32, S8,   VecOp.vcvt_f32_s8),
    CvtPair(S8,  F32,  VecOp.vcvt_s8_f32),
    CvtPair(F32, BF16, VecOp.vcvt_f32_bf16),
    CvtPair(BF16, F32, VecOp.vcvt_bf16_f32),
    CvtPair(F32, BF8,  VecOp.vcvt_f32_bf8),
    CvtPair(BF8, F32,  VecOp.vcvt_bf8_f32),
    CvtPair(S16, S32,  VecOp.vcvt_s16_s32),
    CvtPair(S32, S16,  VecOp.vcvt_s32_s16),
  )

  // -------------------------------------------------------------------------
  // Derived indices.
  // -------------------------------------------------------------------------
  val byOpcode:   Map[Int, Seq[InstrDef]] = defs.groupBy(_.opcode)
  val byMnemonic: Map[String, InstrDef]    = defs.map(d => d.mnemonic -> d).toMap

  // -------------------------------------------------------------------------
  // Construction-time invariants.
  // -------------------------------------------------------------------------
  require(byMnemonic.size == defs.size, "InstrTable: duplicate mnemonic")
  require(defs.forall(d => d.opcode >= 0 && d.opcode <= 0x7F && d.funct3 >= 0 && d.funct3 <= 7),
    "InstrTable: opcode must fit 7 bits and funct3 must be in 0..7")
  require(cvtPairs.map(p => (p.dst, p.src)).distinct.size == cvtPairs.size,
    "InstrTable: duplicate cvt pair")
}
