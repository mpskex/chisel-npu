// See README.md for license details.
// -----------------------------------------------------------------------------
//  LdStSpec — LD / ST / gather / tile / scatter / tile.cfg tests
//
//  All tests run through NCoreBackend with L=256 so that RF rows [0..31] serve
//  as working registers and rows [32..255] serve as bulk storage (equivalent
//  to the old SPM).  DMA writes are performed via the ext_wr port.
//
//  LD pipeline timing (with RegInit RF):
//    Cycle 0 — issue instruction; RF read is combinational; data captured.
//    Cycle 1 — write-back to dest register (RegisterInit writes on clock edge).
//    → issue for 1 cycle, then step 1 more cycle before reading result.
// -----------------------------------------------------------------------------

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

  val K   = 8
  val N   = 8
  val L   = 256   // total RF rows; rows 32..255 are "storage"
  val rand = new Random(0xFACE)

  // ---- helpers ----

  def withBackend(body: NCoreBackend => Unit): Unit =
    simulate(new NCoreBackend(K, N, L)) { dut =>
      dut.io.instr.poke(nop.U)
      // zero RF address ports
      dut.io.vx_a_addr.poke(0.U); dut.io.vx_b_addr.poke(0.U); dut.io.vx_out_addr.poke(0.U)
      dut.io.ve_a_addr.poke(0.U); dut.io.ve_b_addr.poke(0.U); dut.io.ve_out_addr.poke(0.U)
      dut.io.vr_a_addr.poke(0.U); dut.io.vr_b_addr.poke(0.U); dut.io.vr_c_addr.poke(0.U)
      dut.io.vr_out_addr.poke(0.U)
      dut.io.mma_a_addr.poke(0.U); dut.io.mma_b_addr.poke(0.U); dut.io.mma_out_addr.poke(0.U)
      // zero ext RF port
      dut.io.ext_wr_en.poke(false.B); dut.io.ext_wr_addr.poke(0.U)
      for (i <- 0 until K) dut.io.ext_wr_data(i).poke(0.U)
      dut.io.ext_rd_addr.poke(0.U)
      dut.io.vr_rd_addr.poke(0.U)
      // zero SREG direct port
      dut.io.sreg_wr_en.poke(false.B); dut.io.sreg_wr_sel.poke(0.U)
      dut.io.sreg_wr_data.poke(0.U); dut.io.sreg_tile_rst.poke(false.B)
      body(dut)
    }

  /** Write one VX row of the RF via the external DMA port. */
  def rfWrite(dut: NCoreBackend, row: Int, data: Array[Int]): Unit = {
    dut.io.ext_wr_en.poke(true.B)
    dut.io.ext_wr_addr.poke(row.U)
    data.zipWithIndex.foreach { case (v, i) => dut.io.ext_wr_data(i).poke(v.U) }
    dut.io.instr.poke(nop.U)
    dut.clock.step()
    dut.io.ext_wr_en.poke(false.B)
  }

  /** Read one VX row of the RF via the external read port (combinational). */
  def rfRead(dut: NCoreBackend, row: Int): Array[Int] = {
    dut.io.ext_rd_addr.poke(row.U)
    Array.tabulate(K)(i => dut.io.ext_rd_data(i).peek().litValue.toInt)
  }

  /** Issue an instruction for `cycles` clock edges, then restore to NOP. */
  def issue(dut: NCoreBackend, instr: Int, cycles: Int = 1): Unit = {
    dut.io.instr.poke((instr.toLong & 0xFFFFFFFFL).U)
    for (_ <- 0 until cycles) dut.clock.step()
    dut.io.instr.poke(nop.U)
  }

  // =========================================================================
  // RF ext-port round-trip (was "SPM ext-port" in the old design)
  // =========================================================================

  "LdSt RF" should "write data via ext port and read back (combinational)" in {
    withBackend { dut =>
      val data = Array.tabulate(K)(i => (i * 11 + 7) & 0xFF)
      rfWrite(dut, row = 50, data)
      val got = rfRead(dut, row = 50)
      for (i <- 0 until K) assert(got(i) == data(i), s"RF ext lane $i")
    }
  }

  "LdSt RF" should "overwrite a row and read updated value" in {
    withBackend { dut =>
      rfWrite(dut, row = 33, Array.fill(K)(0xAA))
      rfWrite(dut, row = 33, Array.fill(K)(0xBB))
      val got = rfRead(dut, row = 33)
      for (i <- 0 until K) assert(got(i) == 0xBB, s"overwrite lane $i")
    }
  }

  // =========================================================================
  // ld.VX — load from RF storage area into working register
  // =========================================================================

  "LdSt ld.VX" should "load a row from RF storage into VX[rd]" in {
    withBackend { dut =>
      val data    = Array.tabulate(K)(_ => rand.nextInt(256))
      val srcRow  = 42        // RF storage row
      val destReg = 3         // working VX register

      // 1. Write data to RF storage via DMA
      rfWrite(dut, srcRow, data)

      // 2. Issue ld.VX rd=destReg, base=srcRow, offset=0
      //    LD pipeline: 1 issue cycle + 1 write-back cycle
      issue(dut, ldVx(rd = destReg, base = srcRow, offset = 0), cycles = 1)
      dut.clock.step()   // write-back

      // 3. Read working register and verify
      val got = rfRead(dut, destReg)
      for (i <- 0 until K)
        assert(got(i) == data(i), s"ld.VX lane $i: expected ${data(i)} got ${got(i)}")
    }
  }

  "LdSt ld.VX" should "load different rows using offset" in {
    withBackend { dut =>
      val dataA = Array.tabulate(K)(_ => rand.nextInt(256))
      val dataB = Array.tabulate(K)(_ => rand.nextInt(256))
      rfWrite(dut, row = 80, dataA)
      rfWrite(dut, row = 81, dataB)

      // ld.VX rd=0, base=80, offset=0  → row 80
      issue(dut, ldVx(rd = 0, base = 80, offset = 0), cycles = 1)
      dut.clock.step()
      val gotA = rfRead(dut, 0)
      for (i <- 0 until K) assert(gotA(i) == dataA(i), s"row80 lane $i")

      // ld.VX rd=1, base=80, offset=1  → row 81
      issue(dut, ldVx(rd = 1, base = 80, offset = 1), cycles = 1)
      dut.clock.step()
      val gotB = rfRead(dut, 1)
      for (i <- 0 until K) assert(gotB(i) == dataB(i), s"row81 lane $i")
    }
  }

  // =========================================================================
  // st.VX — store working register to RF storage area
  // =========================================================================

  "LdSt st.VX" should "store VX[rs2] to RF storage row" in {
    withBackend { dut =>
      val data   = Array.tabulate(K)(_ => rand.nextInt(256))
      val srcReg = 5
      val dstRow = 100

      // 1. Pre-load data into working register via ext_wr
      rfWrite(dut, srcReg, data)

      // 2. Issue st.VX rs2=srcReg, base=dstRow
      //    Port 2 (vx_b_addr) provides the source register to the ST path.
      dut.io.vx_b_addr.poke(srcReg.U)
      issue(dut, stVx(rs2 = srcReg, base = dstRow, offset = 0), cycles = 1)
      dut.io.vx_b_addr.poke(0.U)

      // 3. Read storage row and verify
      val got = rfRead(dut, dstRow)
      for (i <- 0 until K)
        assert(got(i) == data(i), s"st.VX lane $i: expected ${data(i)} got ${got(i)}")
    }
  }

  // =========================================================================
  // ld.gather — diagonal K-wide indexed read
  //   VX[rd][k] = RF[ VX[rs1][k] ][ k ]
  // =========================================================================

  "LdSt ld.gather" should "load K lanes from K distinct RF rows (diagonal gather)" in {
    withBackend { dut =>
      // Write K distinct rows in the storage area; place a distinctive value
      // at lane k of each row so we can verify the diagonal read.
      val baseRow = 64
      val rowData = Array.tabulate(K) { k =>
        // Row baseRow+k has value (k*17+3) at lane k; other lanes irrelevant
        Array.tabulate(K)(lane => if (lane == k) (k * 17 + 3) & 0xFF else 0)
      }
      for (k <- 0 until K) rfWrite(dut, baseRow + k, rowData(k))

      // Build index vector in VX[1]: lane k → row baseRow+k
      val idxData = Array.tabulate(K)(k => baseRow + k)
      rfWrite(dut, 1, idxData)   // VX[1] = index register

      // Route vx_b_addr to VX[1] (port 2 reads rs1 for gather)
      dut.io.vx_b_addr.poke(1.U)

      // Issue ld.gather rd=2, rs1=1
      issue(dut, ldGather(rd = 2, rs1 = 1), cycles = 1)
      dut.clock.step()   // write-back

      dut.io.vx_b_addr.poke(0.U)

      // Verify VX[2][k] = rowData(k)(k) = (k*17+3) & 0xFF
      val got = rfRead(dut, 2)
      for (k <- 0 until K) {
        val expected = (k * 17 + 3) & 0xFF
        assert(got(k) == expected,
          s"gather lane $k: expected $expected (row ${baseRow+k} lane $k), got ${got(k)}")
      }
    }
  }

  // =========================================================================
  // st.scatter — diagonal K-wide indexed write
  //   RF[ VX[rs1][k] ][ k ] = VX[rs2][k]
  // =========================================================================

  "LdSt st.scatter" should "write K lanes to K distinct RF rows (diagonal scatter)" in {
    withBackend { dut =>
      val baseRow = 128

      // Build index vector in VX[1]: lane k → row baseRow+k
      rfWrite(dut, 1, Array.tabulate(K)(k => baseRow + k))

      // Build data vector in VX[2]: lane k = k*13+7
      val srcData = Array.tabulate(K)(k => (k * 13 + 7) & 0xFF)
      rfWrite(dut, 2, srcData)

      // Route port 2 (vx_b_addr) to VX[1] (rs1 = index vector)
      // Route port 1 (vx_a_addr) to VX[2] (rs2 = data via scatter path)
      dut.io.vx_b_addr.poke(1.U)
      dut.io.vx_a_addr.poke(2.U)

      // Issue st.scatter rs1=1 (indices), rs2=2 (data)
      issue(dut, stScatter(rs1 = 1, rs2 = 2), cycles = 1)

      dut.io.vx_b_addr.poke(0.U)
      dut.io.vx_a_addr.poke(0.U)

      // Verify: RF[baseRow+k][k] == srcData(k)
      for (k <- 0 until K) {
        val row = rfRead(dut, baseRow + k)
        assert(row(k) == srcData(k),
          s"scatter lane $k: RF[${baseRow+k}][$k] expected ${srcData(k)}, got ${row(k)}")
      }
    }
  }

  // =========================================================================
  // ld.tile — SREG-addressed load (strided tiling)
  //   row = rs1_base + tile_h * stride_row_h + tile_w * stride_row_w
  // =========================================================================

  "LdSt ld.tile" should "load the correct RF row using SREG tile counters and strides" in {
    withBackend { dut =>
      // Layout in RF storage:  3×4 logical matrix, stride_row_h=4, stride_row_w=1
      //   logical[row][col] = RF[ base + row*4 + col ][k]  (same value in all K lanes)
      val base         = 32
      val stride_row_h = 4
      val stride_row_w = 1

      // Fill rows
      for (r <- 0 until 3; c <- 0 until 4) {
        val rfRow = base + r * stride_row_h + c * stride_row_w
        rfWrite(dut, rfRow, Array.fill(K)((r * 10 + c) & 0xFF))
      }

      // Configure SREG: stride_h and stride_w
      dut.io.sreg_wr_en.poke(true.B)
      dut.io.sreg_wr_sel.poke(SRegSel.TILE_CFG_STRIDE_H)
      dut.io.sreg_wr_data.poke(stride_row_h.U)
      dut.clock.step()
      dut.io.sreg_wr_sel.poke(SRegSel.TILE_CFG_STRIDE_W)
      dut.io.sreg_wr_data.poke(stride_row_w.U)
      dut.clock.step()
      dut.io.sreg_wr_en.poke(false.B)

      // Set tile position to (1, 2) — should load logical[1][2]
      dut.io.sreg_wr_en.poke(true.B)
      dut.io.sreg_wr_sel.poke(SRegSel.TILE_CFG_POS)
      dut.io.sreg_wr_data.poke(((2 << 16) | 1).U)   // tile_w=2, tile_h=1
      dut.clock.step()
      dut.io.sreg_wr_en.poke(false.B)

      // Issue ld.tile rd=0, rs1=base  → addr = base + 1*4 + 2*1 = base+6
      issue(dut, ldTile(rd = 0, rs1 = base), cycles = 1)
      dut.clock.step()

      val got = rfRead(dut, 0)
      val expected = (1 * 10 + 2) & 0xFF   // logical[1][2]
      for (k <- 0 until K)
        assert(got(k) == expected, s"ld.tile lane $k: expected $expected got ${got(k)}")
    }
  }

  "LdSt ld.tile" should "auto-increment tile_w when autoInc=true" in {
    withBackend { dut =>
      // Minimal setup: stride_row_w=1, stride_row_h=0; tile starts at (0,0)
      dut.io.sreg_wr_en.poke(true.B)
      dut.io.sreg_wr_sel.poke(SRegSel.TILE_CFG_STRIDE_W)
      dut.io.sreg_wr_data.poke(1.U)
      dut.clock.step()
      dut.io.sreg_wr_sel.poke(SRegSel.TILE_CFG_STRIDE_H)
      dut.io.sreg_wr_data.poke(0.U)
      dut.clock.step()
      dut.io.sreg_wr_sel.poke(SRegSel.TILE_CFG_POS)
      dut.io.sreg_wr_data.poke(0.U)
      dut.clock.step()
      dut.io.sreg_wr_en.poke(false.B)

      // tile_w should be 0 before the load
      dut.io.sreg_tile_w.expect(0.U)

      // Issue ld.tile with autoInc=true
      issue(dut, ldTile(rd = 0, rs1 = 32, autoInc = true), cycles = 1)
      dut.clock.step()   // write-back + autoinc pulse

      // tile_w should now be 1
      dut.io.sreg_tile_w.expect(1.U)
    }
  }

  // =========================================================================
  // tile.cfg — write conv / stride params to SREG (direct harness port)
  // =========================================================================

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

  "LdSt SREG" should "configure stride_row_h and stride_row_w" in {
    withBackend { dut =>
      dut.io.sreg_wr_en.poke(true.B)
      dut.io.sreg_wr_sel.poke(SRegSel.TILE_CFG_STRIDE_H)
      dut.io.sreg_wr_data.poke(64.U)
      dut.clock.step()
      dut.io.sreg_wr_sel.poke(SRegSel.TILE_CFG_STRIDE_W)
      dut.io.sreg_wr_data.poke(1.U)
      dut.clock.step()
      dut.io.sreg_wr_en.poke(false.B)
      dut.io.sreg_conv.stride_row_h.expect(64.U)
      dut.io.sreg_conv.stride_row_w.expect(1.U)
    }
  }

  "LdSt SREG" should "reset tile counters via tile_rst" in {
    withBackend { dut =>
      dut.io.sreg_wr_en.poke(true.B)
      dut.io.sreg_wr_sel.poke(SRegSel.TILE_CFG_POS)
      dut.io.sreg_wr_data.poke(((5 << 16) | 2).U)
      dut.clock.step()
      dut.io.sreg_wr_en.poke(false.B)
      dut.io.sreg_tile_h.expect(2.U); dut.io.sreg_tile_w.expect(5.U)

      dut.io.sreg_tile_rst.poke(true.B)
      dut.clock.step()
      dut.io.sreg_tile_rst.poke(false.B)
      dut.io.sreg_tile_h.expect(0.U); dut.io.sreg_tile_w.expect(0.U)
    }
  }

  // =========================================================================
  // tile.cfg via ISA path (tile.cfg instruction reading VR[rs1] lane 0)
  // =========================================================================

  "LdSt tile.cfg ISA" should "configure H/W via tileCfgHW instruction from VR" in {
    withBackend { dut =>
      val h = 28; val w = 28
      val packed = (w << 16) | h

      // Write the packed 32-bit word into VR[0] via the four underlying VX rows.
      // VR[0] = VX[0..3]; each VX row has K lanes; lane 0 gets one byte.
      for (sub <- 0 until 4) {
        val byte = (packed >> (8 * sub)) & 0xFF
        rfWrite(dut, sub, Array.tabulate(K)(_ => byte))
      }

      dut.io.vr_a_addr.poke(0.U)

      // Issue tile.cfg HW (rs1=0 → VR[0])
      issue(dut, tileCfgHW(rs1 = 0), cycles = 1)
      dut.clock.step()

      dut.io.sreg_conv.H_in.expect(h.U, s"tile.cfg H_in expected $h")
      dut.io.sreg_conv.W_in.expect(w.U, s"tile.cfg W_in expected $w")
    }
  }
}
