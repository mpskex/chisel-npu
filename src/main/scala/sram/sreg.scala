// See README.md for license details.
// -----------------------------------------------------------------------------
//  sreg.scala — Special Register File (SREG)
//
//  Holds convolution configuration written by `tile.cfg` instructions and
//  exposes the current tile position (`tile_h`, `tile_w`) to the PAG.
//
//  Register map (written via wr_sel + wr_data):
//
//    sel 0  (TILE_CFG_HW)   wr_data[15:0]  = H_in
//                           wr_data[31:16] = W_in
//
//    sel 1  (TILE_CFG_CH)   wr_data[15:0]  = C_in
//                           wr_data[31:16] = C_out
//
//    sel 2  (TILE_CFG_KERN) wr_data[3:0]   = Kh
//                           wr_data[7:4]   = Kw
//                           wr_data[11:8]  = stride (1-based, subtract 1 from HW value)
//                           wr_data[15:12] = dilation
//                           wr_data[19:16] = pad_h
//                           wr_data[23:20] = pad_w
//                           wr_data[25:24] = mode (00=conv2d, 01=depthwise,
//                                                   10=transposed, 11=reserved)
//
//    sel 3  (TILE_CFG_POS)  wr_data[15:0]  = tile_h (set; use to reset to 0)
//                           wr_data[31:16] = tile_w
//
//  Tile counters auto-increment via `tile_w_inc` / `tile_h_inc` pulses emitted
//  by the PAG after each completed `ld.tile`.
//
//  The `tile_rst` input resets both counters to zero (start of new layer).
// -----------------------------------------------------------------------------

package sram.sreg

import chisel3._
import chisel3.util._

// ---------------------------------------------------------------------------
// ConvParams bundle — static per-layer convolution shape
// ---------------------------------------------------------------------------
class ConvParams extends Bundle {
  val H_in     = UInt(16.W)
  val W_in     = UInt(16.W)
  val C_in     = UInt(16.W)
  val C_out    = UInt(16.W)
  val Kh       = UInt(4.W)
  val Kw       = UInt(4.W)
  // stride stored as (actual_stride - 1) to save one bit; 0 = stride 1
  val stride   = UInt(4.W)
  val dilation = UInt(4.W)   // 0 = dilation 1 (no dilation)
  val pad_h    = UInt(4.W)
  val pad_w    = UInt(4.W)
  val mode     = UInt(2.W)   // see sel 2 comment above
}

object SRegSel {
  val TILE_CFG_HW   = 0.U(3.W)
  val TILE_CFG_CH   = 1.U(3.W)
  val TILE_CFG_KERN = 2.U(3.W)
  val TILE_CFG_POS  = 3.U(3.W)
}

// ---------------------------------------------------------------------------
// SpecialRegFile module
// ---------------------------------------------------------------------------
class SpecialRegFile extends Module {
  val io = IO(new Bundle {

    // ---- Write port (from tile.cfg instruction decoder) ----
    val wr_en   = Input(Bool())
    val wr_sel  = Input(UInt(3.W))   // SRegSel constants
    val wr_data = Input(UInt(32.W))

    // ---- Read outputs (to PAG and frontend) ----
    val tile_h  = Output(UInt(16.W))
    val tile_w  = Output(UInt(16.W))
    val conv    = Output(new ConvParams)

    // ---- Tile counter control (from PAG after ld.tile completion) ----
    val tile_w_inc = Input(Bool())   // increment tile_w
    val tile_h_inc = Input(Bool())   // increment tile_h
    val tile_rst   = Input(Bool())   // reset both to 0
  })

  // ---- Registers ----
  val reg_tile_h = RegInit(0.U(16.W))
  val reg_tile_w = RegInit(0.U(16.W))
  val reg_conv   = RegInit(0.U.asTypeOf(new ConvParams))

  // ---- Output assignments ----
  io.tile_h := reg_tile_h
  io.tile_w := reg_tile_w
  io.conv   := reg_conv

  // ---- Tile counter updates (priority: rst > cfg write > auto-increment) ----
  when (io.tile_rst) {
    reg_tile_h := 0.U
    reg_tile_w := 0.U
  } .elsewhen (io.wr_en && io.wr_sel === SRegSel.TILE_CFG_POS) {
    reg_tile_h := io.wr_data(15, 0)
    reg_tile_w := io.wr_data(31, 16)
  } .otherwise {
    when (io.tile_h_inc) { reg_tile_h := reg_tile_h + 1.U }
    when (io.tile_w_inc) { reg_tile_w := reg_tile_w + 1.U }
  }

  // ---- Conv param writes ----
  when (io.wr_en) {
    switch (io.wr_sel) {
      is (SRegSel.TILE_CFG_HW) {
        reg_conv.H_in := io.wr_data(15, 0)
        reg_conv.W_in := io.wr_data(31, 16)
      }
      is (SRegSel.TILE_CFG_CH) {
        reg_conv.C_in  := io.wr_data(15, 0)
        reg_conv.C_out := io.wr_data(31, 16)
      }
      is (SRegSel.TILE_CFG_KERN) {
        reg_conv.Kh       := io.wr_data(3,  0)
        reg_conv.Kw       := io.wr_data(7,  4)
        reg_conv.stride   := io.wr_data(11, 8)
        reg_conv.dilation := io.wr_data(15, 12)
        reg_conv.pad_h    := io.wr_data(19, 16)
        reg_conv.pad_w    := io.wr_data(23, 20)
        reg_conv.mode     := io.wr_data(25, 24)
      }
      // TILE_CFG_POS handled above in counter logic
    }
  }
}
