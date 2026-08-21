// See README.md for license details.
// Tests for InstrDecoder: encode 32-bit words with NpuAssembler, verify
// decoded fields.  Table-driven across all families × funct3.

package isa

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec
import isa.micro_op._

class InstrDecoderSpec extends AnyFlatSpec {
  import NpuAssembler._

  // Width constants (0=VX, 1=VE, 2=VR) — matches VecWidth enum values
  val WX = 0; val WE = 1; val WR = 2

  // Peek a ChiselEnum field via peekValue() (testableData[T <: Data] in
  // PeekPokeAPI); peek() only exists for SInt/UInt/Bool in Chisel 6.7.
  private def enumLit(dut: InstrDecoder, field: chisel3.Data): BigInt =
    field.peekValue().asBigInt

  def check(dut: InstrDecoder, instr: Int,
            expFamily: OpFamily.Type,
            expOp: VecOp.Type,
            expWidth: Int = WX,   // use WX/WE/WR constants above
            expSat: Boolean = false,
            expRound: Int = RNE,
            expRd: Int = 0, expRs1: Int = 0, expRs2: Int = 0,
            expMemWidth: Int = -1, expMemOff: Int = -1,
            expectIllegal: Boolean = false): Unit = {
    dut.io.instr.poke((instr.toLong & 0xFFFFFFFFL).U)
    dut.clock.step(0)  // combinational
    if (expectIllegal) {
      assert(dut.io.illegal.peek().litToBoolean, s"Expected illegal for 0x${instr.toHexString}")
    } else {
      assert(!dut.io.illegal.peek().litToBoolean, s"Unexpected illegal for 0x${instr.toHexString}")
      val gotFamily = enumLit(dut, dut.io.decoded.family)
      assert(gotFamily == expFamily.litValue,
        s"family mismatch for 0x${instr.toHexString}: got $gotFamily want ${expFamily.litValue}")
      // Check all UInt decoded fields
      dut.io.decoded.valu.regCls.expect(expWidth.U)
      dut.io.decoded.valu.saturate.expect(expSat.B)
      dut.io.decoded.valu.round.expect(expRound.U)
      dut.io.decoded.rd.expect(expRd.U)
      dut.io.decoded.rs1.expect(expRs1.U)
      dut.io.decoded.rs2.expect(expRs2.U)
      if (expMemWidth >= 0) dut.io.decoded.mem_width.expect(expMemWidth.U)
      if (expMemOff >= 0)   dut.io.decoded.mem_off.expect(expMemOff.U)
    }
  }

  // ==========================================================================
  // NOP
  // ==========================================================================
  "InstrDecoder" should "decode NOP" in {
    simulate(new InstrDecoder) { dut =>
      check(dut, nop, OpFamily.NOP, VecOp.vadd, expRd=0, expRs1=0, expRs2=0)
    }
  }

  // ==========================================================================
  // VALU_ARITH
  // ==========================================================================
  "InstrDecoder" should "decode vadd VX" in {
    simulate(new InstrDecoder) { dut =>
      check(dut, vadd(rd=1, rs1=2, rs2=3, width=VX),
            OpFamily.VALU_ARITH, VecOp.vadd,
            expWidth=WX, expRd=1, expRs1=2, expRs2=3)
    }
  }

  "InstrDecoder" should "decode vadd VE saturate" in {
    simulate(new InstrDecoder) { dut =>
      check(dut, vadd(rd=4, rs1=5, rs2=6, width=VE, sat=true),
            OpFamily.VALU_ARITH, VecOp.vadd,
            expWidth=WE, expSat=true, expRd=4, expRs1=5, expRs2=6)
    }
  }

  "InstrDecoder" should "decode vadd VR" in {
    simulate(new InstrDecoder) { dut =>
      check(dut, vadd(rd=0, rs1=0, rs2=1, width=VR),
            OpFamily.VALU_ARITH, VecOp.vadd, expWidth=WR, expRs2=1)
    }
  }

