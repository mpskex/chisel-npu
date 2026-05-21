// See README.md for license details.

package alu.vec

import scala.util.Random
import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec
import isa.micro_op._
import isa.VecWidth

object ArithRef {
  val MIN: Int = -128; val MAX: Int = 127
  def sat(v: Int): Int = math.max(MIN, math.min(MAX, v))
  def trunc(v: Int): Int = v.toByte.toInt
  def vadd(a: Int, b: Int, saturate: Boolean): Int = if (saturate) sat(a+b) else trunc(a+b)
  def vsub(a: Int, b: Int, saturate: Boolean): Int = if (saturate) sat(a-b) else trunc(a-b)
  def vmulNarrow(a: Int, b: Int, saturate: Boolean): Int = if (saturate) sat(a*b) else trunc(a*b)
  def vmulWide(a: Int, b: Int): Int = a * b
  def vneg(a: Int, saturate: Boolean): Int = if (saturate) sat(-a) else trunc(-a)
  def vabs(a: Int, saturate: Boolean): Int = if (saturate) sat(math.abs(a)) else trunc(math.abs(a))
}

class VALUArithSpec extends AnyFlatSpec {
  val N = 8; val K = 8
  val rand = new Random(0xDEAD)

  def pokeCtrl(dut: VALU, op: VecOp.Type, saturate: Boolean = false): Unit = {
    dut.io.ctrl.op.poke(op)
    dut.io.ctrl.regCls.poke(0.U)
    dut.io.ctrl.dtype.poke(VecDType.S8C4)
    dut.io.ctrl.saturate.poke(saturate.B)
    dut.io.ctrl.round.poke(0.U)
    dut.io.ctrl.rs3_idx.poke(0.U)
    dut.io.ctrl.imm.poke(0.S)
  }

  def randVec(): Array[Int] = Array.fill(K)(rand.between(-128, 128))

  def pokeAB(dut: VALU, a: Array[Int], b: Array[Int]): Unit = {
    for (i <- 0 until K) {
      dut.io.in_a_vx(i).poke((a(i) & 0xFF).U)
      dut.io.in_b_vx(i).poke((b(i) & 0xFF).U)
      dut.io.in_a_ve(i).poke(0.U); dut.io.in_b_ve(i).poke(0.U)
      dut.io.in_a_vr(i).poke(0.U); dut.io.in_b_vr(i).poke(0.U)
      dut.io.in_c_vr(i).poke(0.U)
    }
  }

  // ---- Sub-case helpers ------------------------------------------------------

  private def runVaddWrap(dut: VALU): Unit = {
    pokeCtrl(dut, VecOp.vadd, saturate = false)
    for (_ <- 0 until 64) {
      val a = randVec(); val b = randVec()
      pokeAB(dut, a, b)
      dut.clock.step()
      for (i <- 0 until K) {
        val exp = ArithRef.vadd(a(i), b(i), saturate = false)
        dut.io.out_vx(i).expect((exp & 0xFF).U, s"vadd wrap lane $i")
      }
    }
  }

  private def runVaddSat(dut: VALU): Unit = {
    pokeCtrl(dut, VecOp.vadd, saturate = true)
    val a = Array(127, -128, 100, -100, 0, 0, 64, -64)
    val b = Array(  1,   -1, 100, -100, 0, 127, 64, -64)
    pokeAB(dut, a, b)
    dut.clock.step()
    for (i <- 0 until K) {
      val exp = ArithRef.vadd(a(i), b(i), saturate = true)
      dut.io.out_vx(i).expect((exp & 0xFF).U, s"vadd sat lane $i")
    }
  }

  private def runVsubWrap(dut: VALU): Unit = {
    pokeCtrl(dut, VecOp.vsub, saturate = false)
    for (_ <- 0 until 64) {
      val a = randVec(); val b = randVec()
      pokeAB(dut, a, b)
      dut.clock.step()
      for (i <- 0 until K) {
        val exp = ArithRef.vsub(a(i), b(i), saturate = false)
        dut.io.out_vx(i).expect((exp & 0xFF).U, s"vsub wrap lane $i")
      }
    }
  }

