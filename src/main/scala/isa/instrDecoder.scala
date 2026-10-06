// See README.md for license details.
// -----------------------------------------------------------------------------
//  instrDecoder.scala — combinational 32-bit instruction word → DecodedMicroOp
//
//  One clock cycle: combinational only, no registers.
//  The decoded bundle reaches execution units in the same issue cycle.
//
//  Illegal instruction detection:
//    - Reserved opcode family → illegal
//    - Reserved funct3 within a family → illegal
//    - VecDtypeCls = 11 (reserved) in funct7[6:5] → illegal
//    - Width = 11 (reserved) in funct7[1:0] → illegal
//    - vcvt src == dst format → illegal
// -----------------------------------------------------------------------------

package isa

import chisel3._
import chisel3.util._
import isa.micro_op._

// ---------------------------------------------------------------------------
// DecodedMicroOp — output bundle of InstrDecoder
// ---------------------------------------------------------------------------
class DecodedMicroOp extends Bundle {
  val family    = OpFamily()
  val valu      = new NCoreVALUBundle
  val mma_keep  = Bool()          // MMALU: keep/accumulate signal
  val mma_last  = Bool()          // MMALU: assert clct
  val mma_reset = Bool()          // MMALU: clear accumulator
  val rd        = UInt(5.W)
  val rs1       = UInt(5.W)
  val rs2       = UInt(5.W)
  val rs3       = UInt(5.W)       // S-format third source (MMA C accumulator)
  val mem_width = UInt(3.W)       // ld/st funct3 (SEW/register class)
  val mem_off   = UInt(12.W)      // ld/st raw imm[11:0] (unsigned section offset)
}

// ---------------------------------------------------------------------------
// InstrDecoder — pure combinational module
// ---------------------------------------------------------------------------
class InstrDecoder extends Module {
  val io = IO(new Bundle {
    val instr   = Input(UInt(32.W))
    val decoded = Output(new DecodedMicroOp)
    val illegal = Output(Bool())
  })

  // ---------- Field extraction ----------
  val opBits  = io.instr(InstrBits.OPCODE_HI, InstrBits.OPCODE_LO)  // [6:0]
  val rdBits  = io.instr(InstrBits.RD_HI,     InstrBits.RD_LO)      // [11:7]
  val f3      = io.instr(InstrBits.FUNCT3_HI,  InstrBits.FUNCT3_LO)  // [14:12]
  val rs1Bits = io.instr(InstrBits.RS1_HI,     InstrBits.RS1_LO)     // [19:15]
  val rs2Bits = io.instr(InstrBits.RS2_HI,     InstrBits.RS2_LO)     // [24:20]
  val f7      = io.instr(InstrBits.FUNCT7_HI,  InstrBits.FUNCT7_LO)  // [31:25]

  // I-type immediate (sign-extended 12 bits)
  val immI    = io.instr(InstrBits.IMM_I_HI, InstrBits.IMM_I_LO).asSInt

  // S-type fields (FMA)
  val rs3Bits = io.instr(InstrBits.RS3_HI, InstrBits.RS3_LO)
  val rndS    = io.instr(InstrBits.RND_S_HI, InstrBits.RND_S_LO)

  // funct7 attribute sub-fields
  val f7Width = f7(InstrBits.F7_WIDTH_HI, InstrBits.F7_WIDTH_LO)
  val f7Round = f7(InstrBits.F7_ROUND_HI, InstrBits.F7_ROUND_LO)
  val f7Sat   = f7(InstrBits.F7_SAT)
  val f7Dtype = f7(InstrBits.F7_DTYPE_HI, InstrBits.F7_DTYPE_LO)

  // cvt-specific funct7 sub-fields
  val f7CvtSrc = f7(InstrBits.F7_CVT_SRC_HI, InstrBits.F7_CVT_SRC_LO)
  val f7CvtSat = f7(InstrBits.F7_CVT_SAT)
  val f7CvtRnd = f7(InstrBits.F7_CVT_RND_HI, InstrBits.F7_CVT_RND_LO)
  val f7Bf8    = f7(InstrBits.F7_CVT_BF8)

