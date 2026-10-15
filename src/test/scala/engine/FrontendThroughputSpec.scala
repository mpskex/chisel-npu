package engine

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec
import isa._
import isa.NpuAssembler._

class FrontendThroughputSpec extends AnyFlatSpec {
  val K = 32

  def bytes(words: Seq[Int]): Seq[Int] =
    words.flatMap(w => Seq(w & 0xFF, (w >> 8) & 0xFF, (w >> 16) & 0xFF, (w >> 24) & 0xFF))

  def preload(dut: FrontendSpecHarness, base: Long, bs: Seq[Int]): Unit =
    for ((bb, i) <- bs.grouped(16).zipWithIndex) {
      var w = BigInt(0); for ((v, j) <- bb.zipWithIndex) w |= BigInt(v & 0xFF) << (8 * j)
      dut.io.pre_wr_addr.poke((((base + i * 16) >> 4) & 0xFFF).U)
      dut.io.pre_wr_data.poke(w.U); dut.io.pre_wr_en.poke(true.B)
      dut.clock.step(); dut.io.pre_wr_en.poke(false.B)
    }

  def runFrontend(dut: FrontendSpecHarness, prog: Seq[Int]): (Long, Long) = {
    preload(dut, NpuSections.CODE_BASE, bytes(prog))
    def w(addr: Int, d: Long): Unit = {
      dut.io.ctrl_addr.poke(addr.U); dut.io.ctrl_we.poke(true.B)
      dut.io.ctrl_wdata.poke(d.U); dut.clock.step(); dut.io.ctrl_we.poke(false.B)
    }
    w(0x14, prog.length); w(0x0, 1); w(0x0, 0)
    var n = 0
    while (((dut.io.ctrl_rdata.peek().litValue >> 1) & 1) == 0 && n < 2000000) {
      dut.io.ctrl_addr.poke(0x0.U); dut.clock.step(); n += 1
    }
    dut.io.ctrl_addr.poke(0x44.U); dut.clock.step(0)
    val macs = dut.io.ctrl_rdata.peek().litValue.toLong
    dut.io.ctrl_addr.poke(0x40.U); dut.clock.step(0)
    val cyc = dut.io.ctrl_rdata.peek().litValue.toLong
    (macs, cyc)
  }

  "Frontend throughput (C1)" should "match B1's resident long round" in {
    val R = 64
    val prog = Seq(vle8(4, NpuSections.SECT_A, 0), vle8(8, NpuSections.SECT_B, 0)) ++
      (0 until R).flatMap(i => Seq(mma(0, 4, 8, 0), vse32(0, NpuSections.SECT_OUT, (i % 8) * 4 * K))) :+
      mmaLast(0, 0, 0, 0)
    simulate(new FrontendSpecHarness(K = K)) { dut =>
      val a = Array.fill(K)(3); val b = Array.fill(K)(5)
      preload(dut, NpuSections.A, a); preload(dut, NpuSections.B, b)
      val (macs, cyc) = runFrontend(dut, prog)
      assert(macs == BigInt(R * K * K), s"frontend feeds should equal R*K^2, got $macs")
      println(f"[perf/fe] C1 R=$R cycles=$cyc macs=$macs macs/cyc=${macs.toDouble / cyc}%8.1f " +
        f"peak=${100.0 * (macs / (K.toLong * K)).toDouble / cyc}%6.1f%%")
    }
  }
}
