// See README.md for license details.
// =============================================================================
//  NCoreBackendGemmSoftmaxSpec.scala — GEMM + Softmax quantization pipeline test
//
//  Models the post-accumulation activation of a transformer attention block:
//
//    softmax( QK^T / sqrt(d_k) )
//
//  Full pipeline (attention score → INT8 softmax weights):
//
//  Preamble — load LUT banks via vsetlut (once per kernel)
//    exp   table → bank A   (used in Phase 4)
//    recip table → bank B   (used in Phase 6)
//
//  Phase 0  — Seed FP32 accumulator (simulates GEMM / QK^T output in VR[0])
//    vbcastImm(acc) → VX[8];  vcvt_s8_f32 → VR[0]
//
//  Phase 1  — Attention score scaling  (multiply by scale ≈ 1/√d_k)
//    vbcastImm(scale) → VX[8];  vcvt_s8_f32 → VR[2];  vfmul → VR[1]
//
//  Phase 2  — FP32 → SQ1.6 INT8 quantize
//    vcvt(F32→S8, sat)  VR[1] → VX[0]
//
//  Phase 3  — Numerical stability: x - max(x)
//    vrmax VX[0] → VR[3];  extWrite max → VX[5];  vsub → VX[1]
//
//  Phase 4  — Element-wise exp LUT via vlut bank A  (SQ1.6 → UQ0.8)
//    vlut(bank=A)  VX[1] → VX[2]
//
//  Phase 5  — Horizontal sum of exp
//    vsum VX[2] → VR[4];  extWrite clamped sum → VX[6]
//
//  Phase 6  — Reciprocal of sum via vlut bank B  (SQ1.6 → SQ1.6)
//    vlut(bank=B)  VX[6] → VX[7]
//
//  Phase 7  — Promote INT8 → FP32 then multiply
//    vcvt_s8_f32 VX[2]→VR[5];  vcvt_s8_f32 VX[7]→VR[6];  vfmul→VR[7]
//
//  Phase 8  — INT32 right-shift to recover quantized probability
//    vcvt_f32_s32 VR[7]→VR[0];  vsra(>>7) VR[0]→VR[3]
//
//  Phase 9  — Narrow INT32 → INT8 saturated
//    vcvt_s32_s8 sat  VR[3] → VR[2]
//
//  LUT loading protocol (vsetlut via backend):
//    vsetlut is an I-type instruction that reads from in_a_vr (vr_a_addr) and
//    writes K×4 table bytes into the selected bank at the given segment offset.
//    Since the backend has no direct VR immediate-write, the test harness
//    uses extWrite to populate four VX rows with packed byte data, then issues
//    vcvt_s8_f32 (INT8→FP32, widening) to load those rows into VR.
//    NOTE: vcvt_s8_f32 promotes INT8 to FP32 (not a raw byte copy), so it
//    cannot directly pack table bytes into VR.  Instead, we use a simpler
//    direct approach: poke the VALU ctrl + in_a_vr directly is not available
//    through the backend; we load via extWrite + 4× vcvt_s8_s32 (sign-extend
//    single byte to VR lane) for each set of K entries.
//
//    Simplest practical approach for the backend test: write the K×4 raw bytes
//    of each segment directly into four consecutive VX registers (each holding
//    K bytes for one sub-row of the segment), then use the vsetlut instruction.
//    The vsetlut reads in_a_vr[k][8*(b+1)-1:8*b] = segment byte k*4+b.
//    To get these bytes into VR, we use extWrite to write 4 groups of K bytes
//    into VX[20..23] (scratch), then issue a vcvt_s8_s32 chain to assemble
//    a VR register with the correct byte layout.
//
//    For test simplicity we use an alternative: populate VR directly by
//    issuing vbcastImm for each byte lane and packing into a VR via shifted
//    OR.  Given K=8 (32 bytes per segment) and the K×4 packing, this requires
//    a 4-pass approach.  The loadLutBank helper below encapsulates this.
// =============================================================================

package backend

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec
import isa._
import alu.vec.{FpRef, Qfmt}

class NCoreBackendGemmSoftmaxSpec extends AnyFlatSpec {
  import NpuAssembler._

