// See README.md for license details.
// -----------------------------------------------------------------------------
//  npuProgramEngine.scala — instruction-driven core: InstrDecoder + RF + MMALU
//  + NpuDmaEngine.
//
//  Executes the current ISA scope: NOP, MMA (mma/mma.last/mma.reset), and the
//  RVV-aligned vector ld/st (vle8/16/32, vse8/16/32) between the L3 DATA
//  section and the VX/VE/VR register file.
//
//  MMA execution replicates the silicon-proven one-shot control pattern from
//  npu_dma_master.v:
//    FEED:      one cycle of  ctrl.busy=1, ctrl.keep=mma_keep,
//               ctrl.use_accum=(mma.last), in_a/in_b/in_accum held constant.
//    CLCT_WAIT: ctrl.busy=0, ctrl.keep=1, operands held; on the io.clct pulse
//               (tick 2n-2) latch io.out — the K×1 collect.  mma.last then
//               writes it to VR[rd] (128 B, one wide write).
//    DRAIN:     2K-2 cycles after clct with zeroed operands — the residual
//               keep=1 entries in the control pipeline must pass before the
//               next mma feed, or they would alias into it (the hardware
//               one-shot never noticed because host kicks are µs apart).
//
//  Pinned semantics (verified against the silicon one-shot formula):
//    mma.last keep=0  → OUT[i] = A[i] · B[K-1] + ACCUM[i]  (signed int8)
//    mma.last keep=1  → undocumented behaviour on this MMALU (multiplier
//                       becomes s8(B[0])·K); do NOT use.  The assembler and
//                       driver always emit mma.last with keep=false;
//                       cross-program accumulation is host-driven via the
//                       L3 ACCUM operand (K×1, added at every collect).
//
//  Section-relative ld/st addressing: rs1[1:0] = section (0=A, 1=B, 2=ACCUM,
//  3=OUT), imm[11:0] = byte offset.  Section bases live in NpuSections and
//  must mirror the host driver's staging table (native.cpp kSections).
// -----------------------------------------------------------------------------

package engine

import chisel3._
import chisel3.util._

import alu.mma._
import alu.pe._
import isa._
import isa.micro_op._
import sram.mwreg._
import dma._

// ---------------------------------------------------------------------------
// L3 memory map (mirrors drivers/chisel_npu_py native section table).
// ---------------------------------------------------------------------------
object NpuSections {
  val DATA_BASE = 0x40000000L
  val A         = 0x40000000L   // int8[K×K]   1 KiB
  val B         = 0x40000400L   // int8[K×K]   1 KiB
  val ACCUM     = 0x40000800L   // int32[K×1]  128 B
  val OUT       = 0x40000880L   // int32[K×K]  4 KiB
  val CODE_BASE = 0x40004000L   // code section (entry at base)

  val SECT_A     = 0
  val SECT_B     = 1
  val SECT_ACCUM = 2
  val SECT_OUT   = 3
}

class NpuProgramEngine(val K: Int = 32, val N: Int = 8) extends Module {

  require((K == 16 || K == 32) && N == 8, "NpuProgramEngine: K=16 or K=32, N=8 only")

  val VX_ADDR = log2Ceil(K)
  val VE_ADDR = log2Ceil(K / 2)
  val VR_ADDR = log2Ceil(K / 4)

  val io = IO(new Bundle {
    // ---- Instruction issue (A4 frontend feeds this) ----
    val instr       = Input(UInt(32.W))
    val instr_valid = Input(Bool())
    val instr_ready = Output(Bool())
    val illegal_out = Output(Bool())   // illegal/unsupported instruction seen

    // ---- Debug: RF VX read (testbench only) ----
    val dbg_vx_addr = Input(UInt(5.W))
    val dbg_vx_data = Output(Vec(K, UInt(N.W)))

    // ---- Instruction-line fetch (shared DMA; frontend issues fills) ----
    val fetch_fill_req  = Input(Bool())
    val fetch_fill_addr = Input(UInt(32.W))
    val fetch_fill_done = Output(Bool())     // line valid pulse (DMA INSTRW)
    val fetch_fill_data = Output(UInt(128.W))
    val fetch_busy      = Output(Bool())     // DMA busy (core + fill)

    // ---- AXI4 master (128-bit, same shape as NpuDmaEngine) ----
    val m_axi_awaddr  = Output(UInt(32.W))
    val m_axi_awlen   = Output(UInt(8.W))
    val m_axi_awsize  = Output(UInt(3.W))
    val m_axi_awburst = Output(UInt(2.W))
    val m_axi_awvalid = Output(Bool())
    val m_axi_awready = Input(Bool())
    val m_axi_wdata   = Output(UInt(128.W))
    val m_axi_wstrb   = Output(UInt(16.W))
    val m_axi_wlast   = Output(Bool())
    val m_axi_wvalid  = Output(Bool())
    val m_axi_wready  = Input(Bool())
    val m_axi_bvalid  = Input(Bool())
    val m_axi_bready  = Output(Bool())
    val m_axi_araddr  = Output(UInt(32.W))
    val m_axi_arlen   = Output(UInt(8.W))
    val m_axi_arsize  = Output(UInt(3.W))
    val m_axi_arburst = Output(UInt(2.W))
    val m_axi_arvalid = Output(Bool())
    val m_axi_arready = Input(Bool())
    val m_axi_rdata   = Input(UInt(128.W))
    val m_axi_rlast   = Input(Bool())
    val m_axi_rvalid  = Input(Bool())
    val m_axi_rready  = Output(Bool())
  })

