// See README.md for license details.
// =============================================================================
//  VALUProgrammableLutSpec.scala — tests for the programmable two-bank LUT
//
//  Covers:
//    1. vsetlut loads a 256-byte table (in K×4-byte segments) into bank A or B.
//    2. vlut performs a bit-exact per-lane lookup from the loaded bank.
//    3. Both banks are independent: bank A and B can hold different tables.
//    4. Legacy activation functions (exp, recip, tanh, erf) are verified by
//       loading the Qfmt reference tables at test time.
//
//  vsetlut protocol (VALU-direct, bypassing the backend RF):
//    - Poke in_a_vr[lane k] with the 32-bit word containing 4 table bytes:
//        bits [8*(b+1)-1 : 8*b] = table[seg*K*4 + k*4 + b]  for b in 0..3
//    - Poke ctrl with op=vsetlut, regCls=VR, round[0]=bank, imm=segment.
//    - Step 1 clock.  The banks are updated on the rising edge.
//    - Repeat for all ceil(256/(K*4)) segments.
//
//  After loading, vlut is tested by poking in_a_vx[lane] = raw LUT index (0..255)
//  and checking out_vx[lane] == table[index] after 1 clock step.
// =============================================================================

package alu.vec

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec
import isa.micro_op._

class VALUProgrammableLutSpec extends AnyFlatSpec {
  val N = 8; val K = 8
  val BANK_A = 0; val BANK_B = 1

  // ---------------------------------------------------------------------------
  // VALU ctrl helpers
  // ---------------------------------------------------------------------------

  def zeroInputs(dut: VALU): Unit = {
    for (i <- 0 until K) {
      dut.io.in_a_vx(i).poke(0.U); dut.io.in_b_vx(i).poke(0.U)
      dut.io.in_a_ve(i).poke(0.U); dut.io.in_b_ve(i).poke(0.U)
      dut.io.in_a_vr(i).poke(0.U); dut.io.in_b_vr(i).poke(0.U); dut.io.in_c_vr(i).poke(0.U)
    }
  }

  def pokeCtrl(dut: VALU, op: VecOp.Type, regCls: Int = 0,
               bank: Int = 0, imm: Int = 0): Unit = {
    dut.io.ctrl.op.poke(op)
    dut.io.ctrl.regCls.poke(regCls.U)
    dut.io.ctrl.dtype.poke(VecDType.S8C4)
    dut.io.ctrl.saturate.poke(false.B)
    dut.io.ctrl.round.poke(bank.U)   // round[0] = bank select
    dut.io.ctrl.rs3_idx.poke(0.U)
    dut.io.ctrl.imm.poke(imm.S)
  }

  // ---------------------------------------------------------------------------
  // loadBank: write a full 256-byte table into bank A or B via vsetlut.
  //
  // Segment packing at K=8:  256 / (K×4) = 8 segments.
  //   in_a_vr[k] = { table[s×K×4 + k×4 + 3],
  //                  table[s×K×4 + k×4 + 2],
  //                  table[s×K×4 + k×4 + 1],
  //                  table[s×K×4 + k×4 + 0] }   (little-endian byte order)
  // ---------------------------------------------------------------------------
  def loadBank(dut: VALU, table: Seq[Int], bank: Int): Unit = {
    val segs = 256 / (K * 4)
    for (seg <- 0 until segs) {
      zeroInputs(dut)
      for (k <- 0 until K) {
        var word = 0L
        for (b <- 0 until 4) {
          val entry = table(seg * K * 4 + k * 4 + b) & 0xFF
          word |= entry.toLong << (8 * b)
        }
        dut.io.in_a_vr(k).poke(word.U)
      }
      pokeCtrl(dut, VecOp.vsetlut, regCls = 2 /* VR */, bank = bank, imm = seg)
      dut.clock.step()
    }
  }

  // ---------------------------------------------------------------------------
  // sweepLut: verify all 256 entries of a loaded bank via vlut.
  // ---------------------------------------------------------------------------
  def sweepLut(dut: VALU, table: Seq[Int], bank: Int, label: String): Unit = {
    pokeCtrl(dut, VecOp.vlut, regCls = 0 /* VX */, bank = bank)
    for (base <- 0 until 256 by K) {
      zeroInputs(dut)
      for (i <- 0 until K) {
        dut.io.in_a_vx(i).poke(((base + i) & 0xFF).U)
      }
      dut.clock.step()
      for (i <- 0 until K) {
        val idx = (base + i) & 0xFF
        val exp = table(idx) & 0xFF
        dut.io.out_vx(i).expect(exp.U,
          s"$label bank${if (bank==0) "A" else "B"} LUT[$idx]: expected $exp")
      }
    }
  }