  val K = 8
  val N = 8

  // ---------------------------------------------------------------------------
  // Simulation helpers
  // ---------------------------------------------------------------------------

  def withBackend(body: NCoreBackend => Unit): Unit =
    simulate(new NCoreBackend(K, N, 32)) { dut =>
      dut.io.vx_a_addr.poke(0.U);   dut.io.vx_b_addr.poke(0.U);   dut.io.vx_out_addr.poke(0.U)
      dut.io.ve_a_addr.poke(0.U);   dut.io.ve_b_addr.poke(0.U);   dut.io.ve_out_addr.poke(0.U)
      dut.io.vr_a_addr.poke(0.U);   dut.io.vr_b_addr.poke(0.U);   dut.io.vr_c_addr.poke(0.U)
      dut.io.vr_out_addr.poke(0.U)
      dut.io.mma_a_addr.poke(0.U);  dut.io.mma_b_addr.poke(0.U);  dut.io.mma_out_addr.poke(0.U)
      dut.io.ext_wr_en.poke(false.B); dut.io.ext_wr_addr.poke(0.U)
      for (i <- 0 until K) dut.io.ext_wr_data(i).poke(0.U)
      dut.io.ext_rd_addr.poke(0.U)
      dut.io.vr_rd_addr.poke(0.U)
      dut.io.instr.poke((nop.toLong & 0xFFFFFFFFL).U)
      body(dut)
    }

  /** Write K bytes to VX[addr] via external write port. */
  def extWrite(dut: NCoreBackend, addr: Int, data: Array[Int]): Unit = {
    dut.io.ext_wr_en.poke(true.B)
    dut.io.ext_wr_addr.poke(addr.U)
    data.zipWithIndex.foreach { case (v, i) => dut.io.ext_wr_data(i).poke((v & 0xFF).U) }
    dut.io.instr.poke((nop.toLong & 0xFFFFFFFFL).U)
    dut.clock.step()
    dut.io.ext_wr_en.poke(false.B)
  }

  /**
   * Issue one instruction, hold for `cycles` clocks, then one NOP cycle.
   * Default cycles=2: 1 execute + 1 write-back (VALU 1-cycle output register).
   */
  def issue(dut: NCoreBackend, instr: Int, cycles: Int = 2): Unit = {
    dut.io.instr.poke((instr.toLong & 0xFFFFFFFFL).U)
    for (_ <- 0 until cycles) dut.clock.step()
    dut.io.instr.poke((nop.toLong & 0xFFFFFFFFL).U)
    dut.clock.step()
  }

  /**
   * Peek VR[addr] lane 0 as a signed INT32.
   * step(0) forces combinational re-evaluation in EphemeralSimulator.
   */
  def peekVR0(dut: NCoreBackend, addr: Int): Int = {
    dut.io.vr_a_addr.poke(addr.U)
    dut.clock.step(0)
    dut.io.vr_rd_data(0).peek().litValue.toInt
  }

