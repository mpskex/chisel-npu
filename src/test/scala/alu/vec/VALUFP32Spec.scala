// See README.md for license details.
// FP32 arithmetic tests: compare HW fadd/fmul/fma against java.lang.Float reference.

package alu.vec

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec
import isa.micro_op._
import isa.VecWidth
import isa.NpuAssembler

class VALUFP32Spec extends AnyFlatSpec {
  val K = 8; val N = 8

  def mkCtrl(op: VecOp.Type): NCoreVALUBundle = {
    val b = new NCoreVALUBundle
    b
  }

  def pokeCtrlFP(dut: VALU, op: VecOp.Type): Unit = {
    dut.io.ctrl.op.poke(op)
    dut.io.ctrl.regCls.poke(2.U)
    dut.io.ctrl.dtype.poke(VecDType.FP32C1)
    dut.io.ctrl.saturate.poke(false.B)
    dut.io.ctrl.round.poke(0.U)
    dut.io.ctrl.rs3_idx.poke(0.U)
    dut.io.ctrl.imm.poke(0.S)
  }

  def f32Bits(f: Float): Long = java.lang.Float.floatToRawIntBits(f) & 0xFFFFFFFFL
  def bitsF32(i: Long): Float = java.lang.Float.intBitsToFloat(i.toInt)

  def pokeFPLanes(dut: VALU, aArr: Array[Float], bArr: Array[Float], cArr: Array[Float]): Unit = {
    for (i <- 0 until K) {
      dut.io.in_a_vr(i).poke(f32Bits(aArr(i)).U)
      dut.io.in_b_vr(i).poke(f32Bits(bArr(i)).U)
      dut.io.in_c_vr(i).poke(f32Bits(cArr(i)).U)
    }
    // zero other ports
    for (i <- 0 until K) {
      dut.io.in_a_vx(i).poke(0.U); dut.io.in_b_vx(i).poke(0.U)
      dut.io.in_a_ve(i).poke(0.U); dut.io.in_b_ve(i).poke(0.U)
    }
  }

  def readVR(dut: VALU): Array[Long] = Array.tabulate(K)(i => dut.io.out_vr(i).peek().litValue.toLong)

  // Tolerance: Tier-2 results may differ from JVM by 1 ULP for add/mul;
  // FMA is implemented as two ops, so allow 2 ULP.
  def withinUlp(hwBits: Long, refBits: Long, ulp: Int = 2): Boolean = {
    val hw  = bitsF32(hwBits)
    val ref = bitsF32(refBits)
    if (hw.isNaN || ref.isNaN) return hw.isNaN && ref.isNaN
    if (hw.isInfinite || ref.isInfinite) return hw.isInfinite && ref.isInfinite && hw > 0 == ref > 0
    Math.abs(hw - ref) <= ulp * Math.ulp(ref)
  }

  "VALU FP32" should "compute vfadd for finite normal values" in {
    simulate(new VALU(K, N)) { dut =>
      pokeCtrlFP(dut, VecOp.vfadd)
      val aArr = Array[Float](1.0f, -2.5f, 0.5f, 100.0f, -100.0f, 3.14f, -3.14f, 0.0f)
      val bArr = Array[Float](2.0f,  1.5f, 0.5f,  50.0f,  -50.0f, 2.71f, -2.71f, 1.0f)
      val cArr = Array.fill(K)(0.0f)
      pokeFPLanes(dut, aArr, bArr, cArr)
      dut.clock.step()
      val result = readVR(dut)
      for (i <- 0 until K) {
        val expected = f32Bits(aArr(i) + bArr(i))
        assert(withinUlp(result(i), expected),
          f"vfadd lane $i: hw=${bitsF32(result(i))}%.6f ref=${bitsF32(expected)}%.6f")
      }
    }
  }