  private def runVsubSat(dut: VALU): Unit = {
    pokeCtrl(dut, VecOp.vsub, saturate = true)
    val a = Array(-128, 127, 0, 0, 100, -100, 64, -64)
    val b = Array(   1,  -1, 0, 0,-100,  100,-64,  64)
    pokeAB(dut, a, b)
    dut.clock.step()
    for (i <- 0 until K) {
      val exp = ArithRef.vsub(a(i), b(i), saturate = true)
      dut.io.out_vx(i).expect((exp & 0xFF).U, s"vsub sat lane $i")
    }
  }

  private def runVmulSat(dut: VALU): Unit = {
    pokeCtrl(dut, VecOp.vmul, saturate = true)
    for (_ <- 0 until 64) {
      val a = randVec(); val b = randVec()
      pokeAB(dut, a, b)
      dut.clock.step()
      for (i <- 0 until K) {
        val narrow = ArithRef.vmulNarrow(a(i), b(i), saturate = true)
        dut.io.out_vx(i).expect((narrow & 0xFF).U, s"vmul narrow sat lane $i")
      }
    }
  }

  private def runVmulWrap(dut: VALU): Unit = {
    pokeCtrl(dut, VecOp.vmul, saturate = false)
    val a = Array(64, -64, 127, -128, 10, -10, 3, -3)
    val b = Array( 2,   2,   2,    2,  5,   5, 9,  9)
    pokeAB(dut, a, b)
    dut.clock.step()
    for (i <- 0 until K) {
      val narrow = ArithRef.vmulNarrow(a(i), b(i), saturate = false)
      dut.io.out_vx(i).expect((narrow & 0xFF).U, s"vmul wrap narrow lane $i")
    }
  }

  private def runVnegWrap(dut: VALU): Unit = {
    pokeCtrl(dut, VecOp.vneg, saturate = false)
    for (_ <- 0 until 64) {
      val a = randVec()
      pokeAB(dut, a, Array.fill(K)(0))
      dut.clock.step()
      for (i <- 0 until K) {
        val exp = ArithRef.vneg(a(i), saturate = false)
        dut.io.out_vx(i).expect((exp & 0xFF).U, s"vneg lane $i")
      }
    }
  }

  private def runVnegSat(dut: VALU): Unit = {
    pokeCtrl(dut, VecOp.vneg, saturate = true)
    val a = Array(-128, 127, 0, 1, -1, 64, -64, 100)
    pokeAB(dut, a, Array.fill(K)(0))
    dut.clock.step()
    for (i <- 0 until K) {
      val exp = ArithRef.vneg(a(i), saturate = true)
      dut.io.out_vx(i).expect((exp & 0xFF).U, s"vneg sat lane $i")
    }
  }

  private def runVabsWrap(dut: VALU): Unit = {
    pokeCtrl(dut, VecOp.vabs, saturate = false)
    for (_ <- 0 until 64) {
      val a = randVec()
      pokeAB(dut, a, Array.fill(K)(0))
      dut.clock.step()
      for (i <- 0 until K) {
        val exp = ArithRef.vabs(a(i), saturate = false)
        dut.io.out_vx(i).expect((exp & 0xFF).U, s"vabs lane $i")
      }
    }
  }

  private def runVabsSat(dut: VALU): Unit = {
    pokeCtrl(dut, VecOp.vabs, saturate = true)
    val a = Array(-128, -127, -1, 0, 1, 127, -64, 64)
    pokeAB(dut, a, Array.fill(K)(0))
    dut.clock.step()
    for (i <- 0 until K) {
      val exp = ArithRef.vabs(a(i), saturate = true)
      dut.io.out_vx(i).expect((exp & 0xFF).U, s"vabs sat lane $i")
    }
  }

  // ---- Coalesced test --------------------------------------------------------

  "VALU arith" should "pass all VALU arithmetic sub-cases" in {
    simulate(new VALU(K, N)) { dut =>
      withClue("vadd wrap: ") { runVaddWrap(dut) }
      withClue("vadd sat: ")  { runVaddSat(dut)  }
      withClue("vsub wrap: ") { runVsubWrap(dut) }
      withClue("vsub sat: ")  { runVsubSat(dut)  }
      withClue("vmul sat: ")  { runVmulSat(dut)  }
      withClue("vmul wrap: ") { runVmulWrap(dut) }
      withClue("vneg wrap: ") { runVnegWrap(dut) }
      withClue("vneg sat: ")  { runVnegSat(dut)  }
      withClue("vabs wrap: ") { runVabsWrap(dut) }
      withClue("vabs sat: ")  { runVabsSat(dut)  }
    }
  }
}
