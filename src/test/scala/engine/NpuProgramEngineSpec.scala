// See README.md for license details.
// Integration tests for NpuProgramEngine: full programs against the AXI RAM
// model, pinning the silicon-proven one-shot MMA formula:
//     OUT[i] = A[i] · B[K-1] + ACCUM[i]

package engine

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec
import testUtil._
import isa._
import isa.NpuAssembler._

class EngineSpecHarness(val K: Int = 32) extends Module {
  val io = IO(new Bundle {
    val instr       = Input(UInt(32.W))
    val instr_valid = Input(Bool())
    val instr_ready = Output(Bool())
    val illegal_out = Output(Bool())

    val dbg_rd_addr = Input(UInt(12.W))
    val dbg_rd_data = Output(UInt(128.W))
    val pre_wr_en   = Input(Bool())
    val pre_wr_addr = Input(UInt(12.W))
    val pre_wr_data = Input(UInt(128.W))

    // RF VX debug read
    val dbg_vx_addr = Input(UInt(5.W))
    val dbg_vx_data = Output(Vec(K, UInt(8.W)))
  })

  val eng = Module(new NpuProgramEngine(K, 8))
  val ram = Module(new AxiRamModel())

  eng.io.instr       := io.instr
  eng.io.instr_valid := io.instr_valid
  io.instr_ready     := eng.io.instr_ready
  io.illegal_out     := eng.io.illegal_out

  eng.io.dbg_vx_addr := io.dbg_vx_addr
  io.dbg_vx_data     := eng.io.dbg_vx_data

  eng.io.fetch_fill_req  := false.B
  eng.io.fetch_fill_addr := 0.U

  ram.io.s_axi_awaddr  := eng.io.m_axi_awaddr
  ram.io.s_axi_awlen   := eng.io.m_axi_awlen
  ram.io.s_axi_awsize  := eng.io.m_axi_awsize
  ram.io.s_axi_awburst := eng.io.m_axi_awburst
  ram.io.s_axi_awvalid := eng.io.m_axi_awvalid
  eng.io.m_axi_awready := ram.io.s_axi_awready
  ram.io.s_axi_wdata   := eng.io.m_axi_wdata
  ram.io.s_axi_wstrb   := eng.io.m_axi_wstrb
  ram.io.s_axi_wlast   := eng.io.m_axi_wlast
  ram.io.s_axi_wvalid  := eng.io.m_axi_wvalid
  eng.io.m_axi_wready  := ram.io.s_axi_wready
  eng.io.m_axi_bvalid  := ram.io.s_axi_bvalid
  ram.io.s_axi_bready  := eng.io.m_axi_bready
  ram.io.s_axi_araddr  := eng.io.m_axi_araddr
  ram.io.s_axi_arlen   := eng.io.m_axi_arlen
  ram.io.s_axi_arsize  := eng.io.m_axi_arsize
  ram.io.s_axi_arburst := eng.io.m_axi_arburst
  ram.io.s_axi_arvalid := eng.io.m_axi_arvalid
  eng.io.m_axi_arready := ram.io.s_axi_arready
  eng.io.m_axi_rdata   := ram.io.s_axi_rdata
  eng.io.m_axi_rlast   := ram.io.s_axi_rlast
  eng.io.m_axi_rvalid  := ram.io.s_axi_rvalid
  ram.io.s_axi_rready  := eng.io.m_axi_rready

  ram.io.dbg_rd_addr := io.dbg_rd_addr
  io.dbg_rd_data     := ram.io.dbg_rd_data
  ram.io.pre_wr_en   := io.pre_wr_en
  ram.io.pre_wr_addr := io.pre_wr_addr
  ram.io.pre_wr_data := io.pre_wr_data
}

class NpuProgramEngineSpec extends AnyFlatSpec {

  val K = 32

  // ---- RAM helpers ---------------------------------------------------------

