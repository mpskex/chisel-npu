// See README.md for license details.

package alu.vec

import scala.util.Random
import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec
import isa.micro_op._
import isa.VecWidth

class VALUReduceSpec extends AnyFlatSpec {
  val N = 8; val K = 8
  val rand = new Random(0xF00D)

  def pokeCtrl(dut: VALU, op: VecOp.Type): Unit = {
    dut.io.ctrl.op.poke(op); dut.io.ctrl.regCls.poke(0.U)
    dut.io.ctrl.dtype.poke(VecDType.S8C4); dut.io.ctrl.saturate.poke(false.B)
    dut.io.ctrl.round.poke(0.U); dut.io.ctrl.rs3_idx.poke(0.U); dut.io.ctrl.imm.poke(0.S)
  }

  def pokeA(dut: VALU, a: Array[Int]): Unit = {
    for (i <- 0 until K) {
      dut.io.in_a_vx(i).poke((a(i) & 0xFF).U); dut.io.in_b_vx(i).poke(0.U)
      dut.io.in_a_ve(i).poke(0.U); dut.io.in_b_ve(i).poke(0.U)
      dut.io.in_a_vr(i).poke(0.U); dut.io.in_b_vr(i).poke(0.U); dut.io.in_c_vr(i).poke(0.U)
    }
  }

  "VALU vsum" should "produce horizontal sum on out_vr, broadcast to all lanes" in {
    simulate(new VALU(K, N)) { dut =>
      pokeCtrl(dut, VecOp.vsum)
      for (_ <- 0 until 64) {
        val a = Array.fill(K)(rand.between(-128, 128))
        pokeA(dut, a)
        dut.clock.step()
        val expected = a.map(_.toLong).sum
        for (i <- 0 until K) {
          dut.io.out_vr(i).expect((expected & 0xFFFFFFFFL).U,
            s"vsum out_vr lane $i expected $expected")
        }
        val lane0 = dut.io.out_vr(0).peek().litValue
        for (i <- 1 until K) {
          assert(lane0 == dut.io.out_vr(i).peek().litValue, s"vsum broadcast invariant lane $i")
        }
      }
    }
  }

  "VALU vrmax" should "broadcast horizontal max on out_vr" in {
    simulate(new VALU(K, N)) { dut =>
      pokeCtrl(dut, VecOp.vrmax)
      for (_ <- 0 until 64) {
        val a = Array.fill(K)(rand.between(-128, 128))
        pokeA(dut, a)
        dut.clock.step()
        val expected = a.max
        for (i <- 0 until K) {
          dut.io.out_vr(i).expect((expected & 0xFFFFFFFFL).U,
            s"vrmax out_vr lane $i expected $expected")
        }
        val lane0 = dut.io.out_vr(0).peek().litValue
        for (i <- 1 until K) {
          assert(lane0 == dut.io.out_vr(i).peek().litValue, s"vrmax broadcast invariant lane $i")
        }
      }
    }
  }
}
