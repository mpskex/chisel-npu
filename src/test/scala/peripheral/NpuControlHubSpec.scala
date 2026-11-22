// See README.md for license details.
// -----------------------------------------------------------------------------
//  NpuControlHubSpec — NPUCTL register file, session FSM, IRQ, watchdog, trace.
// -----------------------------------------------------------------------------

package peripheral

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec

class HubHarness extends Module {
  val io = IO(new Bundle {
    // control-bus master
    val req_valid = Input(Bool())
    val req_write = Input(Bool())
    val req_addr  = Input(UInt(12.W))
    val req_wdata = Input(UInt(32.W))
    val req_wstrb = Input(UInt(4.W))
    val resp_ready = Input(Bool())
    val req_ready  = Output(Bool())
    val resp_valid = Output(Bool())
    val resp_rdata = Output(UInt(32.W))
    val resp_error = Output(Bool())

    // core status inputs
    val core_busy       = Input(Bool())
    val core_done       = Input(Bool())
    val core_illegal    = Input(Bool())
    val core_mem_fault  = Input(Bool())
    val core_fault_seg  = Input(UInt(3.W))
    val core_fault_addr = Input(UInt(32.W))
    val core_pc         = Input(UInt(16.W))
    val core_err_instr  = Input(UInt(32.W))
    val core_cycles     = Input(UInt(32.W))
    val core_macs       = Input(UInt(32.W))
    val core_fetch_stats = Input(UInt(32.W))
    val core_trace_retire = Input(Bool())
    val core_trace_pc     = Input(UInt(16.W))
    val trace_ready       = Input(Bool())

    // observed
    val core_start    = Output(Bool())
    val core_abort    = Output(Bool())
    val core_prog_len = Output(UInt(16.W))
    val core_entry_pc = Output(UInt(16.W))
    val seg_base0     = Output(UInt(64.W))
    val seg_limit0    = Output(UInt(32.W))
    val irq           = Output(Bool())
    val trace_valid   = Output(Bool())
    val trace_data    = Output(UInt(64.W))
    val start_count   = Output(UInt(8.W))
    val abort_count   = Output(UInt(8.W))
  })

  val hub = Module(new NpuControlHub)

  hub.io.ctrl.req.valid      := io.req_valid
  hub.io.ctrl.req.bits.write := io.req_write
  hub.io.ctrl.req.bits.addr  := io.req_addr
  hub.io.ctrl.req.bits.wdata := io.req_wdata
  hub.io.ctrl.req.bits.wstrb := io.req_wstrb
  hub.io.ctrl.resp.ready     := io.resp_ready

  io.req_ready      := hub.io.ctrl.req.ready
  io.resp_valid     := hub.io.ctrl.resp.valid
  io.resp_rdata     := hub.io.ctrl.resp.bits.rdata
  io.resp_error     := hub.io.ctrl.resp.bits.error

  hub.io.core_busy       := io.core_busy
  hub.io.core_done       := io.core_done
  hub.io.core_illegal    := io.core_illegal
  hub.io.core_mem_fault  := io.core_mem_fault
  hub.io.core_fault_seg  := io.core_fault_seg
  hub.io.core_fault_addr := io.core_fault_addr
  hub.io.core_pc         := io.core_pc
  hub.io.core_err_instr  := io.core_err_instr
  hub.io.core_cycles     := io.core_cycles
  hub.io.core_macs       := io.core_macs
  hub.io.core_fetch_stats := io.core_fetch_stats
  hub.io.core_trace_retire := io.core_trace_retire
  hub.io.core_trace_pc     := io.core_trace_pc
  hub.io.trace_ready       := io.trace_ready
  hub.io.bus_error         := false.B

  io.core_start    := hub.io.core_start
  io.core_abort    := hub.io.core_abort
  io.core_prog_len := hub.io.core_prog_len
  io.core_entry_pc := hub.io.core_entry_pc
  io.seg_base0     := hub.io.seg_base(0)
  io.seg_limit0    := hub.io.seg_limit(0)
  io.irq           := hub.io.irq
  io.trace_valid   := hub.io.trace_valid
  io.trace_data    := hub.io.trace_data

  val sc = RegInit(0.U(8.W))
  val ac = RegInit(0.U(8.W))
  when (hub.io.core_start) { sc := sc + 1.U }
  when (hub.io.core_abort) { ac := ac + 1.U }
  io.start_count := sc
  io.abort_count := ac
}

class NpuControlHubSpec extends AnyFlatSpec {

