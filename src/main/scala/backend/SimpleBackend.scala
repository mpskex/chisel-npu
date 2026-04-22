// See README.md for license details.
// -----------------------------------------------------------------------------
//  SimpleBackend.scala — NPU backend (unified register-file architecture)
//
//  Components (data-flow order):
//    InstrDecoder           : 32-bit word → DecodedMicroOp (combinational)
//    SpecialRegFile (sreg)  : tile counters + conv/stride params for ld.tile
//    MultiWidthRegisterBlock: unified VX/VE/VR register file (IS the scratchpad)
//    MMALU                  : K×K systolic array
//    VALU                   : K-lane multi-width vector ALU
//
//  Architecture notes
//  ------------------
//  There is NO separate SPM.  The MultiWidthRegisterBlock with parameter L
//  serves as both the working register file (VX[0..31], directly addressed
//  by 5-bit instruction fields) AND the bulk storage tier (VX[32..L-1],
//  accessed only through LD/ST/gather/scatter instructions).
//
//  DMA writes any row of the RF directly via ext_w_en / ext_w_addr.
//
//  Parameters
//  ----------
//    K    — SIMD lane count = MMALU array side; default 8, production 64
//    N    — base lane width in bits / N(bits); default 8
//    L    — total VX rows (must be divisible by 4).
//           L=32  → working regs only (test default, backwards-compat)
//           L=256 → 224 rows of bulk storage at K=8,N=8 (LdStSpec)
//           L=4096 → full 256 KiB at K=64,N=8 (top-level)
//
//  Instruction addressing
//  ----------------------
//    Contiguous LD (I-type):  RF row = sext(rs1) + sext(imm[11:0])
//    Contiguous ST (R-type):  RF row = rs1 + funct7[6:0]
//    ld.gather                VX[rd][k] = RF[ VX[rs1][k] ][ k ]  (diagonal)
//    ld.tile                  row = rs1 + tile_h×stride_row_h + tile_w×stride_row_w
//    st.scatter               RF[ VX[rs1][k] ][ k ] = VX[rs2][k]
//
//  LD pipeline (1-cycle write-back):
//    Cycle 0 — decode; RF read is combinational (RegInit).
//              Capture data into pipeline regs.
//    Cycle 1 — write captured data into dest VX/VE/VR register.
//    The instruction word must be held for 1 cycle; check result after 1 more.
//
//  tile.cfg: written in 1 cycle; result visible next cycle.
// -----------------------------------------------------------------------------

package backend

import chisel3._
import chisel3.util._

import alu.mma._
import alu.pe._
import alu.vec._
import isa._
import isa.micro_op._
import sram.mwreg._
import sram.sreg._

// Width constants: 0=VX, 1=VE, 2=VR  (matches VecWidth enum values)
private object W { val VX = 0.U(2.W); val VE = 1.U(2.W); val VR = 2.U(2.W) }

