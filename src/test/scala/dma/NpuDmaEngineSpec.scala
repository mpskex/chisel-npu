// See README.md for license details.
// Unit tests for NpuDmaEngine against the AXI4 RAM model.

package dma

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import chisel3.util.log2Ceil
import org.scalatest.flatspec.AnyFlatSpec
import testUtil._

object DmaSpecHarness {
  val K = 32
  val N = 8
}

/** Test harness: DMA engine + AXI RAM model wired at elaboration time
  * (EphemeralSimulator forbids Module creation inside the simulate body). */
class DmaSpecHarness(val K: Int = DmaSpecHarness.K,
                     val N: Int = DmaSpecHarness.N,
                     val readTimeout: Int = 16384,
                     val dropReads: Int = 0) extends Module {
  val io = IO(new Bundle {
    val req_valid   = Input(Bool())
    val req_ready   = Output(Bool())
    val req_dir     = Input(UInt(2.W))
    val req_width   = Input(UInt(2.W))
    val req_rf_addr = Input(UInt(5.W))
    val req_l3_addr = Input(UInt(32.W))
    val busy        = Output(Bool())
    val done        = Output(Bool())

    val acc_valid   = Output(Bool())
    val acc_out     = Output(Vec(K, UInt((4 * N).W)))

    val instr_valid = Output(Bool())
    val instr_out   = Output(UInt(128.W))

    val rf_w_vx_en   = Output(Bool())
    val rf_w_vx_addr = Output(UInt(log2Ceil(K).W))
    val rf_w_vx_data = Output(Vec(K, UInt(N.W)))
    val rf_w_ve_en   = Output(Bool())
    val rf_w_ve_addr = Output(UInt(log2Ceil(K / 2).W))
    val rf_w_ve_data = Output(Vec(K, UInt((2 * N).W)))
    val rf_w_vr_en   = Output(Bool())
    val rf_w_vr_addr = Output(UInt(log2Ceil(K / 4).W))
    val rf_w_vr_data = Output(Vec(K, UInt((4 * N).W)))

    val rf_r_vx_addr = Output(UInt(log2Ceil(K).W))
    val rf_r_vx_data = Input(Vec(K, UInt(N.W)))
    val rf_r_ve_addr = Output(UInt(log2Ceil(K / 2).W))
    val rf_r_ve_data = Input(Vec(K, UInt((2 * N).W)))
    val rf_r_vr_addr = Output(UInt(log2Ceil(K / 4).W))
    val rf_r_vr_data = Input(Vec(K, UInt((4 * N).W)))

    // RAM test hooks
    val dbg_rd_addr = Input(UInt(12.W))
    val dbg_rd_data = Output(UInt(128.W))
    val pre_wr_en   = Input(Bool())
    val pre_wr_addr = Input(UInt(12.W))
    val pre_wr_data = Input(UInt(128.W))
  })

  val dma = Module(new NpuDmaEngine(K, N, readTimeout))
  val ram = Module(new AxiRamModel(dropReads))

  dma.io.req_valid   := io.req_valid
  io.req_ready       := dma.io.req_ready
  dma.io.req_dir     := io.req_dir
  dma.io.req_width   := io.req_width
  dma.io.req_rf_addr := io.req_rf_addr
  dma.io.req_l3_addr := io.req_l3_addr
  io.busy            := dma.io.busy
  io.done            := dma.io.done
  io.acc_valid       := dma.io.acc_valid
  io.acc_out         := dma.io.acc_out
  io.instr_valid     := dma.io.instr_valid
  io.instr_out       := dma.io.instr_out

  io.rf_w_vx_en   := dma.io.rf_w_vx_en
  io.rf_w_vx_addr := dma.io.rf_w_vx_addr
  io.rf_w_vx_data := dma.io.rf_w_vx_data
  io.rf_w_ve_en   := dma.io.rf_w_ve_en
  io.rf_w_ve_addr := dma.io.rf_w_ve_addr
  io.rf_w_ve_data := dma.io.rf_w_ve_data
  io.rf_w_vr_en   := dma.io.rf_w_vr_en
  io.rf_w_vr_addr := dma.io.rf_w_vr_addr
  io.rf_w_vr_data := dma.io.rf_w_vr_data

  io.rf_r_vx_addr := dma.io.rf_r_vx_addr
  dma.io.rf_r_vx_data := io.rf_r_vx_data
  io.rf_r_ve_addr := dma.io.rf_r_ve_addr
  dma.io.rf_r_ve_data := io.rf_r_ve_data
  io.rf_r_vr_addr := dma.io.rf_r_vr_addr
  dma.io.rf_r_vr_data := io.rf_r_vr_data

  ram.io.s_axi_awaddr  := dma.io.m_axi_awaddr
  ram.io.s_axi_awlen   := dma.io.m_axi_awlen
  ram.io.s_axi_awsize  := dma.io.m_axi_awsize
  ram.io.s_axi_awburst := dma.io.m_axi_awburst
  ram.io.s_axi_awvalid := dma.io.m_axi_awvalid
  dma.io.m_axi_awready := ram.io.s_axi_awready
  ram.io.s_axi_wdata   := dma.io.m_axi_wdata
  ram.io.s_axi_wstrb   := dma.io.m_axi_wstrb
  ram.io.s_axi_wlast   := dma.io.m_axi_wlast
  ram.io.s_axi_wvalid  := dma.io.m_axi_wvalid
  dma.io.m_axi_wready  := ram.io.s_axi_wready
  dma.io.m_axi_bvalid  := ram.io.s_axi_bvalid
  ram.io.s_axi_bready  := dma.io.m_axi_bready
  ram.io.s_axi_araddr  := dma.io.m_axi_araddr
  ram.io.s_axi_arlen   := dma.io.m_axi_arlen
  ram.io.s_axi_arsize  := dma.io.m_axi_arsize
  ram.io.s_axi_arburst := dma.io.m_axi_arburst
  ram.io.s_axi_arvalid := dma.io.m_axi_arvalid
  dma.io.m_axi_arready := ram.io.s_axi_arready
  dma.io.m_axi_rdata   := ram.io.s_axi_rdata
  dma.io.m_axi_rlast   := ram.io.s_axi_rlast
  dma.io.m_axi_rvalid  := ram.io.s_axi_rvalid
  ram.io.s_axi_rready  := dma.io.m_axi_rready

  ram.io.dbg_rd_addr := io.dbg_rd_addr
  io.dbg_rd_data     := ram.io.dbg_rd_data
  ram.io.pre_wr_en   := io.pre_wr_en
  ram.io.pre_wr_addr := io.pre_wr_addr
  ram.io.pre_wr_data := io.pre_wr_data
}

