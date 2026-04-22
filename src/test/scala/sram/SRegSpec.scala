// See README.md for license details.
// Tests for the Special Register File (SREG).

package sram.sreg

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec

class SRegSpec extends AnyFlatSpec {

  def zeroCtrl(dut: SpecialRegFile): Unit = {
    dut.io.wr_en.poke(false.B)
    dut.io.wr_sel.poke(0.U)
    dut.io.wr_data.poke(0.U)
    dut.io.tile_w_inc.poke(false.B)
    dut.io.tile_h_inc.poke(false.B)
    dut.io.tile_rst.poke(false.B)
  }

  "SREG" should "initialise tile_h and tile_w to zero" in {
    simulate(new SpecialRegFile) { dut =>
      zeroCtrl(dut)
      dut.clock.step(0)
      dut.io.tile_h.expect(0.U)
      dut.io.tile_w.expect(0.U)
    }
  }

  "SREG" should "write H_in and W_in via sel=0" in {
    simulate(new SpecialRegFile) { dut =>
      zeroCtrl(dut)
      val h = 112; val w = 112
      dut.io.wr_en.poke(true.B)
      dut.io.wr_sel.poke(SRegSel.TILE_CFG_HW)
      dut.io.wr_data.poke(((w << 16) | h).U)
      dut.clock.step()
      dut.io.wr_en.poke(false.B)
      dut.io.conv.H_in.expect(h.U)
      dut.io.conv.W_in.expect(w.U)
    }
  }

  "SREG" should "write C_in and C_out via sel=1" in {
    simulate(new SpecialRegFile) { dut =>
      zeroCtrl(dut)
      val cin = 64; val cout = 128
      dut.io.wr_en.poke(true.B)
      dut.io.wr_sel.poke(SRegSel.TILE_CFG_CH)
      dut.io.wr_data.poke(((cout << 16) | cin).U)
      dut.clock.step()
      dut.io.wr_en.poke(false.B)
      dut.io.conv.C_in.expect(cin.U)
      dut.io.conv.C_out.expect(cout.U)
    }
  }

  "SREG" should "write kernel params via sel=2" in {
    simulate(new SpecialRegFile) { dut =>
      zeroCtrl(dut)
      // Kh=3, Kw=3, stride=0(=1), dilation=0(=1), pad_h=1, pad_w=1, mode=0
      val kh=3; val kw=3; val stride=0; val dilation=0; val pad_h=1; val pad_w=1; val mode=0
      val packed = kh | (kw << 4) | (stride << 8) | (dilation << 12) |
                   (pad_h << 16) | (pad_w << 20) | (mode << 24)
      dut.io.wr_en.poke(true.B)
      dut.io.wr_sel.poke(SRegSel.TILE_CFG_KERN)
      dut.io.wr_data.poke(packed.U)
      dut.clock.step()
      dut.io.wr_en.poke(false.B)
      dut.io.conv.Kh.expect(kh.U)
      dut.io.conv.Kw.expect(kw.U)
      dut.io.conv.stride.expect(stride.U)
      dut.io.conv.dilation.expect(dilation.U)
      dut.io.conv.pad_h.expect(pad_h.U)
      dut.io.conv.pad_w.expect(pad_w.U)
      dut.io.conv.mode.expect(mode.U)
    }
  }

  "SREG" should "set tile position via sel=3" in {
    simulate(new SpecialRegFile) { dut =>
      zeroCtrl(dut)
      val th = 5; val tw = 12
      dut.io.wr_en.poke(true.B)
      dut.io.wr_sel.poke(SRegSel.TILE_CFG_POS)
      dut.io.wr_data.poke(((tw << 16) | th).U)
      dut.clock.step()
      dut.io.wr_en.poke(false.B)
      dut.io.tile_h.expect(th.U)
      dut.io.tile_w.expect(tw.U)
    }
  }

  "SREG" should "auto-increment tile_w on tile_w_inc" in {
    simulate(new SpecialRegFile) { dut =>
      zeroCtrl(dut)
      dut.clock.step()
      for (i <- 1 to 5) {
        dut.io.tile_w_inc.poke(true.B)
        dut.clock.step()
        dut.io.tile_w_inc.poke(false.B)
        dut.io.tile_w.expect(i.U, s"tile_w after $i increments")
      }
    }
  }

  "SREG" should "auto-increment tile_h on tile_h_inc" in {
    simulate(new SpecialRegFile) { dut =>
      zeroCtrl(dut)
      dut.clock.step()
      for (i <- 1 to 3) {
        dut.io.tile_h_inc.poke(true.B)
        dut.clock.step()
        dut.io.tile_h_inc.poke(false.B)
        dut.io.tile_h.expect(i.U, s"tile_h after $i increments")
      }
    }
  }

  "SREG" should "reset tile_h and tile_w to 0 on tile_rst" in {
    simulate(new SpecialRegFile) { dut =>
      zeroCtrl(dut)
      // advance counters
      dut.io.tile_w_inc.poke(true.B)
      dut.clock.step(); dut.clock.step(); dut.clock.step()
      dut.io.tile_w_inc.poke(false.B)
      dut.io.tile_h_inc.poke(true.B)
      dut.clock.step()
      dut.io.tile_h_inc.poke(false.B)
      // assert tile_h=1, tile_w=3
      dut.io.tile_h.expect(1.U); dut.io.tile_w.expect(3.U)

      // reset
      dut.io.tile_rst.poke(true.B)
      dut.clock.step()
      dut.io.tile_rst.poke(false.B)
      dut.io.tile_h.expect(0.U); dut.io.tile_w.expect(0.U)
    }
  }

  "SREG" should "ignore tile_w_inc during tile_rst" in {
    simulate(new SpecialRegFile) { dut =>
      zeroCtrl(dut)
      dut.clock.step()
      // simultaneous rst and inc: rst wins
      dut.io.tile_w_inc.poke(true.B)
      dut.io.tile_rst.poke(true.B)
      dut.clock.step()
      dut.io.tile_w_inc.poke(false.B)
      dut.io.tile_rst.poke(false.B)
      dut.io.tile_w.expect(0.U, "rst beats inc")
    }
  }
}
