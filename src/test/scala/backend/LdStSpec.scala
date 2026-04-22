// See README.md for license details.
// LD / ST / tile.cfg instruction tests through NCoreBackend.
// Verifies the SPM ↔ register-file data path and SREG configuration.

package backend

import scala.util.Random
import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec
import isa._
import isa.micro_op._
import sram.sreg.SRegSel

class LdStSpec extends AnyFlatSpec {
  import NpuAssembler._

  val K = 8; val N = 8; val SPM_ROWS = 256
  val rand = new Random(0xFACE)

  // ---- helpers ----

  def withBackend(body: NCoreBackend => Unit): Unit =
    simulate(new NCoreBackend(K, N, 32, SPM_ROWS)) { dut =>
      dut.io.instr.poke(nop.U)
      // zero all RF address ports
      dut.io.vx_a_addr.poke(0.U); dut.io.vx_b_addr.poke(0.U); dut.io.vx_out_addr.poke(0.U)
      dut.io.ve_a_addr.poke(0.U); dut.io.ve_b_addr.poke(0.U); dut.io.ve_out_addr.poke(0.U)
      dut.io.vr_a_addr.poke(0.U); dut.io.vr_b_addr.poke(0.U); dut.io.vr_c_addr.poke(0.U)
      dut.io.vr_out_addr.poke(0.U)
      dut.io.mma_a_addr.poke(0.U); dut.io.mma_b_addr.poke(0.U); dut.io.mma_out_addr.poke(0.U)
      // zero RF ext port
      dut.io.ext_wr_en.poke(false.B); dut.io.ext_wr_addr.poke(0.U)
      for (i <- 0 until K) dut.io.ext_wr_data(i).poke(0.U)
      dut.io.ext_rd_addr.poke(0.U)
      dut.io.vr_rd_addr.poke(0.U)
      // zero SPM ext port
      dut.io.spm_ext_wr_en.poke(false.B); dut.io.spm_ext_wr_addr.poke(0.U)
      for (i <- 0 until K) dut.io.spm_ext_wr_data(i).poke(0.U)
      dut.io.spm_ext_rd_addr.poke(0.U); dut.io.spm_ext_rd_en.poke(false.B)
      // zero SREG direct port
      dut.io.sreg_wr_en.poke(false.B); dut.io.sreg_wr_sel.poke(0.U); dut.io.sreg_wr_data.poke(0.U)
      dut.io.sreg_tile_rst.poke(false.B)
      body(dut)
    }

  def spmWrite(dut: NCoreBackend, row: Int, data: Array[Int]): Unit = {
    dut.io.spm_ext_wr_en.poke(true.B)
    dut.io.spm_ext_wr_addr.poke(row.U)
    data.zipWithIndex.foreach { case (v, i) => dut.io.spm_ext_wr_data(i).poke(v.U) }
    dut.io.instr.poke(nop.U)
    dut.clock.step()
    dut.io.spm_ext_wr_en.poke(false.B)
  }

  def spmRead(dut: NCoreBackend, row: Int): Array[Int] = {
    dut.io.spm_ext_rd_addr.poke(row.U); dut.io.spm_ext_rd_en.poke(true.B)
    dut.clock.step()
    dut.io.spm_ext_rd_en.poke(false.B)
    assert(dut.io.spm_ext_rd_valid.peek().litToBoolean, "spm_ext_rd_valid must be high")
    Array.tabulate(K)(i => dut.io.spm_ext_rd_data(i).peek().litValue.toInt)
  }

  def issue(dut: NCoreBackend, instr: Int, cycles: Int = 1): Unit = {
    dut.io.instr.poke((instr.toLong & 0xFFFFFFFFL).U)
    for (_ <- 0 until cycles) dut.clock.step()
    dut.io.instr.poke(nop.U)
  }

  def rfRead(dut: NCoreBackend, addr: Int): Array[Int] = {
    dut.io.ext_rd_addr.poke(addr.U)
    Array.tabulate(K)(i => dut.io.ext_rd_data(i).peek().litValue.toInt)
  }

