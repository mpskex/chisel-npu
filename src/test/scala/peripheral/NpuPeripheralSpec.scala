// See README.md for license details.
// -----------------------------------------------------------------------------
//  NpuPeripheralSpec — end-to-end NPUCTL + NPU + segmented AXI translation.
//
//  Runs a K=16 one-shot MMA with the five segment bases relocated into the
//  generic AXI RAM model, so the core never sees a system address.
// -----------------------------------------------------------------------------

package peripheral

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec
import testUtil._
import isa.NpuAssembler._
import engine.NpuSections

class PeripheralHarness extends Module {
  val io = IO(new Bundle {
    // AXI4-Lite master
    val s_axil_awaddr  = Input(UInt(12.W))
    val s_axil_awprot  = Input(UInt(3.W))
    val s_axil_awvalid = Input(Bool())
    val s_axil_awready = Output(Bool())
    val s_axil_wdata   = Input(UInt(32.W))
    val s_axil_wstrb   = Input(UInt(4.W))
    val s_axil_wvalid  = Input(Bool())
    val s_axil_wready  = Output(Bool())
    val s_axil_bresp   = Output(UInt(2.W))
    val s_axil_bvalid  = Output(Bool())
    val s_axil_bready  = Input(Bool())
    val s_axil_araddr  = Input(UInt(12.W))
    val s_axil_arprot  = Input(UInt(3.W))
    val s_axil_arvalid = Input(Bool())
    val s_axil_arready = Output(Bool())
    val s_axil_rdata   = Output(UInt(32.W))
    val s_axil_rresp   = Output(UInt(2.W))
    val s_axil_rvalid  = Output(Bool())
    val s_axil_rready  = Input(Bool())

    // RAM test hooks
    val pre_wr_en   = Input(Bool())
    val pre_wr_addr = Input(UInt(12.W))
    val pre_wr_data = Input(UInt(128.W))
    val dbg_rd_addr = Input(UInt(12.W))
    val dbg_rd_data = Output(UInt(128.W))

    val irq = Output(Bool())
  })

  val dut = Module(new NpuPeripheral(K = 16, N = 8, W = 4))
  val ram = Module(new AxiRamModel())

  dut.io.s_axil_awaddr  := io.s_axil_awaddr
  dut.io.s_axil_awprot  := io.s_axil_awprot
  dut.io.s_axil_awvalid := io.s_axil_awvalid
  io.s_axil_awready     := dut.io.s_axil_awready
  dut.io.s_axil_wdata   := io.s_axil_wdata
  dut.io.s_axil_wstrb   := io.s_axil_wstrb
  dut.io.s_axil_wvalid  := io.s_axil_wvalid
  io.s_axil_wready      := dut.io.s_axil_wready
  io.s_axil_bresp       := dut.io.s_axil_bresp
  io.s_axil_bvalid      := dut.io.s_axil_bvalid
  dut.io.s_axil_bready  := io.s_axil_bready
  dut.io.s_axil_araddr  := io.s_axil_araddr
  dut.io.s_axil_arprot  := io.s_axil_arprot
  dut.io.s_axil_arvalid := io.s_axil_arvalid
  io.s_axil_arready     := dut.io.s_axil_arready
  io.s_axil_rdata       := dut.io.s_axil_rdata
  io.s_axil_rresp       := dut.io.s_axil_rresp
  io.s_axil_rvalid      := dut.io.s_axil_rvalid
  dut.io.s_axil_rready  := io.s_axil_rready

  io.irq := dut.io.irq
  dut.io.trace_ready := false.B

  ram.io.s_axi_awaddr  := dut.io.m_axi_awaddr(31, 0)
  ram.io.s_axi_awlen   := dut.io.m_axi_awlen
  ram.io.s_axi_awsize  := dut.io.m_axi_awsize
  ram.io.s_axi_awburst := dut.io.m_axi_awburst
  ram.io.s_axi_awvalid := dut.io.m_axi_awvalid
  dut.io.m_axi_awready := ram.io.s_axi_awready
  ram.io.s_axi_wdata   := dut.io.m_axi_wdata
  ram.io.s_axi_wstrb   := dut.io.m_axi_wstrb
  ram.io.s_axi_wlast   := dut.io.m_axi_wlast
  ram.io.s_axi_wvalid  := dut.io.m_axi_wvalid
  dut.io.m_axi_wready  := ram.io.s_axi_wready
  dut.io.m_axi_bresp   := 0.U
  dut.io.m_axi_bvalid  := ram.io.s_axi_bvalid
  ram.io.s_axi_bready  := dut.io.m_axi_bready
  ram.io.s_axi_araddr  := dut.io.m_axi_araddr(31, 0)
  ram.io.s_axi_arlen   := dut.io.m_axi_arlen
  ram.io.s_axi_arsize  := dut.io.m_axi_arsize
  ram.io.s_axi_arburst := dut.io.m_axi_arburst
  ram.io.s_axi_arvalid := dut.io.m_axi_arvalid
  dut.io.m_axi_arready := ram.io.s_axi_arready
  dut.io.m_axi_rdata   := ram.io.s_axi_rdata
  dut.io.m_axi_rresp   := 0.U
  dut.io.m_axi_rlast   := ram.io.s_axi_rlast
  dut.io.m_axi_rvalid  := ram.io.s_axi_rvalid
  ram.io.s_axi_rready  := dut.io.m_axi_rready

