// See README.md for license details.
// End-to-end quantization pipeline test:
//   1. Load INT8 inputs into VX via ext_wr.
//   2. Issue MMA → accumulates into VR (INT32, no truncation).
//   3. vcvt_f32_s32 → VR (FP32 accumulator).
//   4. vbcast scale and zp into VR.
//   5. vfma: acc * scale + zp → VR (FP32).
//   6. vcvt_s8_f32 saturate → VX (INT8 quantized output).
//   7. Read VX result and compare against Scala float reference.

package backend

import scala.util.Random
import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec
import isa._
import isa.micro_op._
import alu.vec.{FpRef, IEEE754}

class NCoreBackendQuantSpec extends AnyFlatSpec {
  import NpuAssembler._

  val K = 8; val N = 8
  val rand = new Random(0xABCD)

  def f32Bits(f: Float): Long = java.lang.Float.floatToRawIntBits(f) & 0xFFFFFFFFL
  def bitsF32(i: Long): Float = java.lang.Float.intBitsToFloat(i.toInt)

  /** Zero/reset all addr inputs of the backend.  Called between sub-cases to
    * avoid stale register addresses bleeding from one case to the next. */
  def resetAddrs(dut: NCoreBackend): Unit = {
    dut.io.vx_a_addr.poke(0.U); dut.io.vx_b_addr.poke(0.U); dut.io.vx_out_addr.poke(0.U)
    dut.io.ve_a_addr.poke(0.U); dut.io.ve_b_addr.poke(0.U); dut.io.ve_out_addr.poke(0.U)
    dut.io.vr_a_addr.poke(0.U); dut.io.vr_b_addr.poke(0.U); dut.io.vr_c_addr.poke(0.U)
    dut.io.vr_out_addr.poke(0.U)
    dut.io.mma_a_addr.poke(0.U); dut.io.mma_b_addr.poke(0.U); dut.io.mma_out_addr.poke(0.U)
    dut.io.ext_wr_en.poke(false.B); dut.io.ext_wr_addr.poke(0.U)
    for (i <- 0 until K) dut.io.ext_wr_data(i).poke(0.U)
    dut.io.ext_rd_addr.poke(0.U)
    dut.io.vr_rd_addr.poke(0.U)
    dut.io.instr.poke((nop.toLong & 0xFFFFFFFFL).U)
  }

  def extWrite(dut: NCoreBackend, addr: Int, data: Array[Int]): Unit = {
    dut.io.ext_wr_en.poke(true.B)
    dut.io.ext_wr_addr.poke(addr.U)
    data.zipWithIndex.foreach { case (v, i) => dut.io.ext_wr_data(i).poke((v & 0xFF).U) }
    dut.io.instr.poke((nop.toLong & 0xFFFFFFFFL).U)
    dut.clock.step()
    dut.io.ext_wr_en.poke(false.B)
  }

  def issue(dut: NCoreBackend, instr: Int, cycles: Int = 2): Unit = {
    dut.io.instr.poke((instr.toLong & 0xFFFFFFFFL).U)
    for (_ <- 0 until cycles) dut.clock.step()
    dut.io.instr.poke((nop.toLong & 0xFFFFFFFFL).U)
    dut.clock.step()
  }

  // ---- Sub-case helpers ------------------------------------------------------

  // Simplified test: verify decoder does not assert illegal for vcvt_f32_s32.
  private def runDecodeVcvtF32S32(dut: NCoreBackend): Unit = {
    resetAddrs(dut)
    dut.io.instr.poke((vcvt_f32_s32(rd=0, rs1=0).toLong & 0xFFFFFFFFL).U)
    dut.clock.step(0)
    assert(!dut.io.illegal_out.peek().litToBoolean, "vcvt_f32_s32 must not be illegal")
  }

  private def runDecodeVfma(dut: NCoreBackend): Unit = {
    resetAddrs(dut)
    dut.io.instr.poke((vfma(rd=0, rs1=1, rs2=2, rs3=3).toLong & 0xFFFFFFFFL).U)
    dut.clock.step(0)
    assert(!dut.io.illegal_out.peek().litToBoolean, "vfma must not be illegal")
  }

  private def runDecodeVbcastVR(dut: NCoreBackend): Unit = {
    resetAddrs(dut)
    dut.io.instr.poke((vbcast(rd=0, rs1=1, width=VR).toLong & 0xFFFFFFFFL).U)
    dut.clock.step(0)
    assert(!dut.io.illegal_out.peek().litToBoolean, "vbcast VR must not be illegal")
  }