  "InstrDecoder" should "decode vsub" in {
    simulate(new InstrDecoder) { dut =>
      check(dut, vsub(rd=1, rs1=2, rs2=3),
            OpFamily.VALU_ARITH, VecOp.vsub, expRd=1, expRs1=2, expRs2=3)
    }
  }

  "InstrDecoder" should "decode vmul" in {
    simulate(new InstrDecoder) { dut =>
      check(dut, vmul(rd=1, rs1=2, rs2=3),
            OpFamily.VALU_ARITH, VecOp.vmul, expRd=1, expRs1=2, expRs2=3)
    }
  }

  "InstrDecoder" should "decode vneg/vabs" in {
    simulate(new InstrDecoder) { dut =>
      check(dut, vneg(rd=0, rs1=1), OpFamily.VALU_ARITH, VecOp.vneg, expRs1=1)
      check(dut, vabs(rd=0, rs1=1), OpFamily.VALU_ARITH, VecOp.vabs, expRs1=1)
    }
  }

  "InstrDecoder" should "decode vmax/vmin/vrsub" in {
    simulate(new InstrDecoder) { dut =>
      check(dut, vmax(rd=0, rs1=1, rs2=2), OpFamily.VALU_ARITH, VecOp.vmax, expRs1=1, expRs2=2)
      check(dut, vmin(rd=0, rs1=1, rs2=2), OpFamily.VALU_ARITH, VecOp.vmin, expRs1=1, expRs2=2)
      check(dut, vrsub(rd=0, rs1=1, rs2=2), OpFamily.VALU_ARITH, VecOp.vrsub, expRs1=1, expRs2=2)
    }
  }

  // ==========================================================================
  // VALU_LOGIC
  // ==========================================================================
  "InstrDecoder" should "decode vand/vor/vxor/vnot" in {
    simulate(new InstrDecoder) { dut =>
      check(dut, vand(rd=1, rs1=2, rs2=3), OpFamily.VALU_LOGIC, VecOp.vand, expRd=1, expRs1=2, expRs2=3)
      check(dut, vor (rd=1, rs1=2, rs2=3), OpFamily.VALU_LOGIC, VecOp.vor,  expRd=1, expRs1=2, expRs2=3)
      check(dut, vxor(rd=1, rs1=2, rs2=3), OpFamily.VALU_LOGIC, VecOp.vxor, expRd=1, expRs1=2, expRs2=3)
      check(dut, vnot(rd=1, rs1=2),         OpFamily.VALU_LOGIC, VecOp.vnot, expRd=1, expRs1=2)
    }
  }

  "InstrDecoder" should "decode vsll/vsrl/vsra/vrol" in {
    simulate(new InstrDecoder) { dut =>
      check(dut, vsll(rd=0, rs1=1, rs2=2), OpFamily.VALU_LOGIC, VecOp.vsll, expRs1=1, expRs2=2)
      check(dut, vsrl(rd=0, rs1=1, rs2=2), OpFamily.VALU_LOGIC, VecOp.vsrl, expRs1=1, expRs2=2)
      check(dut, vsra(rd=0, rs1=1, rs2=2), OpFamily.VALU_LOGIC, VecOp.vsra, expRs1=1, expRs2=2)
      check(dut, vrol(rd=0, rs1=1, rs2=2), OpFamily.VALU_LOGIC, VecOp.vrol, expRs1=1, expRs2=2)
    }
  }

  // ==========================================================================
  // VALU_REDUCE
  // ==========================================================================
  "InstrDecoder" should "decode vsum/vrmax on VX" in {
    simulate(new InstrDecoder) { dut =>
      check(dut, vsum(rd=0, rs1=1),  OpFamily.VALU_REDUCE, VecOp.vsum,  expRs1=1)
      check(dut, vrmax(rd=0, rs1=1), OpFamily.VALU_REDUCE, VecOp.vrmax, expRs1=1)
    }
  }