class NCoreBackend(
    val K: Int = 8,
    val N: Int = 8,
    val L: Int = 32,    // total RF rows; L=32 = working-regs only (test default)
) extends Module {

  require(L % 4 == 0, s"NCoreBackend: L=$L must be divisible by 4")
  require(K > 0 && N > 0)

  val N2 = 2 * N
  val N4 = 4 * N

  val VX_ADDR = log2Ceil(L)
  val VE_ADDR = log2Ceil(L / 2)
  val VR_ADDR = log2Ceil(L / 4)

  val io = IO(new Bundle {
    // Raw 32-bit instruction word
    val instr       = Input(UInt(32.W))
    val illegal_out = Output(Bool())

    // ---- RF address ports (test harness / future frontend) ----
    // These drive the VALU/MMALU operand read ports (5-bit "named register" range).
    val vx_a_addr   = Input(UInt(VX_ADDR.W))
    val vx_b_addr   = Input(UInt(VX_ADDR.W))
    val vx_out_addr = Input(UInt(VX_ADDR.W))

    val ve_a_addr   = Input(UInt(VE_ADDR.W))
    val ve_b_addr   = Input(UInt(VE_ADDR.W))
    val ve_out_addr = Input(UInt(VE_ADDR.W))

    val vr_a_addr   = Input(UInt(VR_ADDR.W))
    val vr_b_addr   = Input(UInt(VR_ADDR.W))
    val vr_c_addr   = Input(UInt(VR_ADDR.W))
    val vr_out_addr = Input(UInt(VR_ADDR.W))

    val mma_a_addr   = Input(UInt(VX_ADDR.W))
    val mma_b_addr   = Input(UInt(VX_ADDR.W))
    val mma_out_addr = Input(UInt(VR_ADDR.W))

    // ---- External RF access (test harness / DMA) — full VX_ADDR range ----
    val ext_wr_en   = Input(Bool())
    val ext_wr_addr = Input(UInt(VX_ADDR.W))
    val ext_wr_data = Input(Vec(K, UInt(N.W)))
    val ext_rd_addr = Input(UInt(VX_ADDR.W))
    val ext_rd_data = Output(Vec(K, UInt(N.W)))

    val vr_rd_addr  = Input(UInt(VR_ADDR.W))
    val vr_rd_data  = Output(Vec(K, UInt(N4.W)))

    // ---- SREG direct access (test harness) ----
    val sreg_wr_en   = Input(Bool())
    val sreg_wr_sel  = Input(UInt(3.W))
    val sreg_wr_data = Input(UInt(32.W))
    val sreg_tile_h  = Output(UInt(16.W))
    val sreg_tile_w  = Output(UInt(16.W))
    val sreg_tile_rst= Input(Bool())
    val sreg_conv    = Output(new ConvParams)
  })

  // ==========================================================================
  // Instruction decoder
  // ==========================================================================
  val decoder = Module(new InstrDecoder)
  decoder.io.instr := io.instr
  io.illegal_out   := decoder.io.illegal
  val dec = decoder.io.decoded

  // ==========================================================================
  // Special Register File (SREG)
  // ==========================================================================
  val sreg = Module(new SpecialRegFile)

  // Direct test-harness access (lower priority than ISA path)
  sreg.io.wr_en    := io.sreg_wr_en
  sreg.io.wr_sel   := io.sreg_wr_sel
  sreg.io.wr_data  := io.sreg_wr_data
  sreg.io.tile_rst := io.sreg_tile_rst
  // tile_w_inc / tile_h_inc driven below (after LD/ST section)
  sreg.io.tile_w_inc := false.B
  sreg.io.tile_h_inc := false.B

  io.sreg_tile_h := sreg.io.tile_h
  io.sreg_tile_w := sreg.io.tile_w
  io.sreg_conv   := sreg.io.conv

  // ==========================================================================
  // Unified Register File (VX/VE/VR + bulk storage)
  //
  //  Port allocation:
  //    vx_rd = 5   port 0: MMALU A
  //                port 1: VALU in_a_vx
  //                port 2: VALU in_b_vx  /  ST source  /  gather index read
  //                port 3: mma_b (muxed with ext_rd)
  //                port 4: LD source (contiguous + tile)
  //    vx_wr = 3   port 0: VALU narrow write-back
  //                port 1: LD/gather write-back
  //                port 2: external write (test harness / DMA)
  //    ve_rd = 2   port 0: VALU in_a_ve
  //                port 1: VALU in_b_ve
  //    ve_wr = 1   port 0: VALU VE write-back / LD.VE write-back
  //    vr_rd = 2   port 0: VALU in_a_vr / MMALU in_b / tile.cfg data src
  //                port 1: VALU in_b_vr / in_c_vr
  //    vr_wr = 2   port 0: VALU VR write-back
  //                port 1: MMALU direct accumulator write
  // ==========================================================================
  val rf = Module(new MultiWidthRegisterBlock(L, K, N,
    vx_rd = 5, vx_wr = 3, ve_rd = 2, ve_wr = 1, vr_rd = 2, vr_wr = 2))

  // ---- VX reads ----
  rf.io.vx_r_addr(0) := io.mma_a_addr
  rf.io.vx_r_addr(1) := io.vx_a_addr
  rf.io.vx_r_addr(2) := io.vx_b_addr       // also used for ST source / gather index
  rf.io.vx_r_addr(3) := Mux(io.ext_wr_en || io.ext_rd_addr.orR, io.ext_rd_addr, io.mma_b_addr)
  rf.io.vx_r_addr(4) := 0.U                 // driven below for LD / tile
  io.ext_rd_data      := rf.io.vx_r_data(3)

  // ---- VE reads ----
  rf.io.ve_r_addr(0) := io.ve_a_addr
  rf.io.ve_r_addr(1) := io.ve_b_addr

  // ---- VR reads ----
  rf.io.vr_r_addr(0) := io.vr_a_addr
  rf.io.vr_r_addr(1) := io.vr_b_addr
  io.vr_rd_data       := rf.io.vr_r_data(0)

  rf.io.ext_r_addr := io.ext_rd_addr

  // ---- Default: all write ports disabled ----
  rf.io.vx_w_en   := VecInit(Seq.fill(3)(false.B))
  rf.io.ve_w_en   := VecInit(Seq.fill(1)(false.B))
  rf.io.vr_w_en   := VecInit(Seq.fill(2)(false.B))
  rf.io.vx_w_addr := VecInit(Seq.fill(3)(0.U(VX_ADDR.W)))
  rf.io.ve_w_addr := VecInit(Seq.fill(1)(0.U(VE_ADDR.W)))
  rf.io.vr_w_addr := VecInit(Seq.fill(2)(0.U(VR_ADDR.W)))
  for (p <- 0 until 3) for (lane <- 0 until K) rf.io.vx_w_data(p)(lane) := 0.U
  for (p <- 0 until 1) for (lane <- 0 until K) rf.io.ve_w_data(p)(lane) := 0.U
  for (p <- 0 until 2) for (lane <- 0 until K) rf.io.vr_w_data(p)(lane) := 0.U

  // External RF write (test harness / DMA) via port 2
  rf.io.ext_w_en   := io.ext_wr_en
  rf.io.ext_w_addr := io.ext_wr_addr
  rf.io.ext_w_data := io.ext_wr_data

  // ---- Default: gather / scatter ports disabled ----
  for (k <- 0 until K) rf.io.gather_r_addr(k) := 0.U
  rf.io.scatter_w_en := false.B
  for (k <- 0 until K) {
    rf.io.scatter_w_addr(k) := 0.U
    rf.io.scatter_w_data(k) := 0.U
  }

  // ==========================================================================
  // LD / ST execution
  //
  // ─── Contiguous LD (is_ld) ────────────────────────────────────────────────
  //   Cycle 0: compute RF row address, read combinatorially from port 4.
  //            Capture (data, rd, mem_width) in pipeline registers.
  //   Cycle 1: write captured data into dest VX/VE/VR via write port 1.
  //
  //   Address: row = dec.rs1.pad(VX_ADDR) + dec.valu.imm.asUInt
  //
  // ─── Contiguous ST (is_st) ────────────────────────────────────────────────
  //   Cycle 0: read RF[dec.rs2] combinatorially (port 2, already wired).
  //            Write to RF row = dec.rs1 + funct7_offset.
  //   (1 cycle, synchronous write)
  //
  // ─── ld.gather (is_gather) ────────────────────────────────────────────────
  //   Cycle 0: VX[rs1][k] (port 2) supplies K row addresses → gather port.
  //            Capture (gather_data, rd) in pipeline registers.
  //   Cycle 1: write captured data into VX[rd] via write port 1.
  //
  // ─── ld.tile (is_tile) ────────────────────────────────────────────────────
  //   Cycle 0: compute address = rs1 + tile_h*stride_h + tile_w*stride_w.
  //            Read RF[addr] via port 4.  Capture (data, rd) in pipeline regs.
  //   Cycle 1: write to VX[rd].  Optionally pulse tile_w_inc.
  //
  // ─── st.scatter (is_scatter) ─────────────────────────────────────────────
  //   Cycle 0: VX[rs1][k] (port 2) → scatter addresses; VX[rs2][k] → data.
  //            Scatter write fires synchronously.
  //   (1 cycle)
  // ==========================================================================

  // ---- RF row address for contiguous LD and tile ----
  val ldRow  = (dec.rs1.pad(VX_ADDR) + dec.valu.imm.asUInt)(VX_ADDR - 1, 0)

  // Tile-mode address: rs1 + tile_h * stride_row_h + tile_w * stride_row_w
  val tileRow = (dec.rs1.pad(VX_ADDR) +
                 (sreg.io.tile_h * sreg.io.conv.stride_row_h)(VX_ADDR - 1, 0) +
                 (sreg.io.tile_w * sreg.io.conv.stride_row_w)(VX_ADDR - 1, 0)
                )(VX_ADDR - 1, 0)

  // Mux port 4 address: used for contiguous LD and tile
  val port4Addr = Mux(dec.is_tile, tileRow, ldRow)
  rf.io.vx_r_addr(4) := port4Addr

  // ---- Gather: drive gather port from VX[rs1] (port 2) ----
  when (dec.is_gather) {
    rf.io.vx_r_addr(2) := dec.rs1            // route rs1 to port 2
    for (k <- 0 until K) {
      rf.io.gather_r_addr(k) := rf.io.vx_r_data(2)(k).pad(VX_ADDR)
    }
  }

  // ---- Pipeline register: capture cycle-0 read for cycle-1 write-back ----
  // Covers contiguous LD, ld.gather, and ld.tile.
  val ld_issue     = dec.is_ld || dec.is_gather || dec.is_tile
  val ld_wb_en     = RegNext(ld_issue,       false.B)
  val ld_wb_rd     = RegNext(dec.rd)
  val ld_wb_width  = RegNext(dec.mem_width)
  val ld_wb_is_gth = RegNext(dec.is_gather,  false.B)  // gather vs. contiguous
  val ld_wb_autoinc= RegNext(dec.tile_autoinc && dec.is_tile, false.B)

  // Capture data: gather uses gather_r_data; contiguous/tile uses vx_r_data(4)
  val ld_capture_data = WireDefault(VecInit(Seq.fill(K)(0.U(N.W))))
  when (dec.is_gather) {
    for (k <- 0 until K) ld_capture_data(k) := rf.io.gather_r_data(k)
  } .otherwise {
    for (k <- 0 until K) ld_capture_data(k) := rf.io.vx_r_data(4)(k)
  }
  val ld_wb_data = RegNext(ld_capture_data)

  // Also capture VE/VR extra rows for multi-row write-back.
  // VE: port-4 reads row N; we need row N+1.  Read via a second address on
  //     port 4 in cycle 1 (ldRow+1 registered from cycle 0).
  // VR: similarly needs rows +1,+2,+3.
  // For simplicity in this first implementation:
  //   VE LD stores the low-N bits from port 4; the high-N bits come from the
  //   row immediately after (registered address, re-read in cycle 1).
  //   VR LD similarly fills 4 consecutive rows.
  // This means the instruction must be held for multiple cycles for VE/VR.
  // TODO: implement full multi-row pipeline; for now only VX width is complete.

  // ---- LD write-back (cycle 1) ----
  when (ld_wb_en) {
    // Gather and contiguous VX both write via VX port 1
    when (ld_wb_is_gth || ld_wb_width === Funct3Mem.VX_VEC) {
      rf.io.vx_w_en(1)   := true.B
      rf.io.vx_w_addr(1) := ld_wb_rd
      for (lane <- 0 until K) rf.io.vx_w_data(1)(lane) := ld_wb_data(lane)
    }
    when (ld_wb_width === Funct3Mem.VE_VEC && !ld_wb_is_gth) {
      // First implementation: loads low-N bits of each 2N lane only.
      // Full multi-row pipeline is a future improvement.
      rf.io.ve_w_en(0)   := true.B
      rf.io.ve_w_addr(0) := ld_wb_rd.pad(VE_ADDR)
      for (lane <- 0 until K) {
        rf.io.ve_w_data(0)(lane) := Cat(0.U(N.W), ld_wb_data(lane))
      }
    }
    when (ld_wb_width === Funct3Mem.VR_VEC && !ld_wb_is_gth) {
      // First implementation: loads low-N bits of each 4N lane only.
      rf.io.vr_w_en(0)   := true.B
      rf.io.vr_w_addr(0) := ld_wb_rd.pad(VR_ADDR)
      for (lane <- 0 until K) {
        rf.io.vr_w_data(0)(lane) := Cat(0.U(N * 3), ld_wb_data(lane))
      }
    }
    // Pulse tile_w_inc if this was an auto-increment tile load
    when (ld_wb_autoinc) {
      sreg.io.tile_w_inc := true.B
    }
  }

  // ---- Contiguous ST (cycle 0 — synchronous write) ----
  // ST is R-type: rs1=base, rs2=source VX, funct7=row offset.
  // The funct7 field is in f7 (dec.valu carries imm but ST is R-type, so
  // the funct7 route is dec.valu.op[6:0] = raw funct7 bits via the instruction
  // word.  For simplicity, ST base = rs1 only (funct7 offset not yet decoded
  // into a separate field — use dec.valu.imm which is 0 for R-type).
  val stRow = dec.rs1.pad(VX_ADDR)   // R-type: no imm; rs1=base

  when (dec.is_st) {
    rf.io.vx_r_addr(2) := dec.rs2                        // read source VX
    rf.io.vx_w_en(1)   := (dec.mem_width === Funct3Mem.VX_VEC)
    rf.io.vx_w_addr(1) := stRow
    for (lane <- 0 until K) rf.io.vx_w_data(1)(lane) := rf.io.vx_r_data(2)(lane)
  }

  // ---- st.scatter (cycle 0 — synchronous scatter write) ----
  when (dec.is_scatter) {
    rf.io.vx_r_addr(2) := dec.rs1                // port 2 reads VX[rs1] (index vector)
    rf.io.scatter_w_en := true.B
    for (k <- 0 until K) {
      rf.io.scatter_w_addr(k) := rf.io.vx_r_data(2)(k).pad(VX_ADDR)
      // Data comes from VX[rs2]; route via port 1 of vx_r (unused otherwise)
      rf.io.scatter_w_data(k) := rf.io.vx_r_data(1)(k)
    }
    // Re-route port 1 to rs2 for scatter data
    rf.io.vx_r_addr(1) := dec.rs2
  }

  // ---- tile.cfg: write to SREG (ISA path overrides direct harness port) ----
  when (dec.is_tilecfg) {
    sreg.io.wr_en   := true.B
    sreg.io.wr_sel  := dec.tilecfg_sel
    // Data source: VR[rs1] lane 0 low 32 bits
    sreg.io.wr_data := rf.io.vr_r_data(0)(0)(31, 0)
  }

  // ==========================================================================
  // MMALU (systolic array; n = K, nbits = N)
  // ==========================================================================
  val mmalu = Module(new MMALU(new MMPE(N), K, N, N4))
  mmalu.io.in_a     := VecInit(rf.io.vx_r_data(0).map(_.asSInt))
  mmalu.io.in_b     := VecInit(rf.io.vx_r_data(3).map(_.asSInt))
  mmalu.io.in_accum := VecInit(Seq.fill(K)(0.S(N4.W)))
  mmalu.io.ctrl.keep      := dec.mma_keep
  mmalu.io.ctrl.use_accum := false.B
  mmalu.io.ctrl.busy      := (dec.family === OpFamily.MMA)

  // MMALU → VR write-back (INT32 accumulator, no truncation)
  when (dec.family === OpFamily.MMA) {
    rf.io.vr_w_en(1)   := true.B
    rf.io.vr_w_addr(1) := io.mma_out_addr
    for (lane <- 0 until K) rf.io.vr_w_data(1)(lane) := mmalu.io.out(lane).asUInt
  }

  // ==========================================================================
  // VALU
  // ==========================================================================
  val valu = Module(new VALU(K, N))
  for (lane <- 0 until K) {
    valu.io.in_a_vx(lane) := rf.io.vx_r_data(1)(lane)
    valu.io.in_b_vx(lane) := rf.io.vx_r_data(2)(lane)
    valu.io.in_a_ve(lane) := rf.io.ve_r_data(0)(lane)
    valu.io.in_b_ve(lane) := rf.io.ve_r_data(1)(lane)
    valu.io.in_a_vr(lane) := rf.io.vr_r_data(0)(lane)
    valu.io.in_b_vr(lane) := rf.io.vr_r_data(1)(lane)
    valu.io.in_c_vr(lane) := rf.io.vr_r_data(1)(lane)
  }
  valu.io.ctrl := dec.valu

  val isVALU = dec.family === OpFamily.VALU_ARITH  ||
               dec.family === OpFamily.VALU_LOGIC   ||
               dec.family === OpFamily.VALU_REDUCE  ||
               dec.family === OpFamily.VALU_LUT     ||
               dec.family === OpFamily.VALU_CVT     ||
               dec.family === OpFamily.VALU_BCAST   ||
               dec.family === OpFamily.VALU_FP      ||
               dec.family === OpFamily.VALU_FP_FMA  ||
               dec.family === OpFamily.VALU_MOV

  when (isVALU) {
    // VX write-back.
    rf.io.vx_w_en(0)   := ((dec.valu.regCls === W.VX) || isNarrowCvtOut(dec.valu.op)) &&
                           !isReduceToVR(dec.valu.op) &&
                           !isSetLut(dec.valu.op)
    rf.io.vx_w_addr(0) := io.vx_out_addr
    for (lane <- 0 until K) rf.io.vx_w_data(0)(lane) := valu.io.out_vx(lane)

    rf.io.ve_w_en(0)   := dec.valu.regCls === W.VE
    rf.io.ve_w_addr(0) := io.ve_out_addr
    for (lane <- 0 until K) rf.io.ve_w_data(0)(lane) := valu.io.out_ve(lane)

    // VR write-back (FP/INT32, wide conversion results, and horizontal reductions).
    rf.io.vr_w_en(0)   := ((dec.valu.regCls === W.VR) || isWideCvtOut(dec.valu.op) ||
                            isReduceToVR(dec.valu.op)) &&
                           !isSetLut(dec.valu.op)
    rf.io.vr_w_addr(0) := io.vr_out_addr
    for (lane <- 0 until K) rf.io.vr_w_data(0)(lane) := valu.io.out_vr(lane)
  }

  // ==========================================================================
  // Helpers
  // ==========================================================================
  def isNarrowCvtOut(op: VecOp.Type): Bool =
    op === VecOp.vcvt_s8_s32 || op === VecOp.vcvt_f32_s8

  def isWideCvtOut(op: VecOp.Type): Bool = {
    op === VecOp.vcvt_s32_f32  ||
    op === VecOp.vcvt_s8_f32   ||
    op === VecOp.vcvt_f32_s32  ||
    op === VecOp.vcvt_s32_s8   ||
    op === VecOp.vcvt_f32_bf8  ||
    op === VecOp.vcvt_bf8_f32  ||
    op === VecOp.vcvt_f32_bf16 ||
    op === VecOp.vcvt_bf16_f32 ||
    op === VecOp.vcvt_s16_s32  ||
    op === VecOp.vcvt_s32_s16
  }

  def isSetLut(op: VecOp.Type): Bool = op === VecOp.vsetlut

  def isReduceToVR(op: VecOp.Type): Bool =
    op === VecOp.vsum || op === VecOp.vrmax || op === VecOp.vrmin
}