  def preload(dut: EngineSpecHarness, base: Long, bytes: Seq[Int]): Unit = {
    for ((b, i) <- bytes.grouped(16).zipWithIndex) {
      var w = BigInt(0)
      for ((v, j) <- b.zipWithIndex) w |= (BigInt(v & 0xFF) << (8 * j))
      dut.io.pre_wr_addr.poke((((base + i * 16) >> 4) & 0xFFF).U)
      dut.io.pre_wr_data.poke(w.U)
      dut.io.pre_wr_en.poke(true.B)
      dut.clock.step()
      dut.io.pre_wr_en.poke(false.B)
    }
  }

  /** Read nbytes of little-endian bytes starting at *base*. */
  def readBytes(dut: EngineSpecHarness, base: Long, nbytes: Int): Seq[Int] = {
    val out = Array.fill(nbytes)(0)
    for (w <- 0 until nbytes / 16) {
      dut.io.dbg_rd_addr.poke((((base + 16 * w) >> 4) & 0xFFF).U)
      dut.clock.step(0)
      val got = dut.io.dbg_rd_data.peek().litValue
      for (b <- 0 until 16) out(16 * w + b) = ((got >> (8 * b)) & 0xFF).toInt
    }
    out.toSeq
  }

  def readI32(dut: EngineSpecHarness, base: Long, n: Int): Array[Int] = {
    val bytes = readBytes(dut, base, 4 * n)
    Array.tabulate(n)(i =>
      bytes(4 * i) | (bytes(4 * i + 1) << 8) | (bytes(4 * i + 2) << 16) | (bytes(4 * i + 3) << 24))
  }

  def preloadI32(dut: EngineSpecHarness, base: Long, values: Seq[Int]): Unit = {
    val bytes = values.flatMap(v => Seq(v & 0xFF, (v >> 8) & 0xFF, (v >> 16) & 0xFF, (v >> 24) & 0xFF))
    preload(dut, base, bytes)
  }

  def preloadI8(dut: EngineSpecHarness, base: Long, values: Seq[Int]): Unit =
    preload(dut, base, values.map(_ & 0xFF))

  /** Issue one instruction and wait for the engine to complete it. */
  def issue(dut: EngineSpecHarness, instr: Int, maxCycles: Int = 100000): Unit = {
    dut.io.instr.poke((instr.toLong & 0xFFFFFFFFL).U)
    dut.io.instr_valid.poke(true.B)
    var n = 0
    while (!dut.io.instr_ready.peek().litToBoolean && n < maxCycles) { dut.clock.step(); n += 1 }
    assert(dut.io.instr_ready.peek().litToBoolean, s"engine never accepted 0x${instr.toHexString}")
    dut.clock.step()                 // leave IDLE
    dut.io.instr_valid.poke(false.B)
    n = 0
    while (!dut.io.instr_ready.peek().litToBoolean && n < maxCycles) { dut.clock.step(); n += 1 }
    assert(dut.io.instr_ready.peek().litToBoolean, s"engine never finished 0x${instr.toHexString}")
  }

  // ==========================================================================

  "NpuProgramEngine" should "reproduce the one-shot MMA formula OUT[i]=A[i]·B[K-1]+ACCUM[i]" in {
    simulate(new EngineSpecHarness) { dut =>
      val a = Array.tabulate(K)(i => i + 1)             // 1..32
      val b = Array.tabulate(K)(i => i + 1)             // 1..32
      val acc = Array.tabulate(K)(i => 1000 * i + 7)

      preloadI8(dut, NpuSections.A, a)
      preloadI8(dut, NpuSections.B, b)
      preloadI32(dut, NpuSections.ACCUM, acc)

      // vle8 A → VX[0]; vle8 B → VX[1]; mma.last VR[0]; vse32 VR[0] → OUT
      issue(dut, vle8(rd = 0, sect = NpuSections.SECT_A, off = 0))
      issue(dut, vle8(rd = 1, sect = NpuSections.SECT_B, off = 0))
      issue(dut, mmaLast(rd = 0, rs1 = 0, rs2 = 1, keep = false))
      issue(dut, vse32(src = 0, sect = NpuSections.SECT_OUT, off = 0))

      val out = readI32(dut, NpuSections.OUT, K)
      for (i <- 0 until K) {
        val exp = a(i) * b(K - 1) + acc(i)
        assert(out(i) == exp, s"lane $i: got ${out(i)} want $exp (a=${a(i)} b31=${b(K-1)} acc=${acc(i)})")
      }
    }
  }