  "InstrDecoder" should "flag reserved VALU_REDUCE funct3 (6,7) as illegal" in {
    simulate(new InstrDecoder) { dut =>
      for (f3 <- Seq(6, 7)) {
        val instr = encR(0x12, f3, f7(VX), 0, 1, 0)
        dut.io.instr.poke((instr.toLong & 0xFFFFFFFFL).U)
        dut.clock.step(0)
        assert(dut.io.illegal.peek().litToBoolean, s"VALU_REDUCE funct3=$f3 should be illegal")
      }
    }
  }

  // ==========================================================================
  // VALU_LUT
  // ==========================================================================
  "InstrDecoder" should "decode vlut (bank A and bank B)" in {
    simulate(new InstrDecoder) { dut =>
      // vlut bank A: funct3=0, round[0]=0
      check(dut, vlut(rd=2, rs1=1, bank=0), OpFamily.VALU_LUT, VecOp.vlut,
            expWidth=WX, expRd=2, expRs1=1)
      dut.io.decoded.valu.round.expect(0.U)  // bank A → round[0]=0

      // vlut bank B: funct3=1, round[0]=1
      check(dut, vlut(rd=2, rs1=1, bank=1), OpFamily.VALU_LUT, VecOp.vlut,
            expWidth=WX, expRound=1, expRd=2, expRs1=1)
      dut.io.decoded.valu.round.expect(1.U)  // bank B → round[0]=1
    }
  }

  "InstrDecoder" should "decode vsetlut (bank A and bank B)" in {
    simulate(new InstrDecoder) { dut =>
      // vsetlut bank A: funct3=4, I-type, imm=segment, width=VR
      dut.io.instr.poke((vsetlut(rs1=3, segment=2, bank=0).toLong & 0xFFFFFFFFL).U)
      dut.clock.step(0)
      assert(!dut.io.illegal.peek().litToBoolean, "vsetlut bank A must not be illegal")
      dut.io.decoded.valu.round.expect(0.U)          // bank A
      dut.io.decoded.valu.imm.expect(2.S)            // segment=2
      dut.io.decoded.rs1.expect(3.U)                 // rs1=3

      // vsetlut bank B: funct3=5
      dut.io.instr.poke((vsetlut(rs1=5, segment=7, bank=1).toLong & 0xFFFFFFFFL).U)
      dut.clock.step(0)
      assert(!dut.io.illegal.peek().litToBoolean, "vsetlut bank B must not be illegal")
      dut.io.decoded.valu.round.expect(1.U)          // bank B
      dut.io.decoded.valu.imm.expect(7.S)            // segment=7
      dut.io.decoded.rs1.expect(5.U)                 // rs1=5
    }
  }

  "InstrDecoder" should "flag reserved VALU_LUT funct3 values as illegal" in {
    simulate(new InstrDecoder) { dut =>
      // funct3=2 and funct3=3 are reserved
      for (f3 <- Seq(2, 3, 6, 7)) {
        val instr = encR(0x13, f3, f7(VX), 0, 1, 0)
        dut.io.instr.poke((instr.toLong & 0xFFFFFFFFL).U)
        dut.clock.step(0)
        assert(dut.io.illegal.peek().litToBoolean,
          s"VALU_LUT funct3=$f3 should be illegal")
      }
    }
  }

  // ==========================================================================
  // VALU_CVT
  // ==========================================================================
  "InstrDecoder" should "decode vcvt s32->f32" in {
    simulate(new InstrDecoder) { dut =>
      check(dut, vcvt_f32_s32(rd=1, rs1=0),
            OpFamily.VALU_CVT, VecOp.vcvt_f32_s32, expWidth=WR, expRd=1)
    }
  }