class NpuDmaEngineSpec extends AnyFlatSpec {

  import DmaSpecHarness._

  val RAM_ADDR = 0x1000  // L3 test window base

  // K=16 variant: the same DMA with a 16-lane RF (1/2/4 beats for VX/VE/VR)
  "NpuDmaEngine" should "load VX at K=16 (16 B, 1 beat)" in {
    simulate(new DmaSpecHarness(K = 16, readTimeout = 8)) { dut =>
      val pat = pattern(RAM_ADDR, 16)
      preload(dut, RAM_ADDR, pat)
      issue(dut, dir = 0, width = 0, rfAddr = 3, l3Addr = RAM_ADDR)
      runCapturing(dut, dut.io.rf_w_vx_en.peek().litToBoolean, {
        dut.io.rf_w_vx_addr.expect(3.U)
        for (lane <- 0 until 16) dut.io.rf_w_vx_data(lane).expect(pat(lane).U, s"VX16 lane $lane")
      })
    }
  }

  "NpuDmaEngine" should "store VR at K=16 (64 B, 4 beats)" in {
    simulate(new DmaSpecHarness(K = 16, readTimeout = 8)) { dut =>
      val pat = pattern(0x400, 64)
      for (lane <- 0 until 16) {
        var v = 0L
        for (b <- 0 until 4) v |= (pat(4 * lane + b).toLong << (8 * b))
        dut.io.rf_r_vr_data(lane).poke(v.U)
      }
      issue(dut, dir = 1, width = 2, rfAddr = 0, l3Addr = RAM_ADDR)
      waitDone(dut)
      checkStored(dut, RAM_ADDR, 64, pat)
    }
  }

  def preload(dut: DmaSpecHarness, base: Int, bytes: Seq[Int]): Unit = {
    for ((b, i) <- bytes.grouped(16).zipWithIndex) {
      var w = BigInt(0)
      for ((v, j) <- b.zipWithIndex) w |= (BigInt(v & 0xFF) << (8 * j))
      dut.io.pre_wr_addr.poke(((base + i * 16) >> 4).U)
      dut.io.pre_wr_data.poke(w.U)
      dut.io.pre_wr_en.poke(true.B)
      dut.clock.step()
      dut.io.pre_wr_en.poke(false.B)
    }
  }

  def readWord(dut: DmaSpecHarness, addr: Int): BigInt = {
    dut.io.dbg_rd_addr.poke((addr >> 4).U)
    dut.clock.step(0)
    dut.io.dbg_rd_data.peek().litValue
  }

