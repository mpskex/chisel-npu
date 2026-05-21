// See README.md for license details.
// Tests for vbcast (vcast) broadcast ops: reg-lane0 and immediate.

package alu.vec

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec
import isa.micro_op._

class VALUCastSpec extends AnyFlatSpec {
  val K = 8; val N = 8
  // Width constants: 0=VX, 1=VE, 2=VR (matches VecWidth enum)
  val WX = 0; val WE = 1; val WR = 2

  def pokeCtrl(dut: VALU, op: VecOp.Type, width: Int, imm: Int = 0): Unit = {
    dut.io.ctrl.op.poke(op)
    dut.io.ctrl.regCls.poke(width.U)
    dut.io.ctrl.dtype.poke(VecDType.S8C4)
    dut.io.ctrl.saturate.poke(false.B)
    dut.io.ctrl.round.poke(0.U)
    dut.io.ctrl.rs3_idx.poke(0.U)
    dut.io.ctrl.imm.poke(imm.S)
  }

  def zeroInputs(dut: VALU): Unit = {
    for (i <- 0 until K) {
      dut.io.in_a_vx(i).poke(0.U); dut.io.in_b_vx(i).poke(0.U)
      dut.io.in_a_ve(i).poke(0.U); dut.io.in_b_ve(i).poke(0.U)
      dut.io.in_a_vr(i).poke(0.U); dut.io.in_b_vr(i).poke(0.U)
      dut.io.in_c_vr(i).poke(0.U)
    }
  }

  // ---- Sub-case helpers ------------------------------------------------------

  private def runBcastVxPos(dut: VALU): Unit = {
    zeroInputs(dut)
    val scalar = 42
    dut.io.in_a_vx(0).poke(scalar.U)
    for (i <- 1 until K) dut.io.in_a_vx(i).poke(0.U)
    pokeCtrl(dut, VecOp.vbcast_reg, WX)
    dut.clock.step()
    for (i <- 0 until K) {
      dut.io.out_vx(i).expect(scalar.U, s"vbcast_reg VX lane $i")
    }
  }

  private def runBcastVxNeg(dut: VALU): Unit = {
    zeroInputs(dut)
    // -5 as unsigned 8-bit = 251
    val scalar: Int = (-5) & 0xFF
    dut.io.in_a_vx(0).poke(scalar.U)
    for (i <- 1 until K) dut.io.in_a_vx(i).poke(127.U)  // should be overwritten
    pokeCtrl(dut, VecOp.vbcast_reg, WX)
    dut.clock.step()
    for (i <- 0 until K) {
      dut.io.out_vx(i).expect(scalar.U, s"vbcast_reg VX neg lane $i")
    }
  }

  private def runBcastVe(dut: VALU): Unit = {
    zeroInputs(dut)
    val scalar16: Int = 0xBEEF & 0xFFFF
    dut.io.in_a_ve(0).poke(scalar16.U)
    for (i <- 1 until K) dut.io.in_a_ve(i).poke(0xDEAD.U)
    pokeCtrl(dut, VecOp.vbcast_reg, WE)
    dut.clock.step()
    for (i <- 0 until K) {
      dut.io.out_ve(i).expect(scalar16.U, s"vbcast_reg VE lane $i")
    }
  }

  private def runBcastVr(dut: VALU): Unit = {
    zeroInputs(dut)
    val scalar32: Long = 0xDEADBEEFL
    dut.io.in_a_vr(0).poke(scalar32.U)
    for (i <- 1 until K) dut.io.in_a_vr(i).poke(0.U)
    pokeCtrl(dut, VecOp.vbcast_reg, WR)
    dut.clock.step()
    for (i <- 0 until K) {
      dut.io.out_vr(i).expect(scalar32.U, s"vbcast_reg VR lane $i")
    }
  }

  private def runBcastImmPos(dut: VALU): Unit = {
    zeroInputs(dut)
    val imm = 42  // positive
    pokeCtrl(dut, VecOp.vbcast_imm, WX, imm = imm)
    dut.clock.step()
    val expected = (imm & 0xFF).U
    for (i <- 0 until K) {
      dut.io.out_vx(i).expect(expected, s"vbcast_imm VX pos lane $i")
    }
  }

  private def runBcastImmNeg(dut: VALU): Unit = {
    zeroInputs(dut)
    val imm = -5  // 12-bit: 0b111111111011 = 0xFFB
    pokeCtrl(dut, VecOp.vbcast_imm, WX, imm = imm)
    dut.clock.step()
    // sign-extended to N bits: -5 & 0xFF = 251
    val expected = ((-5) & 0xFF).U
    for (i <- 0 until K) {
      dut.io.out_vx(i).expect(expected, s"vbcast_imm VX neg lane $i")
    }
  }

  private def runBcastInvariant(dut: VALU): Unit = {
    zeroInputs(dut)
    for (v <- Seq(0, 1, 127, 255, 128)) {
      dut.io.in_a_vx(0).poke(v.U)
      for (i <- 1 until K) dut.io.in_a_vx(i).poke(99.U)
      pokeCtrl(dut, VecOp.vbcast_reg, WX)
      dut.clock.step()
      val lane0 = dut.io.out_vx(0).peek().litValue
      for (i <- 1 until K) {
        val laneI = dut.io.out_vx(i).peek().litValue
        assert(lane0 == laneI, s"broadcast invariant: lane0=$lane0 lane$i=$laneI for scalar=$v")
      }
    }
  }

  // ---- Coalesced test --------------------------------------------------------

  "VALU vbcast" should "pass all broadcast sub-cases" in {
    simulate(new VALU(K, N)) { dut =>
      withClue("vbcast VX positive: ")    { runBcastVxPos(dut)      }
      withClue("vbcast VX negative: ")    { runBcastVxNeg(dut)      }
      withClue("vbcast VE: ")             { runBcastVe(dut)         }
      withClue("vbcast VR: ")             { runBcastVr(dut)         }
      withClue("vbcast_imm positive: ")   { runBcastImmPos(dut)     }
      withClue("vbcast_imm negative: ")   { runBcastImmNeg(dut)     }
      withClue("broadcast invariant: ")   { runBcastInvariant(dut)  }
    }
  }
}