  "InstrDecoder" should "decode vcvt f32->s8 with saturation" in {
    simulate(new InstrDecoder) { dut =>
      check(dut, vcvt_s8_f32(rd=31, rs1=0, sat=true),
            OpFamily.VALU_CVT, VecOp.vcvt_s8_f32, expWidth=WR, expSat=true, expRd=31)
    }
  }

  "InstrDecoder" should "decode vcvt bf16 conversions" in {
    simulate(new InstrDecoder) { dut =>
      check(dut, vcvt_f32_bf16(rd=0, rs1=1), OpFamily.VALU_CVT, VecOp.vcvt_f32_bf16, expWidth=WR, expRs1=1)
      check(dut, vcvt_bf16_f32(rd=0, rs1=1), OpFamily.VALU_CVT, VecOp.vcvt_bf16_f32, expWidth=WR, expRs1=1)
    }
  }

  "InstrDecoder" should "decode vcvt bf8 conversions (E4M3 and E5M2)" in {
    simulate(new InstrDecoder) { dut =>
      dut.io.instr.poke((vcvt_bf8_f32(rd=0, rs1=1, e5m2=false).toLong & 0xFFFFFFFFL).U)
      dut.clock.step(0)
      assert(!dut.io.illegal.peek().litToBoolean, "E4M3 vcvt should not be illegal")

      dut.io.instr.poke((vcvt_bf8_f32(rd=0, rs1=1, e5m2=true).toLong & 0xFFFFFFFFL).U)
      dut.clock.step(0)
      assert(!dut.io.illegal.peek().litToBoolean, "E5M2 vcvt should not be illegal")
    }
  }

  "InstrDecoder" should "assert illegal for vcvt same src==dst" in {
    simulate(new InstrDecoder) { dut =>
      val illegalCvt = encR(0x14, 3, f7Cvt(srcFmt=3), 0, 0, 0)
      dut.io.instr.poke((illegalCvt.toLong & 0xFFFFFFFFL).U)
      dut.clock.step(0)
      assert(dut.io.illegal.peek().litToBoolean, "Same src==dst cvt should be illegal")
    }
  }

  "InstrDecoder" should "decode rounding mode" in {
    simulate(new InstrDecoder) { dut =>
      val instrRTZ = vcvt_s8_f32(rd=0, rs1=1, round=RTZ)
      dut.io.instr.poke((instrRTZ.toLong & 0xFFFFFFFFL).U)
      dut.clock.step(0)
      dut.io.decoded.valu.round.expect(RTZ.U)

      val instrFloor = vcvt_s8_f32(rd=0, rs1=1, round=FLOOR)
      dut.io.instr.poke((instrFloor.toLong & 0xFFFFFFFFL).U)
      dut.clock.step(0)
      dut.io.decoded.valu.round.expect(FLOOR.U)
    }
  }

  // ==========================================================================
  // VALU_BCAST
  // ==========================================================================
  "InstrDecoder" should "decode vbcast reg and imm" in {
    simulate(new InstrDecoder) { dut =>
      check(dut, vbcast(rd=2, rs1=0, width=VR),
            OpFamily.VALU_BCAST, VecOp.vbcast_reg, expWidth=WR, expRd=2)
      // I-format: bits[24:20] carry imm[9:5]. imm=42=0b101010, so imm[4:0]=10.
      check(dut, vbcastImm(rd=3, imm=42, width=VX),
            OpFamily.VALU_BCAST, VecOp.vbcast_imm, expWidth=WX, expRd=3, expRs2=10)
    }
  }

  "InstrDecoder" should "flag reserved VALU_BCAST funct3 (2..7) as illegal" in {
    simulate(new InstrDecoder) { dut =>
      for (f3 <- 2 to 7) {
        val instr = encR(0x15, f3, f7(VX), 0, 1, 0)
        dut.io.instr.poke((instr.toLong & 0xFFFFFFFFL).U)
        dut.clock.step(0)
        assert(dut.io.illegal.peek().litToBoolean, s"VALU_BCAST funct3=$f3 should be illegal")
      }
    }
  }