  def issue(dut: DmaSpecHarness, dir: Int, width: Int, rfAddr: Int, l3Addr: Int): Unit = {
    dut.io.req_dir.poke(dir.U)
    dut.io.req_width.poke(width.U)
    dut.io.req_rf_addr.poke(rfAddr.U)
    dut.io.req_l3_addr.poke(l3Addr.U)
    dut.io.req_valid.poke(true.B)
    dut.clock.step()
    dut.io.req_valid.poke(false.B)
  }

  def waitDone(dut: DmaSpecHarness, maxCycles: Int = 2000): Unit = {
    var n = 0
    while (!dut.io.done.peek().litToBoolean && n < maxCycles) { dut.clock.step(); n += 1 }
    assert(dut.io.done.peek().litToBoolean, s"DMA did not finish within $maxCycles cycles")
  }

  /** Step until done; while stepping, if `enFn` is true run `check` once
    * (the RF write pulse fires one cycle before DONE). */
  def runCapturing(dut: DmaSpecHarness, enFn: => Boolean, check: => Unit, maxCycles: Int = 2000): Unit = {
    var captured = false
    var n = 0
    while (!dut.io.done.peek().litToBoolean && n < maxCycles) {
      if (!captured && enFn) { check; captured = true }
      dut.clock.step(); n += 1
    }
    assert(dut.io.done.peek().litToBoolean, s"DMA did not finish within $maxCycles cycles")
    assert(captured, "RF write pulse was never observed")
  }

  def pattern(base: Int, nbytes: Int): Seq[Int] =
    Seq.tabulate(nbytes)(i => (base + i) & 0xFF)

  def checkStored(dut: DmaSpecHarness, addr: Int, nbytes: Int, pat: Seq[Int]): Unit = {
    for (w <- 0 until nbytes / 16) {
      val got = readWord(dut, addr + 16 * w)
      for (b <- 0 until 16) {
        val exp = BigInt(pat(16 * w + b) & 0xFF)
        val act = (got >> (8 * b)) & 0xFF
        assert(act == exp, s"store word $w byte $b @ 0x${(addr + 16 * w + b).toHexString}: got $act want $exp")
      }
    }
  }

  // ==========================================================================

  "NpuDmaEngine" should "load VX (32 B) from L3 to RF" in {
    simulate(new DmaSpecHarness(readTimeout = 8)) { dut =>
      val pat = pattern(RAM_ADDR, 32)
      preload(dut, RAM_ADDR, pat)
      issue(dut, dir = 0, width = 0, rfAddr = 5, l3Addr = RAM_ADDR)
      runCapturing(dut, dut.io.rf_w_vx_en.peek().litToBoolean, {
        dut.io.rf_w_vx_addr.expect(5.U)
        for (lane <- 0 until K) dut.io.rf_w_vx_data(lane).expect(pat(lane).U, s"VX lane $lane")
      })
    }
  }

  "NpuDmaEngine" should "load VE (64 B) from L3 to RF" in {
    simulate(new DmaSpecHarness(readTimeout = 8)) { dut =>
      val pat = pattern(RAM_ADDR, 64)
      preload(dut, RAM_ADDR, pat)
      issue(dut, dir = 0, width = 1, rfAddr = 3, l3Addr = RAM_ADDR)
      runCapturing(dut, dut.io.rf_w_ve_en.peek().litToBoolean, {
        dut.io.rf_w_ve_addr.expect(3.U)
        for (lane <- 0 until K) {
          val lo = pat(2 * lane).toLong
          val hi = pat(2 * lane + 1).toLong
          dut.io.rf_w_ve_data(lane).expect(((hi << 8) | lo).U, s"VE lane $lane")
        }
      })
    }
  }

  "NpuDmaEngine" should "load VR (128 B) from L3 to RF" in {
    simulate(new DmaSpecHarness(readTimeout = 8)) { dut =>
      val pat = pattern(RAM_ADDR, 128)
      preload(dut, RAM_ADDR, pat)
      issue(dut, dir = 0, width = 2, rfAddr = 1, l3Addr = RAM_ADDR)
      runCapturing(dut, dut.io.rf_w_vr_en.peek().litToBoolean, {
        dut.io.rf_w_vr_addr.expect(1.U)
        for (lane <- 0 until K) {
          var v = 0L
          for (b <- 0 until 4) v |= (pat(4 * lane + b).toLong << (8 * b))
          dut.io.rf_w_vr_data(lane).expect(v.U, s"VR lane $lane")
        }
      })
    }
  }

  "NpuDmaEngine" should "store VX (32 B) from RF to L3" in {
    simulate(new DmaSpecHarness(readTimeout = 8)) { dut =>
      val pat = pattern(0x200, 32)
      for (lane <- 0 until K) dut.io.rf_r_vx_data(lane).poke(pat(lane).U)
      issue(dut, dir = 1, width = 0, rfAddr = 9, l3Addr = RAM_ADDR)
      waitDone(dut)
      checkStored(dut, RAM_ADDR, 32, pat)
    }
  }

