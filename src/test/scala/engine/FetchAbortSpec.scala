// See README.md for license details.
// -----------------------------------------------------------------------------
//  FetchAbortSpec.scala — deterministic regression for the post-abort fill
//  gate.  A session_reset (abort) flushes the dmaQ and clears fillPending but
//  does NOT reset the DMA engine, so the aborted fill keeps streaming its
//  beats.  A new fill must therefore not be queued until the DMA drains to
//  IDLE; otherwise the aborted fill's INSTRW beats are attributed to the new
//  fill's dmaHead (stale-data corruption on a fast restart).
// -----------------------------------------------------------------------------

package engine

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec
import testUtil._

class FetchAbortHarness extends Module {
  val io = IO(new Bundle {
    val session_reset   = Input(Bool())
    val fetch_fill_req  = Input(Bool())
    val fetch_fill_addr = Input(UInt(32.W))
    val fetch_fill_beats = Input(UInt(3.W))
    val fetch_busy      = Output(Bool())
    val dbg_dmaq        = Output(UInt(4.W))
  })

  val eng = Module(new NpuProgramEngine(16, 8, 8))
  val ram = Module(new AxiRamModel(readLatency = 1))

  // This harness never issues instructions; drive the instruction stream idle.
  eng.io.instr := 0.U
  eng.io.instr_valid := false.B
  eng.io.session_reset := io.session_reset
  eng.io.dbg_vx_addr := 0.U

  // Direct instruction-line fill interface (frontend is not instantiated).
  eng.io.fetch_fill_req   := io.fetch_fill_req
  eng.io.fetch_fill_addr  := io.fetch_fill_addr
  eng.io.fetch_fill_beats := io.fetch_fill_beats

  io.fetch_busy := eng.io.fetch_busy
  io.dbg_dmaq   := eng.io.dbg_dmaq

  ram.io.s_axi_awaddr := eng.io.m_axi_awaddr; ram.io.s_axi_awlen := eng.io.m_axi_awlen
  ram.io.s_axi_awsize := eng.io.m_axi_awsize; ram.io.s_axi_awburst := eng.io.m_axi_awburst
  ram.io.s_axi_awvalid := eng.io.m_axi_awvalid; eng.io.m_axi_awready := ram.io.s_axi_awready
  ram.io.s_axi_wdata := eng.io.m_axi_wdata; ram.io.s_axi_wstrb := eng.io.m_axi_wstrb
  ram.io.s_axi_wlast := eng.io.m_axi_wlast; ram.io.s_axi_wvalid := eng.io.m_axi_wvalid
  eng.io.m_axi_wready := ram.io.s_axi_wready
  eng.io.m_axi_bvalid := ram.io.s_axi_bvalid; ram.io.s_axi_bready := eng.io.m_axi_bready
  ram.io.s_axi_araddr := eng.io.m_axi_araddr; ram.io.s_axi_arlen := eng.io.m_axi_arlen
  ram.io.s_axi_arsize := eng.io.m_axi_arsize; ram.io.s_axi_arburst := eng.io.m_axi_arburst
  ram.io.s_axi_arvalid := eng.io.m_axi_arvalid; eng.io.m_axi_arready := ram.io.s_axi_arready
  eng.io.m_axi_rdata := ram.io.s_axi_rdata; eng.io.m_axi_rlast := ram.io.s_axi_rlast
  eng.io.m_axi_rvalid := ram.io.s_axi_rvalid; ram.io.s_axi_rready := eng.io.m_axi_rready

  // RAM test hooks unused here (the RAM just returns zeros).
  ram.io.dbg_rd_addr := 0.U
  ram.io.pre_wr_en   := false.B
  ram.io.pre_wr_addr := 0.U
  ram.io.pre_wr_data := 0.U
}

class FetchAbortSpec extends AnyFlatSpec {
  "NpuProgramEngine" should "not queue a new fill while an aborted fill is still draining" in {
    simulate(new FetchAbortHarness) { dut =>
      // Pulse session_reset to settle into a known idle state.
      dut.io.session_reset.poke(true.B)
      dut.io.fetch_fill_req.poke(false.B)
      dut.io.fetch_fill_addr.poke(0.U)
      dut.io.fetch_fill_beats.poke(4.U)
      dut.clock.step(2)
      dut.io.session_reset.poke(false.B)
      dut.clock.step()

      // Request a 4-beat fill at CODE base and wait for the DMA to go busy.
      dut.io.fetch_fill_req.poke(true.B)
      dut.io.fetch_fill_addr.poke(NpuSections.CODE_BASE.U(32.W))
      dut.io.fetch_fill_beats.poke(4.U)
      var n = 0
      while (!dut.io.fetch_busy.peek().litToBoolean && n < 200) { dut.clock.step(); n += 1 }
      assert(dut.io.fetch_busy.peek().litToBoolean, "first fill never went busy")

      // Abort (session_reset flushes dmaQ/fillPending but not the DMA engine).
      dut.io.session_reset.poke(true.B)
      dut.clock.step()
      dut.io.session_reset.poke(false.B)

      // Immediately hold a new fill request at a different address.
      dut.io.fetch_fill_req.poke(true.B)
      dut.io.fetch_fill_addr.poke((NpuSections.CODE_BASE + 0x100L).U(32.W))
      dut.io.fetch_fill_beats.poke(4.U)

      // While the aborted fill is still draining (DMA busy), the new fill must
      // not be queued: its beats would otherwise be the aborted fill's data.
      var busy = 0
      while (dut.io.fetch_busy.peek().litToBoolean && busy < 500) {
        assert(dut.io.dbg_dmaq.peek().litValue == 0,
          s"new fill queued while DMA busy (dbg_dmaq=${dut.io.dbg_dmaq.peek().litValue})")
        dut.clock.step(); busy += 1
      }
      assert(busy > 0, "aborted fill never drained")

      // Once the DMA is idle, the held fill request is accepted.
      dut.clock.step()
      assert(dut.io.dbg_dmaq.peek().litValue != 0, "fill not accepted after DMA idle")
    }
  }
}