  // ---------------------------------------------------------------------------
  // loadLutBank: load a 256-byte LUT table into bank A (0) or B (1) via vsetlut.
  //
  // For each segment s (0..segs-1):
  //   1. Write K×4 table bytes into four consecutive VX scratch registers
  //      VX[24..27], each holding one sub-row of K bytes:
  //        VX[24] = bytes [s×K×4+0, s×K×4+4, ..., s×K×4+4×(K-1)]   (b=0 of each lane)
  //        VX[25] = bytes [s×K×4+1, ...]                              (b=1)
  //        VX[26] = bytes [s×K×4+2, ...]                              (b=2)
  //        VX[27] = bytes [s×K×4+3, ...]                              (b=3)
  //   2. Load VX[24..27] into a VR staging register via 4 word-OR passes
  //      using VALU arith shifted into VR.
  //
  //   Simpler path used here: populate VR via the multi-width RF write directly.
  //   We use vcvt_s8_s32 (sign-extend VX lane k low byte → VR lane k bits[7:0])
  //   along with shift+OR logic to pack 4 bytes per lane.  However, the backend
  //   doesn't expose a raw byte-pack path.
  //
  //   Practical workaround: since we need 4 bytes per VR lane (UInt(32.W)), and
  //   each vbcastImm / extWrite can only set one value, we use the following
  //   4-pass approach per segment:
  //     Pass 0: extWrite VX[scratch_vx] with byte0 of each lane
  //             vcvt_s8_s32(VX[scratch_vx] → VR[staging])
  //             → VR[staging][k] = sign_ext(byte0[k])  (low 8 bits correct)
  //     Pass 1: extWrite VX[scratch_vx] with byte1
  //             manually shift+OR in Scala (we can't do << in VR without VALU)
  //
  //   This is complex.  Cleanest alternative for the test: since vsetlut reads
  //   in_a_vr, which is driven by vr_r_data(0) = rf[vr_a_addr], and the RF VR
  //   read packs 4 consecutive VX rows into one VR lane, we can write the 4
  //   VX sub-rows and then use vr_a_addr to point at that VR.
  //
  //   Segment s maps to VR[s] (since 256/(K×4)=8 and we have 8 VR regs at K=8).
  //   For K=8, VR[s] = VX[4s..4s+3].  We write those 4 VX rows with extWrite,
  //   then issue vsetlut with vr_a_addr=s.  This is the cleanest path.
  //
  //   Byte packing per VR lane k:
  //     VX[4s+0][k] = table[s×K×4 + k×4 + 0]   (lane k, byte 0)
  //     VX[4s+1][k] = table[s×K×4 + k×4 + 1]   (lane k, byte 1)
  //     VX[4s+2][k] = table[s×K×4 + k×4 + 2]   (lane k, byte 2)
  //     VX[4s+3][k] = table[s×K×4 + k×4 + 3]   (lane k, byte 3)
  //   VR[s][k] = Cat(VX[4s+3][k], VX[4s+2][k], VX[4s+1][k], VX[4s+0][k])
  //            = table[s×K×4 + k×4 + 3..0]   ← little-endian, matches vsetlut protocol
  // ---------------------------------------------------------------------------
  def loadLutBank(dut: NCoreBackend, table: Seq[Int], bank: Int): Unit = {
    val segs = 256 / (K * 4)  // = 8 at K=8
    for (seg <- 0 until segs) {
      // Write 4 VX sub-rows: VX[4*seg+b][k] = table[seg*K*4 + k*4 + b]
      for (b <- 0 until 4) {
        val vrBase = 4 * seg     // VR[seg] starts at VX[4*seg]
        val vxRow  = vrBase + b  // VX row for byte sub-index b
        val rowData = (0 until K).map { k =>
          table(seg * K * 4 + k * 4 + b) & 0xFF
        }.toArray
        extWrite(dut, addr = vxRow, data = rowData)
      }
      // Issue vsetlut: vr_a_addr = seg so in_a_vr reads VR[seg]
      //   vsetlut(rs1=seg, segment=seg, bank=bank)
      //   encodes as I-type: rd=0, rs1=seg, funct3=4+bank, imm=seg
      dut.io.vr_a_addr.poke(seg.U)
      dut.io.vx_out_addr.poke(0.U)  // vsetlut does not write VX; harmless
      dut.io.vr_out_addr.poke(0.U)  // vsetlut does not write RF; harmless
      issue(dut, vsetlut(rs1 = seg, segment = seg, bank = bank))
    }
  }