  // ==========================================================================
  // Decoder (combinational)
  // ==========================================================================
  val decoder = Module(new InstrDecoder)
  decoder.io.instr := io.instr
  val dec = decoder.io.decoded

  // ==========================================================================
  // Execution state and latched decoded fields (declared before use)
  // ==========================================================================
  object State extends ChiselEnum {
    val IDLE, DMA_REQ, DMA_WAIT, FEED, CLCT_WAIT, DRAIN, VRWR, DONE_1, ILLEGAL = Value
  }
  val state = RegInit(State.IDLE)

  val rdReg     = RegInit(0.U(5.W))
  val rs1Reg    = RegInit(0.U(5.W))
  val rs2Reg    = RegInit(0.U(5.W))
  val memWidthReg = RegInit(0.U(2.W))
  val sectReg   = RegInit(0.U(2.W))
  val offReg    = RegInit(0.U(12.W))
  val mmaKeepReg  = RegInit(false.B)
  val mmaLastReg  = RegInit(false.B)
  val mmaResetReg = RegInit(false.B)
  val isStoreReg  = RegInit(false.B)

  val outBuf   = RegInit(VecInit(Seq.fill(K)(0.S((4 * N).W))))
  val clctWaitCnt = RegInit(0.U(8.W))
  val drainCnt = RegInit(0.U(8.W))

  // ---- FSM control wires ----
  val inMma      = WireDefault(false.B)      // FEED || CLCT_WAIT (operands held)
  val ctrlKeep   = WireDefault(false.B)
  val ctrlBusy   = WireDefault(false.B)
  val ctrlUseAccum = WireDefault(false.B)
  val dmaIssue   = WireDefault(false.B)
  val vrWrite    = WireDefault(false.B)
  val accRead    = WireDefault(false.B)

  // L3 address = section base + offset (ACCUM mode: fixed ACCUM base)
  val l3Addr = Wire(UInt(32.W))
  l3Addr := Mux(accRead, NpuSections.ACCUM.U(32.W),
    MuxLookup(sectReg, 0.U(32.W))(Seq(
      NpuSections.SECT_A.U(2.W)     -> NpuSections.A.U(32.W),
      NpuSections.SECT_B.U(2.W)     -> NpuSections.B.U(32.W),
      NpuSections.SECT_ACCUM.U(2.W) -> NpuSections.ACCUM.U(32.W),
      NpuSections.SECT_OUT.U(2.W)   -> NpuSections.OUT.U(32.W),
    )) + offReg)

  // ==========================================================================
  // DMA engine (AXI master; declared before the RF which borrows its ports)
  // ==========================================================================
  val dma = Module(new NpuDmaEngine(K, N))

  // Fetch fills share the DMA: honoured whenever the core's own FSM is not
  // using it (DMA_REQ / DMA_WAIT).
  val coreDmaActive = state === State.DMA_REQ || state === State.DMA_WAIT
  val doFetchFill   = io.fetch_fill_req && !coreDmaActive

  dma.io.req_dir     := Mux(doFetchFill, 3.U, Mux(accRead, 2.U, Mux(isStoreReg, 1.U, 0.U)))
  dma.io.req_width   := Mux(doFetchFill, 0.U, memWidthReg)
  dma.io.req_rf_addr := Mux(doFetchFill, 0.U, rdReg)
  dma.io.req_l3_addr := Mux(doFetchFill, io.fetch_fill_addr, l3Addr)
  dma.io.req_valid   := dmaIssue || doFetchFill

  io.fetch_fill_done := doFetchFill && dma.io.instr_valid
  io.fetch_fill_data := dma.io.instr_out
  io.fetch_busy      := dma.io.busy