  def idleCore(dut: HubHarness): Unit = {
    dut.io.core_busy.poke(false.B)
    dut.io.core_done.poke(false.B)
    dut.io.core_illegal.poke(false.B)
    dut.io.core_mem_fault.poke(false.B)
    dut.io.core_fault_seg.poke(0.U)
    dut.io.core_fault_addr.poke(0.U)
    dut.io.core_pc.poke(0.U)
    dut.io.core_err_instr.poke(0.U)
    dut.io.core_cycles.poke(0.U)
    dut.io.core_macs.poke(0.U)
    dut.io.core_fetch_stats.poke(0.U)
    dut.io.core_trace_retire.poke(false.B)
    dut.io.core_trace_pc.poke(0.U)
    dut.io.trace_ready.poke(false.B)
    dut.io.resp_ready.poke(true.B)
  }

  def bus(dut: HubHarness, write: Boolean, addr: Int, data: Long = 0, strb: Int = 0xF): Long = {
    dut.io.req_valid.poke(true.B)
    dut.io.req_write.poke(write.B)
    dut.io.req_addr.poke(addr.U)
    dut.io.req_wdata.poke((data & 0xFFFFFFFFL).U)
    dut.io.req_wstrb.poke(strb.U)

    var n = 0
    while (dut.io.req_ready.peek().litValue == 0 && n < 20) { dut.clock.step(); n += 1 }
    assert(dut.io.req_ready.peek().litValue == 1, s"ctrl request not accepted at 0x${addr.toHexString}")
    dut.clock.step()
    dut.io.req_valid.poke(false.B)

    var m = 0
    while (dut.io.resp_valid.peek().litValue == 0 && m < 20) { dut.clock.step(); m += 1 }
    assert(dut.io.resp_valid.peek().litValue == 1, s"ctrl response missing at 0x${addr.toHexString}")
    val rd = dut.io.resp_rdata.peek().litValue.toLong
    assert(dut.io.resp_error.peek().litValue == 0, "hub response must be OKAY")
    dut.clock.step()
    rd
  }

  def write(dut: HubHarness, addr: Int, data: Long): Unit =
    bus(dut, write = true, addr, data)
  def read(dut: HubHarness, addr: Int): Long =
    bus(dut, write = false, addr)

  "NpuControlHub" should "expose identity, capabilities and config" in {
    simulate(new HubHarness) { dut =>
      idleCore(dut)
      assert(read(dut, NpuCtrlProtocol.ID) == NpuCtrlProtocol.ID_MAGIC)
      assert(read(dut, NpuCtrlProtocol.VERSION) == 0x01000000L)
      val caps = read(dut, NpuCtrlProtocol.CAPS)
      assert(((caps >> 8) & 0xF) == 5, s"NUM_SEG must be 5, caps=0x${caps.toHexString}")
      assert(((caps >> 16) & 0xFF) == 8, "trace depth log2 must be 8")
      assert(((caps >> 28) & 0xF) == 6, "AXI data width log2(bytes) must be 6")
      assert((caps & 0xF) == 0xF, "IRQ/trace/watchdog/AXI64 caps must be set")
      val cfg = read(dut, NpuCtrlProtocol.CONFIG)
      assert((cfg & 0xFF) == 32)
      assert(((cfg >> 8) & 0xFF) == 8)
      assert(((cfg >> 16) & 0xFF) == 32)
      assert(((cfg >> 24) & 0xFF) == 8)
      assert(read(dut, NpuCtrlProtocol.STATUS) == 0)
    }
  }

  it should "read and write scratch and the segment table" in {
    simulate(new HubHarness) { dut =>
      idleCore(dut)
      write(dut, NpuCtrlProtocol.SCRATCH, 0xDEADBEEFL)
      assert(read(dut, NpuCtrlProtocol.SCRATCH) == 0xDEADBEEFL)
      write(dut, NpuCtrlProtocol.SCRATCH + 4, 0x01234567L)
      assert(read(dut, NpuCtrlProtocol.SCRATCH + 4) == 0x01234567L)

      write(dut, NpuCtrlProtocol.segBaseLo(0), 0x00001000L)
      write(dut, NpuCtrlProtocol.segBaseHi(0), 0x00000001L)
      write(dut, NpuCtrlProtocol.segLimit(0), 0x1000L)
      assert(read(dut, NpuCtrlProtocol.segBaseLo(0)) == 0x1000L)
      assert(read(dut, NpuCtrlProtocol.segBaseHi(0)) == 1L)
      assert(read(dut, NpuCtrlProtocol.segLimit(0)) == 0x1000L)
      assert(dut.io.seg_base0.peek().litValue == 0x100001000L)
      assert(dut.io.seg_limit0.peek().litValue == 0x1000L)

      // Reserved entries read as zero; writes there are ignored.
      write(dut, NpuCtrlProtocol.segBaseLo(5), 0x1234L)
      assert(read(dut, NpuCtrlProtocol.segBaseLo(5)) == 0)
    }
  }