  "NpuProgramEngine" should "compute per-column K×1 results (K×K program)" in {
    simulate(new EngineSpecHarness) { dut =>
      val a = Array.tabulate(K)(i => (i * 3 + 1) & 0xFF)
      // B: K×K row-major, B[k][j] = k*100 + j.  A vle8 at offset j*32 loads
      // B ROW j; the one-shot formula uses its lane K-1 = B[j][K-1].
      val bBytes = Array.tabulate(K * K)(idx => (idx / K) * 100 + (idx % K))
      val acc = Array.tabulate(K)(i => 7 - i)

      preloadI8(dut, NpuSections.A, a)
      preloadI8(dut, NpuSections.B, bBytes)
      preloadI32(dut, NpuSections.ACCUM, acc)

      issue(dut, vle8(rd = 0, sect = NpuSections.SECT_A, off = 0))
      val cols = 4
      for (j <- 0 until cols) {
        // Collect to VR[4+j] (VX[16..19+..]) — away from the VX[0]/VX[1]
        // operands (VR aliases VX rows, writes would clobber them).
        issue(dut, vle8(rd = 1, sect = NpuSections.SECT_B, off = j * K))
        issue(dut, mmaLast(rd = 4 + j, rs1 = 0, rs2 = 1, keep = false))
        issue(dut, vse32(src = 4 + j, sect = NpuSections.SECT_OUT, off = j * 128))
      }

      val out = readI32(dut, NpuSections.OUT, K * cols)
      def s8(v: Int): Int = { val b = v & 0xFF; if (b >= 128) b - 256 else b }  // signed int8
      for (j <- 0 until cols) {
        val bLast = s8(j * 100 + 31)   // B row j, lane K-1 (signed int8)
        for (i <- 0 until K) {
          val exp = a(i) * bLast + acc(i)
          val got = out(j * K + i)
          assert(got == exp, s"col $j lane $i: got $got want $exp")
        }
      }
    }
  }

  "NpuProgramEngine" should "accumulate across programs via the L3 ACCUM operand" in {
    simulate(new EngineSpecHarness) { dut =>
      val a1 = Array.tabulate(K)(i => i + 1)
      val b1 = Array.tabulate(K)(i => i + 1)
      val a2 = Array.tabulate(K)(i => (i * 5 + 3) & 0xFF)
      val b2 = Array.tabulate(K)(i => (i * 7 + 2) & 0xFF)
      def s8(v: Int): Int = { val b = v & 0xFF; if (b >= 128) b - 256 else b }

      preloadI8(dut, NpuSections.A, a1)
      preloadI8(dut, NpuSections.B, b1)
      // second operand pair in the A/B section tails (offsets < 1 KiB)
      preloadI8(dut, NpuSections.A + 512, a2)
      preloadI8(dut, NpuSections.A + 768, b2)
      preloadI32(dut, NpuSections.ACCUM, Array.fill(K)(0))

      // Program A: OUT1 = a1·b1[K-1] + ACCUM(0)
      issue(dut, vle8(rd = 0, sect = NpuSections.SECT_A, off = 0))
      issue(dut, vle8(rd = 1, sect = NpuSections.SECT_B, off = 0))
      issue(dut, mmaLast(rd = 4, rs1 = 0, rs2 = 1, keep = false))
      issue(dut, vse32(src = 4, sect = NpuSections.SECT_OUT, off = 0))
      val out1 = readI32(dut, NpuSections.OUT, K)
      for (i <- 0 until K) assert(out1(i) == a1(i) * s8(b1(K - 1)), s"progA lane $i")

      // Host accumulates: stage ACCUM = OUT1, then Program B accumulates on top.
      preloadI32(dut, NpuSections.ACCUM, out1)
      issue(dut, vle8(rd = 2, sect = NpuSections.SECT_A, off = 512))
      issue(dut, vle8(rd = 3, sect = NpuSections.SECT_A, off = 768))
      issue(dut, mmaLast(rd = 4, rs1 = 2, rs2 = 3, keep = false))
      issue(dut, vse32(src = 4, sect = NpuSections.SECT_OUT, off = 128))
      val out2 = readI32(dut, NpuSections.OUT + 128, K)
      for (i <- 0 until K) {
        val exp = s8(a2(i)) * s8(b2(K - 1)) + out1(i)
        assert(out2(i) == exp, s"progB lane $i: got ${out2(i)} want $exp (a2=${a2(i)} b2l=${s8(b2(K-1))} prev=${out1(i)})")
      }
    }
  }