  // Decode opcode family from the full 7-bit opcode field.
  // OpFamily auto-infers its width from the maximum enum value (0x27 = 39
  // → 6 bits), so it cannot represent opcodes with bit 6 set.  Truncating the
  // field before decode would alias 0x40..0x7F onto valid families
  // (0x40 → NOP).  Both the validity check and the family value are derived
  // from OpFamily.all, the single source of truth for the member set.
  val familyOK = OpFamily.all.map(v => opBits === v.litValue.U).reduce(_ || _)
  val family   = MuxCase(OpFamily.NOP: OpFamily.Type,
                         OpFamily.all.map(v => (opBits === v.litValue.U) -> v))

  // ---------- VALU op decode (opcode+funct3 → VecOp) ----------

  // Fold (opcode, funct3) → VecOp over InstrTable.byOpcode: InstrTable is the
  // single source of truth for the legal decode map.  CVT (opcode 0x14) is the
  // exception — its legality is a correlated (dst, src) format pair rather than
  // funct3 membership — so it folds over InstrTable.cvtPairs instead.
  // Default = vadd (harmless; illegal flag suppresses write-back).
  val vecOp = WireDefault(VecOp.vadd)
  // f3OK tracks whether funct3 is legal for the decoded opcode.  It defaults
  // true so NOP (opcode 0x00), the sole funct3 don't-care opcode, is legal for
  // every funct3 value.  Table opcodes clear it and re-assert it only on a
  // funct3 match.  Reserved opcodes are rejected by familyOK regardless of
  // f3OK, so the true default cannot leak an unaliased reserved word.
  val f3OK = WireDefault(true.B)
  for ((opcode, defs) <- InstrTable.byOpcode if opcode != 0x00 && opcode != 0x14) {
    when (opBits === opcode.U) {
      f3OK := false.B
      for (d <- defs) when (f3 === d.funct3.U) { vecOp := d.op; f3OK := true.B }
    }
  }
  when (opBits === 0x14.U) {          // CVT: legality from correlated (dst, src)
    f3OK := false.B
    for (p <- InstrTable.cvtPairs)
      when (f3 === p.dst.U && f7CvtSrc === p.src.U) { vecOp := p.op; f3OK := true.B }
  }

  // ---------- Width decode — drive as raw UInt(2.W) to match NCoreVALUBundle ----------
  // VX=0, VE=1, VR=2 (matches VecWidth enum values)
  val width = WireDefault(0.U(2.W))  // default = VX
  when (f7Width === 1.U) { width := 1.U }  // VE
  .elsewhen (f7Width === 2.U) { width := 2.U }  // VR
  // FP family always uses VR
  when (family === OpFamily.VALU_FP || family === OpFamily.VALU_FP_FMA) {
    width := 2.U  // VR
  }
  // CVT: for simplicity set width to VR (widest); actual regCls determined by the
  // backend based on the VecOp. The VALU handles width selection per-op internally.
  when (family === OpFamily.VALU_CVT) {
    width := 2.U  // VR — conservative; backend picks correct src/dst via VecOp
  }
  // BCAST IMM (I-format): no funct7, width defaults to VX (IMM always goes to VX)
  when (family === OpFamily.VALU_BCAST && f3 === Funct3Bcast.IMM) {
    width := 0.U  // VX
  }
  // LD/ST (I-format): funct7 bits overlap the immediate; width/round/dtype
  // attributes are not applicable.  Force them to defaults so imm bits do
  // not leak into the decoded bundle or trip the width/dtype illegal checks.
  when (family === OpFamily.LD || family === OpFamily.ST) {
    width := 0.U  // VX (unused)
  }
  // vsetlut (I-format): reads from a VR source register → force VR width so
  // the backend routes in_a_vr correctly.
  when (family === OpFamily.VALU_LUT &&
        (f3 === Funct3Lut.VSETLUT_A || f3 === Funct3Lut.VSETLUT_B)) {
    width := 2.U  // VR
  }
  // Width bits are repurposed for src format in CVT family; skip width check for CVT.
  val widthOK = !((f7Width === 3.U) &&
    (family =/= OpFamily.VALU_FP) &&
    (family =/= OpFamily.VALU_FP_FMA) &&
    (family =/= OpFamily.VALU_CVT) &&
    (family =/= OpFamily.LD) &&
    (family =/= OpFamily.ST))