  // ---------------------------------------------------------------------------
  // Scala reference: full GEMM + Softmax pipeline.
  // accInt8:  K INT8 values (all same broadcast value)
  // scaleInt: INT8 attention scale
  // Returns K INT8 softmax weights.
  // ---------------------------------------------------------------------------
  def gemmSoftmaxRef(accInt8: Array[Int], scaleInt: Int): Array[Int] = {
    val scaleFp  = FpRef.s8ToF32(scaleInt.toByte)
    val scoreFp  = accInt8.map(a => FpRef.fmul(FpRef.s8ToF32(a.toByte), scaleFp))
    val scoreRaw = scoreFp.map(b => FpRef.f32ToS8(b).toInt & 0xFF)
    val scoreSgn = scoreRaw.map(b => if (b >= 128) b - 256 else b)
    val maxSgn   = scoreSgn.max
    val shifted  = scoreSgn.map(x => math.max(-128, math.min(127, x - maxSgn)))
    val shiftRaw = shifted.map(_ & 0xFF)
    val expRaw   = shiftRaw.map(b => Qfmt.lutExp(b) & 0xFF)
    val expSgn   = expRaw.map(b => if (b >= 128) b - 256 else b)
    val sumSgn   = expSgn.map(_.toLong).sum
    val sumClamp = math.max(1, math.min(127, sumSgn.toInt))
    val recipRaw = Qfmt.lutRecip(sumClamp & 0xFF) & 0xFF
    val expFp    = expSgn.map(e => FpRef.s8ToF32(e.toByte))
    val recipSgn = if (recipRaw >= 128) recipRaw - 256 else recipRaw
    val recipFp  = FpRef.s8ToF32(recipSgn.toByte)
    val prodFp   = expFp.map(e => FpRef.fmul(e, recipFp))
    val prodInt  = prodFp.map(b => FpRef.f32ToS32(b))
    val shiftd7  = prodInt.map(p => p >> 7)
    shiftd7.map(v => math.max(-128, math.min(127, v)))
  }

