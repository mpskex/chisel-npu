// See README.md for license details.

package alu.vec

import scala.util.Random
import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec
import isa.micro_op._
import isa.VecWidth

class VALUMinMaxSpec extends AnyFlatSpec {
  val N = 8; val K = 8
  val rand = new Random(0xCAFE)

  def pokeCtrl(dut: VALU, op: VecOp.Type): Unit = {
    dut.io.ctrl.op.poke(op); dut.io.ctrl.regCls.poke(0.U)
    dut.io.ctrl.dtype.poke(VecDType.S8C4); dut.io.ctrl.saturate.poke(false.B)
    dut.io.ctrl.round.poke(0.U); dut.io.ctrl.rs3_idx.poke(0.U); dut.io.ctrl.imm.poke(0.S)
  }

  def pokeAB(dut: VALU, a: Array[Int], b: Array[Int]): Unit = {
    for (i <- 0 until K) {
      dut.io.in_a_vx(i).poke((a(i) & 0xFF).U); dut.io.in_b_vx(i).poke((b(i) & 0xFF).U)
      dut.io.in_a_ve(i).poke(0.U); dut.io.in_b_ve(i).poke(0.U)
      dut.io.in_a_vr(i).poke(0.U); dut.io.in_b_vr(i).poke(0.U); dut.io.in_c_vr(i).poke(0.U)
    }
  }

  "VALU vmax" should "return elementwise maximum" in {
    simulate(new VALU(K, N)) { dut =>
      pokeCtrl(dut, VecOp.vmax)
      for (_ <- 0 until 64) {
        val a = Array.fill(K)(rand.between(-128, 128))
        val b = Array.fill(K)(rand.between(-128, 128))
        pokeAB(dut, a, b)
        dut.clock.step()
        for (i <- 0 until K) {
          val exp = (math.max(a(i), b(i)) & 0xFF).U
          dut.io.out_vx(i).expect(exp, s"vmax lane $i")
        }
      }
    }
  }

  "VALU vmax" should "handle boundary values" in {
    simulate(new VALU(K, N)) { dut =>
      pokeCtrl(dut, VecOp.vmax)
      val a = Array(-128, 127, 0, 0, -1, 1, -128, 127)
      val b = Array( 127,-128, 0, 0,  1,-1,  127,-128)
      pokeAB(dut, a, b)
      dut.clock.step()
      for (i <- 0 until K) {
        dut.io.out_vx(i).expect((math.max(a(i), b(i)) & 0xFF).U, s"vmax boundary lane $i")
      }
    }
  }

  "VALU vmin" should "return elementwise minimum" in {
    simulate(new VALU(K, N)) { dut =>
      pokeCtrl(dut, VecOp.vmin)
      for (_ <- 0 until 64) {
        val a = Array.fill(K)(rand.between(-128, 128))
        val b = Array.fill(K)(rand.between(-128, 128))
        pokeAB(dut, a, b)
        dut.clock.step()
        for (i <- 0 until K) {
          dut.io.out_vx(i).expect((math.min(a(i), b(i)) & 0xFF).U, s"vmin lane $i")
        }
      }
    }
  }
}