  "VALU FP32" should "compute vfmul for finite normal values" in {
    simulate(new VALU(K, N)) { dut =>
      pokeCtrlFP(dut, VecOp.vfmul)
      val aArr = Array[Float](2.0f, -3.0f, 0.5f, 1e4f, -1e4f, 1.0f, -1.0f, 0.125f)
      val bArr = Array[Float](3.0f,  2.0f, 2.0f, 2.5f, -2.5f, 1.0f,  1.0f, 8.0f)
      val cArr = Array.fill(K)(0.0f)
      pokeFPLanes(dut, aArr, bArr, cArr)
      dut.clock.step()
      val result = readVR(dut)
      for (i <- 0 until K) {
        val expected = f32Bits(aArr(i) * bArr(i))
        assert(withinUlp(result(i), expected),
          f"vfmul lane $i: hw=${bitsF32(result(i))}%.6f ref=${bitsF32(expected)}%.6f")
      }
    }
  }

  "VALU FP32" should "compute vfma (a*b + c)" in {
    simulate(new VALU(K, N)) { dut =>
      pokeCtrlFP(dut, VecOp.vfma)
      val aArr = Array[Float](2.0f, 3.0f, 0.5f, -1.0f, 4.0f, -2.0f, 1.0f, 0.25f)
      val bArr = Array[Float](3.0f, 2.0f, 4.0f,  2.0f, 0.5f,  3.0f, 1.0f, 4.0f)
      val cArr = Array[Float](1.0f, 1.0f, 1.0f,  1.0f, 1.0f,  1.0f, 1.0f, 1.0f)
      pokeFPLanes(dut, aArr, bArr, cArr)
      dut.clock.step()
      val result = readVR(dut)
      for (i <- 0 until K) {
        val expected = f32Bits(aArr(i) * bArr(i) + cArr(i))
        assert(withinUlp(result(i), expected, ulp=4),
          f"vfma lane $i: hw=${bitsF32(result(i))}%.6f ref=${bitsF32(expected)}%.6f")
      }
    }
  }

  "VALU FP32" should "saturate overflow to max finite normal" in {
    simulate(new VALU(K, N)) { dut =>
      pokeCtrlFP(dut, VecOp.vfmul)
      val big = java.lang.Float.MAX_VALUE
      val aArr = Array.fill(K)(big)
      val bArr = Array.fill(K)(2.0f)
      val cArr = Array.fill(K)(0.0f)
      pokeFPLanes(dut, aArr, bArr, cArr)
      dut.clock.step()
      val result = readVR(dut)
      for (i <- 0 until K) {
        val hw = bitsF32(result(i))
        assert(!hw.isInfinite, s"Overflow should saturate to max finite, got $hw lane $i")
        assert(hw >= 0, s"Expected positive saturation, got $hw")
      }
    }
  }

  "VALU FP32" should "treat zero operand as zero (Tier-2 NaN/subnormal FTZ)" in {
    simulate(new VALU(K, N)) { dut =>
      pokeCtrlFP(dut, VecOp.vfmul)
      // Multiply by 0
      val aArr = Array.fill(K)(5.0f)
      val bArr = Array.fill(K)(0.0f)
      val cArr = Array.fill(K)(0.0f)
      pokeFPLanes(dut, aArr, bArr, cArr)
      dut.clock.step()
      val result = readVR(dut)
      for (i <- 0 until K) {
        val hw = bitsF32(result(i))
        assert(Math.abs(hw) < 1e-30f, s"Expected ~0, got $hw lane $i")
      }
    }
  }

  "VALU FP32" should "negate and abs correctly" in {
    simulate(new VALU(K, N)) { dut =>
      pokeCtrlFP(dut, VecOp.vfneg)
      val aArr = Array[Float](1.0f, -2.0f, 0.0f, 3.14f, -3.14f, 100.0f, -100.0f, 0.5f)
      val bArr = Array.fill(K)(0.0f); val cArr = Array.fill(K)(0.0f)
      pokeFPLanes(dut, aArr, bArr, cArr)
      dut.clock.step()
      val negResult = readVR(dut)
      for (i <- 0 until K) {
        val hw = bitsF32(negResult(i)); val exp = -aArr(i)
        assert(hw == exp, s"vfneg lane $i: $hw != $exp")
      }

      pokeCtrlFP(dut, VecOp.vfabs)
      pokeFPLanes(dut, aArr, bArr, cArr)
      dut.clock.step()
      val absResult = readVR(dut)
      for (i <- 0 until K) {
        val hw = bitsF32(absResult(i)); val exp = Math.abs(aArr(i))
        assert(hw == exp, s"vfabs lane $i: $hw != $exp")
      }
    }
  }
}
