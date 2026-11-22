// See README.md for license details.
// -----------------------------------------------------------------------------
//  NpuCoreControlSpec — native core control/status, entry PC, abort, limits,
//  and retire trace.  Uses K=16 to keep elaboration reasonable.
// -----------------------------------------------------------------------------

package engine

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec
import testUtil._
import isa.NpuAssembler._

class CoreControlHarness(val readLatency: Int = 1) extends Module {
  val io = IO(new Bundle {
    val native_ctrl   = Input(Bool())
    val core_start    = Input(Bool())
    val core_abort    = Input(Bool())
    val core_prog_len = Input(UInt(16.W))
    val core_entry_pc = Input(UInt(16.W))
    val a_limit       = Input(UInt(32.W))
    val code_limit    = Input(UInt(32.W))

    val core_busy     = Output(Bool())
    val core_done     = Output(Bool())
    val core_illegal  = Output(Bool())
    val core_mem_fault = Output(Bool())
    val core_fault_seg = Output(UInt(3.W))
    val core_fault_addr = Output(UInt(32.W))
    val core_pc       = Output(UInt(16.W))
    val trace_retire  = Output(Bool())
    val trace_pc      = Output(UInt(16.W))
    val retire_count  = Output(UInt(16.W))
    val dbg_drain     = Output(Bool())
    val dbg_dmaq      = Output(UInt(4.W))
    val dbg_wcount    = Output(UInt(5.W))

    val pre_wr_en   = Input(Bool())
    val pre_wr_addr = Input(UInt(12.W))
    val pre_wr_data = Input(UInt(128.W))
  })

  val fe = Module(new NpuProgramEngineFrontend(16, 8))
  val ram = Module(new AxiRamModel(readLatency = readLatency))

  fe.io.native_ctrl   := io.native_ctrl
  fe.io.core_start    := io.core_start
  fe.io.core_abort    := io.core_abort
  fe.io.core_prog_len := io.core_prog_len
  fe.io.core_entry_pc := io.core_entry_pc
  fe.io.seg_limit     := VecInit(Seq(
    io.a_limit, 0xFFFFFFFFL.U, 0xFFFFFFFFL.U, 0xFFFFFFFFL.U, io.code_limit))

  fe.io.ctrl_addr  := 0.U
  fe.io.ctrl_we    := false.B
  fe.io.ctrl_wdata := 0.U
  fe.io.m_axi_awready := ram.io.s_axi_awready
  fe.io.m_axi_wready  := ram.io.s_axi_wready
  fe.io.m_axi_bvalid  := ram.io.s_axi_bvalid
  fe.io.m_axi_arready := ram.io.s_axi_arready
  fe.io.m_axi_rdata   := ram.io.s_axi_rdata
  fe.io.m_axi_rlast   := ram.io.s_axi_rlast
  fe.io.m_axi_rvalid  := ram.io.s_axi_rvalid

  ram.io.s_axi_awaddr  := fe.io.m_axi_awaddr
  ram.io.s_axi_awlen   := fe.io.m_axi_awlen
  ram.io.s_axi_awsize  := fe.io.m_axi_awsize
  ram.io.s_axi_awburst := fe.io.m_axi_awburst
  ram.io.s_axi_awvalid := fe.io.m_axi_awvalid
  ram.io.s_axi_wdata   := fe.io.m_axi_wdata
  ram.io.s_axi_wstrb   := fe.io.m_axi_wstrb
  ram.io.s_axi_wlast   := fe.io.m_axi_wlast
  ram.io.s_axi_wvalid  := fe.io.m_axi_wvalid
  ram.io.s_axi_bready  := fe.io.m_axi_bready
  ram.io.s_axi_araddr  := fe.io.m_axi_araddr
  ram.io.s_axi_arlen   := fe.io.m_axi_arlen
  ram.io.s_axi_arsize  := fe.io.m_axi_arsize
  ram.io.s_axi_arburst := fe.io.m_axi_arburst
  ram.io.s_axi_arvalid := fe.io.m_axi_arvalid
  ram.io.s_axi_rready  := fe.io.m_axi_rready

