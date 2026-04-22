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
  val mem_width      = UInt(3.W)  // ld/st funct3 (Funct3Mem values)
  val is_ld          = Bool()     // true: LD family, VX/VE/VR contiguous load from RF
  val is_st          = Bool()     // true: ST family, VX/VE/VR contiguous store to RF
  // ---- funct3=6 indexed ops (unified gather/tile/scatter) ----
  val is_gather      = Bool()     // LD opcode, funct3=6, funct7[USE_TILE_CNT]=0
                                  //   VX[rd][k] = RF[ VX[rs1][k] ][ k ]  (diagonal gather)
  val is_tile        = Bool()     // LD opcode, funct3=6, funct7[USE_TILE_CNT]=1
                                  //   addr = rs1 + tile_h*stride_row_h + tile_w*stride_row_w
  val tile_zpad      = Bool()     // ld.tile: funct7[ZERO_PAD]
  val tile_trans     = Bool()     // ld.tile: funct7[TRANSPOSED]
  val tile_autoinc   = Bool()     // ld.tile/gather: funct7[AUTO_INC], pulse tile_w_inc after
  val is_scatter     = Bool()     // ST opcode, funct3=6
                                  //   RF[ VX[rs1][k] ][ k ] = VX[rs2][k]  (diagonal scatter)
  // ---- tile.cfg ----
  val is_tilecfg    = Bool()      // LD opcode, funct3=7
  val tilecfg_sel   = UInt(3.W)   // TileCfgSel value
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

  // Try to decode opcode family.
  // OpFamily auto-infers minimum bit width (5 bits for max value 0x18=24).
  // opBits is 7 bits; truncate to match the enum width (5) before safe-cast.
  // 5 = ceil(log2(0x18 + 1)) computed at Scala level.
  val OP_FAMILY_BITS = 5  // covers 0x00..0x18 = 0..24
  val opBitsTrunc = opBits(OP_FAMILY_BITS - 1, 0)
  val familyOpt = OpFamily.safe(opBitsTrunc)
  val familyOK  = familyOpt._2
  val family    = familyOpt._1

  // ---------- VALU op decode (opcode+funct3 → VecOp) ----------

  // Map (family, funct3) → VecOp.  Use a big MuxCase to stay Chisel-idiomatic.
  // Default = vadd (harmless; illegal flag suppresses write-back)
  val vecOp = WireDefault(VecOp.vadd)
  val f3Valid = WireDefault(true.B)

  switch (family) {
    is (OpFamily.VALU_ARITH) {
      switch (f3) {
        is (Funct3Arith.ADD)  { vecOp := VecOp.vadd  }
        is (Funct3Arith.SUB)  { vecOp := VecOp.vsub  }
        is (Funct3Arith.MUL)  { vecOp := VecOp.vmul  }
        is (Funct3Arith.NEG)  { vecOp := VecOp.vneg  }
        is (Funct3Arith.ABS)  { vecOp := VecOp.vabs  }
        is (Funct3Arith.MAX)  { vecOp := VecOp.vmax  }
        is (Funct3Arith.MIN)  { vecOp := VecOp.vmin  }
        is (Funct3Arith.RSUB) { vecOp := VecOp.vrsub }
      }
    }
    is (OpFamily.VALU_LOGIC) {
      switch (f3) {
        is (Funct3Logic.SLL) { vecOp := VecOp.vsll }
        is (Funct3Logic.SRL) { vecOp := VecOp.vsrl }
        is (Funct3Logic.SRA) { vecOp := VecOp.vsra }
        is (Funct3Logic.ROL) { vecOp := VecOp.vrol }
        is (Funct3Logic.XOR) { vecOp := VecOp.vxor }
        is (Funct3Logic.NOT) { vecOp := VecOp.vnot }
        is (Funct3Logic.OR)  { vecOp := VecOp.vor  }
        is (Funct3Logic.AND) { vecOp := VecOp.vand }
      }
    }
    is (OpFamily.VALU_REDUCE) {
      switch (f3) {
        is (Funct3Reduce.SUM)  { vecOp := VecOp.vsum  }
        is (Funct3Reduce.RMAX) { vecOp := VecOp.vrmax }
        is (Funct3Reduce.RMIN) { vecOp := VecOp.vrmin }
        is (Funct3Reduce.RAND) { vecOp := VecOp.vrand }
        is (Funct3Reduce.ROR)  { vecOp := VecOp.vror  }
        is (Funct3Reduce.RXOR) { vecOp := VecOp.vrxor }
      }
    }
    is (OpFamily.VALU_LUT) {
      // vlut (funct3=0/1): R-type lookup. Bank A (0) or B (1) via funct3[0],
      //   propagated as round[0] in the decoded bundle.
      // vsetlut (funct3=4/5): I-type segment write. Bank A (4) or B (5).
      //   imm carries the segment index; no register-file write.
      // funct3 2, 3, 6, 7: reserved — flag as illegal.
      switch (f3) {
        is (Funct3Lut.VLUT_A)    { vecOp := VecOp.vlut    }
        is (Funct3Lut.VLUT_B)    { vecOp := VecOp.vlut    }
        is (Funct3Lut.VSETLUT_A) { vecOp := VecOp.vsetlut }
        is (Funct3Lut.VSETLUT_B) { vecOp := VecOp.vsetlut }
      }
      // illegal: reserved funct3 values 2, 3, 6, 7
      when (f3 === 2.U || f3 === 3.U || f3 === 6.U || f3 === 7.U) {
        f3Valid := false.B
      }
    }
    is (OpFamily.VALU_CVT) {
      // funct3 = dst format; funct7[2:0] = src format
      val dst = f3
      val src = f7CvtSrc
      val bf8 = f7Bf8
      // decode into VecOp
      vecOp := MuxCase(VecOp.vadd, Seq(
        (dst === FmtCode.S8  && src === FmtCode.S32)  -> VecOp.vcvt_s8_s32,
        (dst === FmtCode.S32 && src === FmtCode.S8)   -> VecOp.vcvt_s32_s8,
        (dst === FmtCode.S32 && src === FmtCode.F32)  -> VecOp.vcvt_s32_f32,
        (dst === FmtCode.F32 && src === FmtCode.S32)  -> VecOp.vcvt_f32_s32,
        (dst === FmtCode.F32 && src === FmtCode.S8)   -> VecOp.vcvt_f32_s8,
        (dst === FmtCode.S8  && src === FmtCode.F32)  -> VecOp.vcvt_s8_f32,
        (dst === FmtCode.F32 && src === FmtCode.BF16) -> VecOp.vcvt_f32_bf16,
        (dst === FmtCode.BF16 && src === FmtCode.F32) -> VecOp.vcvt_bf16_f32,
        (dst === FmtCode.F32 && src === FmtCode.BF8)  -> VecOp.vcvt_f32_bf8,
        (dst === FmtCode.BF8 && src === FmtCode.F32)  -> VecOp.vcvt_bf8_f32,
        (dst === FmtCode.S16 && src === FmtCode.S32)  -> VecOp.vcvt_s16_s32,
        (dst === FmtCode.S32 && src === FmtCode.S16)  -> VecOp.vcvt_s32_s16,
      ))
      // illegal: same src and dst
      when (dst === src) { f3Valid := false.B }
    }
    is (OpFamily.VALU_BCAST) {
      switch (f3) {
        is (Funct3Bcast.REG) { vecOp := VecOp.vbcast_reg }
        is (Funct3Bcast.IMM) { vecOp := VecOp.vbcast_imm }
        // default: f3Valid = true but vecOp harmless; non-listed values not hit via safe
      }
    }
    is (OpFamily.VALU_FP) {
      switch (f3) {
        is (Funct3Fp.FADD) { vecOp := VecOp.vfadd }
        is (Funct3Fp.FSUB) { vecOp := VecOp.vfsub }
        is (Funct3Fp.FMUL) { vecOp := VecOp.vfmul }
        is (Funct3Fp.FNEG) { vecOp := VecOp.vfneg }
        is (Funct3Fp.FABS) { vecOp := VecOp.vfabs }
        is (Funct3Fp.FMAX) { vecOp := VecOp.vfmax }
        is (Funct3Fp.FMIN) { vecOp := VecOp.vfmin }
      }
    }
    is (OpFamily.VALU_FP_FMA) {
      switch (f3) {
        is (Funct3Fma.FMA)  { vecOp := VecOp.vfma  }
        is (Funct3Fma.FMS)  { vecOp := VecOp.vfms  }
        is (Funct3Fma.NFMA) { vecOp := VecOp.vnfma }
        is (Funct3Fma.NFMS) { vecOp := VecOp.vnfms }
      }
    }
    is (OpFamily.VALU_MOV) {
      switch (f3) {
        is (Funct3Mov.MOV)  { vecOp := VecOp.vmov  }
        is (Funct3Mov.MOVI) { vecOp := VecOp.vmovi }
        is (Funct3Mov.MOVH) { vecOp := VecOp.vmovh }
      }
    }
    // MMA, LD, ST, NOP: vecOp stays at default (not used)
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
  // vsetlut (I-format): reads from a VR source register → force VR width so
  // the backend routes in_a_vr correctly.
  when (family === OpFamily.VALU_LUT &&
        (f3 === Funct3Lut.VSETLUT_A || f3 === Funct3Lut.VSETLUT_B)) {
    width := 2.U  // VR
  }
  // Width bits are repurposed for src format in CVT family; skip width check for CVT.
  val widthIllegal = (f7Width === 3.U) &&
    (family =/= OpFamily.VALU_FP) &&
    (family =/= OpFamily.VALU_FP_FMA) &&
    (family =/= OpFamily.VALU_CVT)

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

  val dtypeIllegal = (f7Dtype === 3.U)

  // ---------- MMA control ----------
  val mmaKeep  = WireDefault(false.B)
  val mmaLast  = WireDefault(false.B)
  val mmaReset = WireDefault(false.B)
  when (family === OpFamily.MMA) {
    switch (f3) {
      is (Funct3Mma.MMA)       { mmaKeep  := f7Sat.asBool }   // reuse sat bit for keep
      is (Funct3Mma.MMA_LAST)  { mmaLast  := true.B }
      is (Funct3Mma.MMA_RESET) { mmaReset := true.B }
    }
  }

  // ---------- Illegal detection ----------
  val illegal = WireDefault(false.B)
  when (!familyOK)    { illegal := true.B }
  when (!f3Valid)     { illegal := true.B }
  when (widthIllegal) { illegal := true.B }
  when (dtypeIllegal) { illegal := true.B }

  // ---------- Drive outputs ----------
  io.illegal := illegal

  io.decoded.family    := family
  io.decoded.rd        := rdBits
  io.decoded.rs1       := rs1Bits
  io.decoded.rs2       := rs2Bits
  io.decoded.mem_width := f3

  io.decoded.mma_keep  := mmaKeep
  io.decoded.mma_last  := mmaLast
  io.decoded.mma_reset := mmaReset

  // VALU control bundle
  io.decoded.valu.op       := vecOp
  io.decoded.valu.regCls    := width
  io.decoded.valu.dtype    := dtype
  // For CVT, sat bit is at funct7[3] not funct7[4]
  io.decoded.valu.saturate := Mux(family === OpFamily.VALU_CVT, f7CvtSat.asBool, f7Sat.asBool)
  // For LUT ops, round[0] carries the bank select (taken from funct3[0]):
  //   vlut.A (f3=0) → round=0, vlut.B (f3=1) → round=1
  //   vsetlut.A (f3=4) → round=0, vsetlut.B (f3=5) → round=1
  io.decoded.valu.round    := Mux(
    family === OpFamily.VALU_FP_FMA,
    rndS,
    Mux(family === OpFamily.VALU_CVT, f7CvtRnd,
      Mux(family === OpFamily.VALU_LUT, Cat(0.U(1.W), f3(0)),
        f7Round))
  )
  io.decoded.valu.rs3_idx  := rs3Bits
  io.decoded.valu.imm      := immI

  // ---------- LD / ST / gather / tile / scatter / tile.cfg decode ----------
  val isLdFamily = (family === OpFamily.LD)
  val isStFamily = (family === OpFamily.ST)

  // funct3=6 sub-mode: USE_TILE_CNT bit (funct7[2])
  val useTileCnt = f7(Funct7Gather.USE_TILE_CNT).asBool

  // Standard contiguous LD: funct3 = 3/4/5
  io.decoded.is_ld := isLdFamily &&
    (f3 === Funct3Mem.VX_VEC || f3 === Funct3Mem.VE_VEC || f3 === Funct3Mem.VR_VEC)

  // Standard contiguous ST: funct3 = 3/4/5
  io.decoded.is_st := isStFamily &&
    (f3 === Funct3Mem.VX_VEC || f3 === Funct3Mem.VE_VEC || f3 === Funct3Mem.VR_VEC)

  // ld.gather (LD, funct3=6, funct7[USE_TILE_CNT]=0)
  io.decoded.is_gather := isLdFamily && (f3 === Funct3Mem.GATHER) && !useTileCnt

  // ld.tile  (LD, funct3=6, funct7[USE_TILE_CNT]=1)
  io.decoded.is_tile     := isLdFamily && (f3 === Funct3Mem.GATHER) && useTileCnt
  io.decoded.tile_zpad   := f7(Funct7Gather.ZERO_PAD).asBool
  io.decoded.tile_trans  := f7(Funct7Gather.TRANSPOSED).asBool
  io.decoded.tile_autoinc := f7(Funct7Gather.AUTO_INC).asBool

  // st.scatter (ST, funct3=6)
  io.decoded.is_scatter := isStFamily && (f3 === Funct3Mem.GATHER)

  // tile.cfg (LD, funct3=7); wr_sel from imm[2:0]
  io.decoded.is_tilecfg  := isLdFamily && (f3 === Funct3Mem.TILE_CFG)
  io.decoded.tilecfg_sel := immI(2, 0).asUInt
}
