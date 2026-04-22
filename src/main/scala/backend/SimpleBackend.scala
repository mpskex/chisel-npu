// See README.md for license details.
// -----------------------------------------------------------------------------
//  SimpleBackend.scala — NPU backend
//
//  Components (in order of data flow):
//    InstrDecoder      : 32-bit word → DecodedMicroOp (combinational)
//    SpecialRegFile    : .sreg — tile counters + conv params for ld.tile
//    SPM               : scratch-pad memory — source/destination for LD/ST
//    MultiWidthRegisterBlock : VX/VE/VR register file
//    MMALU             : K×K systolic array (n=K, nbits=N, accum=4N)
//    VALU              : K-lane multi-width vector ALU
//
//  Parameters:
//    K        — SIMD lane count = MMALU array side; default 8, top 64
//    N        — base lane width in bits / N(bits); default 8
//    L        — number of VX registers (div-by-4); default 32
//    SPM_ROWS — SPM capacity in VX rows; default 4096 (32 KiB at K=8,N=8)
//
//  LD/ST addressing (first implementation):
//    Row address = rs1_field + sext(imm).  rs1 is a 5-bit page base (0..31);
//    imm is a 12-bit signed row offset.  For SPM_ROWS ≤ 2048 use rs1=0.
//
//  LD pipeline (2 cycles):
//    Cycle 0: decoder asserts is_ld; SPM read issued.
//    Cycle 1: SPM rd_valid; result written to RF.
//    The instruction must be held for 2 cycles (or the frontend stalls).
//
//  tile.cfg: written in a single cycle; result visible next cycle.
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
import sram.spm._
import sram.sreg._

// Width constants: 0=VX, 1=VE, 2=VR  (matches VecWidth enum values)
private object W { val VX = 0.U(2.W); val VE = 1.U(2.W); val VR = 2.U(2.W) }