  ram.io.pre_wr_en   := io.pre_wr_en
  ram.io.pre_wr_addr := io.pre_wr_addr
  ram.io.pre_wr_data := io.pre_wr_data
  ram.io.dbg_rd_addr := 0.U

  io.core_busy      := fe.io.core_busy
  io.core_done      := fe.io.core_done
  io.core_illegal   := fe.io.core_illegal
  io.core_mem_fault := fe.io.core_mem_fault
  io.core_fault_seg := fe.io.core_fault_seg
  io.core_fault_addr := fe.io.core_fault_addr
  io.core_pc        := fe.io.core_pc
  io.trace_retire   := fe.io.trace_retire
  io.trace_pc       := fe.io.trace_pc
  io.dbg_drain      := fe.io.dbg_drain
  io.dbg_dmaq       := fe.io.dbg_dmaq
  io.dbg_wcount     := fe.io.dbg_wcount

  val rc = RegInit(0.U(16.W))
  when (fe.io.trace_retire) { rc := rc + 1.U }
  io.retire_count := rc
}

class NpuCoreControlSpec extends AnyFlatSpec {
  def preloadWord(dut: CoreControlHarness, base: Long, word: Int): Unit = {
    var w = BigInt(0)
    for (b <- 0 until 4) w |= (BigInt((word >> (8 * b)) & 0xFF) << (8 * b))
    dut.io.pre_wr_addr.poke((((base) >> 4) & 0xFFF).U)
    dut.io.pre_wr_data.poke(w.U)
    dut.io.pre_wr_en.poke(true.B)
    dut.clock.step()
    dut.io.pre_wr_en.poke(false.B)
  }

  def waitDone(dut: CoreControlHarness, maxCycles: Int = 20000): Int = {
    var n = 0
    while (dut.io.core_done.peek().litValue == 0 && n < maxCycles) {
      dut.clock.step(); n += 1
    }
    assert(dut.io.core_done.peek().litValue == 1, s"core did not finish in $maxCycles cycles")
    n
  }

  "NpuProgramEngineFrontend" should "run from a native start with a nonzero entry PC and trace retires" in {
    simulate(new CoreControlHarness) { dut =>
      dut.io.native_ctrl.poke(true.B)
      dut.io.core_start.poke(false.B)
      dut.io.core_abort.poke(false.B)
      dut.io.a_limit.poke("hFFFFFFFF".U)
      dut.io.code_limit.poke("hFFFFFFFF".U)
      dut.io.pre_wr_en.poke(false.B)

      // Four zero words (NOP) starting at word 2.
      dut.io.core_entry_pc.poke(2.U)
      dut.io.core_prog_len.poke(4.U)
      dut.clock.step(22) // let the warm fetch settle

      dut.io.core_start.poke(true.B)
      dut.clock.step()
      dut.io.core_start.poke(false.B)
      waitDone(dut)
      dut.clock.step()

      assert(dut.io.core_busy.peek().litValue == 0, "core must be idle after done")
      assert(dut.io.core_pc.peek().litValue == 5, s"last pc must be 5, got ${dut.io.core_pc.peek().litValue}")
      assert(dut.io.retire_count.peek().litValue == 4, s"expected 4 retires, got ${dut.io.retire_count.peek().litValue}")

      // A second native session must start cleanly.
      dut.io.core_entry_pc.poke(0.U)
      dut.io.core_prog_len.poke(2.U)
      dut.clock.step(22)
      dut.io.core_start.poke(true.B)
      dut.clock.step()
      dut.io.core_start.poke(false.B)
      waitDone(dut)
      assert(dut.io.core_pc.peek().litValue == 1, "second session must finish at pc 1")
    }
  }

