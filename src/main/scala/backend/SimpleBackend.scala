// See README.md for license details.
// -----------------------------------------------------------------------------
//  SimpleBackend.scala — NPU backend: InstrDecoder + MultiWidthRF + MMALU + VALU
//
//  Parameters:
//    K    — SIMD lane count per register = MMALU array side (default 8).
//           At the top level, K = 64 to match MMALU(new MMPE(), 64, 8, 32).
//    N    — base lane width in bits / N(bits) (default 8).
//    L    — number of VX base registers (default 32, must be div-by-4).
//
//  MMALU mapping: MMALU.n (array side) == K; MMALU.nbits == N.
//  This is the backend boundary alignment constraint. Both must be equal.
//
//  Register file (MultiWidthRegisterBlock):
//    VX[0..L-1]     K × N bits     INT8 / BF8 results, MMALU narrow inputs
//    VE[0..L/2-1]   K × 2N bits    INT16 / BF16 results
//    VR[0..L/4-1]   K × 4N bits    INT32 / FP32 results; MMALU accumulator direct write
//
//  Port assignments:
//    VX read:  port 0 = MMALU in_a; port 1 = VALU in_a_vx
//    VX read:  port 2 = VALU in_b_vx; port 3 = external read
//    VE read:  port 0 = VALU in_a_ve; port 1 = VALU in_b_ve
//    VR read:  port 0 = VALU in_a_vr + MMALU in_b; port 1 = VALU in_b_vr + in_c_vr
//    VX write: port 0 = VALU narrow out; port 1 = external write
//    VE write: port 0 = VALU VE out
//    VR write: port 0 = VALU VR out; port 1 = MMALU accumulator direct (INT32, no truncation)
//
//  Instruction decode: 32-bit word → InstrDecoder → DecodedMicroOp.
//  All dispatch is based on the decoded family + VecOp.
//
//  Write-back timing: VALU has a 1-cycle output register.
//  The backend keeps the decoded op active for 2 cycles (issue + write-back).
//  A production frontend should use a 1-cycle pipeline stall or forwarding.
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

// Width constants matching VecWidth enum values (0=VX, 1=VE, 2=VR)
// Used to compare dec.valu.regCls (UInt(2.W)) without importing ChiselEnum
private object W { val VX = 0.U(2.W); val VE = 1.U(2.W); val VR = 2.U(2.W) }

