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
  // MMALU (systolic array; n = K lanes, nbits = N)
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
    // VX write-back
    rf.io.vx_w_en(0)   := (dec.valu.regCls === W.VX) || isNarrowCvtOut(dec.valu.op)
    rf.io.vx_w_addr(0) := io.vx_out_addr
    for (lane <- 0 until K) rf.io.vx_w_data(0)(lane) := valu.io.out_vx(lane)

    // VE write-back
    rf.io.ve_w_en(0)   := dec.valu.regCls === W.VE
    rf.io.ve_w_addr(0) := io.ve_out_addr
    for (lane <- 0 until K) rf.io.ve_w_data(0)(lane) := valu.io.out_ve(lane)

    // VR write-back (FP/INT32 and wide conversion results)
    rf.io.vr_w_en(0)   := (dec.valu.regCls === W.VR) || isWideCvtOut(dec.valu.op)
    rf.io.vr_w_addr(0) := io.vr_out_addr
    for (lane <- 0 until K) rf.io.vr_w_data(0)(lane) := valu.io.out_vr(lane)
  }

  // ==========================================================================
  // Helpers: determine output register class for conversion ops
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
}