  // ---------- Dtype decode ----------
  // BF8 variant from funct7[6] (cvt family) or bf8E5M2 forced
  val dtype = WireDefault(VecDType.S8C4)
  switch (f7Dtype) {
    is (1.U) { dtype := VecDType.FP32C1 }
    is (2.U) {
      // BF class: BF16 unless BF8 format codes are in play
      dtype := VecDType.BF16C2
    }
  }
  // For CVT, override with BF8 variant
  when (family === OpFamily.VALU_CVT) {
    when (f7Bf8 === 1.U) { dtype := VecDType.BF8E5M2 }
    .otherwise           { dtype := VecDType.BF8E4M3  }
  }

  val dtypeOK = !((f7Dtype === 3.U) &&
    (family =/= OpFamily.LD) &&
    (family =/= OpFamily.ST))

  // ---------- MMA control ----------
  val mmaKeep  = WireDefault(false.B)
  val mmaLast  = WireDefault(false.B)
  val mmaReset = WireDefault(false.B)
  when (family === OpFamily.MMA) {
    switch (f3) {
      is (Funct3Mma.MMA)       { mmaKeep  := f7Sat.asBool }   // reuse sat bit for keep
      is (Funct3Mma.MMA_LAST)  {
        mmaLast  := true.B
        mmaKeep  := f7Sat.asBool   // keep honoured on mma.last too
      }
      is (Funct3Mma.MMA_RESET) { mmaReset := true.B }
    }
  }

  // ---------- Illegal detection ----------
  // Single pass: family, funct3, width and dtype validity are each computed
  // exactly once; an instruction is legal iff all four hold.
  val illegal = !(familyOK && f3OK && widthOK && dtypeOK)

  // ---------- Drive outputs ----------
  io.illegal := illegal

  io.decoded.family    := family
  io.decoded.rd        := rdBits
  io.decoded.rs1       := rs1Bits
  io.decoded.rs2       := rs2Bits
  io.decoded.rs3       := rs3Bits
  io.decoded.mem_width := f3
  io.decoded.mem_off   := io.instr(InstrBits.IMM_I_HI, InstrBits.IMM_I_LO)

  io.decoded.mma_keep  := mmaKeep
  io.decoded.mma_last  := mmaLast
  io.decoded.mma_reset := mmaReset

  // VALU control bundle
  io.decoded.valu.op       := vecOp
  io.decoded.valu.regCls    := width
  io.decoded.valu.dtype    := dtype
  // For CVT, sat bit is at funct7[3] not funct7[4]; LD/ST carry no saturate
  // (funct7 overlaps the immediate).
  io.decoded.valu.saturate := Mux(
    family === OpFamily.VALU_CVT,
    f7CvtSat.asBool,
    Mux(family === OpFamily.LD || family === OpFamily.ST, false.B, f7Sat.asBool)
  )
  // For LUT ops, round[0] carries the bank select (taken from funct3[0]):
  //   vlut.A (f3=0) → round=0, vlut.B (f3=1) → round=1
  //   vsetlut.A (f3=4) → round=0, vsetlut.B (f3=5) → round=1
  io.decoded.valu.round    := Mux(
    family === OpFamily.VALU_FP_FMA,
    rndS,
    Mux(family === OpFamily.VALU_CVT, f7CvtRnd,
      Mux(family === OpFamily.VALU_LUT, Cat(0.U(1.W), f3(0)),
        Mux(family === OpFamily.LD || family === OpFamily.ST, 0.U(2.W),
          f7Round)))
  )
  io.decoded.valu.rs3_idx  := rs3Bits
  io.decoded.valu.imm      := immI
}