  // ==========================================================================
  // VALU_FP
  // ==========================================================================
  "InstrDecoder" should "decode FP32 arith ops" in {
    simulate(new InstrDecoder) { dut =>
      check(dut, vfadd(rd=0, rs1=1, rs2=2), OpFamily.VALU_FP, VecOp.vfadd, expWidth=WR, expRs1=1, expRs2=2)
      check(dut, vfsub(rd=0, rs1=1, rs2=2), OpFamily.VALU_FP, VecOp.vfsub, expWidth=WR, expRs1=1, expRs2=2)
      check(dut, vfmul(rd=0, rs1=1, rs2=2), OpFamily.VALU_FP, VecOp.vfmul, expWidth=WR, expRs1=1, expRs2=2)
      check(dut, vfneg(rd=0, rs1=1),         OpFamily.VALU_FP, VecOp.vfneg, expWidth=WR, expRs1=1)
      check(dut, vfabs(rd=0, rs1=1),         OpFamily.VALU_FP, VecOp.vfabs, expWidth=WR, expRs1=1)
      check(dut, vfmax(rd=0, rs1=1, rs2=2),  OpFamily.VALU_FP, VecOp.vfmax, expWidth=WR, expRs1=1, expRs2=2)
      check(dut, vfmin(rd=0, rs1=1, rs2=2),  OpFamily.VALU_FP, VecOp.vfmin, expWidth=WR, expRs1=1, expRs2=2)
    }
  }

  "InstrDecoder" should "flag reserved VALU_FP funct3 (7) as illegal" in {
    simulate(new InstrDecoder) { dut =>
      val instr = encR(0x16, 7, f7(VR, dtype=FP), 0, 1, 2)
      dut.io.instr.poke((instr.toLong & 0xFFFFFFFFL).U)
      dut.clock.step(0)
      assert(dut.io.illegal.peek().litToBoolean, "VALU_FP funct3=7 should be illegal")
    }
  }

  // ==========================================================================
  // VALU_FP_FMA
  // ==========================================================================
  "InstrDecoder" should "decode vfma (S-format)" in {
    simulate(new InstrDecoder) { dut =>
      dut.io.instr.poke((vfma(rd=0, rs1=1, rs2=2, rs3=3).toLong & 0xFFFFFFFFL).U)
      dut.clock.step(0)
      assert(!dut.io.illegal.peek().litToBoolean)
      dut.io.decoded.valu.rs3_idx.expect(3.U)
    }
  }

  "InstrDecoder" should "flag reserved VALU_FP_FMA funct3 (4..7) as illegal" in {
    simulate(new InstrDecoder) { dut =>
      for (f3 <- 4 to 7) {
        val instr = encS(0x17, f3, 0, 1, 2, 3)
        dut.io.instr.poke((instr.toLong & 0xFFFFFFFFL).U)
        dut.clock.step(0)
        assert(dut.io.illegal.peek().litToBoolean, s"VALU_FP_FMA funct3=$f3 should be illegal")
      }
    }
  }

  // ==========================================================================
  // VALU_MOV
  // ==========================================================================
  "InstrDecoder" should "decode vmov/vmovi/vmovh" in {
    simulate(new InstrDecoder) { dut =>
      check(dut, vmov(rd=1, rs1=2), OpFamily.VALU_MOV, VecOp.vmov, expRd=1, expRs1=2)
      // I-format: rs2 field carries imm[4:0]
      check(dut, vmovi(rd=1, imm=5), OpFamily.VALU_MOV, VecOp.vmovi, expRd=1, expRs2=5)
      check(dut, vmovh(rd=1, imm=5), OpFamily.VALU_MOV, VecOp.vmovh, expRd=1, expRs2=5)
    }
  }