  // ---- SPM direct access ----

  "LdSt SPM" should "write data via ext port and read back" in {
    withBackend { dut =>
      val data = Array.tabulate(K)(i => (i * 11 + 7) % 256)
      spmWrite(dut, row=3, data)
      val got = spmRead(dut, row=3)
      for (i <- 0 until K) assert(got(i) == data(i), s"SPM ext lane $i")
    }
  }

  "LdSt SPM" should "overwrite a row and read updated value" in {
    withBackend { dut =>
      spmWrite(dut, row=1, Array.fill(K)(0xAA))
      spmWrite(dut, row=1, Array.fill(K)(0xBB))
      val got = spmRead(dut, row=1)
      for (i <- 0 until K) assert(got(i) == 0xBB, s"overwrite lane $i")
    }
  }

  // ---- ld.VX ----

  "LdSt ld.VX" should "load a row from SPM into VX[rd]" in {
    withBackend { dut =>
      val data = Array.tabulate(K)(i => rand.nextInt(256))
      val spmRow = 5; val rfDest = 3

      // 1. Write test data to SPM
      spmWrite(dut, spmRow, data)

      // 2. Issue ld.VX rd=3, base=spmRow, offset=0
      //    The instruction keeps the op for 2 cycles (LD pipeline)
      issue(dut, ldVx(rd=rfDest, base=spmRow, offset=0), cycles=2)
      dut.clock.step()  // extra cycle for write-back to settle

      // 3. Read back from RF
      val got = rfRead(dut, rfDest)
      for (i <- 0 until K) {
        assert(got(i) == data(i), s"ld.VX lane $i: expected ${data(i)} got ${got(i)}")
      }
    }
  }

  "LdSt ld.VX" should "load different rows using offset" in {
    withBackend { dut =>
      val dataA = Array.tabulate(K)(_ => rand.nextInt(256))
      val dataB = Array.tabulate(K)(_ => rand.nextInt(256))
      spmWrite(dut, row=10, dataA)
      spmWrite(dut, row=11, dataB)

      // ld.VX rd=0, base=10, offset=0  → row 10
      issue(dut, ldVx(rd=0, base=10, offset=0), cycles=2)
      dut.clock.step()
      val gotA = rfRead(dut, 0)
      for (i <- 0 until K) assert(gotA(i) == dataA(i), s"row10 lane $i")

      // ld.VX rd=1, base=10, offset=1  → row 11
      issue(dut, ldVx(rd=1, base=10, offset=1), cycles=2)
      dut.clock.step()
      val gotB = rfRead(dut, 1)
      for (i <- 0 until K) assert(gotB(i) == dataB(i), s"row11 lane $i")
    }
  }

  // ---- st.VX ----

  "LdSt st.VX" should "store VX[rd] to SPM" in {
    withBackend { dut =>
      val data = Array.tabulate(K)(i => rand.nextInt(256))
      val rfSrc = 4; val spmRow = 20

      // 1. Load data into RF via ext_wr
      dut.io.ext_wr_en.poke(true.B); dut.io.ext_wr_addr.poke(rfSrc.U)
      data.zipWithIndex.foreach { case (v, i) => dut.io.ext_wr_data(i).poke(v.U) }
      dut.io.instr.poke(nop.U); dut.clock.step()
      dut.io.ext_wr_en.poke(false.B)

      // 2. Issue st.VX rs2=rfSrc, base=spmRow, offset=0
      dut.io.vx_b_addr.poke(rfSrc.U)   // vx_b_addr feeds vx_r_addr(2) which ST uses
      issue(dut, stVx(rs2=rfSrc, base=spmRow, offset=0), cycles=1)

      // 3. Read SPM and verify
      val got = spmRead(dut, spmRow)
      for (i <- 0 until K) {
        assert(got(i) == data(i), s"st.VX lane $i: expected ${data(i)} got ${got(i)}")
      }
    }
  }

  // ---- tile.cfg direct SREG write ----