  "NpuProgramEngine" should "move VE and VR vectors between L3 and the RF" in {
    simulate(new EngineSpecHarness) { dut =>
      // VE roundtrip: 64 B of int16
      val ve = Array.tabulate(K)(i => 0x2000 + i)
      val veBytes = ve.flatMap(v => Seq(v & 0xFF, (v >> 8) & 0xFF))
      preload(dut, NpuSections.A, veBytes)
      issue(dut, vle16(rd = 0, sect = NpuSections.SECT_A, off = 0))
      issue(dut, vse16(src = 0, sect = NpuSections.SECT_OUT, off = 0))
      val gotVe = readBytes(dut, NpuSections.OUT, 64)
      assert(gotVe == veBytes.toSeq, "VE roundtrip mismatch")

      // VR roundtrip: 128 B of int32
      val vr = Array.tabulate(K)(i => 0x1000000 + i * 0x101)
      val vrBytes = vr.flatMap(v => Seq(v & 0xFF, (v >> 8) & 0xFF, (v >> 16) & 0xFF, (v >> 24) & 0xFF))
      preload(dut, NpuSections.A, vrBytes)
      issue(dut, vle32(rd = 0, sect = NpuSections.SECT_A, off = 0))
      issue(dut, vse32(src = 0, sect = NpuSections.SECT_OUT, off = 128))
      val gotVr = readBytes(dut, NpuSections.OUT + 128, 128)
      assert(gotVr == vrBytes.toSeq, "VR roundtrip mismatch")
    }
  }

  /** Issue one instruction, waiting for acceptance then completion; returns
    * true if io.illegal_out pulsed at any point during execution. */
  def issueCollect(dut: EngineSpecHarness, instr: Int, maxCycles: Int = 100000): Boolean = {
    dut.io.instr.poke((instr.toLong & 0xFFFFFFFFL).U)
    dut.io.instr_valid.poke(true.B)
    var illegal = false
    var n = 0
    while (!dut.io.instr_ready.peek().litToBoolean && n < maxCycles) {
      if (dut.io.illegal_out.peek().litToBoolean) illegal = true
      dut.clock.step(); n += 1
    }
    assert(dut.io.instr_ready.peek().litToBoolean, s"engine never accepted 0x${instr.toHexString}")
    dut.clock.step()                 // leave IDLE
    dut.io.instr_valid.poke(false.B)
    while (!dut.io.instr_ready.peek().litToBoolean && n < maxCycles) {
      if (dut.io.illegal_out.peek().litToBoolean) illegal = true
      dut.clock.step(); n += 1
    }
    assert(dut.io.instr_ready.peek().litToBoolean, s"engine never finished 0x${instr.toHexString}")
    illegal
  }

  "NpuProgramEngine" should "flag illegal and unsupported instructions" in {
    simulate(new EngineSpecHarness) { dut =>
      assert(issueCollect(dut, 0x7F), "reserved opcode must set illegal_out")
      assert(issueCollect(dut, vadd(rd = 0, rs1 = 1, rs2 = 2)), "VALU instruction (unsupported) must set illegal_out")
      assert(issueCollect(dut, vle8(rd = 0, sect = 4, off = 0)), "bad section selector must set illegal_out")
      assert(!issueCollect(dut, nop), "NOP must not set illegal_out")
    }
  }

