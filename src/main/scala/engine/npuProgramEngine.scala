// See README.md for license details.
// -----------------------------------------------------------------------------
//  npuProgramEngine.scala — streamed-matmul dispatch unit.
//
//  Replaces the stall-based FSM (IDLE/DMA_REQ/DMA_WAIT/DONE_1) with a
//  windowed scoreboard dispatcher that issues instructions to concurrent
//  execution units (DMA, MMALU) with producer/consumer chaining.  See
//  docs/designs/02.streamed-dispatch.md for the design.
//
//  The program is a stream:
//    vle8 a → VX[a];  vle8 b → VX[b];  mma vd, a, b, c; ...
//    mma.last;  vse32 ...
//  Each mma's clct captures the running partial sum of the settled feeds to
//  VR[vd] and pushes the value into the capture-data queue; the chained vse
//  (a capture consumer) pops that queue at its issue and the store drains via
//  the DMA — the value is read right after the mma's final (clct) tick.
//  Register reuse (vd rings, operand slots) is entirely the software's job;
//  the scoreboard only enforces the RAW/WAR hazards.
//
//  mma.last also starts the boundary countdown (4K−1 ticks; the keep=0 PE
//  reset pulse at the 2K offset).  mma-family issue is gated on the
//  countdown, so the next round's first mma waits until every PE has reset —
//  rounds chain back-to-back with zero software padding.
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

class NpuProgramEngine(val K: Int = 32, val N: Int = 8, val W: Int = 16) extends Module {

  require((K == 16 || K == 32) && N == 8, "NpuProgramEngine: K=16 or K=32, N=8 only")

  val L = K                          // number of base VX registers (aliases into VE/VR)
  val VX_ADDR = log2Ceil(L)
  val VE_ADDR = log2Ceil(L / 2)
  val VR_ADDR = log2Ceil(L / 4)
  // ---- L1 tag ring (P2: allocated at entry, freed at completion; the slot
  //      retires at issue/feed so the in-flight capacity is the tag ring, not
  //      the W-slot window).  T is the in-flight capacity; TAG_IDX indexes the
  //      ring.
  //
  //      ALIASING BOUND (do not retune silently): `priorSnap` is a snapshot of
  //      `tagValid`, i.e. a raw T-bit vector of the ring positions live at
  //      allocation time.  When a position frees it can be reallocated to a
  //      *different* op while a younger op still holds a `priorSnap` recording
  //      that position, so the snapshot aliases the new owner.  This is safe
  //      only while the ring cannot wrap between an allocation and the
  //      retirement of every op that snapshotted it, i.e. the design must hold
  //      maxInFlight < T - W.  A generational scheme (tag + generation counter)
  //      would remove the bound.  Retuning T (or CAPTURE_DELAY, which sets the
  //      in-flight span) down to/below 2W + L requires that generational
  //      `priorSnap` fix FIRST — otherwise the §13 mma.last ordering gate can
  //      read a stale alias bit (miss a real prior mma or block on a dead one).
  //
  //      T is sized to the completion latency; retuning below `2W+L` requires
  //      the generational priorSnap fix.
  val CAPTURE_DELAY = 16
  val T = 1 << log2Ceil(2 * K + 2 * CAPTURE_DELAY)
  val TAG_IDX = log2Ceil(T)
  // The completion queues carry one entry per in-flight op, so they must be
  // deep enough to hold the whole tag ring or feed issue stalls below peak
  // (the vd FIFO also carries the K×32-bit `accum` column — decision (a)).
  val FIFO_DEPTH = T          // in-flight feeds (vd FIFO, carries accum)
  val CAPQ_DEPTH = 16         // capture queue (chained vse drains promptly)
  val DMAQ_DEPTH = 4
  val SLOT_IDX = log2Ceil(W)

  val io = IO(new Bundle {
    // ---- Instruction issue (frontend feeds this) ----
    val instr       = Input(UInt(32.W))
    val instr_valid = Input(Bool())
    val instr_ready = Output(Bool())
    val illegal_out = Output(Bool())   // illegal/unsupported instruction seen
    val drain       = Output(Bool())   // the pipeline fully drained (window +
                                       // DMA + captures + boundary countdown)

    // Session reset: pulsed by the frontend at every start to clear the
    // streaming state (window, scoreboard, queues) so a wedged or aborted
    // session cannot contaminate the next run.
    val session_reset = Input(Bool())

    // Store-sparse reduce mode (opt-in, default OFF).  In a reduction round,
    // the regular mmas accumulate in the array (the MMALU holds keep=1) and
    // push no capture; only the round's FINAL (mma.last) feed pushes the
    // accumulated capture, which a single trailing vse stores.  This removes
    // the 128 B-per-feed store roofline from the array-pipeline body.
    val reduce_mode = Input(Bool())

    // ---- Debug: RF VX read (testbench only) ----
    val dbg_vx_addr = Input(UInt(5.W))
    val dbg_vx_data = Output(Vec(K, UInt(N.W)))

    // ---- Debug: mma issue/collect counters (silicon bring-up) ----
    val dbg_mma_acc = Output(UInt(16.W))   // mma instructions accepted
    val dbg_clct    = Output(UInt(16.W))   // clct pulses captured
    val dbg_win     = Output(UInt(2.W))    // capture FIFO occupancy (low bits)
    val dbg_dma_done = Output(UInt(16.W))  // DMA requests completed
    val dbg_wcount   = Output(UInt(5.W))   // dispatch window occupancy
    val dbg_bnd      = Output(UInt(7.W))   // boundary countdown (0 when idle)
    val dbg_dmaq     = Output(UInt(3.W))   // dma queue occupancy
    val dbg_capq     = Output(UInt(5.W))   // capture queue occupancy
    val dbg_mma_out0 = Output(UInt((4 * N).W))  // mmalu out lane 0
    val dbg_mma_out1 = Output(UInt((4 * N).W))  // mmalu out lane 1
    val dbg_cnt      = Output(UInt(4.W))        // collector phase
    val dbg_in_a0    = Output(UInt(N.W))         // mma in_a lane 0
    val dbg_feed_rs1 = Output(UInt(5.W))         // feed rs1
    val dbg_feed_last = Output(Bool())           // feed is mma_last
    val dbg_slot_rs1  = Output(UInt(5.W))          // fed slot rs1
    val dbg_mma_pend = Output(Bool())            // any pending non-last mma
    val dbg_s2_valid = Output(Bool())
    val dbg_s2_done  = Output(Bool())
    val dbg_s2_unit  = Output(UInt(2.W))
    val dbg_s2_last  = Output(Bool())
    // ---- Debug: collector {keep, dat_clct, cnt} trajectory (last 16 ticks) ----
    val dbg_traj = Output(Vec(16, UInt(6.W)))

    // ---- Debug: per-session performance counters (benchmarking) ----
    val dbg_cycles = Output(UInt(32.W))  // free-running session cycles
    val dbg_mac    = Output(UInt(32.W))  // useful K² MACs (excl. mma.last)

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
    val m_axi_wdata   = Output(UInt(512.W))
    val m_axi_wstrb   = Output(UInt(64.W))
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
    val m_axi_rdata   = Input(UInt(512.W))
    val m_axi_rlast   = Input(Bool())
    val m_axi_rvalid  = Input(Bool())
    val m_axi_rready  = Output(Bool())
  })

  // ==========================================================================
  // Decoder (combinational, for the incoming instruction)
  // ==========================================================================
  val decoder = Module(new InstrDecoder)
  decoder.io.instr := io.instr
  val dec = decoder.io.decoded

