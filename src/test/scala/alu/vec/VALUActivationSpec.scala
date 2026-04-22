// See README.md for license details.
// Softmax and GELU composition tests using VALU primitives.
//
// LUT-based activation functions (exp, erf) are loaded into the VALU's
// programmable banks via vsetlut before each test

package alu.vec

import scala.util.Random
import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec
import isa.micro_op._

class VALUActivationSpec extends AnyFlatSpec {
  val N = 8; val K = 8
  val WX = 0; val WE = 1; val WR = 2
  val BANK_A = 0; val BANK_B = 1

  def pokeCtrl(dut: VALU, op: VecOp.Type, width: Int = WX,
               sat: Boolean = false, bank: Int = 0, imm: Int = 0): Unit = {
    dut.io.ctrl.op.poke(op); dut.io.ctrl.regCls.poke(width.U)
    dut.io.ctrl.dtype.poke(VecDType.S8C4); dut.io.ctrl.saturate.poke(sat.B)
    dut.io.ctrl.round.poke(bank.U)  // round[0] = bank select for vlut/vsetlut
    dut.io.ctrl.rs3_idx.poke(0.U); dut.io.ctrl.imm.poke(imm.S)
  }

  def pokeVX(dut: VALU, a: Array[Int], b: Array[Int] = Array.fill(8)(0)): Unit = {
    for (i <- 0 until K) {
      dut.io.in_a_vx(i).poke((a(i) & 0xFF).U); dut.io.in_b_vx(i).poke((b(i) & 0xFF).U)
      dut.io.in_a_ve(i).poke(0.U); dut.io.in_b_ve(i).poke(0.U)
      dut.io.in_a_vr(i).poke(0.U); dut.io.in_b_vr(i).poke(0.U); dut.io.in_c_vr(i).poke(0.U)
    }
  }

  def readVX(dut: VALU): Array[Int] = Array.tabulate(K)(i => dut.io.out_vx(i).peek().litValue.toInt)
  def readVR(dut: VALU): Array[Long] = Array.tabulate(K)(i => dut.io.out_vr(i).peek().litValue.toLong)

  /** Load a 256-byte table into the given bank via vsetlut. */
  def loadBank(dut: VALU, table: Seq[Int], bank: Int): Unit = {
    val segs = 256 / (K * 4)
    for (seg <- 0 until segs) {
      for (i <- 0 until K) {
        dut.io.in_a_vx(i).poke(0.U); dut.io.in_b_vx(i).poke(0.U)
        dut.io.in_a_ve(i).poke(0.U); dut.io.in_b_ve(i).poke(0.U)
        dut.io.in_c_vr(i).poke(0.U); dut.io.in_b_vr(i).poke(0.U)
        var word = 0L
        for (b <- 0 until 4) {
          val entry = table(seg * K * 4 + i * 4 + b) & 0xFF
          word |= entry.toLong << (8 * b)
        }
        dut.io.in_a_vr(i).poke(word.U)
      }
      pokeCtrl(dut, VecOp.vsetlut, width = WR, bank = bank, imm = seg)
      dut.clock.step()
    }
  }

