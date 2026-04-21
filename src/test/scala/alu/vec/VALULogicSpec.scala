// See README.md for license details.

package alu.vec

import scala.util.Random
import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec
import isa.micro_op._
import isa.VecWidth

class VALULogicSpec extends AnyFlatSpec {
  val N = 8; val K = 8; val MASK = 0xFF
  val rand = new Random(0xBEEF)

  def pokeCtrl(dut: VALU, op: VecOp.Type): Unit = {
    dut.io.ctrl.op.poke(op)
    dut.io.ctrl.regCls.poke(0.U)
    dut.io.ctrl.dtype.poke(VecDType.S8C4)
    dut.io.ctrl.saturate.poke(false.B)
    dut.io.ctrl.round.poke(0.U); dut.io.ctrl.rs3_idx.poke(0.U); dut.io.ctrl.imm.poke(0.S)
  }

  def pokeAB(dut: VALU, a: Array[Int], b: Array[Int]): Unit = {
    for (i <- 0 until K) {
      dut.io.in_a_vx(i).poke((a(i) & 0xFF).U); dut.io.in_b_vx(i).poke((b(i) & 0xFF).U)
      dut.io.in_a_ve(i).poke(0.U); dut.io.in_b_ve(i).poke(0.U)
      dut.io.in_a_vr(i).poke(0.U); dut.io.in_b_vr(i).poke(0.U); dut.io.in_c_vr(i).poke(0.U)
    }
  }

  def randVec() = Array.fill(K)(rand.between(-128, 128))
  def u2s8(u: Int): Int = (u & MASK).toByte.toInt
  def readVX(dut: VALU): Array[Int] = Array.tabulate(K)(i => dut.io.out_vx(i).peek().litValue.toInt)

  "VALU vand" should "AND lane bit-patterns" in {
    simulate(new VALU(K, N)) { dut =>
      pokeCtrl(dut, VecOp.vand)
      for (_ <- 0 until 64) {
        val a = randVec(); val b = randVec()
        pokeAB(dut, a, b)
        dut.clock.step()
        val out = readVX(dut)
        for (i <- 0 until K) assert(out(i) == ((a(i) & MASK) & (b(i) & MASK)), s"vand lane $i")
      }
    }
  }

  "VALU vor" should "OR lane bit-patterns" in {
    simulate(new VALU(K, N)) { dut =>
      pokeCtrl(dut, VecOp.vor)
      for (_ <- 0 until 64) {
        val a = randVec(); val b = randVec()
        pokeAB(dut, a, b)
        dut.clock.step()
        val out = readVX(dut)
        for (i <- 0 until K) assert(out(i) == ((a(i) & MASK) | (b(i) & MASK)), s"vor lane $i")
      }
    }
  }

  "VALU vxor" should "XOR lane bit-patterns" in {
    simulate(new VALU(K, N)) { dut =>
      pokeCtrl(dut, VecOp.vxor)
      for (_ <- 0 until 64) {
        val a = randVec(); val b = randVec()
        pokeAB(dut, a, b)
        dut.clock.step()
        val out = readVX(dut)
        for (i <- 0 until K) assert(out(i) == ((a(i) & MASK) ^ (b(i) & MASK)), s"vxor lane $i")
      }
    }
  }

  "VALU vnot" should "invert all bits of in_a" in {
    simulate(new VALU(K, N)) { dut =>
      pokeCtrl(dut, VecOp.vnot)
      for (_ <- 0 until 64) {
        val a = randVec()
        pokeAB(dut, a, Array.fill(K)(0))
        dut.clock.step()
        val out = readVX(dut)
        for (i <- 0 until K) assert(out(i) == (~a(i) & MASK), s"vnot lane $i")
      }
    }
  }

  "VALU vsll" should "logical-left-shift by low bits of in_b" in {
    simulate(new VALU(K, N)) { dut =>
      pokeCtrl(dut, VecOp.vsll)
      val a   = Array(1, -1, 3, -3,  0x7F, 0x01, -128, 64)
      val amt = Array(0,  1, 2,  3,     1,    7,     1,  1)
      pokeAB(dut, a, amt)
      dut.clock.step()
      val out = readVX(dut)
      for (i <- 0 until K) {
        val exp = ((a(i) & MASK) << (amt(i) & 7)) & MASK
        assert(out(i) == exp, s"vsll lane $i: ${a(i)} << ${amt(i) & 7} expected $exp got ${out(i)}")
      }
    }
  }

  "VALU vsra" should "arithmetic-right-shift in_a by low bits of in_b" in {
    simulate(new VALU(K, N)) { dut =>
      pokeCtrl(dut, VecOp.vsra)
      val a   = Array(64, -64, 127, -128,  1, -1,  0, -16)
      val amt = Array( 1,   1,   1,    1,  0,  0,  3,   2)
      pokeAB(dut, a, amt)
      dut.clock.step()
      val out = readVX(dut)
      for (i <- 0 until K) {
        val aS = a(i).toByte.toInt
        val exp = (aS >> (amt(i) & 7)) & MASK
        assert(out(i) == exp, s"vsra lane $i: ${a(i)} >> ${amt(i)} expected $exp got ${out(i)}")
      }
    }
  }
}