  io.m_axi_awaddr  := dma.io.m_axi_awaddr
  io.m_axi_awlen   := dma.io.m_axi_awlen
  io.m_axi_awsize  := dma.io.m_axi_awsize
  io.m_axi_awburst := dma.io.m_axi_awburst
  io.m_axi_awvalid := dma.io.m_axi_awvalid
  dma.io.m_axi_awready := io.m_axi_awready
  io.m_axi_wdata   := dma.io.m_axi_wdata
  io.m_axi_wstrb   := dma.io.m_axi_wstrb
  io.m_axi_wlast   := dma.io.m_axi_wlast
  io.m_axi_wvalid  := dma.io.m_axi_wvalid
  dma.io.m_axi_wready := io.m_axi_wready
  dma.io.m_axi_bvalid := io.m_axi_bvalid
  io.m_axi_bready  := dma.io.m_axi_bready
  io.m_axi_araddr  := dma.io.m_axi_araddr
  io.m_axi_arlen   := dma.io.m_axi_arlen
  io.m_axi_arsize  := dma.io.m_axi_arsize
  io.m_axi_arburst := dma.io.m_axi_arburst
  io.m_axi_arvalid := dma.io.m_axi_arvalid
  dma.io.m_axi_arready := io.m_axi_arready
  dma.io.m_axi_rdata  := io.m_axi_rdata
  dma.io.m_axi_rlast  := io.m_axi_rlast
  dma.io.m_axi_rvalid := io.m_axi_rvalid
  io.m_axi_rready  := dma.io.m_axi_rready

  // ACCUM vector from L3 (K×1, read at the start of mma.last)
  val accBuf = RegInit(VecInit(Seq.fill(K)(0.S((4 * N).W))))
  when (dma.io.acc_valid) {
    for (lane <- 0 until K) accBuf(lane) := dma.io.acc_out(lane).asSInt
  }

  // ==========================================================================
  // Register file (engine instance with DMA + MMA ports)
  // ==========================================================================
  val rf = Module(new MultiWidthRegisterBlock(L = K, K = K, N = N,
    vx_rd = 3, vx_wr = 1, ve_rd = 1, ve_wr = 1, vr_rd = 1, vr_wr = 2))

  // ---- MMA operand read ports (in_a = VX[rs1], in_b = VX[rs2]) ----
  rf.io.vx_r_addr(0) := Mux(inMma, rs1Reg, 0.U)
  rf.io.vx_r_addr(1) := Mux(inMma, rs2Reg, 0.U)

  // ---- DMA RF ports ----
  rf.io.vx_r_addr(2) := dma.io.rf_r_vx_addr
  rf.io.ve_r_addr(0) := dma.io.rf_r_ve_addr
  rf.io.vr_r_addr(0) := dma.io.rf_r_vr_addr

  // DMA R2L read data (from the same RF read ports)
  dma.io.rf_r_vx_data := rf.io.vx_r_data(2)
  dma.io.rf_r_ve_data := rf.io.ve_r_data(0)
  dma.io.rf_r_vr_data := rf.io.vr_r_data(0)

  rf.io.vx_w_en(0)   := dma.io.rf_w_vx_en
  rf.io.vx_w_addr(0) := dma.io.rf_w_vx_addr
  rf.io.vx_w_data(0) := dma.io.rf_w_vx_data
  rf.io.ve_w_en(0)   := dma.io.rf_w_ve_en
  rf.io.ve_w_addr(0) := dma.io.rf_w_ve_addr
  rf.io.ve_w_data(0) := dma.io.rf_w_ve_data
  rf.io.vr_w_en(0)   := dma.io.rf_w_vr_en
  rf.io.vr_w_addr(0) := dma.io.rf_w_vr_addr
  rf.io.vr_w_data(0) := dma.io.rf_w_vr_data

  // ---- MMA collect write to VR (port 1, top priority) ----
  rf.io.vr_w_en(1)   := vrWrite
  rf.io.vr_w_addr(1) := rdReg(VR_ADDR - 1, 0)
  for (lane <- 0 until K) rf.io.vr_w_data(1)(lane) := outBuf(lane).asUInt

  // ---- External ports unused; tie off ----
  rf.io.ext_r_addr := io.dbg_vx_addr
  rf.io.ext_w_en   := false.B
  rf.io.ext_w_addr := 0.U
  for (lane <- 0 until K) rf.io.ext_w_data(lane) := 0.U
  io.dbg_vx_data := rf.io.ext_r_data