  // ---------------------------------------------------------------------------
  // Core helper: load LUT banks, then run the full pipeline.
  //
  // Register map:
  //   VX[ 0..3]  = LUT sub-rows for segment 0  (also written by extWrite in preamble)
  //   VX[ 4..7]  = LUT sub-rows for segment 1
  //   ...
  //   VX[28..31] = LUT sub-rows for segment 7
  //   (all 32 VX regs used for LUT loading; they are reused freely after vsetlut)
  //
  //   VX[ 0] = scores_sq16    VX[ 1] = shifted_sq16   VX[ 2] = exp_uq08
  //   VX[ 5] = max_byte        VX[ 6] = sum_clamped    VX[ 7] = recip_sq16
  //   VX[ 8] = staging (vbcastImm target)
  //   VR[ 0] = acc_fp32   → product_int32  (reused)
  //   VR[ 1] = scaled_fp32 → shift_amount=7 (reused)
  //   VR[ 2] = scale_fp32  → result (vcvt_s32_s8 output, reused)
  //   VR[ 3] = max_int32   → vsra output (reused)
  //   VR[ 4] = sum_int32
  //   VR[ 5] = exp_fp32    VR[ 6] = recip_fp32   VR[ 7] = product_fp32
  // ---------------------------------------------------------------------------
  def runGemmSoftmax(
      dut:     NCoreBackend,
      accInt8: Array[Int],
      scaleInt: Int
  ): Array[Int] = {

    val accVal = accInt8(0)

    // ----------------------------------------------------------------
    // Preamble: load exp into LUT bank A, recip into LUT bank B.
    // Must be done before the computation kernel.
    // ----------------------------------------------------------------
    loadLutBank(dut, Qfmt.lutExp,   bank = 0)  // bank A
    loadLutBank(dut, Qfmt.lutRecip, bank = 1)  // bank B

    // ----------------------------------------------------------------
    // Phase 0: vbcastImm(acc) → VX[8];  vcvt_s8_f32 → VR[0]
    // ----------------------------------------------------------------
    dut.io.vx_out_addr.poke(8.U)
    issue(dut, vbcastImm(rd = 8, imm = accVal))

    dut.io.vx_a_addr.poke(8.U)
    dut.io.vr_out_addr.poke(0.U)
    issue(dut, vcvt_s8_f32(rd = 0, rs1 = 8))

    // ----------------------------------------------------------------
    // Phase 1: scale → VR[2];  vfmul VR[0]*VR[2] → VR[1]
    // ----------------------------------------------------------------
    dut.io.vx_out_addr.poke(8.U)
    issue(dut, vbcastImm(rd = 8, imm = scaleInt))

    dut.io.vx_a_addr.poke(8.U)
    dut.io.vr_out_addr.poke(2.U)
    issue(dut, vcvt_s8_f32(rd = 2, rs1 = 8))

    dut.io.vr_a_addr.poke(0.U)
    dut.io.vr_b_addr.poke(2.U)
    dut.io.vr_out_addr.poke(1.U)
    issue(dut, vfmul(rd = 1, rs1 = 0, rs2 = 2))

    // ----------------------------------------------------------------
    // Phase 2: FP32 → SQ1.6   VR[1] → VX[0]
    // ----------------------------------------------------------------
    dut.io.vr_a_addr.poke(1.U)
    dut.io.vx_out_addr.poke(0.U)
    issue(dut, vcvt(rd = 0, rs1 = 1, dstFmt = F32, srcFmt = S8, sat = true))

    // ----------------------------------------------------------------
    // Phase 3: vrmax VX[0] → VR[3]; extWrite max → VX[5]; vsub → VX[1]
    // ----------------------------------------------------------------
    dut.io.vx_a_addr.poke(0.U)
    dut.io.vx_out_addr.poke(0.U)
    dut.io.vr_out_addr.poke(3.U)
    issue(dut, vrmax(rd = 3, rs1 = 0))

    val maxByte = peekVR0(dut, 3).toByte.toInt
    extWrite(dut, addr = 5, Array.fill(K)(maxByte))

    dut.io.vx_a_addr.poke(0.U)
    dut.io.vx_b_addr.poke(5.U)
    dut.io.vx_out_addr.poke(1.U)
    issue(dut, vsub(rd = 1, rs1 = 0, rs2 = 5, width = VX, sat = true))

    // ----------------------------------------------------------------
    // Phase 4: vlut bank A (exp)  VX[1] → VX[2]
    // ----------------------------------------------------------------
    dut.io.vx_a_addr.poke(1.U)
    dut.io.vx_out_addr.poke(2.U)
    issue(dut, vlut(rd = 2, rs1 = 1, bank = 0))

    // ----------------------------------------------------------------
    // Phase 5: vsum VX[2] → VR[4]; extWrite clamped sum → VX[6]
    // ----------------------------------------------------------------
    dut.io.vx_a_addr.poke(2.U)
    dut.io.vx_out_addr.poke(2.U)
    dut.io.vr_out_addr.poke(4.U)
    issue(dut, vsum(rd = 4, rs1 = 2))

    val sumInt32   = peekVR0(dut, 4)
    val sumClamped = math.max(1, math.min(127, sumInt32))
    extWrite(dut, addr = 6, Array.fill(K)(sumClamped))

    // ----------------------------------------------------------------
    // Phase 6: vlut bank B (recip)  VX[6] → VX[7]
    // ----------------------------------------------------------------
    dut.io.vx_a_addr.poke(6.U)
    dut.io.vx_out_addr.poke(7.U)
    issue(dut, vlut(rd = 7, rs1 = 6, bank = 1))

    // ----------------------------------------------------------------
    // Phase 7: vcvt_s8_f32(VX[2]→VR[5]);  vcvt_s8_f32(VX[7]→VR[6]);
    //          vfmul VR[5]*VR[6] → VR[7]
    // ----------------------------------------------------------------
    dut.io.vr_out_addr.poke(5.U)
    dut.io.vx_a_addr.poke(2.U)
    dut.io.vx_out_addr.poke(0.U)
    issue(dut, vcvt_s8_f32(rd = 5, rs1 = 2))

    dut.io.vr_out_addr.poke(6.U)
    dut.io.vx_a_addr.poke(7.U)
    issue(dut, vcvt_s8_f32(rd = 6, rs1 = 7))

    dut.io.vr_a_addr.poke(5.U)
    dut.io.vr_b_addr.poke(6.U)
    dut.io.vr_out_addr.poke(7.U)
    issue(dut, vfmul(rd = 7, rs1 = 5, rs2 = 6))

    // ----------------------------------------------------------------
    // Phase 8: vcvt_f32_s32 VR[7]→VR[0]; vsra(>>7) VR[0]→VR[3]
    // ----------------------------------------------------------------
    dut.io.vr_a_addr.poke(7.U)
    dut.io.vr_out_addr.poke(0.U)
    issue(dut, vcvt_f32_s32(rd = 0, rs1 = 7))

    dut.io.vx_out_addr.poke(8.U)
    issue(dut, vbcastImm(rd = 8, imm = 7))

    dut.io.vx_a_addr.poke(8.U)
    dut.io.vr_out_addr.poke(1.U)
    issue(dut, vcvt_s8_f32(rd = 1, rs1 = 8))

    dut.io.vr_a_addr.poke(1.U)
    dut.io.vr_out_addr.poke(1.U)
    issue(dut, vcvt_f32_s32(rd = 1, rs1 = 1))

    dut.io.vr_a_addr.poke(0.U)
    dut.io.vr_b_addr.poke(1.U)
    dut.io.vr_out_addr.poke(3.U)
    issue(dut, vsra(rd = 3, rs1 = 0, rs2 = 1, width = VR))

    // ----------------------------------------------------------------
    // Phase 9: vcvt_s32_s8 sat  VR[3] → VR[2]
    // ----------------------------------------------------------------
    dut.io.vr_a_addr.poke(3.U)
    dut.io.vr_out_addr.poke(2.U)
    issue(dut, vcvt_s32_s8(rd = 2, rs1 = 3))

    dut.io.vr_a_addr.poke(2.U)
    dut.clock.step(0)
    (0 until K).map { i =>
      val raw32 = dut.io.vr_rd_data(i).peek().litValue.toInt
      val low8  = raw32 & 0xFF
      if (low8 >= 128) low8 - 256 else low8
    }.toArray
  }