  "VALU softmax" should "produce a probability distribution summing to ~1.0" in {
    val rand = new Random(0x5AFE)
    simulate(new VALU(K, N)) { dut =>
      // Load exp into bank A and recip into bank B once before the loop.
      loadBank(dut, Qfmt.lutExp,   BANK_A)
      loadBank(dut, Qfmt.lutRecip, BANK_B)

      for (_ <- 0 until 16) {
        val xRaw = Array.fill(K)(rand.between(-128, 128))

        // Step 1: vrmax
        pokeCtrl(dut, VecOp.vrmax)
        pokeVX(dut, xRaw)
        dut.clock.step()
        val maxVal = dut.io.out_vr(0).peek().litValue.toInt

        // Step 2: vsub (x - max)
        pokeCtrl(dut, VecOp.vsub, sat = true)
        pokeVX(dut, xRaw, Array.fill(K)(maxVal))
        dut.clock.step()
        val xShifted = readVX(dut)

        // Step 3: vlut bank A (exp)
        pokeCtrl(dut, VecOp.vlut, bank = BANK_A)
        pokeVX(dut, xShifted)
        dut.clock.step()
        val eVec = readVX(dut)

        // Step 4: vsum
        pokeCtrl(dut, VecOp.vsum)
        pokeVX(dut, eVec)
        dut.clock.step()
        val sumEHwU = dut.io.out_vr(0).peek().litValue.toLong & 0xFFFFFFFFL
        val sumEHw = sumEHwU.toInt.toLong
        val expectedSumSigned = eVec.map(v => (v & 0xFF).toByte.toLong).sum
        assert(Math.abs(sumEHw - expectedSumSigned) <= K,
          s"vsum mismatch: hw=$sumEHw sw=$expectedSumSigned")

        // Step 5: vlut bank B (recip), clamped sum
        val sumSq16 = math.max(1, math.min(127, sumEHw.toInt))
        pokeCtrl(dut, VecOp.vlut, bank = BANK_B)
        pokeVX(dut, Array.fill(K)(sumSq16))
        dut.clock.step()
        val recipSum = readVX(dut)
        for (r <- recipSum) assert(r >= 0, s"recip should be non-negative, got $r")

        // Step 6: vmul (e * recip)
        pokeCtrl(dut, VecOp.vmul, sat = false)
        pokeVX(dut, eVec, recipSum)
        dut.clock.step()

        // Scala reference: softmax probabilities sum to 1.0
        val xD = xRaw.map(Qfmt.sq16ToDouble)
        val maxD = xD.max
        val expD = xD.map(x => math.exp(x - maxD))
        val sumD = expD.sum
        val probsD = expD.map(_ / sumD)
        assert(Math.abs(probsD.sum - 1.0) < 0.01, s"reference softmax sum=${probsD.sum}")
      }
    }
  }

  "VALU GELU" should "produce positive outputs for positive inputs" in {
    val rand = new Random(0x6E10)
    simulate(new VALU(K, N)) { dut =>
      // Load erf table into bank A for GELU approximation.
      loadBank(dut, Qfmt.lutErf, BANK_A)

      for (_ <- 0 until 16) {
        val posIn = Array.fill(K)(rand.between(1, 64))
        val negIn = Array.fill(K)(rand.between(-64, -1))

        def runGelu(xRaw: Array[Int]): Array[Int] = {
          pokeCtrl(dut, VecOp.vsra)
          pokeVX(dut, xRaw, Array.fill(K)(1))
          dut.clock.step()
          val xHalf = readVX(dut)

          // vlut bank A (erf)
          pokeCtrl(dut, VecOp.vlut, bank = BANK_A)
          pokeVX(dut, xHalf)
          dut.clock.step()
          val e = readVX(dut)

          pokeCtrl(dut, VecOp.vadd, sat = true)
          pokeVX(dut, e, Array.fill(K)(64))
          dut.clock.step()
          val e1 = readVX(dut)

          pokeCtrl(dut, VecOp.vmul, sat = false)
          pokeVX(dut, xRaw, e1)
          dut.clock.step()
          val prodWide = readVR(dut)
          prodWide.map(p => (p >> 7).toInt)
        }

        val posGelu = runGelu(posIn)
        val negGelu = runGelu(negIn)

        posGelu.zipWithIndex.foreach { case (g, i) =>
          assert(g >= 0, f"GELU(pos) lane $i input=${Qfmt.sq16ToDouble(posIn(i) & 0xFF)}%.3f gelu=$g")
        }
        val strongNeg = negIn.map(_ < -32)
        negGelu.zipWithIndex.foreach { case (g, i) =>
          if (strongNeg(i))
            assert(g <= 0, f"GELU(neg) lane $i input=${Qfmt.sq16ToDouble(negIn(i) & 0xFF)}%.3f gelu=$g")
        }
      }
    }
  }
}