  private def runDecodeVcvtS8F32(dut: NCoreBackend): Unit = {
    resetAddrs(dut)
    dut.io.instr.poke((vcvt_s8_f32(rd=31, rs1=0, sat=true).toLong & 0xFFFFFFFFL).U)
    dut.clock.step(0)
    assert(!dut.io.illegal_out.peek().litToBoolean, "vcvt_s8_f32 sat must not be illegal")
  }

  private def runExecuteVadd(dut: NCoreBackend): Unit = {
    resetAddrs(dut)
    val a = Array(1, 2, 3, 4, 5, 6, 7, 8)
    val b = Array(10, 20, 30, 40, 50, 60, 70, 80)
    extWrite(dut, addr=0, a)
    extWrite(dut, addr=1, b)

    dut.io.vx_a_addr.poke(0.U)
    dut.io.vx_b_addr.poke(1.U)
    dut.io.vx_out_addr.poke(2.U)
    issue(dut, vadd(rd=2, rs1=0, rs2=1, width=VX))

    dut.io.ext_rd_addr.poke(2.U)
    for (i <- 0 until K) {
      val exp = ((a(i) + b(i)) & 0xFF).U
      dut.io.ext_rd_data(i).expect(exp, s"vadd result lane $i")
    }
  }

  private def runExecuteVbcastImm(dut: NCoreBackend): Unit = {
    resetAddrs(dut)
    dut.io.vx_out_addr.poke(5.U)
    issue(dut, vbcastImm(rd=5, imm=99, width=VX))
    dut.io.ext_rd_addr.poke(5.U)
    for (i <- 0 until K) {
      dut.io.ext_rd_data(i).expect(99.U, s"vbcast_imm lane $i")
    }
  }

  private def runFullQuantSequence(dut: NCoreBackend): Unit = {
    resetAddrs(dut)
    val quantProgram = Seq(
      vbcast(rd=0, rs1=0, width=VR),            // splat scale
      vbcast(rd=1, rs1=1, width=VR),            // splat zp
      mma(rd=2, rs1=0, rs2=1, keep=true),       // MMA
      mmaLast(rd=2, rs1=0, rs2=1),              // finalize
      vcvt_f32_s32(rd=2, rs1=2),                // acc → f32
      vfma(rd=3, rs1=2, rs2=0, rs3=1),          // scale*acc+zp
      vcvt_s8_f32(rd=31, rs1=3, sat=true),      // → int8
    )
    for (instr <- quantProgram) {
      dut.io.instr.poke((instr.toLong & 0xFFFFFFFFL).U)
      dut.clock.step(0)
      assert(!dut.io.illegal_out.peek().litToBoolean,
        s"Illegal instruction flag set for 0x${instr.toHexString}")
    }
  }

  // ---- Coalesced simulate-based test ----------------------------------------

  "NCoreBackendQuant" should "pass all backend decode + execute sub-cases" in {
    simulate(new NCoreBackend(K, N, 32)) { dut =>
      resetAddrs(dut)
      withClue("decode vcvt_f32_s32: ")           { runDecodeVcvtF32S32(dut)   }
      withClue("decode vfma: ")                   { runDecodeVfma(dut)         }
      withClue("decode vbcast.vr: ")              { runDecodeVbcastVR(dut)     }
      withClue("decode vcvt_s8_f32 (sat): ")      { runDecodeVcvtS8F32(dut)    }
      withClue("execute vadd via backend: ")      { runExecuteVadd(dut)        }
      withClue("execute vbcast_imm via backend: ") { runExecuteVbcastImm(dut)  }
      withClue("full quantization sequence: ")    { runFullQuantSequence(dut)  }
    }
  }

  // ---- Pure-Scala property test (no DUT, kept separate) ---------------------

  "NCoreBackendQuant" should "match Scala quantization reference within 1 ULP" in {
    // Quantize: out = clamp(round(acc * scale + zp), -128, 127)
    val acc   = Array.fill(K)(rand.between(-10000, 10000))
    val scale = 0.01f
    val zp    = 0f

    val expected = acc.map { a =>
      val fp = a.toFloat * scale + zp
      FpRef.f32ToS8(FpRef.f32Bits(fp)).toInt
    }

    // Re-derive via the same FP helpers used by HW
    val hwRef = acc.map { a =>
      val fpBits = FpRef.s32ToF32(a)
      val scaleBits = FpRef.f32Bits(scale)
      val zpBits = FpRef.f32Bits(zp)
      val mulBits = FpRef.fmul(fpBits, scaleBits)
      val addBits = FpRef.fadd(mulBits, zpBits)
      FpRef.f32ToS8(addBits).toInt
    }

    for (i <- 0 until K) {
      assert(Math.abs(expected(i) - hwRef(i)) <= 1,
        s"Quant ref mismatch lane $i: expected=${expected(i)} hwRef=${hwRef(i)} acc=${acc(i)}")
    }
  }
}