  // ==========================================================================
  // Execution units
  // ==========================================================================
  object DispatchUnit extends ChiselEnum {
    val NONE, DMA, MMALU = Value
  }

  class DispatchSlot extends Bundle {
    val valid      = Bool()
    val unit       = DispatchUnit()
    val family     = UInt(7.W)      // OpFamily (values up to 0x27=ST need 7 bits)
    val rd         = UInt(5.W)
    val rs1        = UInt(5.W)
    val rs2        = UInt(5.W)
    val vs3        = UInt(5.W)
    val memWidth   = UInt(2.W)
    val sect       = UInt(2.W)
    val off        = UInt(12.W)
    val isStore    = Bool()
    val isMmaLast  = Bool()
    val illegal    = Bool()
    val useCapData = Bool()         // vse: source produced by an mma capture
    val issued     = Bool()
    val done       = Bool()
    // ---- L1 tag ring (P1a shadow).  `tag` is the allocated ring index; the
    //      `priorSnap` is the tagValid snapshot taken just before its own
    //      allocation (the hazard set for a later readiness rewrite).  This is
    //      a raw bit-per-ring-position snapshot and is only alias-safe while
    //      maxInFlight < T - W — see the bound note at the `T` definition. ----
    val tag        = UInt(TAG_IDX.W)
    val priorSnap  = UInt(T.W)
  }

  class DmaEntry extends Bundle {
    val valid      = Bool()
    val inFlight   = Bool()         // accepted by the DMA, awaiting done
    val tag        = UInt(TAG_IDX.W) // L1 ring tag
    val dir        = UInt(2.W)      // 0 = vle, 1 = vse, 3 = fetch fill
    val dmaWidth   = UInt(2.W)
    val rfAddr     = UInt(5.W)
    val l3Addr     = UInt(32.W)
    // The vse's VX read mask captured at issue: the window slot retires at
    // issue (P2), so the completion can no longer read it back off the slot to
    // clear the reader tag bits.  (Unused for vle/fetch fills.)
    val rdMask     = UInt(L.W)
    val storeDataValid = Bool()
    val storeData = Vec(K, UInt((4 * N).W))
  }

  class CapEntry extends Bundle {
    val valid = Bool()
    val data  = Vec(K, UInt((4 * N).W))
  }

  // ==========================================================================
  // L1 scoreboard: a T-entry tag ring (P1b).  The old W-bit per-slot masks
  // (vxWrMask/vxRdMask/vxWrCap) were retired here — tags are allocated at
  // window entry, freed at completion, and give the hazard set for free
  // without tying in-flight capacity to the W-slot issue window.
  // VE[i] → VX[2i..2i+1]; VR[i] → VX[4i..4i+3] (alias-aware, uniform).
  //   tagValid(t) : ring entry t holds a live (allocated, not completed) tag
  //   tagIsMma(t) : the allocation is an MMA (capture producer)
  //   tagIsLast(t): the allocation is an mma.last
  //   tagWrMask(i): pending writer tags for VX[i]
  //   tagRdMask(i): in-flight reader tags for VX[i]
  //   lastWrTag(i): the newest writer tag for VX[i]
  // ==========================================================================
  val tagTail = RegInit(0.U(TAG_IDX.W))
  val tagCount = RegInit(0.U((TAG_IDX + 1).W))
  val tagValid = RegInit(VecInit(Seq.fill(T)(false.B)))
  // P2 retire-at-feed: `tagFed` marks an MMA allocation whose operands have
  // been read at MMA feed.  It is NOT set for DMA ops (the §13 ordering gate
  // tests `tagIsMma` first, so a DMA entry never reaches the `tagFed` test).
  // The mma.last ordering gate (§13) must
  // block only on *prior MMAs that have not yet fed* — waiting for their
  // completion would serialize the whole reduce round's tail behind the
  // ~2n+CAPTURE_DELAY capture latency.  `tagValid` still means "not completed"
  // because it drives the RAW/WAR hazard snapshot and must stay live until the
  // write is visible.
  val tagFed   = RegInit(VecInit(Seq.fill(T)(false.B)))
  val tagIsMma = RegInit(VecInit(Seq.fill(T)(false.B)))
  val tagIsLast = RegInit(VecInit(Seq.fill(T)(false.B)))
  val tagWrMask = RegInit(VecInit(Seq.fill(L)(0.U(T.W))))
  val tagRdMask = RegInit(VecInit(Seq.fill(L)(0.U(T.W))))
  val lastWrTag = RegInit(VecInit(Seq.fill(L)(0.U(TAG_IDX.W))))
  // Per-register "last writer was an MMA capture" flag.  Persists like the old
  // `vxWrCap`: set when a writer registers (false for vle, true for mma) and
  // cleared when the register's pending-write tag mask empties on a vle
  // completion.  Using this instead of `tagIsMma(lastWrTag(i))` removes the
  // dependence on a freed tag's kind surviving in the reused ring entry.
  val wrCap = RegInit(VecInit(Seq.fill(L)(false.B)))
  // Entry stalls once the ring is full.  The second term guards the
  // out-of-order-free alias: with completion out of order the net `tagCount`
  // can undercount the reachable span, so also refuse to allocate when the
  // tail still points at a live entry (the oldest in-flight op will free it).
  val tagNotFull = (tagCount =/= T.U) && !tagValid(tagTail)