  it should "reject START with PROG_LEN=0 and raise IRQ.ERROR" in {
    simulate(new HubHarness) { dut =>
      idleCore(dut)
      write(dut, NpuCtrlProtocol.IRQ_ENABLE, 0x3L)
      write(dut, NpuCtrlProtocol.PROG_LEN, 0L)
      write(dut, NpuCtrlProtocol.CONTROL, 1L)
      val st = read(dut, NpuCtrlProtocol.STATUS)
      assert(((st >> 2) & 1) == 1, "ERROR must be set")
      assert(((st >> 4) & 0xF) == NpuCtrlProtocol.State.IDLE)
      assert(read(dut, NpuCtrlProtocol.ERROR_CODE) == NpuCtrlProtocol.Error.BAD_CONFIG)
      assert(((read(dut, NpuCtrlProtocol.IRQ_STATUS)) & 0x2) == 0x2)
      assert(dut.io.irq.peek().litValue == 1)
      assert(dut.io.start_count.peek().litValue == 0)
    }
  }

  it should "run a session, latch counters, and W1C the DONE IRQ" in {
    simulate(new HubHarness) { dut =>
      idleCore(dut)
      write(dut, NpuCtrlProtocol.IRQ_ENABLE, 0x3L)
      write(dut, NpuCtrlProtocol.PROG_LEN, 7L)
      write(dut, NpuCtrlProtocol.ENTRY_PC, 2L)
      // A is 64-byte aligned; limit is a multiple of 64.
      write(dut, NpuCtrlProtocol.segBaseLo(0), 0x00001000L)
      write(dut, NpuCtrlProtocol.segBaseHi(0), 0x0L)
      write(dut, NpuCtrlProtocol.segLimit(0), 0x1000L)

      dut.io.core_busy.poke(false.B)
      write(dut, NpuCtrlProtocol.CONTROL, 1L)
      dut.io.core_busy.poke(true.B)
      val runSt = read(dut, NpuCtrlProtocol.STATUS)
      assert(((runSt >> 1) & 1) == 0, "DONE must not be set yet")
      assert(((runSt >> 4) & 0xF) == NpuCtrlProtocol.State.RUN)
      assert(dut.io.start_count.peek().litValue == 1)
      assert(dut.io.core_prog_len.peek().litValue == 7)
      assert(dut.io.core_entry_pc.peek().litValue == 2)

      // A second START while busy is ignored (not a bad-config error).
      write(dut, NpuCtrlProtocol.CONTROL, 1L)
      assert((read(dut, NpuCtrlProtocol.STATUS) & 0x4) == 0,
        "START while busy must not set ERROR")
      assert(dut.io.start_count.peek().litValue == 1)

      dut.io.core_cycles.poke(1234.U)
      dut.io.core_macs.poke(5678.U)
      dut.io.core_fetch_stats.poke(0x00030002L.U)
      dut.io.core_busy.poke(false.B)
      dut.io.core_done.poke(true.B)
      dut.clock.step()
      dut.io.core_done.poke(false.B)
      dut.clock.step()

      val doneSt = read(dut, NpuCtrlProtocol.STATUS)
      assert(((doneSt >> 1) & 1) == 1, "DONE must be set")
      assert(((doneSt >> 4) & 0xF) == NpuCtrlProtocol.State.DONE)
      assert(read(dut, NpuCtrlProtocol.CYCLES) == 1234)
      assert(read(dut, NpuCtrlProtocol.MACS) == 5678)
      assert(read(dut, NpuCtrlProtocol.FETCH_STATS) == 0x00030002L)
      assert((read(dut, NpuCtrlProtocol.IRQ_STATUS) & 0x1) == 0x1)

      write(dut, NpuCtrlProtocol.IRQ_STATUS, 0x1L)
      assert((read(dut, NpuCtrlProtocol.IRQ_STATUS) & 0x1) == 0)
    }
  }

  it should "abort a running session and report ABORTED" in {
    simulate(new HubHarness) { dut =>
      idleCore(dut)
      write(dut, NpuCtrlProtocol.PROG_LEN, 3L)
      dut.io.core_busy.poke(false.B)
      write(dut, NpuCtrlProtocol.CONTROL, 1L)
      dut.io.core_busy.poke(true.B)
      write(dut, NpuCtrlProtocol.CONTROL, 2L)
      val st = read(dut, NpuCtrlProtocol.STATUS)
      assert(((st >> 3) & 1) == 1, "ABORTED must be set")
      assert(((st >> 2) & 1) == 1, "ERROR must be set")
      assert(read(dut, NpuCtrlProtocol.ERROR_CODE) == NpuCtrlProtocol.Error.ABORTED)
      assert(dut.io.abort_count.peek().litValue == 1)
      assert(((read(dut, NpuCtrlProtocol.IRQ_STATUS)) & 0x2) == 0x2)
    }
  }

