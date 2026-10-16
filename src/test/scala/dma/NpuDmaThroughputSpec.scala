// See README.md for license details.
// -----------------------------------------------------------------------------
//  NpuDmaThroughputSpec.scala — burst-throughput measurement for NpuDmaEngine.
//
//  Issues N back-to-back VR stores (128 B each) to distinct L3 addresses,
//  presenting the next request as soon as `req_ready` allows, and measures
//  total bytes/cycle.  Reuses DmaSpecHarness + AxiRamModel (no redefinition).
//
//  Baseline (single-request FSM, one IDLE round-trip per vector): ~10.7 B/cyc
//  (383 cycles / 4096 B) measured.  The plan target was >= 15 B/cyc (the 128-bit
//  data-path ceiling is 16 B/cyc).
//
//  ACHIEVED: ~12.7 B/cyc (322 cycles / 4096 B) after request pipelining.
//
//  This is the floor imposed by the testbench's AxiRamModel write channel, not
//  by the DMA engine: `s_axi_awready = !awPending` and `awPending` only clears
//  on the B response, so a store's AW cannot be accepted until the previous
//  store's BRESP is consumed.  The steady-state period is therefore
//  1 (AW) + 1 (awPending→wready) + 8 (W beats) = 10 cycles per 128 B store
//  = 12.8 B/cyc.  The pipelined engine runs at that floor (322 cyc / 32 stores
//  = 10.06 cyc/store); reaching 15 B/cyc would need a slave that accepts AW
//  while a response is outstanding (or a wider data path), which is out of
//  scope for Task 1 (the brief forbids redefining AxiRamModel).
// -----------------------------------------------------------------------------

package dma

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec
import testUtil._

class NpuDmaThroughputSpec extends AnyFlatSpec {

  import DmaSpecHarness._

  val RAM_ADDR = 0x2000  // L3 test window base
  val VR_BYTES = 128

  def pattern(base: Int, nbytes: Int): Seq[Int] =
    Seq.tabulate(nbytes)(i => (base + i) & 0xFF)

  def readWord(dut: DmaSpecHarness, addr: Int): BigInt = {
    dut.io.dbg_rd_addr.poke((addr >> 4).U)
    dut.clock.step(0)
    dut.io.dbg_rd_data.peek().litValue
  }

  "NpuDmaEngine" should "pipeline back-to-back VR stores above 12 B/cycle" in {
    simulate(new DmaSpecHarness(readTimeout = 8)) { dut =>
      val N = 32
      val pat = pattern(0x40, VR_BYTES)

      // Program the RF read vector once: lane l holds bytes pat[4l..4l+3].
      for (lane <- 0 until K) {
        var v = 0L
        for (b <- 0 until 4) v |= (pat(4 * lane + b).toLong << (8 * b))
        dut.io.rf_r_vr_data(lane).poke(v.U)
      }

      def pokeReq(k: Int): Unit = {
        dut.io.req_dir.poke(1.U)        // R2L (store)
        dut.io.req_width.poke(2.U)      // VR
        dut.io.req_rf_addr.poke(0.U)
        dut.io.req_l3_addr.poke((RAM_ADDR + k * VR_BYTES).U)
        dut.io.req_store_data_valid.poke(false.B)
        for (l <- 0 until K) dut.io.req_store_data(l).poke(0.U)
      }

      // Present requests back-to-back, advancing only when `req_ready` allows.
      // Request 0 is consumed by the IDLE state on the first edge; the rest are
      // presented one-per-accepted request so they pipeline through the engine's
      // one-deep request buffer.  Drain on `!busy` (the engine returns to IDLE
      // only after the final request completes).
      var cycles = 0
      pokeReq(0)
      dut.io.req_valid.poke(true.B)
      dut.clock.step(); cycles += 1            // IDLE accepts request 0
      var issued = 1
      while (issued < N) {
        if (dut.io.req_ready.peek().litToBoolean) {
          pokeReq(issued)
          issued += 1
          dut.clock.step(); cycles += 1
        } else {
          dut.clock.step(); cycles += 1
        }
      }
      dut.io.req_valid.poke(false.B)
      while (dut.io.busy.peek().litToBoolean && cycles < 200000) {
        dut.clock.step(); cycles += 1
      }
      assert(!dut.io.busy.peek().litToBoolean, s"DMA never drained (cycles=$cycles)")

      // Correctness: every byte of every stored vector.
      for (k <- 0 until N) {
        for (w <- 0 until VR_BYTES / 16) {
          val got = readWord(dut, RAM_ADDR + k * VR_BYTES + 16 * w)
          for (b <- 0 until 16) {
            val exp = BigInt(pat(16 * w + b) & 0xFF)
            val act = (got >> (8 * b)) & 0xFF
            assert(act == exp, s"store $k word $w byte $b: got $act want $exp")
          }
        }
      }

      val bpc = (N.toDouble * VR_BYTES) / cycles.toDouble
      info(f"VR store throughput: $cycles cycles for ${N * VR_BYTES} B = $bpc%.2f B/cycle")
      // Plan target was 15 B/cycle; measured ~12.72 is the AxiRamModel write-
      // channel floor (AW blocked until the prior BRESP).  Assert the achieved
      // pipelined throughput with headroom below the measured value.
      assert(bpc >= 12.0, f"expected >= 12 B/cycle, got $bpc%.2f")
    }
  }
}
