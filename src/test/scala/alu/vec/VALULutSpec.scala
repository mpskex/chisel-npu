// See README.md for license details.

package alu.vec

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec
import isa.micro_op._
import isa.VecWidth

class VALULutSpec extends AnyFlatSpec {
  val N = 8; val K = 8

  def pokeCtrl(dut: VALU, op: VecOp.Type): Unit = {
    dut.io.ctrl.op.poke(op); dut.io.ctrl.regCls.poke(0.U)
    dut.io.ctrl.dtype.poke(VecDType.S8C4); dut.io.ctrl.saturate.poke(false.B)
    dut.io.ctrl.round.poke(0.U); dut.io.ctrl.rs3_idx.poke(0.U); dut.io.ctrl.imm.poke(0.S)
  }

  def sweepLut(dut: VALU, op: VecOp.Type, lutRef: Seq[Int], opName: String): Unit = {
    pokeCtrl(dut, op)
    for (base <- 0 until 256 by K) {
      for (i <- 0 until K) {
        val raw = (base + i) % 256
        val signed = if (raw >= 128) raw - 256 else raw
        dut.io.in_a_vx(i).poke((raw & 0xFF).U)
        dut.io.in_b_vx(i).poke(0.U)
        dut.io.in_a_ve(i).poke(0.U); dut.io.in_b_ve(i).poke(0.U)
        dut.io.in_a_vr(i).poke(0.U); dut.io.in_b_vr(i).poke(0.U); dut.io.in_c_vr(i).poke(0.U)
      }
      dut.clock.step()
      for (i <- 0 until K) {
        val raw = (base + i) % 256
        dut.io.out_vx(i).expect((lutRef(raw) & 0xFF).U,
          s"$opName LUT[$raw]: expected ${lutRef(raw)} got ${dut.io.out_vx(i).peek().litValue}")
      }
    }
  }

  "VALU vexp"   should "match precomputed LUT bit-exactly for all 256 inputs" in {
    simulate(new VALU(K, N)) { dut => sweepLut(dut, VecOp.vexp,   Qfmt.lutExp,   "vexp") }
  }
  "VALU vrecip" should "match precomputed LUT bit-exactly for all 256 inputs" in {
    simulate(new VALU(K, N)) { dut => sweepLut(dut, VecOp.vrecip, Qfmt.lutRecip, "vrecip") }
  }
  "VALU vtanh"  should "match precomputed LUT bit-exactly for all 256 inputs" in {
    simulate(new VALU(K, N)) { dut => sweepLut(dut, VecOp.vtanh,  Qfmt.lutTanh,  "vtanh") }
  }
  "VALU verf"   should "match precomputed LUT bit-exactly for all 256 inputs" in {
    simulate(new VALU(K, N)) { dut => sweepLut(dut, VecOp.verf,   Qfmt.lutErf,   "verf") }
  }

  "VALU vrecip" should "return sentinel 127 for x=0 input" in {
    simulate(new VALU(K, N)) { dut =>
      pokeCtrl(dut, VecOp.vrecip)
      for (i <- 0 until K) {
        dut.io.in_a_vx(i).poke(0.U); dut.io.in_b_vx(i).poke(0.U)
        dut.io.in_a_ve(i).poke(0.U); dut.io.in_b_ve(i).poke(0.U)
        dut.io.in_a_vr(i).poke(0.U); dut.io.in_b_vr(i).poke(0.U); dut.io.in_c_vr(i).poke(0.U)
      }
      dut.clock.step()
      for (i <- 0 until K) dut.io.out_vx(i).expect(127.U, s"vrecip(0) sentinel lane $i")
    }
  }

  "VALU vexp" should "have no zero entries in the LUT" in {
    for (raw <- 0 until 256) assert(Qfmt.lutExp(raw) != 0, s"vexp LUT[$raw] = 0")
  }

  "VALU vtanh" should "be monotone non-decreasing (reference LUT)" in {
    var prev = Qfmt.lutTanh(128)
    val order = (-128 until 128).map(v => if (v < 0) v + 256 else v)
    for (raw <- order) {
      val cur = Qfmt.lutTanh(raw)
      val curS = if (cur >= 128) cur - 256 else cur
      val prevS = if (prev >= 128) prev - 256 else prev
      assert(curS >= prevS - 1, s"vtanh not monotone at raw=$raw: prev=$prevS cur=$curS")
      prev = cur
    }
  }
}