  // ==========================================================================
  // MMALU
  // ==========================================================================
  val mmalu = Module(new MMALU(new MMPE(N), K, N, 4 * N))
  // Operands held constant during the mma window (FEED + CLCT_WAIT), zeroed
  // outside so residual keep=1 entries in the control pipeline accumulate 0
  // (protects keep=1 chaining across instructions).
  mmalu.io.in_a := VecInit(rf.io.vx_r_data(0).map(d => Mux(inMma, d.asSInt, 0.S(N.W))))
  mmalu.io.in_b := VecInit(rf.io.vx_r_data(1).map(d => Mux(inMma, d.asSInt, 0.S(N.W))))
  mmalu.io.in_accum := accBuf
  mmalu.io.ctrl.keep      := ctrlKeep
  mmalu.io.ctrl.busy      := ctrlBusy
  mmalu.io.ctrl.use_accum := ctrlUseAccum

  // ==========================================================================
  // FSM
  // ==========================================================================
  io.instr_ready := state === State.IDLE
  io.illegal_out := false.B

  inMma      := state === State.FEED || state === State.CLCT_WAIT
  dmaIssue   := state === State.DMA_REQ
  vrWrite    := state === State.VRWR
  accRead    := mmaLastReg && state === State.DMA_REQ

  switch (state) {
    is (State.IDLE) {
      when (io.instr_valid) {
        rdReg       := dec.rd
        rs1Reg      := dec.rs1
        rs2Reg      := dec.rs2
        memWidthReg := dec.mem_width(1, 0)
        sectReg     := dec.rs1(1, 0)
        offReg      := dec.mem_off
        mmaKeepReg  := dec.mma_keep
        mmaLastReg  := dec.mma_last
        mmaResetReg := dec.mma_reset
        isStoreReg  := dec.family === OpFamily.ST
        when (decoder.io.illegal ||
              ((dec.family === OpFamily.LD || dec.family === OpFamily.ST) && dec.rs1 > 3.U)) {
          state := State.ILLEGAL
        }
        .elsewhen (dec.family === OpFamily.NOP) { state := State.DONE_1 }
        .elsewhen (dec.family === OpFamily.LD)  { state := State.DMA_REQ }
        .elsewhen (dec.family === OpFamily.ST)  { state := State.DMA_REQ }
        .elsewhen (dec.family === OpFamily.MMA) {
          when (dec.mma_last)      { state := State.DMA_REQ }  // read ACCUM first
          .otherwise               { state := State.FEED }
        }
        .otherwise { state := State.ILLEGAL }  // VALU families: out of scope
      }
    }

    is (State.DMA_REQ) {
      // Issue to the DMA engine (L2R / R2L / ACCUM read).
      when (dma.io.req_ready) { state := State.DMA_WAIT }
    }

    is (State.DMA_WAIT) {
      when (dma.io.done) {
        when (mmaLastReg) { state := State.FEED }
        .otherwise        { state := State.DONE_1 }
      }
    }

    is (State.FEED) {
      // One-cycle busy pulse with constant operands (npu_dma_master S_KICK).
      ctrlBusy := true.B
      ctrlKeep := Mux(mmaResetReg, false.B, mmaKeepReg)
      ctrlUseAccum := mmaLastReg
      state := State.CLCT_WAIT
      clctWaitCnt := 0.U
    }

    is (State.CLCT_WAIT) {
      // Drain window: keep=1 held, operands held; capture on the clct pulse.
      ctrlKeep := true.B
      when (mmalu.io.clct) {
        for (lane <- 0 until K) outBuf(lane) := mmalu.io.out(lane)
        state := State.DRAIN
        drainCnt := 0.U
      } .otherwise {
        when (clctWaitCnt === 255.U) { state := State.ILLEGAL }  // watchdog
        .otherwise { clctWaitCnt := clctWaitCnt + 1.U }
      }
    }

    is (State.DRAIN) {
      // Pipeline drain (2K-2 cycles): the residual keep=1 entries fed during
      // CLCT_WAIT reach the deepest PE 2K-2 cycles after the last feed.
      // Operands are zeroed here (inMma=false), so the drain is a no-op on
      // the PE accumulators but keeps consecutive mma feeds from aliasing.
      when (drainCnt === (2 * K - 3).U) {
        when (mmaLastReg) { state := State.VRWR }
        .otherwise        { state := State.DONE_1 }
      } .otherwise {
        drainCnt := drainCnt + 1.U
      }
    }

    is (State.VRWR) {
      // K×1 collect → VR[rd] (one wide 128 B write, port 1).
      state := State.DONE_1
    }

    is (State.DONE_1) {
      state := State.IDLE
    }

    is (State.ILLEGAL) {
      io.illegal_out := true.B
      state := State.DONE_1
    }
  }
}
