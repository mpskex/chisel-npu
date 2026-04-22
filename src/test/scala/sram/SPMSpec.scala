// See README.md for license details.
// Tests for the Scratch-Pad Memory module.

package sram.spm

import scala.util.Random
import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec

class SPMSpec extends AnyFlatSpec {
  val K = 8; val N = 8; val ROWS = 256   // 2 KiB test SPM

  def mkSpm() = new SPM(K, N, ROWS)

  def zeroCtrl(dut: SPM): Unit = {
    dut.io.rd_addr.poke(0.U); dut.io.rd_en.poke(false.B)
    dut.io.wr_addr.poke(0.U); dut.io.wr_en.poke(false.B)
    for (i <- 0 until K) dut.io.wr_data(i).poke(0.U)
    dut.io.ext_wr_addr.poke(0.U); dut.io.ext_wr_en.poke(false.B)
    for (i <- 0 until K) dut.io.ext_wr_data(i).poke(0.U)
  }

  // ---- basic ext_wr then rd ----
  "SPM" should "write via ext port and read back after 1 cycle" in {
    simulate(mkSpm()) { dut =>
      zeroCtrl(dut)
      val data = Array.tabulate(K)(i => (i * 7 + 3) % 256)
      val addr = 5

      dut.io.ext_wr_en.poke(true.B)
      dut.io.ext_wr_addr.poke(addr.U)
      for (i <- 0 until K) dut.io.ext_wr_data(i).poke(data(i).U)
      dut.clock.step()
      dut.io.ext_wr_en.poke(false.B)

      // Issue read
      dut.io.rd_addr.poke(addr.U); dut.io.rd_en.poke(true.B)
      dut.clock.step()
      dut.io.rd_en.poke(false.B)

      // rd_valid now high; rd_data valid
      dut.io.rd_valid.expect(true.B)
      for (i <- 0 until K) {
        dut.io.rd_data(i).expect(data(i).U, s"SPM read lane $i")
      }
    }
  }

  "SPM" should "write via wr port and read back" in {
    simulate(mkSpm()) { dut =>
      zeroCtrl(dut)
      val data = Array.fill(K)(42)
      val addr = 10

      dut.io.wr_en.poke(true.B)
      dut.io.wr_addr.poke(addr.U)
      for (i <- 0 until K) dut.io.wr_data(i).poke(data(i).U)
      dut.clock.step()
      dut.io.wr_en.poke(false.B)

      dut.io.rd_addr.poke(addr.U); dut.io.rd_en.poke(true.B)
      dut.clock.step()
      dut.io.rd_en.poke(false.B)

      dut.io.rd_valid.expect(true.B)
      for (i <- 0 until K) dut.io.rd_data(i).expect(42.U, s"SPM wr lane $i")
    }
  }

  "SPM" should "rd_valid is low when rd_en was not asserted" in {
    simulate(mkSpm()) { dut =>
      zeroCtrl(dut)
      dut.clock.step()
      dut.io.rd_valid.expect(false.B)
    }
  }

  "SPM" should "write multiple rows and read back independently" in {
    val rand = new Random(0xC0FFEE)
    simulate(mkSpm()) { dut =>
      zeroCtrl(dut)
      val rows = 16
      val stored = Array.tabulate(rows, K)((_, _) => rand.nextInt(256))

      for (r <- 0 until rows) {
        dut.io.ext_wr_en.poke(true.B)
        dut.io.ext_wr_addr.poke(r.U)
        for (i <- 0 until K) dut.io.ext_wr_data(i).poke(stored(r)(i).U)
        dut.clock.step()
      }
      dut.io.ext_wr_en.poke(false.B)

      for (r <- 0 until rows) {
        dut.io.rd_addr.poke(r.U); dut.io.rd_en.poke(true.B)
        dut.clock.step()
        dut.io.rd_en.poke(false.B)
        dut.io.rd_valid.expect(true.B)
        for (i <- 0 until K) {
          dut.io.rd_data(i).expect(stored(r)(i).U, s"SPM multi row=$r lane=$i")
        }
      }
    }
  }

  "SPM" should "overwrite a row correctly" in {
    simulate(mkSpm()) { dut =>
      zeroCtrl(dut)
      val addr = 7

      // write 0xAB to row 7
      dut.io.ext_wr_en.poke(true.B)
      dut.io.ext_wr_addr.poke(addr.U)
      for (i <- 0 until K) dut.io.ext_wr_data(i).poke(0xAB.U)
      dut.clock.step()

      // overwrite with 0xCD
      for (i <- 0 until K) dut.io.ext_wr_data(i).poke(0xCD.U)
      dut.clock.step()
      dut.io.ext_wr_en.poke(false.B)

      dut.io.rd_addr.poke(addr.U); dut.io.rd_en.poke(true.B)
      dut.clock.step()
      dut.io.rd_valid.expect(true.B)
      for (i <- 0 until K) dut.io.rd_data(i).expect(0xCD.U, s"overwrite lane $i")
    }
  }
}