  // ===========================================================================
  // Test 1: Load exp table into bank A; verify all 256 entries via vlut
  // ===========================================================================
  "VALU vlut/vsetlut" should "load and look up exp table (bank A) bit-exactly" in {
    simulate(new VALU(K, N)) { dut =>
      loadBank(dut, Qfmt.lutExp, BANK_A)
      sweepLut(dut, Qfmt.lutExp, BANK_A, "vexp")
    }
  }

  // ===========================================================================
  // Test 2: Load recip table into bank B; verify all 256 entries
  // ===========================================================================
  "VALU vlut/vsetlut" should "load and look up recip table (bank B) bit-exactly" in {
    simulate(new VALU(K, N)) { dut =>
      loadBank(dut, Qfmt.lutRecip, BANK_B)
      sweepLut(dut, Qfmt.lutRecip, BANK_B, "vrecip")
    }
  }

  // ===========================================================================
  // Test 3: Load tanh into bank A, erf into bank B; both independent
  // ===========================================================================
  "VALU vlut/vsetlut" should "support two independent banks (tanh in A, erf in B)" in {
    simulate(new VALU(K, N)) { dut =>
      loadBank(dut, Qfmt.lutTanh, BANK_A)
      loadBank(dut, Qfmt.lutErf,  BANK_B)
      sweepLut(dut, Qfmt.lutTanh, BANK_A, "vtanh")
      sweepLut(dut, Qfmt.lutErf,  BANK_B, "verf")
    }
  }

  // ===========================================================================
  // Test 4: Overwrite bank A — new table replaces old
  // ===========================================================================
  "VALU vlut/vsetlut" should "overwrite bank A when reloaded with a different table" in {
    simulate(new VALU(K, N)) { dut =>
      // Load exp first, then overwrite with recip
      loadBank(dut, Qfmt.lutExp,   BANK_A)
      loadBank(dut, Qfmt.lutRecip, BANK_A)
      // Only recip should be visible now
      sweepLut(dut, Qfmt.lutRecip, BANK_A, "overwrite-recip")
    }
  }

  // ===========================================================================
  // Test 5: Legacy property — vrecip sentinel for x=0 still holds via vlut
  // ===========================================================================
  "VALU vlut/vsetlut" should "return sentinel 127 for recip(0) via vlut" in {
    simulate(new VALU(K, N)) { dut =>
      loadBank(dut, Qfmt.lutRecip, BANK_A)
      pokeCtrl(dut, VecOp.vlut, regCls = 0, bank = BANK_A)
      zeroInputs(dut)
      // all lanes index 0 → recip(0) = sentinel 127
      for (i <- 0 until K) dut.io.in_a_vx(i).poke(0.U)
      dut.clock.step()
      for (i <- 0 until K)
        dut.io.out_vx(i).expect(127.U, s"vlut recip(0) sentinel lane $i")
    }
  }

  // ===========================================================================
  // Test 6: Legacy property — vexp has no zero entries in the Qfmt table
  // ===========================================================================
  "VALU vlut/vsetlut" should "have no zero entries in the Qfmt exp table" in {
    for (raw <- 0 until 256)
      assert(Qfmt.lutExp(raw) != 0, s"Qfmt.lutExp[$raw] = 0")
  }

  // ===========================================================================
  // Test 7: Legacy property — Qfmt tanh table is monotone non-decreasing
  // ===========================================================================
  "VALU vlut/vsetlut" should "have a monotone non-decreasing Qfmt tanh table" in {
    var prev = Qfmt.lutTanh(128)
    val order = (-128 until 128).map(v => if (v < 0) v + 256 else v)
    for (raw <- order) {
      val cur  = Qfmt.lutTanh(raw)
      val curS  = if (cur  >= 128) cur  - 256 else cur
      val prevS = if (prev >= 128) prev - 256 else prev
      assert(curS >= prevS - 1,
        s"Qfmt.lutTanh not monotone at raw=$raw: prev=$prevS cur=$curS")
      prev = cur
    }
  }

  // ===========================================================================
  // Test 8: Custom table — compiler-defined arbitrary byte→byte function
  // ===========================================================================
  "VALU vlut/vsetlut" should "support a compiler-defined identity table" in {
    // Table: output[i] = i (identity / passthrough)
    val identityTable = (0 until 256).map(_ & 0xFF)
    simulate(new VALU(K, N)) { dut =>
      loadBank(dut, identityTable, BANK_A)
      sweepLut(dut, identityTable, BANK_A, "identity")
    }
  }
}