  ram.io.pre_wr_en   := io.pre_wr_en
  ram.io.pre_wr_addr := io.pre_wr_addr
  ram.io.pre_wr_data := io.pre_wr_data
  ram.io.dbg_rd_addr := io.dbg_rd_addr
  io.dbg_rd_data     := ram.io.dbg_rd_data
}

class NpuPeripheralSpec extends AnyFlatSpec {
  def bus(dut: PeripheralHarness, write: Boolean, addr: Int,
          data: Long = 0, strb: Int = 0xF): Long = {
    dut.io.s_axil_awvalid.poke(write.B)
    dut.io.s_axil_awaddr.poke(addr.U)
    dut.io.s_axil_awprot.poke(0.U)
    dut.io.s_axil_wvalid.poke(write.B)
    dut.io.s_axil_wdata.poke((data & 0xFFFFFFFFL).U)
    dut.io.s_axil_wstrb.poke(strb.U)
    dut.io.s_axil_arvalid.poke((!write).B)
    dut.io.s_axil_araddr.poke(addr.U)
    dut.io.s_axil_arprot.poke(0.U)
    dut.io.s_axil_bready.poke(true.B)
    dut.io.s_axil_rready.poke(true.B)

    var n = 0
    while (n < 50 &&
           (dut.io.s_axil_bvalid.peek().litValue == 0 &&
            dut.io.s_axil_rvalid.peek().litValue == 0)) {
      dut.clock.step(); n += 1
    }
    dut.clock.step()
    dut.io.s_axil_awvalid.poke(false.B)
    dut.io.s_axil_wvalid.poke(false.B)
    dut.io.s_axil_arvalid.poke(false.B)
    val rd = dut.io.s_axil_rdata.peek().litValue.toLong
    dut.clock.step()
    rd
  }

  def write(dut: PeripheralHarness, addr: Int, data: Long): Unit =
    bus(dut, write = true, addr, data)
  def read(dut: PeripheralHarness, addr: Int): Long =
    bus(dut, write = false, addr)