  "NpuProgramEngine" should "skip NOP padding instructions" in {
    simulate(new EngineSpecHarness) { dut =>
      issue(dut, nop)
      issue(dut, nop)
      issue(dut, vle8(rd = 0, sect = NpuSections.SECT_A, off = 0))
      issue(dut, vse8(src = 0, sect = NpuSections.SECT_OUT, off = 0))
      // A section byte 0 was never written → RAM still zero
      val got = readBytes(dut, NpuSections.OUT, 32)
      assert(got.forall(_ == 0), "NOP-padded program must not disturb memory")
    }
  }

  // ==========================================================================
  // K=16 variants (the FPGA bitstream configuration)
  // ==========================================================================

  "NpuProgramEngine" should "reproduce the one-shot formula at K=16" in {
    simulate(new EngineSpecHarness(K = 16)) { dut =>
      val k = 16
      val a = Array.tabulate(k)(i => i + 1)
      val b = Array.tabulate(k)(i => (i * 3 + 1) & 0xFF)
      val acc = Array.tabulate(k)(i => 100 * i + 7)
      def s8(v: Int): Int = { val x = v & 0xFF; if (x >= 128) x - 256 else x }

      preloadI8(dut, NpuSections.A, a)
      preloadI8(dut, NpuSections.B, b)
      preloadI32(dut, NpuSections.ACCUM, acc)

      issue(dut, vle8(rd = 0, sect = NpuSections.SECT_A, off = 0))
      issue(dut, vle8(rd = 1, sect = NpuSections.SECT_B, off = 0))
      issue(dut, mmaLast(rd = 4, rs1 = 0, rs2 = 1, keep = false))
      issue(dut, vse32(src = 4, sect = NpuSections.SECT_OUT, off = 0))

      val out = readI32(dut, NpuSections.OUT, k)
      for (i <- 0 until k) {
        val exp = a(i) * s8(b(k - 1)) + acc(i)
        assert(out(i) == exp, s"K16 lane $i: got ${out(i)} want $exp")
      }
    }
  }

  "NpuProgramEngine" should "run a per-column K×K program at K=16" in {
    simulate(new EngineSpecHarness(K = 16)) { dut =>
      val k = 16
      val a = Array.tabulate(k)(i => i + 1)
      val b = Array.tabulate(k * k)(idx => (idx / k) * 40 + (idx % k))
      val acc = Array.fill(k)(0)
      def s8(v: Int): Int = { val x = v & 0xFF; if (x >= 128) x - 256 else x }

      preloadI8(dut, NpuSections.A, a)
      preloadI8(dut, NpuSections.B, b)
      preloadI32(dut, NpuSections.ACCUM, acc)

      val cols = 4
      issue(dut, vle8(rd = 0, sect = NpuSections.SECT_A, off = 0))
      for (j <- 0 until cols) {
        // K=16 VR file = VR[0..3] (VX[0..15]); rd=2 → VR[2] = VX[8..11],
        // away from the VX[0]/VX[1] operands.  Store immediately per column.
        issue(dut, vle8(rd = 1, sect = NpuSections.SECT_B, off = j * k))
        issue(dut, mmaLast(rd = 2, rs1 = 0, rs2 = 1, keep = false))
        issue(dut, vse32(src = 2, sect = NpuSections.SECT_OUT, off = j * 64))
      }

      val out = readI32(dut, NpuSections.OUT, k * cols)
      for (j <- 0 until cols) {
        val bLast = s8(j * 40 + 15)
        for (i <- 0 until k) {
          assert(out(j * k + i) == a(i) * bLast + acc(i),
            s"K16 col $j lane $i: got ${out(j * k + i)} want ${a(i) * bLast}")
        }
      }
    }
  }
}