  "InstrDecoder" should "flag reserved VALU_MOV funct3 (3..7) as illegal" in {
    simulate(new InstrDecoder) { dut =>
      for (f3 <- 3 to 7) {
        val instr = encR(0x18, f3, f7(VX), 0, 1, 0)
        dut.io.instr.poke((instr.toLong & 0xFFFFFFFFL).U)
        dut.clock.step(0)
        assert(dut.io.illegal.peek().litToBoolean, s"VALU_MOV funct3=$f3 should be illegal")
      }
    }
  }

  // ==========================================================================
  // MMA
  // ==========================================================================
  "InstrDecoder" should "decode mma with keep" in {
    simulate(new InstrDecoder) { dut =>
      dut.io.instr.poke((mma(rd=0, rs1=1, rs2=2, keep=true).toLong & 0xFFFFFFFFL).U)
      dut.clock.step(0)
      assert(!dut.io.illegal.peek().litToBoolean)
      dut.io.decoded.mma_keep.expect(true.B)
      dut.io.decoded.mma_last.expect(false.B)
      dut.io.decoded.mma_reset.expect(false.B)
    }
  }

  "InstrDecoder" should "decode mma with keep=false (PE reset feed)" in {
    simulate(new InstrDecoder) { dut =>
      dut.io.instr.poke((mma(rd=0, rs1=1, rs2=2, keep=false).toLong & 0xFFFFFFFFL).U)
      dut.clock.step(0)
      assert(!dut.io.illegal.peek().litToBoolean)
      dut.io.decoded.mma_keep.expect(false.B)
    }
  }

  "InstrDecoder" should "decode mma.last honouring funct7[4] keep" in {
    simulate(new InstrDecoder) { dut =>
      // keep=true: accumulate-then-drain
      dut.io.instr.poke((mmaLast(rd=0, rs1=1, rs2=2, keep=true).toLong & 0xFFFFFFFFL).U)
      dut.clock.step(0)
      assert(!dut.io.illegal.peek().litToBoolean)
      dut.io.decoded.mma_last.expect(true.B)
      dut.io.decoded.mma_keep.expect(true.B)

      // keep=false: reset-then-drain
      dut.io.instr.poke((mmaLast(rd=0, rs1=1, rs2=2, keep=false).toLong & 0xFFFFFFFFL).U)
      dut.clock.step(0)
      assert(!dut.io.illegal.peek().litToBoolean)
      dut.io.decoded.mma_last.expect(true.B)
      dut.io.decoded.mma_keep.expect(false.B)
    }
  }

  "InstrDecoder" should "decode mma.reset" in {
    simulate(new InstrDecoder) { dut =>
      dut.io.instr.poke((mmaReset(rd=0, rs1=1, rs2=2).toLong & 0xFFFFFFFFL).U)
      dut.clock.step(0)
      assert(!dut.io.illegal.peek().litToBoolean)
      dut.io.decoded.mma_reset.expect(true.B)
      dut.io.decoded.mma_last.expect(false.B)
      dut.io.decoded.mma_keep.expect(false.B)
    }
  }

  "InstrDecoder" should "flag reserved MMA funct3 (3..7) as illegal" in {
    simulate(new InstrDecoder) { dut =>
      for (f3 <- 3 to 7) {
        val instr = encR(0x03, f3, f7(VR), 0, 1, 2)
        dut.io.instr.poke((instr.toLong & 0xFFFFFFFFL).U)
        dut.clock.step(0)
        assert(dut.io.illegal.peek().litToBoolean, s"MMA funct3=$f3 should be illegal")
      }
    }
  }