  // The VX-slot sets an instruction reads/writes (L-bit masks).
  def readSlotsL(family: UInt, rd: UInt, rs1: UInt, rs2: UInt, memWidth: UInt,
                 vs3: UInt = 0.U, isMmaLast: Bool = false.B): UInt = {
    val m = WireDefault(0.U(L.W))
    when (family === OpFamily.MMA.asUInt) {
      // The mma.last is a session terminator: the engine ZERO-FORCES its
      // operands (it must not read stale VX[0]), so its true read set is
      // empty.  Counting its encoded vs1/vs2 as reads makes it RAW-wait on
      // every prior mma's destination (VX[0]) completion, which serialises the
      // reduce round's whole tail behind the capture latency.
      m := Mux(isMmaLast, 0.U(L.W),
        (1.U << rs1) | (1.U << rs2) |
          Mux(vs3 === 0.U, 0.U(L.W), (0xF.U << (vs3 << 2))(L - 1, 0)))
    } .elsewhen (family === OpFamily.ST.asUInt) {
      when (memWidth === 0.U) {
        m := 1.U << rd
      } .elsewhen (memWidth === 1.U) {
        m := (1.U << (rd ## 0.U(1.W))) | (1.U << (rd ## 1.U(1.W)))
      } .elsewhen (memWidth === 2.U) {
        m := (1.U << (rd ## 0.U(2.W))) | (1.U << (rd ## 1.U(2.W))) |
             (1.U << (rd ## 2.U(2.W))) | (1.U << (rd ## 3.U(2.W)))
      }
    }
    m
  }

  def writeSlotsL(family: UInt, rd: UInt, memWidth: UInt = 0.U): UInt = {
    val m = WireDefault(0.U(L.W))
    when (family === OpFamily.LD.asUInt) {
      when (memWidth === 0.U) {
        m := 1.U << rd
      } .elsewhen (memWidth === 1.U) {
        m := (1.U << (rd ## 0.U(1.W))) | (1.U << (rd ## 1.U(1.W)))
      } .elsewhen (memWidth === 2.U) {
        m := (0xF.U << (rd << 2))(L - 1, 0)
      }
    } .elsewhen (family === OpFamily.MMA.asUInt) {
      m := (0xF.U << (rd << 2))(L - 1, 0)
    }
    m
  }

  // ==========================================================================
  // Issue window (parameterized W)
  // ==========================================================================
  val slots   = RegInit(VecInit(Seq.fill(W)(0.U.asTypeOf(new DispatchSlot))))
  val wHead   = RegInit(0.U(SLOT_IDX.W))
  val wTail   = RegInit(0.U(SLOT_IDX.W))
  val wCount  = RegInit(0.U((SLOT_IDX + 1).W))
  val wFull   = wCount === W.U

  val illegalAny = slots.map(s => s.valid && s.illegal).reduce(_ || _)

  // Ring distance from the head (power-of-2 wrap).
  def ringDist(idx: UInt): UInt = (idx - wHead)(SLOT_IDX - 1, 0)

  // ==========================================================================
  // DMA unit: request queue (serialized on the bus with the fetch fills)
  // ==========================================================================
  val dma = Module(new NpuDmaEngine(K, N))
  val dmaQ     = RegInit(VecInit(Seq.fill(DMAQ_DEPTH)(0.U.asTypeOf(new DmaEntry))))
  val dmaQHead = RegInit(0.U(log2Ceil(DMAQ_DEPTH).W))
  val dmaQTail = RegInit(0.U(log2Ceil(DMAQ_DEPTH).W))
  val dmaQCount = RegInit(0.U((log2Ceil(DMAQ_DEPTH) + 1).W))
  val dmaQFull  = dmaQCount === DMAQ_DEPTH.U

  // ==========================================================================
  // Register file
  // ==========================================================================
  val rf = Module(new MultiWidthRegisterBlock(L = K, K = K, N = N,
    vx_rd = 3, vx_wr = 1, ve_rd = 1, ve_wr = 1, vr_rd = 2, vr_wr = 2))

  // ==========================================================================
  // Capture-data queue: the clct pushes the capture value; the chained vse
  // (capture consumer) pops it at its issue.
  // ==========================================================================
  val capQ     = RegInit(VecInit(Seq.fill(CAPQ_DEPTH)(0.U.asTypeOf(new CapEntry))))
  val capQHead = RegInit(0.U(log2Ceil(CAPQ_DEPTH).W))
  val capQTail = RegInit(0.U(log2Ceil(CAPQ_DEPTH).W))
  val capQCount = RegInit(0.U((log2Ceil(CAPQ_DEPTH) + 1).W))

  // ==========================================================================
  // vd FIFO: {tag, vd} per issued mma, popped by each clct.
  // ==========================================================================
  class VdEntry extends Bundle {
    val tag      = UInt(TAG_IDX.W)   // L1 ring tag
    val vd       = UInt(5.W)
    val isMmaLast = Bool()
  }
  val fifo     = RegInit(VecInit(Seq.fill(FIFO_DEPTH)(0.U.asTypeOf(new VdEntry))))
  // The C (accum) copy can be K*32 bits and FIFO_DEPTH deep.  A Vec of
  // registers that deep/wide is read through a huge dynamic mux tree that does
  // not map to BRAM and stalls synthesis.  Store it in a SyncReadMem (maps to
  // BRAM) instead; the 1-cycle registered read is absorbed by the delayed
  // completion stage below.
  val accumMem = SyncReadMem(FIFO_DEPTH, Vec(K, SInt((4 * N).W)))
  val fifoHead = RegInit(0.U(log2Ceil(FIFO_DEPTH).W))
  val fifoTail = RegInit(0.U(log2Ceil(FIFO_DEPTH).W))
  val fifoCount = RegInit(0.U((log2Ceil(FIFO_DEPTH) + 1).W))
  val fifoFull  = fifoCount === FIFO_DEPTH.U

  val outBuf = RegInit(VecInit(Seq.fill(K)(0.S((4 * N).W))))
  val capVd  = RegInit(0.U(5.W))
  val clctPrev = RegNext(false.B)   // one-tick delay between the clct and the VR write

  // ==========================================================================
  // Boundary gate: 4K−1 countdown after the mma.last feed; keep=0 pulse at
  // the 2K offset; mma-family issue gated on the countdown.
  // ==========================================================================
  val boundaryCnt   = RegInit(0.U(7.W))
  val boundaryPulse = boundaryCnt === (2 * K).U
  when (boundaryCnt =/= 0.U) { boundaryCnt := boundaryCnt - 1.U }

  // ==========================================================================
  // MMALU
  // ==========================================================================
  val mmalu = Module(new MMALU(new MMPE(N), K, N, 4 * N))
  // The clct delayed by the capture-settle margin (CAPTURE_DELAY, defined with
  // `T` above — see the completion block).
  val clctD = ShiftRegister(mmalu.io.clct, CAPTURE_DELAY, false.B, true.B)
  val mmaluInA = Wire(Vec(K, SInt(N.W)))
  val mmaluInB = Wire(Vec(K, SInt(N.W)))
  val mmaluInAccum = Wire(Vec(K, SInt((4 * N).W)))

  // ==========================================================================
  // Per-slot readiness (combinational)
  // ==========================================================================
  // The mma_last must wait for any pending non-last MMA whose TAG PRECEDES it
  // (slots(s).priorSnap), or it can overtake the mma (it has no operand
  // deps), start the boundary countdown, and gate the mma out entirely
  // (silicon: the mma never fed, the array stayed 0).  Only PRIOR tags count
  // — a global "any pending mma" would deadlock multi-round sessions (round-2
  // mmas are boundary-gated and would hold the mma_last off forever).
  val slotsReady = Wire(Vec(W, Bool()))
  val slotUnitOk = Wire(Vec(W, Bool()))

  // ---- MMALU feed pipeline (stage 2 registers), declared before the
  //      readiness loop so the MMALU queue reservation can account for the
  //      in-flight feed (a 1-stage feed pipeline must never overflow the
  //      FIFO).  Stage 1 = issue selection; stage 2 = RF read + accum mux +
  //      FIFO enq + MMALU drive.  This halves the issue->RF-read->FIFO logic
  //      depth (the critical 200 MHz path on xcvu9p). ----
  val issueMmaV    = WireDefault(false.B)
  val issueMmaSlot = WireDefault(0.U(SLOT_IDX.W))
  val issueMmaRd   = WireDefault(0.U(5.W))
  val issueMmaRs1  = WireDefault(0.U(5.W))
  val issueMmaRs2  = WireDefault(0.U(5.W))
  val issueMmaVs3  = WireDefault(0.U(5.W))
  val issueMmaLast = WireDefault(false.B)
  val issueMmaTag  = WireDefault(0.U(TAG_IDX.W))
  val feedValid    = RegNext(issueMmaV,    false.B)
  val feedSlot     = RegNext(issueMmaSlot, 0.U)
  val feedTag      = RegNext(issueMmaTag,  0.U)
  val feedRd       = RegNext(issueMmaRd,   0.U)
  val feedRs1      = RegNext(issueMmaRs1,  0.U)
  val feedRs2      = RegNext(issueMmaRs2,  0.U)
  val feedVs3      = RegNext(issueMmaVs3,  0.U)
  val feedMmaLast  = RegNext(issueMmaLast, false.B)

  for (s <- 0 until W) {
    val sl = slots(s)
    val rmask = readSlotsL(sl.family, sl.rd, sl.rs1, sl.rs2, sl.memWidth, sl.vs3,
      sl.isMmaLast)
    val wmask = writeSlotsL(sl.family, sl.rd, sl.memWidth)
    val rawOk = WireDefault(true.B)
    val warOk = WireDefault(true.B)
    for (i <- 0 until L) {
      when (rmask(i)) {
        when ((tagWrMask(i) & slots(s).priorSnap) =/= 0.U) { rawOk := false.B }
      }
      when (wmask(i)) {
        when ((tagRdMask(i) & slots(s).priorSnap) =/= 0.U) { warOk := false.B }
      }
    }
    val unitOk = WireDefault(false.B)
    when (sl.unit === DispatchUnit.DMA) {
      unitOk := !dmaQFull
    } .elsewhen (sl.unit === DispatchUnit.MMALU) {
      // Reserve the pending (stage-2) feed slot so the 1-stage feed pipeline
      // can never overflow the FIFO.
      unitOk := ((fifoCount + Mux(feedValid, 1.U, 0.U)) < FIFO_DEPTH.U) &&
        capQCount < CAPQ_DEPTH.U
    } .otherwise {
      unitOk := true.B
    }
    val priorMma = WireDefault(false.B)
    for (i <- 0 until T) {
      when (tagValid(i) && tagIsMma(i) && !tagIsLast(i) && !tagFed(i) &&
            slots(s).priorSnap(i)) {
        priorMma := true.B
      }
    }
    val gateOk = !(sl.family === OpFamily.MMA.asUInt && boundaryCnt =/= 0.U) &&
      !(sl.isMmaLast && priorMma)
    slotUnitOk(s) := unitOk
    slotsReady(s) := sl.valid && !sl.issued && !sl.done &&
      rawOk && warOk && gateOk && !illegalAny
  }
  // Registered dependency readiness: breaks the scoreboard->issue->store cone.
  val slotsReadyR = RegNext(slotsReady)

  // The OLDEST valid slot of each unit issues, and only when ready — the
  // units issue in strict stream order (a later ready slot never overtakes an
  // earlier stalled one; the MMALU feed order determines the capture order).
  // The readiness is REGISTERED: the scoreboard→issue→store-data path was the
  // critical timing path on xc7k480t (WNS −5.6 ns at 165 MHz); registering
  // the per-slot ready halves it (the issue happens one tick after the
  // dependencies clear — the tests/hw search the capture columns, so the
  // one-tick latency shift is transparent).
  def firstForUnit(s: Int): Bool = {
    val first = WireDefault(true.B)
    val isMma = slots(s).unit === DispatchUnit.MMALU
    for (i <- 0 until W) {
      when (slots(i).valid && slots(i).unit === slots(s).unit &&
            ringDist(i.U) < ringDist(s.U) &&
            !(isMma && slots(i).issued)) {
        first := false.B
      }
    }
    first
  }
  val issueSel = Wire(Vec(W, Bool()))
  for (s <- 0 until W) {
    issueSel(s) := slotsReadyR(s) && slotUnitOk(s) &&
      !slots(s).issued && !slots(s).done && firstForUnit(s)
  }

  // ==========================================================================
  // Issue actions
  // ==========================================================================
  // The vse capture-queue pop at its issue.
  val vsePopCap = WireDefault(false.B)
  val vsePopData = Wire(Vec(K, UInt((4 * N).W)))
  for (lane <- 0 until K) vsePopData(lane) := capQ(capQHead).data(lane)

  for (s <- 0 until W) {
    when (issueSel(s)) {
      slots(s).issued := true.B
      when (slots(s).unit === DispatchUnit.MMALU) {
        issueMmaV    := true.B
        issueMmaSlot := s.U
        issueMmaRd   := slots(s).rd
        issueMmaRs1  := slots(s).rs1
        issueMmaRs2  := slots(s).rs2
        issueMmaVs3  := slots(s).vs3
        issueMmaLast := slots(s).isMmaLast
        issueMmaTag  := slots(s).tag
      } .elsewhen (slots(s).unit === DispatchUnit.DMA) {
        val isStore = slots(s).isStore
        when (isStore && slots(s).useCapData && capQCount =/= 0.U) {
          vsePopCap := true.B
        }
        dmaQ(dmaQTail).valid := true.B
        dmaQ(dmaQTail).inFlight := false.B
        dmaQ(dmaQTail).tag := slots(s).tag
        dmaQ(dmaQTail).dir := Mux(isStore, 1.U, 0.U)
        dmaQ(dmaQTail).dmaWidth := slots(s).memWidth
        dmaQ(dmaQTail).rfAddr := slots(s).rd
        dmaQ(dmaQTail).l3Addr := MuxLookup(slots(s).sect, 0.U(32.W))(Seq(
          NpuSections.SECT_A.U(2.W)     -> NpuSections.A.U(32.W),
          NpuSections.SECT_B.U(2.W)     -> NpuSections.B.U(32.W),
          NpuSections.SECT_ACCUM.U(2.W) -> NpuSections.ACCUM.U(32.W),
          NpuSections.SECT_OUT.U(2.W)   -> NpuSections.OUT.U(32.W),
        )) + slots(s).off
        // Snapshot the vse's VX read mask for the tag-reader clear at done.
        dmaQ(dmaQTail).rdMask := readSlotsL(slots(s).family, slots(s).rd,
          slots(s).rs1, slots(s).rs2, slots(s).memWidth, slots(s).vs3)
        dmaQ(dmaQTail).storeDataValid := isStore && slots(s).useCapData
        for (lane <- 0 until K) dmaQ(dmaQTail).storeData(lane) :=
          Mux(isStore && slots(s).useCapData, vsePopData(lane), 0.U)
        // P2: the DMA slot retires at dmaQ enqueue; its tag carries the
        // completion obligation (freed at dmaDoneHead).
        slots(s).done := true.B
        dmaQTail := dmaQTail + 1.U
      } .otherwise { slots(s).done := true.B }   // DispatchUnit.NONE: retire defensively
    }
  }

  when (vsePopCap) {
    capQHead  := capQHead + 1.U
  }

  // ---- The MMALU feed + the vd FIFO enq at the feed ----
  // The pop (clctD) must not fire when the FIFO is empty: a stale clctD from
  // a previous session (still in the ShiftRegister) would underflow fifoCount
  // and, via the completion block, spuriously retire a live slot.
  // enq at the feed (head), pop at clctD (tail).  In the pipelined steady
  // state a feed and a clctD coincide every cycle, so BOTH pointers must
  // advance: the old code advanced only the head when they collided, which
  // froze the tail, re-served the same (stale) head entry to the completion
  // every cycle, and left every later slot without a completion — the body
  // wedged after the first burst.  The net count keeps head-tail occupancy.
  when (feedValid) {
    fifo(fifoHead).tag     := feedTag
    fifo(fifoHead).vd      := feedRd
    fifo(fifoHead).isMmaLast := feedMmaLast
    accumMem.write(fifoHead, mmaluInAccum)
    fifoHead := fifoHead + 1.U
  }
  val fifoPop = clctD && fifoCount =/= 0.U
  when (fifoPop) { fifoTail := fifoTail + 1.U }
  when (feedValid && fifoPop) { fifoCount := fifoCount }
  .elsewhen (feedValid)       { fifoCount := fifoCount + 1.U }
  .elsewhen (fifoPop)         { fifoCount := fifoCount - 1.U }

  when (feedValid) {
    when (feedMmaLast) { boundaryCnt := (6 * K - 1).U }
    // P2: the MMA slot retires at feed — the operands are read here and the
    // write obligation now lives in the carried tag (freed at clctD).  The
    // window slot is then free to be refilled, so in-flight capacity is the
    // tag ring (T), not the W-slot window.
    slots(feedSlot).done := true.B
    // This tag has now been fed: it no longer blocks a later mma.last (§13).
    tagFed(feedTag)      := true.B
    // the feed read its operands: its reader tag bits are cleared by the net
    // tag-mask update below (rdClrFeed).
  }

  // ==========================================================================
  // MMALU / RF wiring (the feed drives the operand reads + the ctrl)
  // ==========================================================================
  rf.io.vx_r_addr(0) := Mux(feedValid, feedRs1, 0.U)
  rf.io.vx_r_addr(1) := Mux(feedValid, feedRs2, 0.U)
  rf.io.vr_r_addr(1) := Mux(feedValid, feedVs3, 0.U)

  // The program engine drives MMALU `keep` from the boundary gate
  // (`keep := !boundaryPulse`); the decoded `mma_keep` (funct7[4]) is retained
  // for `NCoreBackend` but is ignored at the engine top.
  mmalu.io.ctrl.keep      := !boundaryPulse
  mmalu.io.ctrl.busy      := feedValid
  mmalu.io.ctrl.use_accum := feedValid

  for (lane <- 0 until K) {
    // in_a / in_b: read VX[vs1]/VX[vs2] on a normal mma feed; mma_last is a
    // session terminator marker and must inject ZEROS — reading VX[0] there
    // (its encoded vs1=vs2=0) silently feeds stale VX[0] data into the PEs
    // on silicon (sim only stays clean because VX[0] resets to 0).  This is
    // the same zero-forcing the vs3==0 accum path already does.
    mmaluInA(lane)     := Mux(feedValid && !feedMmaLast, rf.io.vx_r_data(0)(lane).asSInt, 0.S(N.W))
    mmaluInB(lane)     := Mux(feedValid && !feedMmaLast, rf.io.vx_r_data(1)(lane).asSInt, 0.S(N.W))
    // accum: mma.last must also zero-force the vs3 VR read.  The mma.last
    // read-set mask is emptied (nothing ordered this read), so letting it read
    // `vr_r_data(1)` would be a RAW/WAR hole — a concurrently-writing producer
    // could still be in flight.  mma.last only starts the boundary countdown;
    // the VR value is discarded (see the capture/accum-carry path), so force 0.
    mmaluInAccum(lane) := Mux(feedValid && !feedMmaLast,
      Mux(feedVs3 === 0.U, 0.S((4 * N).W), rf.io.vr_r_data(1)(lane).asSInt),
      0.S((4 * N).W))
  }
  mmalu.io.in_a     := mmaluInA
  mmalu.io.in_b     := mmaluInB
  mmalu.io.in_accum := mmaluInAccum


  // ==========================================================================
  // Clct completion.  The MMALU's clct fires 2n−1 after the feed, but the
  // pipelined systolic data settles ~δ ticks later (the 2026-07-30 pipe
  // stages).  Sampling io.out at the clct instant reads the still-settling
  // array (cadence-dependent partial columns).  The capture is therefore
  // sampled `CAPTURE_DELAY` ticks after the clct — the collector output has
  // settled by then and equals a clean running-sum column (the column index
  // walks with cnt, so any settled column is fine — the tests search).
  // The completion (FIFO pop, slot done, wrMask clear, capQ push, VR write)
  // all move to the delayed tick so the chained vse pops the right value.
  // The collector adds the in_accum (the C operand) during its own aligned
  // window (at the clct), which the delayed sample has passed; the engine
  // re-adds the feed's accum (carried in the vd FIFO) so the capture =
  // column + C.
  // The completion must only act when the vd FIFO actually has an entry for
  // the clct.  clctD is a delayed pulse from the MMA's clct; a clct from a
  // PREVIOUS session can still be propagating through the ShiftRegister when
  // the next session starts (the session reset does not flush it), and with
  // the FIFO empty the old code still marked a STALE fifo(fifoTail) entry
  // done — spuriously retiring the mma slot, so the mma never fed and the
  // capture read a zero array (the intermittent [0,0,0,0] wrong captures).
  // Capture-push policy.  Default (reduce_mode=0): per-feed capture — every
  // non-mma.last clct pushes (the chained vse stores each running sum);
  // mma.last is a session terminator and does not push.  Reduce mode
  // (opt-in): the regular mmas accumulate in the array and push nothing; only
  // the FINAL mma.last clct pushes the round's accumulated sum.  The SAME
  // expression must drive both the push below and the capQCount net term (or
  // the count desyncs and `drain` wedges).
  val clctHasEntry  = clctD && fifoCount =/= 0.U

  // ---- Delayed completion stage.  The C (accum) copy now lives in a BRAM
  //      (`accumMem`), whose read is registered (1 cycle).  At clctD we latch
  //      the settled array output + the FIFO entry's metadata and issue the
  //      BRAM read; one cycle later the completion effects run, using
  //      `compOut + accumRd` (= array column + C).  This is a pure latency
  //      shift; the capture value and semantics are unchanged.
  val compValid = RegInit(false.B)
  val compTag   = RegInit(0.U(TAG_IDX.W))
  val compVd    = RegInit(0.U(5.W))
  val compLast  = RegInit(false.B)
  val compOut   = RegInit(VecInit(Seq.fill(K)(0.S((4 * N).W))))
  val accumRd = Wire(Vec(K, SInt((4 * N).W)))
  accumRd := accumMem.read(fifoTail)   // data for fifoTail(clctD) appears next cycle

  when (clctHasEntry) {
    compTag  := fifo(fifoTail).tag
    compVd   := fifo(fifoTail).vd
    compLast := fifo(fifoTail).isMmaLast
    compOut  := mmalu.io.out
  }
  compValid := clctHasEntry

  val capturePush = compValid &&
    Mux(io.reduce_mode, compLast, !compLast)

  when (compValid) {
    capVd := compVd
    val capValue = Wire(Vec(K, SInt((4 * N).W)))
    for (lane <- 0 until K) {
      capValue(lane) := compOut(lane) + accumRd(lane)
      outBuf(lane)   := capValue(lane)
    }
    when (capturePush) {
      capQ(capQTail).valid := true.B
      for (lane <- 0 until K) capQ(capQTail).data(lane) := capValue(lane).asUInt
      capQTail  := capQTail + 1.U
    }
  }

  // Net capture-queue occupancy: a push and a vse pop can collide every cycle
  // in the pipelined steady state; compute the net so the count cannot drift
  // (a phantom entry would block `drain`).
  val capPush = capturePush
  val capPop  = vsePopCap
  when (capPush && capPop) { capQCount := capQCount }
  .elsewhen (capPush)      { capQCount := capQCount + 1.U }
  .elsewhen (capPop)       { capQCount := capQCount - 1.U }

  clctPrev := compValid

  // the VR collect write one tick after the delayed capture
  rf.io.vr_w_en(1)   := clctPrev
  rf.io.vr_w_addr(1) := capVd(VR_ADDR - 1, 0)
  for (lane <- 0 until K) rf.io.vr_w_data(1)(lane) := outBuf(lane).asUInt

  // ==========================================================================
  // Window entry (the frontend streams into the window)
  // ==========================================================================
  val mmaRangeBad =
    (dec.family === OpFamily.MMA) &&
    (dec.rd >= (L / 4).U || dec.rs1 >= L.U || dec.rs2 >= L.U ||
     (dec.rs3 =/= 0.U && dec.rs3 >= (L / 4).U))
  val classSize =
    MuxLookup(dec.mem_width, L.U)(Seq(1.U -> (L / 2).U, 2.U -> (L / 4).U))
  val memRangeBad =
    (dec.family === OpFamily.LD || dec.family === OpFamily.ST) &&
    (dec.rd >= classSize)
  val illegalCond =
    decoder.io.illegal ||
    (dec.family === OpFamily.MMA && dec.mma_reset) ||
    ((dec.family === OpFamily.LD || dec.family === OpFamily.ST) && dec.rs1 > 3.U) ||
    (dec.family =/= OpFamily.NOP && dec.family =/= OpFamily.LD &&
     dec.family =/= OpFamily.ST && dec.family =/= OpFamily.MMA) ||
    mmaRangeBad || memRangeBad

  // ---- Entry reader/writer slot sets (hoisted to top level so the tag
  //      mask net update can share them; purely combinational). ----
  // vse capture-consumer detection: the source register's last writer was
  // an mma capture (`tagIsMma(lastWrTag(i))`).  NOTE: this must NOT require
  // the producer to still be a pending prior slot AND must NOT require its
  // tag to still be live (`tagValid`).  On silicon the mma's clctD can retire
  // the producer (freeing its tag) before the store word streams into the
  // window (the MIG's variable latency vs. the frontend's fetch cadence), so
  // the store would silently miss the capture: it would read the RF instead
  // of popping the capQ, the mma's capture entry would sit in capQ forever,
  // and `drain` would never assert (the intermittent pc=4 wedge).
  // `tagIsMma` is never cleared when a tag is freed (only on flush), so the
  // freed producer tag still records that an mma produced VX[i] — exactly the
  // persistent semantics the old `vxWrCap(i)` flag provided.
  val entSrmask = readSlotsL(dec.family.asUInt, dec.rd, dec.rs1, dec.rs2,
    dec.mem_width(1, 0), dec.rs3, dec.family === OpFamily.MMA && dec.mma_last)
  val entIsCapSource = WireDefault(false.B)
  for (i <- 0 until L) {
    when (entSrmask(i) && wrCap(i)) { entIsCapSource := true.B }
  }
  val entIsCapStore = dec.family === OpFamily.ST && entIsCapSource
  val entSwmask = writeSlotsL(dec.family.asUInt, dec.rd, dec.mem_width(1, 0))

  io.instr_ready := !wFull && !illegalAny && tagNotFull

  when (io.instr_valid && !wFull && !illegalAny && tagNotFull) {
    val t = wTail
    val tg = tagTail
    slots(t).valid      := true.B
    slots(t).unit       := Mux(dec.family === OpFamily.LD || dec.family === OpFamily.ST,
      DispatchUnit.DMA, Mux(dec.family === OpFamily.MMA, DispatchUnit.MMALU, DispatchUnit.NONE))
    slots(t).family     := dec.family.asUInt
    slots(t).rd         := dec.rd
    slots(t).rs1        := dec.rs1
    slots(t).rs2        := dec.rs2
    slots(t).vs3        := dec.rs3
    slots(t).memWidth   := dec.mem_width(1, 0)
    slots(t).sect       := dec.rs1(1, 0)
    slots(t).off        := dec.mem_off
    slots(t).isStore    := dec.family === OpFamily.ST
    slots(t).isMmaLast  := dec.family === OpFamily.MMA && dec.mma_last
    slots(t).illegal    := illegalCond
    slots(t).issued     := false.B
    slots(t).done       := dec.family === OpFamily.NOP

    // ---- L1 tag allocation.  Only real (non-NOP) entries allocate a
    //      tag: a NOP is `done` at entry and never completes, so allocating for
    //      it would leak one tag per NOP until `tagCount` hit T and stalled
    //      entry forever.  `priorSnap` is the tagValid snapshot taken just
    //      BEFORE this allocation (reading the register yields the pre-update
    //      value, so it records exactly the tags live ahead of this one).
    //      `tagValid(tg)` is set by the net tag update below (collision-safe
    //      with a same-cycle completion clear). ----
    when (dec.family =/= OpFamily.NOP) {
      tagIsMma(tg)      := dec.family === OpFamily.MMA
      tagIsLast(tg)     := dec.family === OpFamily.MMA && dec.mma_last
      tagFed(tg)        := false.B   // fresh allocation: not yet fed
      slots(t).tag       := tg
      slots(t).priorSnap := tagValid.asUInt
      tagTail := tagTail + 1.U
      // newest writer tag + writer kind per register
      for (i <- 0 until L) {
        when (entSwmask(i)) {
          lastWrTag(i) := tg
          wrCap(i)     := dec.family === OpFamily.MMA
        }
      }
    }

    slots(t).useCapData := entIsCapStore

    // Scoreboard registration (tags, not slot masks).  A capture-consumer
    // store does NOT read the RF — its store data is the capture latched out
    // of the capQ at its issue (the DMA R2L `Mux(storeDataValid,
    // reqStoreData, rf_r_vr_data)`) — so it registers no reader bit.
    // Registering one made the next mma that reuses the destination vd wait
    // for the store's entire DMA to retire, serializing the body to one feed
    // per capture latency (~80 cycles).  The RAW on tagWrMask still orders the
    // store after its producer's capture, so capture order is unchanged.  The
    // set bits are folded into the net tag-mask update below.
    wTail  := wTail + 1.U
  }

  // ==========================================================================
  // Window retirement (the head retires when done)
  // ==========================================================================
  when (slots(wHead).valid && slots(wHead).done) {
    slots(wHead).valid := false.B
    wHead  := wHead + 1.U
  }

  // wCount accounting MUST net an entry and a retire in the same cycle.  With
  // two separate `when` blocks the later retire's `wCount := wCount - 1`
  // overwrites the entry's `wCount := wCount + 1`, losing one count per
  // coincide and eventually underflowing the counter (seen as wcount=0xF on
  // silicon).  Compute the net explicitly.
  val windowEntry  = io.instr_valid && !wFull && !illegalAny && tagNotFull
  val windowRetire = slots(wHead).valid && slots(wHead).done
  when (windowEntry && windowRetire) {
    wCount := wCount          // net zero (one in, one out)
  } .elsewhen (windowEntry) {
    wCount := wCount + 1.U
  } .elsewhen (windowRetire) {
    wCount := wCount - 1.U
  }

  // ==========================================================================
  // Illegal: stop issue, pulse, flush (window + scoreboard + queues).  The
  // in-flight DMA/MMALU operations complete harmlessly afterwards.
  // ==========================================================================
  io.illegal_out := illegalAny
  when (illegalAny) {
    for (s <- 0 until W) slots(s).valid := false.B
    // L1 tag ring flush
    for (q <- 0 until T) {
      tagValid(q)  := false.B
      tagIsMma(q)  := false.B
      tagIsLast(q) := false.B
      tagFed(q)    := false.B
    }
    for (i <- 0 until L) {
      tagWrMask(i) := 0.U
      tagRdMask(i) := 0.U
      lastWrTag(i) := 0.U
      wrCap(i)     := false.B
    }
    tagCount := 0.U
    compValid := false.B
    for (q <- 0 until DMAQ_DEPTH) { dmaQ(q).valid := false.B; dmaQ(q).inFlight := false.B }
    for (q <- 0 until CAPQ_DEPTH) capQ(q).valid := false.B
    for (q <- 0 until FIFO_DEPTH) { fifo(q).isMmaLast := false.B }
    wCount    := 0.U
    wHead     := wTail
    dmaQHead  := dmaQTail
    dmaQCount := 0.U
    capQHead  := capQTail
    capQCount := 0.U
    fifoHead  := fifoTail
    fifoCount := 0.U
  }

  // Session reset (frontend start pulse): same flush as illegal, plus the
  // boundary countdown, so a wedged/aborted session's leftover state can
  // never carry into the next run.  The FULL slot contents must be cleared
  // (valid, done, unit, isMmaLast, issued, etc.) — a stale slot with done=1
  // or unit=NONE from a previous session otherwise corrupts the scoreboard's
  // pending-MMA detection and lets the mma_last overtake the mma.
  when (io.session_reset) {
    for (s <- 0 until W) {
      slots(s).valid      := false.B
      slots(s).done       := false.B
      slots(s).issued     := false.B
      slots(s).unit       := DispatchUnit.NONE
      slots(s).isMmaLast  := false.B
      slots(s).family     := 0.U
      slots(s).rd         := 0.U
      slots(s).rs1        := 0.U
      slots(s).rs2        := 0.U
      slots(s).vs3        := 0.U
      slots(s).memWidth   := 0.U
      slots(s).sect       := 0.U
      slots(s).off        := 0.U
      slots(s).isStore    := false.B
      slots(s).illegal    := false.B
      slots(s).useCapData := false.B
    }
    // L1 tag ring flush
    for (q <- 0 until T) {
      tagValid(q)  := false.B
      tagIsMma(q)  := false.B
      tagIsLast(q) := false.B
      tagFed(q)    := false.B
    }
    for (i <- 0 until L) {
      tagWrMask(i) := 0.U
      tagRdMask(i) := 0.U
      lastWrTag(i) := 0.U
      wrCap(i)     := false.B
    }
    tagCount := 0.U
    compValid := false.B
    for (q <- 0 until DMAQ_DEPTH) { dmaQ(q).valid := false.B; dmaQ(q).inFlight := false.B }
    for (q <- 0 until CAPQ_DEPTH) capQ(q).valid := false.B
    for (q <- 0 until FIFO_DEPTH) { fifo(q).isMmaLast := false.B }
    wCount      := 0.U
    wHead       := wTail
    dmaQHead    := dmaQTail
    dmaQCount   := 0.U
    capQHead    := capQTail
    capQCount   := 0.U
    fifoHead    := fifoTail
    fifoCount   := 0.U
    // Start the boundary countdown (4K-1): this gates the first mma feed for
    // the full countdown and fires the keep=0 PE-reset pulse at the 2K offset
    // while the array is idle, so a leftover accumulator from a previous
    // session cannot contaminate this one (the doubled-capture silicon bug).
    boundaryCnt := (6 * K - 1).U
  }

  // ==========================================================================
  // DMA unit: the queue head drives the DMA; the done completes the slot.
  // ==========================================================================
  val dmaHead = dmaQ(dmaQHead)
  dma.io.req_valid  := dmaHead.valid && !dmaHead.inFlight
  dma.io.req_dir    := dmaHead.dir
  dma.io.req_width  := dmaHead.dmaWidth
  dma.io.req_rf_addr := dmaHead.rfAddr
  dma.io.req_l3_addr := dmaHead.l3Addr
  dma.io.req_store_data_valid := dmaHead.storeDataValid
  for (lane <- 0 until K) dma.io.req_store_data(lane) := dmaHead.storeData(lane)

  when (dma.io.req_ready && dmaHead.valid && !dmaHead.inFlight) {
    dmaHead.inFlight := true.B
  }

  io.fetch_fill_done := dma.io.instr_valid && dmaHead.valid && dmaHead.inFlight && dmaHead.dir === 3.U
  io.fetch_fill_data := dma.io.instr_out
  io.fetch_busy      := dma.io.busy

  val dmaDoneHead = dma.io.done && dmaHead.valid && dmaHead.inFlight
  when (dmaDoneHead) {
    // P2: the DMA slot already retired at issue (dmaQ enqueue), so completion
    // no longer touches the window (and never the fetch fill's placeholder
    // entry).  L1 tag free: this DMA completes → free the carried tag.
    // The tagValid clear and the tagWrMask/tagRdMask bit clears are handled by
    // the net tag updates below.  Fetch fills (dir=3) never allocate a tag
    // (placeholder tag=0), so they must not free one.
    dmaHead.valid := false.B
    dmaQHead  := dmaQHead + 1.U
  }

  // ---- Fetch fills join the DMA queue (when no DMA instruction issues this
  //      tick — single-writer tail) ----
  val dmaIssueAny = (0 until W).map(s => issueSel(s) && slots(s).unit === DispatchUnit.DMA)
    .reduce(_ || _)
  val fillPending = RegInit(false.B)
  val fillPush = io.fetch_fill_req && !fillPending && !dmaQFull && !dmaIssueAny
  when (fillPush) {
    dmaQ(dmaQTail).valid := true.B
    dmaQ(dmaQTail).inFlight := false.B
    dmaQ(dmaQTail).tag := 0.U
    dmaQ(dmaQTail).dir := 3.U
    dmaQ(dmaQTail).dmaWidth := 0.U
    dmaQ(dmaQTail).rfAddr := 0.U
    dmaQ(dmaQTail).l3Addr := io.fetch_fill_addr
    dmaQ(dmaQTail).rdMask := 0.U
    dmaQ(dmaQTail).storeDataValid := false.B
    dmaQTail  := dmaQTail + 1.U
    fillPending := true.B
  }
  when (io.fetch_fill_done) { fillPending := false.B }

  // Net DMA-queue occupancy: a DMA issue (or a fetch fill) and a head
  // completion can collide, and both advance their pointers.  Two independent
  // `dmaQCount :=` assignments would let the later one win and drift the
  // count, mis-reporting `dmaQFull` or underflowing the counter.  Compute the
  // net explicitly.  The flush blocks above (illegal / session_reset) keep
  // priority by clearing the count only when neither is asserted here.
  val dmaEnq = dmaIssueAny || fillPush
  when (!illegalAny && !io.session_reset) {
    when (dmaEnq && dmaDoneHead) { dmaQCount := dmaQCount }
    .elsewhen (dmaEnq)           { dmaQCount := dmaQCount + 1.U }
    .elsewhen (dmaDoneHead)      { dmaQCount := dmaQCount - 1.U }
  }

  // ==========================================================================
  // L1 tag ring occupancy (P1a shadow).  Tags are allocated at window entry
  // for NON-NOP words only (+1) and freed on an MMA clct or a DMA completion
  // (-1 each; both can land on the same cycle).  Correctness rests on the
  // `!tagValid(tagTail)` allocation guard (see `tagNotFull`) plus `tagCount`;
  // there is no in-order head walk.  The flush blocks above win on
  // illegal/reset.  At W=16 the live count never approaches T.
  // ==========================================================================
  val tagAlloc = windowEntry && (dec.family =/= OpFamily.NOP)
  val tagMmaFree = compValid   // completion is delayed 1 cycle (BRAM read)
  val tagDmaFree = dmaDoneHead && dmaHead.dir =/= 3.U
  // 0/1/2 frees can land on one cycle (an MMA clctD and a DMA done).  A bare
  // `1.U + 1.U` was truncating the dual-free to 0 (1-bit literal sum), losing
  // tagCount decrements and wedging `drain` (tagCount stuck above zero) — make
  // the width explicit.
  val tagFreeNum = tagMmaFree.asUInt +& tagDmaFree.asUInt
  val tgAlloc      = tagTail
  val mmaDoneTag   = compTag

  when (!illegalAny && !io.session_reset) {
    tagCount := tagCount + Mux(tagAlloc, 1.U, 0.U) - tagFreeNum
  }

  // ---- L1 tag mask net set/clear (P1a shadow).  Entry SETS bit `tgAlloc`;
  //      MMA completion, MMALU feed (reader release) and DMA completion CLEAR
  //      bits.  All sources are folded into ONE connect per register so a
  //      same-cycle set and clear can no longer race through last-connect (the
  //      later entry assignment used to drop the completion's clear).  The
  //      cap-store (reader) and dir=3/fetch (DMA) exclusions are preserved. ----
  val mmaDoneWmask = (0xF.U << (compVd << 2))(L - 1, 0)
  val mmaFeedRmask = readSlotsL(OpFamily.MMA.asUInt, feedRd, feedRs1, feedRs2, 0.U,
    feedVs3, feedMmaLast)
  // A vle of VE/VR writes SEVERAL VX slots (writeSlotsL expands by the load
  // width); clearing only `rfAddr` leaks the wider load's tag into the fixed
  // `priorSnap` hazard set forever (a same-tag RAW can never clear → deadlock).
  // Use the full write mask, mirroring the entry registration.
  val dmaVleWmask  = writeSlotsL(OpFamily.LD.asUInt, dmaHead.rfAddr, dmaHead.dmaWidth)
  // P2: the DMA window slot retired at issue, so the vse read mask is read
  // from the queue entry (captured at issue) rather than the (possibly reused)
  // slot.
  val dmaVseSrmask = dmaHead.rdMask
  when (!illegalAny && !io.session_reset) {
    for (i <- 0 until L) {
      val wrSet    = tagAlloc && entSwmask(i)
      val wrClrMma = compValid && mmaDoneWmask(i)
      val wrClrDma = dmaDoneHead && dmaHead.dir === 0.U && dmaVleWmask(i)
      val wrNext = (tagWrMask(i) | Mux(wrSet, (1.U << tgAlloc)(T - 1, 0), 0.U(T.W))) &
        ~Mux(wrClrMma, (1.U << mmaDoneTag)(T - 1, 0), 0.U(T.W)) &
        ~Mux(wrClrDma, (1.U << dmaHead.tag)(T - 1, 0), 0.U(T.W))
      tagWrMask(i) := wrNext
      // Mirror the old `vxWrCap` clear: once a vle's write fully retires (mask
      // empties) and no new writer registers this cycle, the register no longer
      // holds a live capture.  Set at entry (below) wins via the guard.
      when (wrClrDma && wrNext === 0.U && !wrSet) { wrCap(i) := false.B }

      val rdSet     = tagAlloc && entSrmask(i) && !entIsCapStore
      val rdClrFeed = feedValid && mmaFeedRmask(i)
      val rdClrDma  = dmaDoneHead && dmaHead.dir === 1.U &&
        !dmaHead.storeDataValid && dmaVseSrmask(i)
      tagRdMask(i) := (tagRdMask(i) | Mux(rdSet, (1.U << tgAlloc)(T - 1, 0), 0.U(T.W))) &
        ~Mux(rdClrFeed, (1.U << feedTag)(T - 1, 0), 0.U(T.W)) &
        ~Mux(rdClrDma, (1.U << dmaHead.tag)(T - 1, 0), 0.U(T.W))
    }
  }

  // ---- L1 tagValid net set/clear (collision-safe).  Entry SETS the allocated
  //      tag; MMA completion and DMA completion CLEAR.  All sources are folded
  //      into ONE connect per entry so a same-cycle set and clear can no longer
  //      race through last-connect.  At W=16 the ring never wraps, but the net
  //      form keeps this correct once P2 frees tags out of order. ----
  when (!illegalAny && !io.session_reset) {
    for (q <- 0 until T) {
      val validSet = tagAlloc && tgAlloc === q.U
      val validClr = (tagMmaFree && mmaDoneTag === q.U) ||
                     (tagDmaFree && dmaHead.tag === q.U)
      when (validSet)      { tagValid(q) := true.B }
      .elsewhen (validClr) { tagValid(q) := false.B }
    }
  }

  // ==========================================================================
  // RF: DMA write ports + external read
  // ==========================================================================
  rf.io.vx_r_addr(2) := dma.io.rf_r_vx_addr
  rf.io.ve_r_addr(0) := dma.io.rf_r_ve_addr
  rf.io.vr_r_addr(0) := dma.io.rf_r_vr_addr
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

  // External ports unused; tie off
  rf.io.ext_r_addr := io.dbg_vx_addr
  rf.io.ext_w_en   := false.B
  rf.io.ext_w_addr := 0.U
  for (lane <- 0 until K) rf.io.ext_w_data(lane) := 0.U
  io.dbg_vx_data := rf.io.ext_r_data

  // ==========================================================================
  // AXI master
  // ==========================================================================
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

  // ==========================================================================
  // Register file instantiation
  // ==========================================================================
  // (declared above — `val rf`)

  // ==========================================================================
  // Debug counters
  // ==========================================================================
  val mmaAccCnt = RegInit(0.U(16.W))
  val clctCnt   = RegInit(0.U(16.W))
  val dmaDoneCnt = RegInit(0.U(16.W))
  val traj      = RegInit(VecInit(Seq.fill(16)(0.U(6.W))))
  val trajSnap  = RegInit(VecInit(Seq.fill(16)(0.U(6.W))))
  val cycleCnt  = RegInit(0.U(32.W))
  val macCnt    = RegInit(0.U(32.W))
  when (feedValid)                                { mmaAccCnt := mmaAccCnt + 1.U }
  when (mmalu.io.clct)                            { clctCnt := clctCnt + 1.U }
  when (dmaDoneHead)                              { dmaDoneCnt := dmaDoneCnt + 1.U }
  for (i <- 0 until 15) traj(i) := traj(i + 1)
  traj(15) := Cat(mmalu.io.ctrl.keep, mmalu.io.dbg_dat_clct, mmalu.io.dbg_cnt)
  when (boundaryPulse) { trajSnap := traj }
  when (io.session_reset) {
    cycleCnt := 0.U
    macCnt   := 0.U
  }.otherwise {
    cycleCnt := cycleCnt + 1.U
    // only USEFUL feeds: the mma.last feed injects zeros, so exclude it
    when (feedValid && !feedMmaLast) { macCnt := macCnt + (K * K).U }
  }
  io.dbg_mma_acc := mmaAccCnt
  io.dbg_clct    := clctCnt
  io.dbg_win     := fifoCount(1, 0)
  io.dbg_dma_done := dmaDoneCnt
  io.dbg_wcount   := wCount
  io.dbg_bnd      := boundaryCnt
  io.dbg_dmaq     := dmaQCount
  io.dbg_capq     := capQCount
  io.dbg_mma_out0 := mmalu.io.out(0).asUInt
  io.dbg_mma_out1 := mmalu.io.out(1).asUInt
  io.dbg_cnt      := mmalu.io.dbg_cnt
  io.dbg_in_a0    := mmaluInA(0).asUInt
  io.dbg_feed_rs1 := feedRs1
  io.dbg_feed_last := feedMmaLast
  io.dbg_slot_rs1  := slots(feedSlot).rs1
  io.dbg_mma_pend := false.B
  io.dbg_s2_valid := slots(2).valid
  io.dbg_s2_done  := slots(2).done
  io.dbg_s2_unit  := slots(2).unit.asUInt
  io.dbg_s2_last  := slots(2).isMmaLast
  val slotsAny = (0 until W).map(i => slots(i).valid).reduce(_ || _)
  io.drain := !slotsAny && !dma.io.busy && dmaQCount === 0.U &&
    fifoCount === 0.U && capQCount === 0.U && boundaryCnt === 0.U &&
    tagCount === 0.U
  io.dbg_traj    := trajSnap
  io.dbg_cycles  := cycleCnt
  io.dbg_mac     := macCnt
}