  // ===========================================================================
  // Test A: Uniform scores — exp table in bank A, recip in bank B
  // ===========================================================================
  "NCoreBackendGemmSoftmax" should "produce equal outputs for uniform input scores" in {
    withBackend { dut =>
      val accInt8  = Array.fill(K)(10)
      val result   = runGemmSoftmax(dut, accInt8, scaleInt = 1)
      val expected = gemmSoftmaxRef(accInt8, scaleInt = 1)

      for (i <- 0 until K)
        assert(result(i) == expected(i),
          s"[Test A] lane $i: got ${result(i)}, expected ${expected(i)}")

      val lane0 = result(0)
      for (i <- 1 until K)
        assert(result(i) == lane0,
          s"[Test A] uniformity: lane $i=${result(i)} != lane 0=$lane0")
    }
  }

  // ===========================================================================
  // Test B: 2× scale
  // ===========================================================================
  "NCoreBackendGemmSoftmax" should "handle 2x scale with full value check" in {
    withBackend { dut =>
      val accInt8  = Array.fill(K)(20)
      val result   = runGemmSoftmax(dut, accInt8, scaleInt = 2)
      val expected = gemmSoftmaxRef(accInt8, scaleInt = 2)

      for (i <- 0 until K)
        assert(result(i) == expected(i),
          s"[Test B] lane $i: got ${result(i)}, expected ${expected(i)}")

      val lane0 = result(0)
      for (i <- 1 until K)
        assert(result(i) == lane0,
          s"[Test B] uniformity: lane $i=${result(i)} != lane 0=$lane0")
    }
  }

  // ===========================================================================
  // Test C: Negative accumulator
  // ===========================================================================
  "NCoreBackendGemmSoftmax" should "handle negative accumulator scores" in {
    withBackend { dut =>
      val accInt8  = Array.fill(K)(-20)
      val result   = runGemmSoftmax(dut, accInt8, scaleInt = 1)
      val expected = gemmSoftmaxRef(accInt8, scaleInt = 1)

      for (i <- 0 until K)
        assert(result(i) == expected(i),
          s"[Test C] lane $i: got ${result(i)}, expected ${expected(i)}")
    }
  }

  // ===========================================================================
  // Test D: scale=3
  // ===========================================================================
  "NCoreBackendGemmSoftmax" should "pass full value check with scale=3" in {
    withBackend { dut =>
      val accInt8  = Array.fill(K)(5)
      val result   = runGemmSoftmax(dut, accInt8, scaleInt = 3)
      val expected = gemmSoftmaxRef(accInt8, scaleInt = 3)

      for (i <- 0 until K)
        assert(result(i) == expected(i),
          s"[Test D] lane $i: got ${result(i)}, expected ${expected(i)}")
    }
  }
}
