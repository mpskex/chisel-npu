package engine

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec
import isa._
import isa.NpuAssembler._
import testUtil._

class FrontendPerfCounterSpec extends AnyFlatSpec {
  "Frontend perf counters" should "be readable at ctrl 0x40/0x44" in {
    val K = 32
    simulate(new FrontendSpecHarness(K = K)) { dut =>
      // stage a resident 8-mma round (operands prefetched, then re-used)
      val a = Array.fill(K)(3); val b = Array.fill(K)(5)
      val preloadBytes = (base: Long, bytes: Seq[Int]) => {
        for ((bb, i) <- bytes.grouped(16).zipWithIndex) {
          var w = BigInt(0); for ((v, j) <- bb.zipWithIndex) w |= BigInt(v & 0xFF) << (8 * j)
          dut.io.pre_wr_addr.poke((((base + i * 16) >> 4) & 0xFFF).U)
          dut.io.pre_wr_data.poke(w.U); dut.io.pre_wr_en.poke(true.B)
          dut.clock.step(); dut.io.pre_wr_en.poke(false.B)
        }
      }
      preloadBytes(NpuSections.A, a)
      preloadBytes(NpuSections.B, b)
      val prog = Seq(vle8(4, NpuSections.SECT_A, 0), vle8(8, NpuSections.SECT_B, 0)) ++
        (0 until 8).flatMap(i => Seq(mma(0, 4, 8, 0), vse32(0, NpuSections.SECT_OUT, (i % 8) * 4 * K))) :+
        mmaLast(0, 0, 0, 0)
      val bytes = prog.flatMap(w => Seq(w & 0xFF, (w >> 8) & 0xFF, (w >> 16) & 0xFF, (w >> 24) & 0xFF))
      preloadBytes(NpuSections.CODE_BASE, bytes)
      dut.io.ctrl_addr.poke(0x14.U); dut.io.ctrl_we.poke(true.B)
      dut.io.ctrl_wdata.poke(prog.length.U); dut.clock.step(); dut.io.ctrl_we.poke(false.B)
      dut.io.ctrl_addr.poke(0x0.U); dut.io.ctrl_we.poke(true.B)
      dut.io.ctrl_wdata.poke(1.U); dut.clock.step()
      dut.io.ctrl_wdata.poke(0.U); dut.clock.step(); dut.io.ctrl_we.poke(false.B)
      var n = 0
      while (((dut.io.ctrl_rdata.peek().litValue >> 1) & 1) == 0 && n < 500000) {
        dut.io.ctrl_addr.poke(0x0.U); dut.clock.step(); n += 1
      }
      dut.io.ctrl_addr.poke(0x44.U); dut.clock.step(0)
      val macs = dut.io.ctrl_rdata.peek().litValue
      dut.io.ctrl_addr.poke(0x40.U); dut.clock.step(0)
      val cyc = dut.io.ctrl_rdata.peek().litValue
      assert(macs == BigInt(8 * K * K), s"0x44 should report 8*K^2 MACs, got $macs")
      assert(cyc > 0, "0x40 cycle count must be non-zero")
      println(s"[perf/fe] macs=$macs cycles=$cyc macs/cyc=${macs.toDouble / cyc.toDouble}")
    }
  }
}
