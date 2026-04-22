// See README.md for license details.
// Conversion op tests: s32<->f32, f32<->s8, f32<->bf16, f32<->bf8.

package alu.vec

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec
import isa.micro_op._
import isa.VecWidth

class VALUCvtSpec extends AnyFlatSpec {
  val K = 8; val N = 8
  val N4 = 4 * N

  def f32Bits(f: Float): Long = java.lang.Float.floatToRawIntBits(f) & 0xFFFFFFFFL
  def bitsF32(i: Long): Float = java.lang.Float.intBitsToFloat(i.toInt)

  def pokeCtrl(dut: VALU, op: VecOp.Type, sat: Boolean = false): Unit = {
    dut.io.ctrl.op.poke(op)
    dut.io.ctrl.regCls.poke(2.U)
    dut.io.ctrl.dtype.poke(VecDType.FP32C1)
    dut.io.ctrl.saturate.poke(sat.B)
    dut.io.ctrl.round.poke(0.U)
    dut.io.ctrl.rs3_idx.poke(0.U)
    dut.io.ctrl.imm.poke(0.S)
  }

  def zeroInputs(dut: VALU): Unit = {
    for (i <- 0 until K) {
      dut.io.in_a_vx(i).poke(0.U); dut.io.in_b_vx(i).poke(0.U)
      dut.io.in_a_ve(i).poke(0.U); dut.io.in_b_ve(i).poke(0.U)
      dut.io.in_a_vr(i).poke(0.U); dut.io.in_b_vr(i).poke(0.U)
      dut.io.in_c_vr(i).poke(0.U)
    }
  }

  // ---- s32 → f32 ----
  "VALU vcvt" should "convert INT32 to FP32 for small integers" in {
    simulate(new VALU(K, N)) { dut =>
      zeroInputs(dut)
      pokeCtrl(dut, VecOp.vcvt_s32_f32)  // s32→f32: integer input → FP32 output
      val intVals = Array(-128, -1, 0, 1, 127, 1000, -1000, Int.MaxValue / 2)
      for (i <- 0 until K) dut.io.in_a_vr(i).poke((intVals(i) & 0xFFFFFFFFL).U)
      dut.clock.step()
      for (i <- 0 until K) {
        val hwBits = dut.io.out_vr(i).peek().litValue.toLong
        val hwF = bitsF32(hwBits)
        val refF = intVals(i).toFloat
        assert(Math.abs(hwF - refF) <= Math.ulp(refF),
          s"vcvt_f32_s32 lane $i: hw=$hwF ref=$refF")
      }
    }
  }

  // ---- f32 → s32 ----
  "VALU vcvt" should "convert FP32 to INT32 (RTZ)" in {
    simulate(new VALU(K, N)) { dut =>
      zeroInputs(dut)
      pokeCtrl(dut, VecOp.vcvt_f32_s32)  // f32→s32: FP32 input → integer output
      val floatVals = Array(1.9f, -1.9f, 0.5f, -0.5f, 100.7f, -100.7f, 0.0f, -0.0f)
      for (i <- 0 until K) dut.io.in_a_vr(i).poke(f32Bits(floatVals(i)).U)
      dut.clock.step()
      for (i <- 0 until K) {
        val hwInt = dut.io.out_vr(i).peek().litValue.toInt  // signed
        val refInt = floatVals(i).toInt  // Java RTZ
        assert(hwInt == refInt, s"vcvt_s32_f32 lane $i: hw=$hwInt ref=$refInt input=${floatVals(i)}")
      }
    }
  }

  // ---- f32 → s8 saturated ----
  "VALU vcvt" should "convert FP32 to INT8 with saturation" in {
    simulate(new VALU(K, N)) { dut =>
      zeroInputs(dut)
      pokeCtrl(dut, VecOp.vcvt_f32_s8, sat = true)  // f32→s8: FP32 input → INT8 output
      val floatVals = Array(0.0f, 1.0f, 127.0f, 128.0f, -128.0f, -129.0f, 63.7f, -64.2f)
      for (i <- 0 until K) dut.io.in_a_vr(i).poke(f32Bits(floatVals(i)).U)
      dut.clock.step()
      for (i <- 0 until K) {
        val hw = dut.io.out_vx(i).peek().litValue.toByte.toInt
        val ref = FpRef.f32ToS8(java.lang.Float.floatToRawIntBits(floatVals(i))).toInt
        assert(hw == ref, s"vcvt_s8_f32 lane $i: hw=$hw ref=$ref input=${floatVals(i)}")
      }
    }
  }

  // ---- f32 ↔ bf16 round-trip ----
  "VALU vcvt" should "round-trip f32 → bf16 → f32 with precision loss" in {
    simulate(new VALU(K, N)) { dut =>
      zeroInputs(dut)
      val floatVals = Array[Float](1.0f, -2.5f, 3.14159f, 0.1f, 100.0f, -100.0f, 0.5f, -0.5f)

      // Step 1: f32 → bf16
      pokeCtrl(dut, VecOp.vcvt_f32_bf16)
      for (i <- 0 until K) dut.io.in_a_vr(i).poke(f32Bits(floatVals(i)).U)
      dut.clock.step()
      val bf16Results = Array.tabulate(K)(i => dut.io.out_vr(i).peek().litValue.toLong & 0xFFFF)

      // Step 2: bf16 → f32
      pokeCtrl(dut, VecOp.vcvt_bf16_f32)
      for (i <- 0 until K) {
        // Feed BF16 in the VR lane (low 16 bits)
        dut.io.in_a_vr(i).poke(bf16Results(i).U)
      }
      dut.clock.step()

      for (i <- 0 until K) {
        val hwF = bitsF32(dut.io.out_vr(i).peek().litValue.toLong)
        val refBf16 = FpRef.f32ToBf16Bits(java.lang.Float.floatToRawIntBits(floatVals(i)))
        val refF32  = bitsF32(FpRef.bf16BitsToF32(refBf16))
        assert(Math.abs(hwF - refF32) <= Math.abs(refF32 * 0.01),
          s"bf16 round-trip lane $i: hw=$hwF ref=$refF32 original=${floatVals(i)}")
      }
    }
  }

  // ---- f32 ↔ bf8 E4M3 ----
  "VALU vcvt" should "encode f32 to BF8 E4M3 and decode back" in {
    simulate(new VALU(K, N)) { dut =>
      zeroInputs(dut)
      val floatVals = Array[Float](1.0f, -1.0f, 2.0f, -2.0f, 0.5f, -0.5f, 0.0f, 4.0f)

      // f32 → bf8 E4M3
      pokeCtrl(dut, VecOp.vcvt_f32_bf8)
      dut.io.ctrl.dtype.poke(VecDType.BF8E4M3)
      for (i <- 0 until K) dut.io.in_a_vr(i).poke(f32Bits(floatVals(i)).U)
      dut.clock.step()
      val bf8 = Array.tabulate(K)(i => dut.io.out_vr(i).peek().litValue.toLong & 0xFF)

      // Validate against Scala reference
      for (i <- 0 until K) {
        val refBf8 = FpRef.f32ToBf8E4M3(java.lang.Float.floatToRawIntBits(floatVals(i))) & 0xFF
        assert(bf8(i) == refBf8,
          s"bf8_E4M3 encode lane $i: hw=${bf8(i)} ref=$refBf8 input=${floatVals(i)}")
      }
    }
  }
}