  it should "fire the watchdog and abort the core" in {
    simulate(new HubHarness) { dut =>
      idleCore(dut)
      write(dut, NpuCtrlProtocol.PROG_LEN, 3L)
      write(dut, NpuCtrlProtocol.WATCHDOG, 5L)
      dut.io.core_busy.poke(false.B)
      write(dut, NpuCtrlProtocol.CONTROL, 1L)
      dut.io.core_busy.poke(true.B)
      dut.clock.step(10)
      val st = read(dut, NpuCtrlProtocol.STATUS)
      assert(((st >> 2) & 1) == 1, "ERROR must be set after watchdog")
      assert(read(dut, NpuCtrlProtocol.ERROR_CODE) == NpuCtrlProtocol.Error.WATCHDOG)
      assert(dut.io.abort_count.peek().litValue == 1)
    }
  }

  it should "record start, retire and done in the trace FIFO" in {
    simulate(new HubHarness) { dut =>
      idleCore(dut)
      write(dut, NpuCtrlProtocol.PROG_LEN, 2L)
      write(dut, NpuCtrlProtocol.TRACE_CTRL, 5L) // enable, mode 2 = events + retire
      dut.io.core_busy.poke(false.B)
      write(dut, NpuCtrlProtocol.CONTROL, 1L)
      dut.io.core_busy.poke(true.B)
      dut.io.core_trace_retire.poke(true.B)
      dut.io.core_trace_pc.poke(0x10.U)
      dut.clock.step()
      dut.io.core_trace_pc.poke(0x11.U)
      dut.clock.step()
      dut.io.core_trace_retire.poke(false.B)
      dut.io.core_busy.poke(false.B)
      dut.io.core_done.poke(true.B)
      dut.clock.step()
      dut.io.core_done.poke(false.B)
      dut.clock.step()

      val ts = read(dut, NpuCtrlProtocol.TRACE_STATUS)
      assert((ts & 0xFFFF) >= 4, s"trace must contain start + 2 retire + done, got $ts")
      // Pop the first record (low word then high word).
      val lo = read(dut, NpuCtrlProtocol.TRACE_DATA)
      val hi = read(dut, NpuCtrlProtocol.TRACE_DATA)
      val rec = (hi << 32) | (lo & 0xFFFFFFFFL)
      val evt = ((rec >> 8) & 0xFF).toInt
      assert(evt == NpuCtrlProtocol.Event.START, s"first trace event must be START, got $evt")

      val lo2 = read(dut, NpuCtrlProtocol.TRACE_DATA)
      val hi2 = read(dut, NpuCtrlProtocol.TRACE_DATA)
      val rec2 = (hi2 << 32) | (lo2 & 0xFFFFFFFFL)
      val evt2 = ((rec2 >> 8) & 0xFF).toInt
      val pc2 = ((rec2 >> 16) & 0xFFFF).toInt
      assert(evt2 == NpuCtrlProtocol.Event.RETIRE, s"second record must be RETIRE, got $evt2")
      assert(pc2 == 0x10, s"retire PC must be 0x10, got 0x${pc2.toHexString}")
    }
  }
  it should "record a watchdog event with its own event id" in {
    simulate(new HubHarness) { dut =>
      idleCore(dut)
      write(dut, NpuCtrlProtocol.PROG_LEN, 2L)
      write(dut, NpuCtrlProtocol.WATCHDOG, 5L)
      write(dut, NpuCtrlProtocol.TRACE_CTRL, 3L) // enable, mode 1 = events only
      dut.io.core_busy.poke(false.B)
      write(dut, NpuCtrlProtocol.CONTROL, 1L)
      dut.io.core_busy.poke(true.B)
      dut.clock.step(10)
      val ts = read(dut, NpuCtrlProtocol.TRACE_STATUS)
      assert((ts & 0xFFFF) >= 2, s"expected START + WATCHDOG records, got $ts")
      var found = false
      for (_ <- 0 until ((ts & 0xFFFF).toInt)) {
        val lo = read(dut, NpuCtrlProtocol.TRACE_DATA)
        val hi = read(dut, NpuCtrlProtocol.TRACE_DATA)
        val rec = (hi << 32) | (lo & 0xFFFFFFFFL)
        if (((rec >> 8) & 0xFF) == NpuCtrlProtocol.Event.WATCHDOG) found = true
      }
      assert(found, "trace must contain a WATCHDOG event")
      assert(read(dut, NpuCtrlProtocol.ERROR_CODE) == NpuCtrlProtocol.Error.WATCHDOG)
    }
  }

}