  // ==========================================================================
  // LD / ST (RVV-aligned vle/vse)
  // ==========================================================================
  "InstrDecoder" should "decode vle8/vle16/vle32 loads" in {
    simulate(new InstrDecoder) { dut =>
      // I-format: rs2 field carries imm[4:0]
      check(dut, vle8 (rd=5,  sect=SECT_A, off=0x123),
            OpFamily.LD, VecOp.vadd, expRd=5, expRs1=SECT_A, expRs2=0x123&0x1F, expMemWidth=0, expMemOff=0x123)
      check(dut, vle16(rd=3,  sect=SECT_B, off=0x400),
            OpFamily.LD, VecOp.vadd, expRd=3, expRs1=SECT_B, expRs2=0x400&0x1F, expMemWidth=1, expMemOff=0x400)
      check(dut, vle32(rd=0,  sect=SECT_OUT, off=0xF80),
            OpFamily.LD, VecOp.vadd, expRd=0, expRs1=SECT_OUT, expRs2=0xF80&0x1F, expMemWidth=2, expMemOff=0xF80)
    }
  }

  "InstrDecoder" should "decode vse8/vse16/vse32 stores" in {
    simulate(new InstrDecoder) { dut =>
      check(dut, vse8 (src=7,  sect=SECT_A, off=0),
            OpFamily.ST, VecOp.vadd, expRd=7, expRs1=SECT_A, expRs2=0, expMemWidth=0, expMemOff=0)
      check(dut, vse16(src=4,  sect=SECT_ACCUM, off=0),
            OpFamily.ST, VecOp.vadd, expRd=4, expRs1=SECT_ACCUM, expRs2=0, expMemWidth=1, expMemOff=0)
      check(dut, vse32(src=2,  sect=SECT_OUT, off=0x080),
            OpFamily.ST, VecOp.vadd, expRd=2, expRs1=SECT_OUT, expRs2=0x080&0x1F, expMemWidth=2, expMemOff=0x080)
    }
  }

  "InstrDecoder" should "flag reserved LD/ST funct3 (3..7) as illegal" in {
    simulate(new InstrDecoder) { dut =>
      for (op <- Seq(0x07, 0x27); f3 <- 3 to 7) {
        val instr = encI(op, f3, 1, 0, 0)
        dut.io.instr.poke((instr.toLong & 0xFFFFFFFFL).U)
        dut.clock.step(0)
        assert(dut.io.illegal.peek().litToBoolean, s"opcode=0x$op funct3=$f3 should be illegal")
      }
    }
  }

  // ==========================================================================
  // Global illegal detection
  // ==========================================================================
  "InstrDecoder" should "assert illegal for reserved opcode" in {
    simulate(new InstrDecoder) { dut =>
      // 0x01/0x02 (legacy LD/ST) are now reserved
      dut.io.instr.poke(0x01.U)
      dut.clock.step(0)
      assert(dut.io.illegal.peek().litToBoolean, "Legacy LD opcode 0x01 should be illegal")
      dut.io.instr.poke(0x02.U)
      dut.clock.step(0)
      assert(dut.io.illegal.peek().litToBoolean, "Legacy ST opcode 0x02 should be illegal")
      // fully reserved opcode
      dut.io.instr.poke(0x7F.U)
      dut.clock.step(0)
      assert(dut.io.illegal.peek().litToBoolean, "Reserved opcode 0x7F should be illegal")
    }
  }

  "InstrDecoder" should "flag reserved width (funct7[1:0]=3) as illegal" in {
    simulate(new InstrDecoder) { dut =>
      val instr = encR(0x10, 0, f7(width=3), 0, 1, 2)
      dut.io.instr.poke((instr.toLong & 0xFFFFFFFFL).U)
      dut.clock.step(0)
      assert(dut.io.illegal.peek().litToBoolean, "width=3 should be illegal")
    }
  }

  "InstrDecoder" should "flag reserved dtype (funct7[6:5]=3) as illegal" in {
    simulate(new InstrDecoder) { dut =>
      val instr = encR(0x10, 0, f7(dtype=3), 0, 1, 2)
      dut.io.instr.poke((instr.toLong & 0xFFFFFFFFL).U)
      dut.clock.step(0)
      assert(dut.io.illegal.peek().litToBoolean, "dtype=3 should be illegal")
    }
  }
}