  "LdSt SREG" should "configure conv params via direct SREG write port" in {
    withBackend { dut =>
      val h = 56; val w = 56
      dut.io.sreg_wr_en.poke(true.B)
      dut.io.sreg_wr_sel.poke(SRegSel.TILE_CFG_HW)
      dut.io.sreg_wr_data.poke(((w << 16) | h).U)
      dut.clock.step()
      dut.io.sreg_wr_en.poke(false.B)
      dut.io.sreg_conv.H_in.expect(h.U)
      dut.io.sreg_conv.W_in.expect(w.U)
    }
  }

  "LdSt SREG" should "configure kernel params via direct SREG write port" in {
    withBackend { dut =>
      // Kh=3, Kw=3, stride=0, dilation=0, pad_h=1, pad_w=1, mode=0 (conv2d)
      val packed = 3 | (3 << 4) | (0 << 8) | (0 << 12) | (1 << 16) | (1 << 20) | (0 << 24)
      dut.io.sreg_wr_en.poke(true.B)
      dut.io.sreg_wr_sel.poke(SRegSel.TILE_CFG_KERN)
      dut.io.sreg_wr_data.poke(packed.U)
      dut.clock.step()
      dut.io.sreg_wr_en.poke(false.B)
      dut.io.sreg_conv.Kh.expect(3.U)
      dut.io.sreg_conv.Kw.expect(3.U)
      dut.io.sreg_conv.pad_h.expect(1.U)
      dut.io.sreg_conv.mode.expect(0.U)
    }
  }

  "LdSt SREG" should "reset tile counters via tile_rst" in {
    withBackend { dut =>
      // Set position to (2, 5)
      dut.io.sreg_wr_en.poke(true.B)
      dut.io.sreg_wr_sel.poke(SRegSel.TILE_CFG_POS)
      dut.io.sreg_wr_data.poke(((5 << 16) | 2).U)
      dut.clock.step()
      dut.io.sreg_wr_en.poke(false.B)
      dut.io.sreg_tile_h.expect(2.U); dut.io.sreg_tile_w.expect(5.U)

      // Reset
      dut.io.sreg_tile_rst.poke(true.B)
      dut.clock.step()
      dut.io.sreg_tile_rst.poke(false.B)
      dut.io.sreg_tile_h.expect(0.U); dut.io.sreg_tile_w.expect(0.U)
    }
  }

  // ---- tile.cfg via ISA (tile.cfg instruction) ----

  "LdSt tile.cfg ISA" should "configure H/W via tileCfgHW instruction from VR" in {
    withBackend { dut =>
      // Pre-load the 32-bit config word into VR[0] lane 0 via ext_wr on the VX rows
      // VR[0] = VX[0..3]; pack into VX[0] (lo byte) and VX[1] (next byte for W)
      // For simplicity: write the packed word byte-by-byte into VX[0..3] all-lane-0
      val h = 28; val w = 28
      val packed = (w << 16) | h  // 32-bit config word

      // Write 4 consecutive VX rows that form VR[0]
      // Each VX row = K lanes; lane 0 gets one byte of the 32-bit word
      for (sub <- 0 until 4) {
        val byte = (packed >> (8 * sub)) & 0xFF
        dut.io.ext_wr_en.poke(true.B)
        dut.io.ext_wr_addr.poke(sub.U)  // VX[0], VX[1], VX[2], VX[3]
        for (i <- 0 until K) dut.io.ext_wr_data(i).poke(byte.U)
        dut.clock.step()
      }
      dut.io.ext_wr_en.poke(false.B)

      // Set VR read address to VR[0] so the tile.cfg instruction reads it
      dut.io.vr_a_addr.poke(0.U)

      // Issue tile.cfg HW (rs1=0 → VR[0])
      dut.io.instr.poke((tileCfgHW(rs1=0).toLong & 0xFFFFFFFFL).U)
      dut.clock.step()
      dut.io.instr.poke(nop.U)
      dut.clock.step()

      dut.io.sreg_conv.H_in.expect(h.U, s"tile.cfg H_in expected $h")
      dut.io.sreg_conv.W_in.expect(w.U, s"tile.cfg W_in expected $w")
    }
  }
}
