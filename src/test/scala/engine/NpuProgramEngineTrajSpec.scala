// See README.md for license details.
// -----------------------------------------------------------------------------
//  NpuProgramEngineTrajSpec.scala — peek the collector's {dat_clct, cnt}
//  during a one-shot session in simulation, to compare with the silicon
//  trajectory (silicon: cnt = 0 and dat_clct low for the whole window).
// -----------------------------------------------------------------------------

package engine

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec
import testUtil._
import isa._
import isa.NpuAssembler._

class TrajHarness extends Module {
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
    val dbg_vx_addr = Input(UInt(5.W))
    val dbg_vx_data = Output(Vec(16, UInt(8.W)))
    val dbg_mma_acc = Output(UInt(16.W))
    val dbg_clct    = Output(UInt(16.W))
    val dbg_traj    = Output(Vec(16, UInt(6.W)))
  })
  val eng = Module(new NpuProgramEngine(16, 8))
  val ram = Module(new AxiRamModel())
  eng.io.instr := io.instr; eng.io.instr_valid := io.instr_valid
  io.instr_ready := eng.io.instr_ready; io.illegal_out := eng.io.illegal_out
  eng.io.session_reset := false.B
  eng.io.dbg_vx_addr := io.dbg_vx_addr; io.dbg_vx_data := eng.io.dbg_vx_data
  io.dbg_mma_acc := eng.io.dbg_mma_acc; io.dbg_clct := eng.io.dbg_clct
  io.dbg_traj := eng.io.dbg_traj
  eng.io.fetch_fill_req := false.B; eng.io.fetch_fill_addr := 0.U
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
  ram.io.dbg_rd_addr := io.dbg_rd_addr; io.dbg_rd_data := ram.io.dbg_rd_data
  ram.io.pre_wr_en := io.pre_wr_en; ram.io.pre_wr_addr := io.pre_wr_addr
  ram.io.pre_wr_data := io.pre_wr_data
}

class NpuProgramEngineTrajSpec extends AnyFlatSpec {
  "NpuProgramEngine" should "track the collector dc/cnt during a one-shot session" in {
    simulate(new TrajHarness) { dut =>
      val k = 16
      def preload(base: Long, bytes: Seq[Int]): Unit = {
        for ((b, i) <- bytes.grouped(16).zipWithIndex) {
          var w = BigInt(0)
          for ((v, j) <- b.zipWithIndex) w |= (BigInt(v & 0xFF) << (8 * j))
          dut.io.pre_wr_addr.poke((((base + i * 16) >> 4) & 0xFFF).U)
          dut.io.pre_wr_data.poke(w.U); dut.io.pre_wr_en.poke(true.B)
          dut.clock.step(); dut.io.pre_wr_en.poke(false.B)
        }
      }
      preload(NpuSections.A, (1 to k).map(_ & 0xFF))
      preload(NpuSections.B, (100 until 100 + k).map(_ & 0xFF))
      def issue(instr: Int): Unit = {
        dut.io.instr.poke((instr.toLong & 0xFFFFFFFFL).U)
        dut.io.instr_valid.poke(true.B)
        while (!dut.io.instr_ready.peek().litToBoolean) dut.clock.step()
        dut.clock.step(); dut.io.instr_valid.poke(false.B)
        while (!dut.io.instr_ready.peek().litToBoolean) dut.clock.step()
      }
      // issue vle8s, then watch the session window
      issue(vle8(rd = 4, sect = NpuSections.SECT_A, off = 0))
      issue(vle8(rd = 8, sect = NpuSections.SECT_B, off = 0))
      // streamed one-shot: mma + mma_last, no NOP padding
      issue(mma(rd = 2, vs1 = 4, vs2 = 8, vs3 = 0))
      issue(mmaLast(rd = 0, vs1 = 0, vs2 = 0, vs3 = 0))
      // wait for the session to finish, then read the frozen snapshot
      for (t <- 0 until 200) { dut.clock.step() }
      val entries = (0 until 16).map { i =>
        val e = dut.io.dbg_traj(i).peek().litValue.toInt
        val k = (e >> 5) & 1
        val d = (e >> 4) & 1
        val c = e & 0xF
        s"${if (k == 1) "K" else "k"}${if (d == 1) "H" else "L"}$c"
      }
      println(s"SIM snapshot: ${entries.mkString(" ")}")
      // the 16-tick window before the boundary must be keep=1, dc=H, cnt
      // a full 0..15 rotation (the engine's idle pattern is sim-correct)
      entries.foreach { e =>
        assert(e.head == 'K' && e(1) == 'H', s"trajectory entry not keep=1/dc=H: $e")
      }
      val cnts = entries.map(e => e.drop(2).toInt)
      assert(cnts.distinct.length == 16, s"cnt must be a full 0..15 rotation: $cnts")
    }
  }
}