  it should "abort a running session with an in-flight load and return to idle" in {
    simulate(new CoreControlHarness(readLatency = 20)) { dut =>
      dut.io.native_ctrl.poke(true.B)
      dut.io.core_start.poke(false.B)
      dut.io.core_abort.poke(false.B)
      dut.io.a_limit.poke("hFFFFFFFF".U)
      dut.io.code_limit.poke("hFFFFFFFF".U)
      dut.io.core_entry_pc.poke(0.U)
      dut.io.core_prog_len.poke(64.U)
      preloadWord(dut, NpuSections.CODE_BASE,
        vle8(rd = 4, sect = NpuSections.SECT_A, off = 0))
      dut.io.pre_wr_addr.poke(0.U)
      dut.io.pre_wr_data.poke(BigInt("0123456789ABCDEF0123456789ABCDEF", 16).U)
      dut.io.pre_wr_en.poke(true.B)
      dut.clock.step()
      dut.io.pre_wr_en.poke(false.B)
      dut.clock.step(22)
      dut.io.core_start.poke(true.B)
      dut.clock.step()
      dut.io.core_start.poke(false.B)
      dut.clock.step(3)
      assert(dut.io.core_busy.peek().litValue == 1, "core should be running before abort")
      dut.io.core_abort.poke(true.B)
      dut.clock.step()
      dut.io.core_abort.poke(false.B)
      var n = 0
      while (dut.io.core_busy.peek().litValue != 0 && n < 200) {
        dut.clock.step(); n += 1
      }
      assert(dut.io.core_busy.peek().litValue == 0,
        s"core must be idle after abort (waited $n cycles, drain=${dut.io.dbg_drain.peek().litValue}, dmaq=${dut.io.dbg_dmaq.peek().litValue}, wcount=${dut.io.dbg_wcount.peek().litValue})")
    }
  }

  it should "raise a segment-range fault without issuing the out-of-range DMA" in {
    simulate(new CoreControlHarness) { dut =>
      dut.io.native_ctrl.poke(true.B)
      dut.io.core_start.poke(false.B)
      dut.io.core_abort.poke(false.B)
      dut.io.a_limit.poke(0x40.U)      // A window is 64 B
      dut.io.code_limit.poke("hFFFFFFFF".U)
      dut.io.core_entry_pc.poke(0.U)
      dut.io.core_prog_len.poke(1.U)
      preloadWord(dut, NpuSections.CODE_BASE, vle8(rd = 0, sect = NpuSections.SECT_A, off = 0x100))
      dut.clock.step(22)

      dut.io.core_start.poke(true.B)
      dut.clock.step()
      dut.io.core_start.poke(false.B)
      dut.clock.step(40)

      assert(dut.io.core_mem_fault.peek().litValue == 1, "out-of-range vle8 must raise mem_fault")
      assert(dut.io.core_fault_seg.peek().litValue == NpuSections.SECT_A)
      assert((dut.io.core_fault_addr.peek().litValue & 0xFFF) == 0x100,
        s"fault address must carry the section offset, got 0x${dut.io.core_fault_addr.peek().litValue.toString(16)}")
    }
  }
  it should "raise a code-fetch fault when the code window is too small" in {
    simulate(new CoreControlHarness) { dut =>
      dut.io.native_ctrl.poke(true.B)
      dut.io.core_start.poke(false.B)
      dut.io.core_abort.poke(false.B)
      dut.io.a_limit.poke("hFFFFFFFF".U)
      dut.io.code_limit.poke(0x20.U)   // one 64-byte line does not fit
      dut.io.core_entry_pc.poke(0.U)
      dut.io.core_prog_len.poke(2.U)
      dut.clock.step(30)
      dut.io.core_start.poke(true.B)
      dut.clock.step()
      dut.io.core_start.poke(false.B)
      dut.clock.step(30)
      assert(dut.io.core_mem_fault.peek().litValue == 1,
        "a code fetch outside the CODE limit must fault")
      assert(dut.io.core_fault_seg.peek().litValue == NpuSections.SEG_CODE)
    }
  }

}