class NCoreBackend(
    val K: Int = 8,
    val N: Int = 8,
    val L: Int = 32,
) extends Module {

  require(L % 4 == 0, s"NCoreBackend: L=$L must be divisible by 4")
  require(K > 0 && N > 0)

  val N2 = 2 * N
  val N4 = 4 * N

  val VX_ADDR = log2Ceil(L)
  val VE_ADDR = log2Ceil(L / 2)
  val VR_ADDR = log2Ceil(L / 4)

  val io = IO(new Bundle {
    // Raw 32-bit instruction word (from frontend or test harness)
    val instr       = Input(UInt(32.W))
    val illegal_out = Output(Bool())  // illegal instruction flag

    // ---- VX address ports (test harness / future frontend) ----
    val vx_a_addr   = Input(UInt(VX_ADDR.W))
    val vx_b_addr   = Input(UInt(VX_ADDR.W))
    val vx_out_addr = Input(UInt(VX_ADDR.W))

    // ---- VE address ports ----
    val ve_a_addr   = Input(UInt(VE_ADDR.W))
    val ve_b_addr   = Input(UInt(VE_ADDR.W))
    val ve_out_addr = Input(UInt(VE_ADDR.W))

    // ---- VR address ports (rd=output; rs1=A; rs2=B; rs3=C for FMA) ----
    val vr_a_addr   = Input(UInt(VR_ADDR.W))
    val vr_b_addr   = Input(UInt(VR_ADDR.W))
    val vr_c_addr   = Input(UInt(VR_ADDR.W))
    val vr_out_addr = Input(UInt(VR_ADDR.W))

    // ---- MMALU control passthrough ----
    val mma_a_addr   = Input(UInt(VX_ADDR.W))
    val mma_b_addr   = Input(UInt(VX_ADDR.W))
    val mma_out_addr = Input(UInt(VR_ADDR.W))  // MMALU output goes to VR

    // ---- External RF access (VX width) ----
    val ext_wr_en   = Input(Bool())
    val ext_wr_addr = Input(UInt(VX_ADDR.W))
    val ext_wr_data = Input(Vec(K, UInt(N.W)))
    val ext_rd_addr = Input(UInt(VX_ADDR.W))
    val ext_rd_data = Output(Vec(K, UInt(N.W)))

    // ---- External VR access (for reading INT32 / FP32 results) ----
    val vr_rd_addr  = Input(UInt(VR_ADDR.W))
    val vr_rd_data  = Output(Vec(K, UInt(N4.W)))
  })

  // ==========================================================================
  // Instruction decoder
  // ==========================================================================
  val decoder = Module(new InstrDecoder)
  decoder.io.instr := io.instr
  io.illegal_out   := decoder.io.illegal
  val dec = decoder.io.decoded

  // ==========================================================================
  // Multi-width register file
  // ==========================================================================
  val rf = Module(new MultiWidthRegisterBlock(L, K, N,
    vx_rd = 4, vx_wr = 2, ve_rd = 2, ve_wr = 1, vr_rd = 2, vr_wr = 2))

  // ---- VX reads ----
  rf.io.vx_r_addr(0) := io.mma_a_addr    // MMALU A
  rf.io.vx_r_addr(1) := io.vx_a_addr     // VALU in_a_vx
  rf.io.vx_r_addr(2) := io.vx_b_addr     // VALU in_b_vx
  rf.io.vx_r_addr(3) := io.ext_rd_addr   // external
  io.ext_rd_data      := rf.io.vx_r_data(3)

  // ---- VE reads ----
  rf.io.ve_r_addr(0) := io.ve_a_addr
  rf.io.ve_r_addr(1) := io.ve_b_addr

  // ---- VR reads ----
  rf.io.vr_r_addr(0) := io.vr_a_addr
  rf.io.vr_r_addr(1) := io.vr_b_addr
  io.vr_rd_data       := rf.io.vr_r_data(0)

  // Drive the external read port of the RF (separate from vx_r_addr)
  rf.io.ext_r_addr := io.ext_rd_addr

  // ---- Default: all write ports disabled ----
  rf.io.vx_w_en  := VecInit(Seq.fill(2)(false.B))
  rf.io.ve_w_en  := VecInit(Seq.fill(1)(false.B))
  rf.io.vr_w_en  := VecInit(Seq.fill(2)(false.B))
  rf.io.vx_w_addr := VecInit(Seq.fill(2)(0.U(VX_ADDR.W)))
  rf.io.ve_w_addr := VecInit(Seq.fill(1)(0.U(VE_ADDR.W)))
  rf.io.vr_w_addr := VecInit(Seq.fill(2)(0.U(VR_ADDR.W)))
  for (p <- 0 until 2) for (lane <- 0 until K) rf.io.vx_w_data(p)(lane) := 0.U
  for (p <- 0 until 1) for (lane <- 0 until K) rf.io.ve_w_data(p)(lane) := 0.U
  for (p <- 0 until 2) for (lane <- 0 until K) rf.io.vr_w_data(p)(lane) := 0.U

  // ---- External write ----
  rf.io.ext_w_en   := io.ext_wr_en
  rf.io.ext_w_addr := io.ext_wr_addr
  rf.io.ext_w_data := io.ext_wr_data

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
  // LD (I-type): address = sext(imm), rs1 is always 0 in the assembler encoding.
  val ldRow  = dec.valu.imm.asUInt(VX_ADDR - 1, 0)

  // Tile-mode base: {rs2[4:0], rs1[4:0]} (10-bit), matches ldTile assembler encoding.
  val tileBase = Cat(dec.rs2(4, 0), dec.rs1(4, 0))

  // Tile-mode address: base + tile_h * stride_row_h + tile_w * stride_row_w
  val tileRow = (tileBase.pad(VX_ADDR) +
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
  // ST is R-type: addr = {funct7[6:0], rs1[4:0]} (12-bit), rs2=source register.
  // funct7 is at io.instr[31:25]; rs1 is dec.rs1[4:0].
  val stFunct7 = io.instr(31, 25)
  val stRow    = Cat(stFunct7, dec.rs1(4, 0)).pad(VX_ADDR)

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
  mmalu.io.in_a := VecInit(rf.io.vx_r_data(0).map(_.asSInt))
  // MMALU in_b taken from VX (same layout as in_a, different addr)
  rf.io.vx_r_addr(0) := io.mma_a_addr  // already assigned above; in_b via separate VX port
  // Note: we route MMALU in_b through vx_r_data(0) with mma_b_addr
  // To give MMALU its own B port, we repurpose ext port when no ext read is active.
  // For simplicity in tests, use the same vx_r_data(3) for MMALU B (driven by mma_b_addr).
  rf.io.vx_r_addr(3) := Mux(io.ext_wr_en || io.ext_rd_addr.orR, io.ext_rd_addr, io.mma_b_addr)
  mmalu.io.in_b     := VecInit(rf.io.vx_r_data(3).map(_.asSInt))
  mmalu.io.in_accum := VecInit(Seq.fill(K)(0.S(N4.W)))

  // MMALU control from decoded instruction
  mmalu.io.ctrl.keep      := dec.mma_keep
  mmalu.io.ctrl.use_accum := false.B
  mmalu.io.ctrl.busy      := (dec.family === OpFamily.MMA)

  // MMALU write-back directly to VR (port 1) — NO precision truncation
  // mmalu.io.out is Vec(K, SInt(N4.W)) = Vec(K, SInt(32.W)) for N=8
  when (dec.family === OpFamily.MMA) {
    rf.io.vr_w_en(1)   := true.B
    rf.io.vr_w_addr(1) := io.mma_out_addr
    for (lane <- 0 until K) {
      rf.io.vr_w_data(1)(lane) := mmalu.io.out(lane).asUInt
    }
  }

  // ==========================================================================
  // VALU
  // ==========================================================================
  val valu = Module(new VALU(K, N))

  // Connect inputs from RF
  for (lane <- 0 until K) {
    valu.io.in_a_vx(lane) := rf.io.vx_r_data(1)(lane)
    valu.io.in_b_vx(lane) := rf.io.vx_r_data(2)(lane)
    valu.io.in_a_ve(lane) := rf.io.ve_r_data(0)(lane)
    valu.io.in_b_ve(lane) := rf.io.ve_r_data(1)(lane)
    valu.io.in_a_vr(lane) := rf.io.vr_r_data(0)(lane)
    valu.io.in_b_vr(lane) := rf.io.vr_r_data(1)(lane)
    valu.io.in_c_vr(lane) := rf.io.vr_r_data(1)(lane)  // C re-uses B port for now
  }

  // Drive VALU ctrl from decoded bundle
  valu.io.ctrl := dec.valu

  // Determine if this is a VALU family
  val isVALU = dec.family === OpFamily.VALU_ARITH  ||
               dec.family === OpFamily.VALU_LOGIC   ||
               dec.family === OpFamily.VALU_REDUCE  ||
               dec.family === OpFamily.VALU_LUT     ||
               dec.family === OpFamily.VALU_CVT     ||
               dec.family === OpFamily.VALU_BCAST   ||
               dec.family === OpFamily.VALU_FP      ||
               dec.family === OpFamily.VALU_FP_FMA  ||
               dec.family === OpFamily.VALU_MOV

  // VALU write-back (port 0 for each class)
  when (isVALU) {
    // VX write-back.
    // isReduceToVR ops (vsum, vrmax, vrmin) encode their *input* class in regCls
    // (e.g. vsum.vx has regCls=VX so the VALU selects the VX reduction path).
    // That would accidentally assert vx_w_en and clobber vx_out_addr — suppress it.
    // isSetLut ops (vsetlut) write only to VALU-internal bank registers;
    // all RF write ports must be suppressed.
    rf.io.vx_w_en(0)   := ((dec.valu.regCls === W.VX) || isNarrowCvtOut(dec.valu.op)) &&
                           !isReduceToVR(dec.valu.op) &&
                           !isSetLut(dec.valu.op)
    rf.io.vx_w_addr(0) := io.vx_out_addr
    for (lane <- 0 until K) rf.io.vx_w_data(0)(lane) := valu.io.out_vx(lane)

    // VE write-back
    rf.io.ve_w_en(0)   := dec.valu.regCls === W.VE
    rf.io.ve_w_addr(0) := io.ve_out_addr
    for (lane <- 0 until K) rf.io.ve_w_data(0)(lane) := valu.io.out_ve(lane)

    // VR write-back (FP/INT32, wide conversion results, and horizontal reductions).
    // vsetlut has regCls=VR but must NOT write the RF — suppress it.
    rf.io.vr_w_en(0)   := ((dec.valu.regCls === W.VR) || isWideCvtOut(dec.valu.op) ||
                            isReduceToVR(dec.valu.op)) &&
                           !isSetLut(dec.valu.op)
    rf.io.vr_w_addr(0) := io.vr_out_addr
    for (lane <- 0 until K) rf.io.vr_w_data(0)(lane) := valu.io.out_vr(lane)
  }

  // ==========================================================================
  // Helpers: determine output register class for conversion and reduce ops
  // ==========================================================================
  def isNarrowCvtOut(op: VecOp.Type): Bool = {
    op === VecOp.vcvt_s8_s32 ||  // s8 sign-extend → VX slice
    op === VecOp.vcvt_f32_s8     // FP32 → INT8 narrow
    // vcvt_s8_f32 = INT8→FP32: wide output → VR, NOT narrow
  }

  def isWideCvtOut(op: VecOp.Type): Bool = {
    op === VecOp.vcvt_s32_f32  ||  // s32→f32: wide
    op === VecOp.vcvt_s8_f32   ||  // s8→f32: wide
    op === VecOp.vcvt_f32_s32  ||
    op === VecOp.vcvt_s32_s8   ||
    op === VecOp.vcvt_f32_bf8  ||
    op === VecOp.vcvt_bf8_f32  ||
    op === VecOp.vcvt_f32_bf16 ||
    op === VecOp.vcvt_bf16_f32 ||
    op === VecOp.vcvt_s16_s32  ||
    op === VecOp.vcvt_s32_s16
  }

  /**
   * vsetlut writes only to VALU-internal LUT bank registers.
   * All register-file write ports must be suppressed for this op.
   */
  def isSetLut(op: VecOp.Type): Bool = op === VecOp.vsetlut

  /**
   * Horizontal reduce ops (vsum, vrmax, vrmin) always produce a VR-width result
   * broadcast across all K lanes, regardless of the regCls field in the instruction.
   * The VALU unconditionally drives out_vr for these ops; the backend must enable
   * the VR write port to store the result in the register file.
   *
   * Root cause: the ISA encodes reduce ops with the *input* register class in
   * funct7[1:0] (e.g. vsum.vx uses regCls=VX to select the VX reduction path),
   * but the output is always VR-width.  The backend's regCls===VR guard therefore
   * misses these ops when issued on VX or VE inputs.
   */
  def isReduceToVR(op: VecOp.Type): Bool = {
    op === VecOp.vsum  ||
    op === VecOp.vrmax ||
    op === VecOp.vrmin
  }
}