  "NpuDmaEngine" should "store VE (64 B) from RF to L3" in {
    simulate(new DmaSpecHarness(readTimeout = 8)) { dut =>
      val pat = pattern(0x300, 64)
      for (lane <- 0 until K) {
        val lo = pat(2 * lane).toLong
        val hi = pat(2 * lane + 1).toLong
        dut.io.rf_r_ve_data(lane).poke(((hi << 8) | lo).U)
      }
      issue(dut, dir = 1, width = 1, rfAddr = 2, l3Addr = RAM_ADDR)
      waitDone(dut)
      checkStored(dut, RAM_ADDR, 64, pat)
    }
  }

  "NpuDmaEngine" should "store VR (128 B) from RF to L3" in {
    simulate(new DmaSpecHarness(readTimeout = 8)) { dut =>
      val pat = pattern(0x400, 128)
      for (lane <- 0 until K) {
        var v = 0L
        for (b <- 0 until 4) v |= (pat(4 * lane + b).toLong << (8 * b))
        dut.io.rf_r_vr_data(lane).poke(v.U)
      }
      issue(dut, dir = 1, width = 2, rfAddr = 0, l3Addr = RAM_ADDR)
      waitDone(dut)
      checkStored(dut, RAM_ADDR, 128, pat)
    }
  }

  "NpuDmaEngine" should "re-issue the read after a dropped AR request (read timeout retry)" in {
    simulate(new DmaSpecHarness(readTimeout = 8, dropReads = 1)) { dut =>
      val pat = pattern(RAM_ADDR, 32)
      preload(dut, RAM_ADDR, pat)
      issue(dut, dir = 0, width = 0, rfAddr = 7, l3Addr = RAM_ADDR)
      runCapturing(dut, dut.io.rf_w_vx_en.peek().litToBoolean, {
        for (lane <- 0 until K) dut.io.rf_w_vx_data(lane).expect(pat(lane).U, s"retry VX lane $lane")
      }, maxCycles = 5000)
    }
  }

  "NpuDmaEngine" should "read ACCUM (128 B, K×1) from L3 into the acc buffer" in {
    simulate(new DmaSpecHarness(readTimeout = 8)) { dut =>
      val pat = pattern(0x500, 128)
      preload(dut, RAM_ADDR, pat)
      issue(dut, dir = 2, width = 2, rfAddr = 0, l3Addr = RAM_ADDR)
      var captured = false
      var n = 0
      while (!dut.io.done.peek().litToBoolean && n < 2000) {
        if (!captured && dut.io.acc_valid.peek().litToBoolean) {
          for (lane <- 0 until K) {
            var v = 0L
            for (b <- 0 until 4) v |= (pat(4 * lane + b).toLong << (8 * b))
            dut.io.acc_out(lane).expect(v.U, s"ACCUM lane $lane")
          }
          captured = true
        }
        dut.clock.step(); n += 1
      }
      assert(dut.io.done.peek().litToBoolean, "ACCUM read did not finish")
      assert(captured, "acc_valid never pulsed")
    }
  }

  "NpuDmaEngine" should "fetch a 16 B instruction line (dir=3)" in {
    simulate(new DmaSpecHarness(readTimeout = 8)) { dut =>
      val pat = pattern(0x600, 16)
      preload(dut, RAM_ADDR, pat)
      issue(dut, dir = 3, width = 0, rfAddr = 0, l3Addr = RAM_ADDR)
      var captured = false
      var n = 0
      while (!dut.io.done.peek().litToBoolean && n < 2000) {
        if (!captured && dut.io.instr_valid.peek().litToBoolean) {
          val got = dut.io.instr_out.peek().litValue
          for (b <- 0 until 16) {
            val exp = BigInt(pat(b) & 0xFF)
            val act = (got >> (8 * b)) & 0xFF
            assert(act == exp, s"fetch byte $b: got $act want $exp")
          }
          captured = true
        }
        dut.clock.step(); n += 1
      }
      assert(dut.io.done.peek().litToBoolean, "fetch did not finish")
      assert(captured, "instr_valid never pulsed")
    }
  }

  "NpuDmaEngine" should "accept only one request at a time (busy/ready)" in {
    simulate(new DmaSpecHarness(readTimeout = 8)) { dut =>
      issue(dut, dir = 0, width = 0, rfAddr = 0, l3Addr = RAM_ADDR)
      dut.io.req_ready.expect(false.B)
      waitDone(dut)
      dut.clock.step()  // DONE → IDLE
      dut.io.req_ready.expect(true.B)
    }
  }
}
