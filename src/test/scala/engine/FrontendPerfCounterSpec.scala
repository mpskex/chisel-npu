package engine

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec
import isa._
import isa.NpuAssembler._

class FrontendPerfCounterSpec extends AnyFlatSpec {

  private def preloadBytes(dut: FrontendSpecHarness, base: Long, bytes: Seq[Int]): Unit = {
    for ((bb, i) <- bytes.grouped(16).zipWithIndex) {
      var w = BigInt(0); for ((v, j) <- bb.zipWithIndex) w |= BigInt(v & 0xFF) << (8 * j)
      dut.io.pre_wr_addr.poke((((base + i * 16) >> 4) & 0xFFF).U)
      dut.io.pre_wr_data.poke(w.U); dut.io.pre_wr_en.poke(true.B)
      dut.clock.step(); dut.io.pre_wr_en.poke(false.B)
    }
  }

  private def writeCtrl(dut: FrontendSpecHarness, addr: Int, data: Long): Unit = {
    dut.io.ctrl_addr.poke(addr.U); dut.io.ctrl_we.poke(true.B)
    dut.io.ctrl_wdata.poke(data.U); dut.clock.step(); dut.io.ctrl_we.poke(false.B)
  }

  private def readCtrl(dut: FrontendSpecHarness, addr: Int): BigInt = {
    dut.io.ctrl_addr.poke(addr.U); dut.clock.step(0)
    dut.io.ctrl_rdata.peek().litValue
  }

  private def stageResidentRound(dut: FrontendSpecHarness, K: Int): Unit = {
    // stage a resident 8-mma round (operands prefetched, then re-used)
    val a = Array.fill(K)(3); val b = Array.fill(K)(5)
    preloadBytes(dut, NpuSections.A, a)
    preloadBytes(dut, NpuSections.B, b)
    val prog = Seq(vle8(4, NpuSections.SECT_A, 0), vle8(8, NpuSections.SECT_B, 0)) ++
      (0 until 8).flatMap(i => Seq(mma(0, 4, 8, 0), vse32(0, NpuSections.SECT_OUT, (i % 8) * 4 * K))) :+
      mmaLast(0, 0, 0, 0)
    val bytes = prog.flatMap(w => Seq(w & 0xFF, (w >> 8) & 0xFF, (w >> 16) & 0xFF, (w >> 24) & 0xFF))
    preloadBytes(dut, NpuSections.CODE_BASE, bytes)
    writeCtrl(dut, 0x14, prog.length)
  }

  private def startSession(dut: FrontendSpecHarness): Unit =
    { writeCtrl(dut, 0x0, 1); writeCtrl(dut, 0x0, 0) }

  private def waitDone(dut: FrontendSpecHarness, max: Int = 500000): Int = {
    var n = 0
    dut.io.ctrl_addr.poke(0x0.U)
    while (((dut.io.ctrl_rdata.peek().litValue >> 1) & 1) == 0 && n < max) {
      dut.clock.step(); n += 1
    }
    assert(((dut.io.ctrl_rdata.peek().litValue >> 1) & 1) == 1, s"done not reached within $max cycles")
    n
  }

  "Frontend perf counters" should "be readable, frozen at done, and reset at session start" in {
    val K = 32
    simulate(new FrontendSpecHarness(K = K)) { dut =>
      stageResidentRound(dut, K)
      startSession(dut)
      waitDone(dut)

      val macs = readCtrl(dut, 0x44)
      val cyc1 = readCtrl(dut, 0x40)
      assert(macs == BigInt(8 * K * K), s"0x44 should report 8*K^2 MACs, got $macs")
      assert(cyc1 > 0, "0x40 cycle count must be non-zero")

      // 50 idle cycles must not change the latched cycle count: a host polling
      // `done` over ctrl_lite before reading 0x40 must not see polling latency.
      for (_ <- 0 until 50) { dut.clock.step() }
      val cyc2 = readCtrl(dut, 0x40)
      assert(cyc1 == cyc2, s"0x40 must be frozen at done (cyc1=$cyc1 cyc2=$cyc2 after 50 idle cycles)")
      println(s"[perf/fe] macs=$macs cycles=$cyc1 macs/cyc=${macs.toDouble / cyc1.toDouble}")

      // session_reset (start edge) resets the latched MAC count
      startSession(dut)
      val macsAfterReset = readCtrl(dut, 0x44)
      assert(macsAfterReset == 0, s"0x44 must be 0 immediately after session start, got $macsAfterReset")
    }
  }
}