class NCoreBackend(
    val K:        Int = 8,
    val N:        Int = 8,
    val L:        Int = 32,
    val SPM_ROWS: Int = 4096,
) extends Module {

  require(L % 4 == 0, s"NCoreBackend: L=$L must be divisible by 4")
  require(K > 0 && N > 0 && SPM_ROWS > 0)

  val N2 = 2 * N
  val N4 = 4 * N

  val VX_ADDR  = log2Ceil(L)
  val VE_ADDR  = log2Ceil(L / 2)
  val VR_ADDR  = log2Ceil(L / 4)
  val SPM_ADDR = log2Ceil(SPM_ROWS)

  val io = IO(new Bundle {
    // Raw 32-bit instruction word
    val instr       = Input(UInt(32.W))
    val illegal_out = Output(Bool())

    // ---- RF address ports (test harness / future frontend) ----
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

    // ---- External RF access (test harness) ----
    val ext_wr_en   = Input(Bool())
    val ext_wr_addr = Input(UInt(VX_ADDR.W))
    val ext_wr_data = Input(Vec(K, UInt(N.W)))
    val ext_rd_addr = Input(UInt(VX_ADDR.W))
    val ext_rd_data = Output(Vec(K, UInt(N.W)))

    val vr_rd_addr  = Input(UInt(VR_ADDR.W))
    val vr_rd_data  = Output(Vec(K, UInt(N4.W)))

    // ---- External SPM access (test harness / DMA loader) ----
    val spm_ext_wr_en   = Input(Bool())
    val spm_ext_wr_addr = Input(UInt(SPM_ADDR.W))
    val spm_ext_wr_data = Input(Vec(K, UInt(N.W)))
    // SPM read-back: 1-cycle latency (rd_valid pulses the cycle after rd_en)
    val spm_ext_rd_addr = Input(UInt(SPM_ADDR.W))
    val spm_ext_rd_en   = Input(Bool())
    val spm_ext_rd_data = Output(Vec(K, UInt(N.W)))
    val spm_ext_rd_valid= Output(Bool())

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
  // Scratch-Pad Memory (SPM)
  // ==========================================================================
  val spm = Module(new SPM(K, N, SPM_ROWS))

  // External (DMA / test-harness) write port
  spm.io.ext_wr_en   := io.spm_ext_wr_en
  spm.io.ext_wr_addr := io.spm_ext_wr_addr
  spm.io.ext_wr_data := io.spm_ext_wr_data

  // External read-back port (for test assertions)
  spm.io.rd_addr := io.spm_ext_rd_addr
  spm.io.rd_en   := io.spm_ext_rd_en
  io.spm_ext_rd_data  := spm.io.rd_data
  io.spm_ext_rd_valid := spm.io.rd_valid

  // ST write port: driven below in the LD/ST section
  spm.io.wr_en   := false.B
  spm.io.wr_addr := 0.U
  for (lane <- 0 until K) spm.io.wr_data(lane) := 0.U

  // ==========================================================================
  // Special Register File (SREG)
  // ==========================================================================
  val sreg = Module(new SpecialRegFile)

  // Direct test-harness access
  sreg.io.wr_en   := io.sreg_wr_en
  sreg.io.wr_sel  := io.sreg_wr_sel
  sreg.io.wr_data := io.sreg_wr_data
  sreg.io.tile_rst := io.sreg_tile_rst
  // Auto-increment not yet wired to PAG (PAG is a future module)
  sreg.io.tile_w_inc := false.B
  sreg.io.tile_h_inc := false.B

  io.sreg_tile_h := sreg.io.tile_h
  io.sreg_tile_w := sreg.io.tile_w
  io.sreg_conv   := sreg.io.conv

  // ==========================================================================
  // Multi-width register file
  // ==========================================================================
  val rf = Module(new MultiWidthRegisterBlock(L, K, N,
    vx_rd = 4, vx_wr = 3, ve_rd = 2, ve_wr = 1, vr_rd = 2, vr_wr = 2))

  // ---- VX reads ----
  rf.io.vx_r_addr(0) := io.mma_a_addr
  rf.io.vx_r_addr(1) := io.vx_a_addr
  rf.io.vx_r_addr(2) := io.vx_b_addr
  rf.io.vx_r_addr(3) := Mux(io.ext_wr_en || io.ext_rd_addr.orR, io.ext_rd_addr, io.mma_b_addr)
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

  // External RF write (test harness)
  rf.io.ext_w_en   := io.ext_wr_en
  rf.io.ext_w_addr := io.ext_wr_addr
  rf.io.ext_w_data := io.ext_wr_data

  // ==========================================================================
  // LD / ST execution
  //
  // LD pipeline (2 cycles):
  //   Cycle 0 — decode fires is_ld; compute SPM row; issue SPM read.
  //             Simultaneously register (rd, mem_width) for use in cycle 1.
  //   Cycle 1 — spm.rd_valid pulses; write SPM data to RF.
  //
  // The SPM rd_addr and rd_en are driven combinationally from the decoded op.
  // The RF write fires one cycle later via registered control signals.
  //
  // ST pipeline (1 cycle):
  //   Cycle 0 — read RF (async); write to SPM (synchronous).
  //
  // Address calculation: SPM row = rs1_field + sext(imm[11:0])
  //   rs1_field: dec.rs1 (5-bit page base)
  //   imm:       dec.valu.imm (signed 12-bit row offset from I-type)
  // ==========================================================================

  // SPM row address from instruction fields
  val spmRow = (dec.rs1.pad(SPM_ADDR) + dec.valu.imm.asUInt)(SPM_ADDR - 1, 0)

  // ---- LD read side (combinational) ----
  // Override the external read port wiring when an LD is in flight.
  // The external port defaults (above) are overridden here.
  when (dec.is_ld) {
    spm.io.rd_addr := spmRow
    spm.io.rd_en   := true.B
    // Override external to avoid conflict (LD takes priority over ext read)
  }

  // Pipeline register: capture rd destination and width for write-back cycle
  val ld_wb_en    = RegNext(dec.is_ld, false.B)
  val ld_wb_rd    = RegNext(dec.rd)
  val ld_wb_width = RegNext(dec.mem_width)

  // ---- LD write-back (cycle 1: spm.rd_valid) ----
  when (ld_wb_en) {
    switch (ld_wb_width) {
      is (Funct3Mem.VX_VEC) {
        rf.io.vx_w_en(2)   := true.B
        rf.io.vx_w_addr(2) := ld_wb_rd
        for (lane <- 0 until K) rf.io.vx_w_data(2)(lane) := spm.io.rd_data(lane)
      }
      is (Funct3Mem.VE_VEC) {
        // VE = 2 consecutive VX rows; write via the VE write port
        rf.io.ve_w_en(0)   := true.B
        rf.io.ve_w_addr(0) := ld_wb_rd(VE_ADDR - 1, 0)
        for (lane <- 0 until K) {
          // SPM stores VE interleaved: two sequential reads fill lo/hi N bits.
          // First read: lo N bits of each 2N lane already in spm.io.rd_data.
          // For simplicity, first implementation loads only the low half.
          // TODO: issue two consecutive SPM reads for VE (one extra cycle).
          rf.io.ve_w_data(0)(lane) := Cat(0.U(N.W), spm.io.rd_data(lane))
        }
      }
      is (Funct3Mem.VR_VEC) {
        rf.io.vr_w_en(0)   := true.B
        rf.io.vr_w_addr(0) := ld_wb_rd(VR_ADDR - 1, 0)
        for (lane <- 0 until K) {
          // VR = 4 consecutive VX rows.  First implementation loads only
          // the low N bits of the 4N lane.
          // TODO: issue 4 consecutive SPM reads for VR.
          rf.io.vr_w_data(0)(lane) := Cat(0.U(N * 3), spm.io.rd_data(lane))
        }
      }
    }
  }

  // ---- ST write side (cycle 0: RF read is async) ----
  // ST is R-type: rs1=SPM base, rs2=source VX register.
  // Route rs2 through vx_r_addr(2) (VALU in_b port); decoder writes dec.rs2.
  when (dec.is_st) {
    spm.io.wr_en   := true.B
    // ST is R-type: rs1 = SPM base row (direct, no imm offset for R-type)
    spm.io.wr_addr := dec.rs1.pad(SPM_ADDR)
    rf.io.vx_r_addr(2) := dec.rs2   // read source VX from port 2
    for (lane <- 0 until K) spm.io.wr_data(lane) := rf.io.vx_r_data(2)(lane)
  }

  // ---- tile.cfg: write to SREG ----
  // ISA-path tile.cfg overrides the direct test-harness sreg write port.
  when (dec.is_tilecfg) {
    sreg.io.wr_en   := true.B
    sreg.io.wr_sel  := dec.tilecfg_sel
    // Data source: VR[rs1] lane 0 (lower 32 bits of the 4N-bit lane)
    sreg.io.wr_data := rf.io.vr_r_data(0)(0)(31, 0)
  }

  // ==========================================================================
  // MMALU (systolic array; n = K lanes, nbits = N)
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
  def isNarrowCvtOut(op: VecOp.Type): Bool =
    op === VecOp.vcvt_s8_s32 || op === VecOp.vcvt_f32_s8

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