  def preload(dut: PeripheralHarness, base: Long, bytes: Seq[Int]): Unit = {
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

  def preloadI8(dut: PeripheralHarness, base: Long, values: Seq[Int]): Unit =
    preload(dut, base, values.map(_ & 0xFF))

  def preloadI32(dut: PeripheralHarness, base: Long, values: Seq[Int]): Unit = {
    val bytes = values.flatMap(v => Seq(v & 0xFF, (v >> 8) & 0xFF, (v >> 16) & 0xFF, (v >> 24) & 0xFF))
    preload(dut, base, bytes)
  }

  def preloadInstrs(dut: PeripheralHarness, instrs: Seq[Int]): Unit = {
    val bytes = instrs.flatMap(w => Seq(w & 0xFF, (w >> 8) & 0xFF, (w >> 16) & 0xFF, (w >> 24) & 0xFF))
    preload(dut, 0x4000L, bytes)
  }

  def readI32(dut: PeripheralHarness, base: Long, n: Int): Array[Int] = {
    val out = Array.fill(4 * n)(0)
    for (w <- 0 until 4 * n / 16) {
      dut.io.dbg_rd_addr.poke((((base + 16 * w) >> 4) & 0xFFF).U)
      dut.clock.step(0)
      val got = dut.io.dbg_rd_data.peek().litValue
      for (b <- 0 until 16) out(16 * w + b) = ((got >> (8 * b)) & 0xFF).toInt
    }
    Array.tabulate(n)(i => out(4 * i) | (out(4 * i + 1) << 8) |
      (out(4 * i + 2) << 16) | (out(4 * i + 3) << 24))
  }

  "NpuPeripheral" should "run a relocated one-shot MMA through NPUCTL" in {
    simulate(new PeripheralHarness) { dut =>
      dut.io.pre_wr_en.poke(false.B)
      dut.io.s_axil_bready.poke(true.B)
      dut.io.s_axil_rready.poke(true.B)

      assert(read(dut, NpuCtrlProtocol.ID) == NpuCtrlProtocol.ID_MAGIC)
      val cfg = read(dut, NpuCtrlProtocol.CONFIG)
      assert((cfg & 0xFF) == 16, s"CONFIG.K must be 16, got $cfg")
      assert(((cfg >> 16) & 0xFF) == 16, "CONFIG.L must be 16")

      // System bases: A=0x0000, B=0x1000, ACCUM=0x2000, OUT=0x3000, CODE=0x4000.
      def seg(base: Int, baseAddr: Long, limit: Long): Unit = {
        write(dut, base + 0, baseAddr & 0xFFFFFFFFL)
        write(dut, base + 4, (baseAddr >> 32) & 0xFFFFFFFFL)
        write(dut, base + 8, limit)
      }
      seg(NpuCtrlProtocol.segBaseLo(0), 0x0000L, 0x1000L)
      seg(NpuCtrlProtocol.segBaseLo(1), 0x1000L, 0x1000L)
      seg(NpuCtrlProtocol.segBaseLo(2), 0x2000L, 0x1000L)
      seg(NpuCtrlProtocol.segBaseLo(3), 0x3000L, 0x1000L)
      seg(NpuCtrlProtocol.segBaseLo(4), 0x4000L, 0x40000L)

      val k = 16
      val a = Array.tabulate(k)(j => j + 1)
      val b = Array.tabulate(k)(i => 100 + i)
      val prog = Seq(
        vle8(rd = 4, sect = NpuSections.SECT_A, off = 0),
        vle8(rd = 8, sect = NpuSections.SECT_B, off = 0),
        mma(rd = 2, vs1 = 4, vs2 = 8, vs3 = 0),
        mmaLast(rd = 2, vs1 = 0, vs2 = 0, vs3 = 0),
        vse32(src = 2, sect = NpuSections.SECT_OUT, off = 0)
      )
      preloadI8(dut, 0x0000L, a)
      preloadI8(dut, 0x1000L, b)
      preloadI32(dut, 0x3000L, Seq.fill(k)(0))
      preloadInstrs(dut, prog)

      write(dut, NpuCtrlProtocol.PROG_LEN, prog.length)
      write(dut, NpuCtrlProtocol.IRQ_ENABLE, 0x3L)
      write(dut, NpuCtrlProtocol.CONTROL, 1L)

      var n = 0
      var status = 0L
      while (n < 20000) {
        status = read(dut, NpuCtrlProtocol.STATUS)
        if (((status >> 1) & 1) == 1 || ((status >> 2) & 1) == 1) n = 20000
        else { dut.clock.step(); n += 1 }
      }
      assert(((status >> 2) & 1) == 0, s"unexpected error: status=0x${status.toHexString} code=${read(dut, NpuCtrlProtocol.ERROR_CODE)}")
      assert(((status >> 1) & 1) == 1, s"session did not complete: status=0x${status.toHexString}")

      val out = readI32(dut, 0x3000L, k)
      val cols = (0 until k).filter(col =>
        (0 until k).forall(i => out(i) == a(i) * b(col)))
      assert(cols.nonEmpty, s"capture is not a column of a*b: ${out.toList}")

      // Let the core leave RUN_DONE before the next START.
      var w = 0
      while ((read(dut, NpuCtrlProtocol.STATUS) & 1) != 0 && w < 100) {
        dut.clock.step(); w += 1
      }

      // Second session with the SAME PROG_LEN/ENTRY_PC but rewritten CODE at
      // the same address.  Native START must invalidate the line cache.
      val c = Array.tabulate(k)(i => 1000 + i)
      preloadI32(dut, 0x2000L, c)
      preloadInstrs(dut, Seq(
        vle32(rd = 3, sect = NpuSections.SECT_ACCUM, off = 0),
        vse32(src = 3, sect = NpuSections.SECT_OUT, off = 0),
        0, 0, 0
      ))
      write(dut, NpuCtrlProtocol.PROG_LEN, 5L)
      write(dut, NpuCtrlProtocol.CONTROL, 1L)
      n = 0
      status = 0L
      while (n < 20000) {
        status = read(dut, NpuCtrlProtocol.STATUS)
        if (((status >> 1) & 1) == 1 || ((status >> 2) & 1) == 1) n = 20000
        else { dut.clock.step(); n += 1 }
      }
      assert(((status >> 2) & 1) == 0,
        s"second session error: status=0x${status.toHexString} code=${read(dut, NpuCtrlProtocol.ERROR_CODE)}")
      assert(((status >> 1) & 1) == 1, s"second session did not complete: 0x${status.toHexString}")
      val out2 = readI32(dut, 0x3000L, k)
      assert(out2.sameElements(c),
        s"stale instruction cache: expected the new CODE to copy ACCUM to OUT, got ${out2.toList}")
    }
  }
}
