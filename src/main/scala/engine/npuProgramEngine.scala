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
//  A round is K accumulate feeds + mma.last.  Only the mma.last captures: its
//  clct writes the settled running-sum column to VR[vd]; a trailing vse reads
//  VR[vd] directly and stores it (there is no capture queue).
//  Register reuse (vd rings, operand slots) is entirely the software's job;
//  the scoreboard enforces the hazards.
//
//  Consecutive matmul rounds PIPELINE (no 6K-1 boundary stall): the mma.last
//  feed carries the keep=0 PE reset for the next round, and the collector
//  snapshots each round's running-sum column on that reset wave.
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
  //      ring.  L2 no longer uses the ring for operand readiness: that is now
  //      the per-VX-register counter scoreboard below.  The ring still tracks
  //      occupancy (tagCount/drain), the fed flag, and the ring metadata.
  val CAPTURE_DELAY = 16
  val T = 1 << log2Ceil(2 * K + 2 * CAPTURE_DELAY)
  val TAG_IDX = log2Ceil(T)
  // ---- L2 count-based scoreboard.  Per-VX-register monotonic issue/done
  //      counters.  WC is sized so the in-flight span (< T) never wraps; the
  //      crossing test uses a modular difference of 0/1/2 because done can
  //      advance at most twice per cycle and the satisfied latch is monotonic.
  val WC = 9
  // The completion queues carry one entry per in-flight op, so they must be
  // deep enough to hold the whole tag ring or feed issue stalls below peak
  // (the vd FIFO also carries the K×32-bit `accum` column — decision (a)).
  val FIFO_DEPTH = T          // in-flight feeds (vd FIFO, carries accum)
  val DMAQ_DEPTH = 8
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

    // ---- Debug: RF VX read (testbench only) ----
    val dbg_vx_addr = Input(UInt(5.W))
    val dbg_vx_data = Output(Vec(K, UInt(N.W)))

    // ---- Debug: mma issue/collect counters (silicon bring-up) ----
    val dbg_mma_acc = Output(UInt(16.W))   // mma instructions accepted
    val dbg_clct    = Output(UInt(16.W))   // clct pulses captured
    val dbg_win     = Output(UInt(2.W))    // capture FIFO occupancy (low bits)
    val dbg_dma_done = Output(UInt(16.W))  // DMA requests completed
    val dbg_wcount   = Output(UInt(5.W))   // dispatch window occupancy
    val dbg_bnd      = Output(UInt(9.W))   // boundary countdown (0 when idle)
    val dbg_dmaq     = Output(UInt(4.W))   // dma queue occupancy
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
    val issued     = Bool()
    val done       = Bool()
    // ---- L1 tag ring (P1a shadow).  `tag` is the allocated ring index. ----
    val tag        = UInt(TAG_IDX.W)
    // ---- L2 count scoreboard: per-operand targets + satisfied latches.
    //      Read operands: rs1, rs2 and vs3's four VX aliases (MMA), or the
    //      width-expanded rd (store).  Write operands: the up-to-four VX
    //      aliases of rd.  Unused entries are masked by their valid set
    //      recomputed from the slot fields, so no per-entry valid bit is
    //      stored. ----
    val rdTgt      = Vec(6, UInt(WC.W))
    val rdSat      = Vec(6, Bool())
    val wrTgt      = Vec(4, UInt(WC.W))
    val wrSat      = Vec(4, Bool())
    // L2c: one-cycle latch that seeds rdSat/wrSat from the registered targets
    // on the cycle after entry (splits the entry -> reached -> sat path).
    val initPending = Bool()
    // mma.last ordering: all prior non-last MMAs must have fed (§13).
    val lastTgt    = UInt(WC.W)
    val lastSat    = Bool()
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
    // VR-store snapshot: a vse32 latches VR[vd] at ITS ISSUE (after the RAW
    // wait) and the DMA writes this frozen copy.  This is a per-store snapshot
    // at the read point, NOT the old producer capture queue; it removes the
    // WAR hazard where a later mma.last reusing the same vd overwrites the VR
    // before the DMA samples it.  No WAR reader/counter is needed.
    val storeDataValid = Bool()
    val storeData = Vec(K, UInt((4 * N).W))
  }

  // ==========================================================================
  // L1 scoreboard: a T-entry tag ring (P1b).  The old W-bit per-slot masks
  // (vxWrMask/vxRdMask/vxWrCap) were retired here — tags are allocated at
  // window entry, freed at completion, and give the hazard set for free
  // without tying in-flight capacity to the W-slot issue window.
  // VE[i] → VX[2i..2i+1]; VR[i] → VX[4i..4i+3] (alias-aware, uniform).
  //   tagValid(t) : ring entry t holds a live (allocated, not completed) tag
  // Operand hazards are tracked by the L2 counters below, not per-tag masks.
  // ==========================================================================
  val tagTail = RegInit(0.U(TAG_IDX.W))
  val tagCount = RegInit(0.U((TAG_IDX + 1).W))
  val tagValid = RegInit(VecInit(Seq.fill(T)(false.B)))
  // P2 retire-at-feed: `tagFed` marks an MMA allocation whose operands have
  // been read at MMA feed.  It is NOT set for DMA ops.
  // The mma.last ordering gate (§13) must
  // block only on *prior MMAs that have not yet fed* — waiting for their
  // completion would serialize the whole reduce round's tail behind the
  // ~2n+CAPTURE_DELAY capture latency.  `tagValid` still means "not completed"
  // because it drives the RAW/WAR hazard snapshot and must stay live until the
  // write is visible.
  val tagFed   = RegInit(VecInit(Seq.fill(T)(false.B)))
  // ---- L2 count-based scoreboard state (replaces tagWrMask/tagRdMask and
  //      the per-slot T-bit priorSnap).  For each VX[i]: writes issued/done
  //      and reads issued/done.  A reader waits until wrDone reaches the
  //      wrIss value captured at its entry; a writer waits until rdDone
  //      reaches the rdIss value captured at its entry.
  val wrIss  = RegInit(VecInit(Seq.fill(L)(0.U(WC.W))))
  val wrDone = RegInit(VecInit(Seq.fill(L)(0.U(WC.W))))
  val rdIss  = RegInit(VecInit(Seq.fill(L)(0.U(WC.W))))
  val rdDone = RegInit(VecInit(Seq.fill(L)(0.U(WC.W))))
  // Global non-last-MMA issue/feed counts for the §13 mma.last ordering gate.
  // MMALU feeds happen in strict MMA program order, so a mma.last entry can
  // snapshot the number of prior non-last entries and wait until that many
  // non-last feeds have happened.  Later MMAs cannot feed before the
  // mma.last (in-order issue), so this is exactly the prior-pending set.
  val mmaIssCnt = RegInit(0.U(WC.W))
  val mmaFedCnt = RegInit(0.U(WC.W))
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
      // The mma.last is the round-capture marker: the engine ZERO-FORCES its
      // A/B operands (it must not read stale VX[0]), so only its C (vs3) can
      // be a real read.  Counting the encoded vs1/vs2 as reads makes it
      // RAW-wait on every prior mma's destination (VX[0]) completion, which
      // serialises the reduce round's whole tail behind the capture latency.
      val vs3Mask = Mux(vs3 === 0.U, 0.U(L.W), (0xF.U << (vs3 << 2))(L - 1, 0))
      m := Mux(isMmaLast, vs3Mask,
        (1.U << rs1) | (1.U << rs2) | vs3Mask)
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

  // Only the round's mma.last writes its destination VR in reduce-only mode;
  // the accumulate feeds leave the array result in the PEs and must NOT
  // register a WR obligation (wrIss would otherwise run ahead of wrDone and a
  // trailing vse could never observe its capture write).
  def writeSlotsL(family: UInt, rd: UInt, memWidth: UInt = 0.U,
                  isMmaLast: Bool = false.B): UInt = {
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
      when (isMmaLast) { m := (0xF.U << (rd << 2))(L - 1, 0) }
    }
    m
  }

  // Canonical per-operand VX indices for the count scoreboard.  The same
  // expansion as `readSlotsL`/`writeSlotsL`, but positional so a target can be
  // stored per operand without storing an index.  Reads: MMA reads rs1, rs2
  // and vs3's four VX aliases (mma.last zero-forces A/B, but still reads its
  // C = vs3, if any); ST reads its width-expanded rd.  Writes: only the
  // round-capturing mma.last writes rd's four VX aliases; LD writes its
  // width-expanded rd.  Unused ordinals are masked by the returned valid
  // vector.
  def readOperands(family: UInt, rd: UInt, rs1: UInt, rs2: UInt, memWidth: UInt,
                   vs3: UInt, isMmaLast: Bool): (Vec[UInt], Vec[Bool]) = {
    val regs   = Wire(Vec(6, UInt(5.W)))
    val valids = Wire(Vec(6, Bool()))
    for (j <- 0 until 6) { regs(j) := 0.U; valids(j) := false.B }
    when (family === OpFamily.MMA.asUInt) {
      val live = !isMmaLast
      regs(0) := rs1
      regs(1) := rs2
      valids(0) := live
      valids(1) := live
      for (j <- 0 until 4) {
        regs(2 + j)   := (vs3 << 2) + j.U
        valids(2 + j) := vs3 =/= 0.U
      }
    } .elsewhen (family === OpFamily.ST.asUInt) {
      regs(0) := rd
      valids(0) := true.B
      when (memWidth === 1.U) {
        regs(0) := rd << 1
        regs(1) := (rd << 1) + 1.U
        valids(1) := true.B
      } .elsewhen (memWidth === 2.U) {
        for (j <- 0 until 4) {
          regs(j) := (rd << 2) + j.U
          valids(j) := true.B
        }
      }
    }
    (regs, valids)
  }

  def writeOperands(family: UInt, rd: UInt, memWidth: UInt,
                    isMmaLast: Bool = false.B): (Vec[UInt], Vec[Bool]) = {
    val regs   = Wire(Vec(4, UInt(5.W)))
    val valids = Wire(Vec(4, Bool()))
    for (j <- 0 until 4) { regs(j) := 0.U; valids(j) := false.B }
    when (family === OpFamily.LD.asUInt) {
      regs(0) := rd
      valids(0) := true.B
      when (memWidth === 1.U) {
        regs(0) := rd << 1
        regs(1) := (rd << 1) + 1.U
        valids(1) := true.B
      } .elsewhen (memWidth === 2.U) {
        for (j <- 0 until 4) {
          regs(j) := (rd << 2) + j.U
          valids(j) := true.B
        }
      }
    } .elsewhen (family === OpFamily.MMA.asUInt) {
      when (isMmaLast) {
        for (j <- 0 until 4) {
          regs(j) := (rd << 2) + j.U
          valids(j) := true.B
        }
      }
    }
    (regs, valids)
  }

  // A target has been reached when the monotonic done counter has caught up.
  // `done - tgt` is modular (WC bits); done advances at most twice per cycle
  // and the satisfied latch is monotonic, so the first crossing is 0/1/2.
  def reached(done: UInt, tgt: UInt): Bool = {
    val d = (done - tgt)(WC - 1, 0)
    d === 0.U || d === 1.U || d === 2.U
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
  // Round control.  Consecutive matmul rounds PIPELINE: there is no inter-round
  // 6K-1 idle countdown.  A round is reset by the `mma.last` feed itself (its
  // data is zero-forced, so it contributes nothing) and the next round's first
  // feed immediately reopens the accumulator with `keep=0`.  Note that the
  // control path to the PEs is one cycle deeper than the operand path: a
  // `keep=0` driven on feed F resets the PE with the operands of feed F+1.
  // Resetting on `mma.last` therefore clears the PEs exactly on the next
  // round's first feed (all K feeds accumulate).  A leading first-feed reset
  // would instead reset on the *second* feed and silently drop the first.
  // ==========================================================================
  // There is no inter-round boundary countdown any more (rounds pipeline);
  // dbg_bnd is kept as a constant-0 status bit for the frontend/debug map.

  // ==========================================================================
  // MMALU
  // ==========================================================================
  val mmalu = Module(new MMALU(new MMPE(N), K, N, 4 * N, CAPTURE_DELAY))
  // The clct delayed by the capture-settle margin (CAPTURE_DELAY, defined with
  // `T` above — see the completion block).
  val clctD = ShiftRegister(mmalu.io.clct, CAPTURE_DELAY, false.B, true.B)
  val mmaluInA = Wire(Vec(K, SInt(N.W)))
  val mmaluInB = Wire(Vec(K, SInt(N.W)))
  val mmaluInAccum = Wire(Vec(K, SInt((4 * N).W)))

  // ==========================================================================
  // Per-slot readiness (combinational)
  // ==========================================================================
  // The mma_last must wait for any pending non-last MMA that precedes it, or
  // it can overtake the mma (it has no operand deps), start the boundary
  // countdown, and gate the mma out entirely (silicon: the mma never fed, the
  // array stayed 0).  Only PRIOR mmas count — a global "any pending mma" would
  // deadlock multi-round sessions (round-2 mmas are boundary-gated and would
  // hold the mma_last off forever).  L2 tracks this with `mmaIssCnt` snapshot
  // in the slot and `mmaFedCnt` (see the gate below).
  val slotsReady = Wire(Vec(W, Bool()))
  val slotUnitOk = Wire(Vec(W, Bool()))

  // ---- MMALU feed pipeline (stage 2 registers), declared before the
  //      readiness loop so the MMALU queue reservation can account for the
  //      in-flight feed (a 1-stage feed pipeline must never overflow the
  //      FIFO).  Stage 1 = issue selection; stage 2 = RF read + accum mux +
  //      FIFO enq + MMALU drive.  This halves the issue->RF-read->FIFO logic
  //      depth (the critical 200 MHz path on xcvu9p). ----
  // VR-store issue-time snapshot: the dmaQ entry latches VR[vd] on the issue
  // cycle (after the RAW gate) through the RF VR read port 0.
  val issueVrStore  = WireDefault(false.B)
  val issueVrStoreVd = WireDefault(0.U(5.W))
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

  // Has any MMA fed in this session?  Idle keep=0 is only used to clear the
  // PEs before a session's first feed.
  val feedSeen = RegInit(false.B)
  when (io.session_reset) {
    feedSeen := false.B
  } .elsewhen (feedValid) {
    feedSeen := true.B
  }
  // Per-feed `keep` (active low reset).  This is the round-open control, but
  // it must be presented one feed EARLY: the ControlUnit path to the PEs is
  // one cycle deeper than the DataFeeder/operand path (reg(0) shift stage in
  // ControlUnit + pipe_ctrl vs pipe_a/pipe_b).  A keep=0 driven on feed F is
  // therefore applied to the operands of feed F+1.  Hence:
  //   * the trailing mma.last of a round drives keep=0, which resets the PEs
  //     exactly on the NEXT round's first feed (all K feeds accumulate, and
  //     the previous round's snapshot is taken on the reset wave before the
  //     reset is visible);
  //   * the idle before a session's first feed drives keep=0, which resets on
  //     that first feed (clean start, no cross-session residue);
  //   * every other feed keeps accumulating (keep=1).
  // Driving keep=0 on the literal round-first feed instead would apply the
  // reset on the SECOND feed and drop the first contribution (and the capture
  // would carry the previous round's residue), so the reset is attached to
  // the preceding mma.last as above.
  val feedReset = (feedValid && feedMmaLast) ||
                  (!feedValid && !feedSeen)

  // (No inter-round boundary gate: consecutive rounds pipeline.  The mma.last
  // ordering safety is enforced by the strict program-order feed invariant
  // asserted below.)

  for (s <- 0 until W) {
    val sl = slots(s)
    val (rdRegs, rdValidRaw) = readOperands(sl.family, sl.rd, sl.rs1, sl.rs2,
      sl.memWidth, sl.vs3, sl.isMmaLast)
    // A capture store reads VR[vd] directly from the RF, so it must wait for
    // the mma.last capture write (RAW through wrDone).  This is the ordering
    // that guarantees the vse never samples a stale VR.
    val rdValid = (0 until 6).map(j => rdValidRaw(j))
    val (wrRegs, wrValid) = writeOperands(sl.family, sl.rd, sl.memWidth,
      sl.isMmaLast)
    val rawOk = WireDefault(true.B)
    val warOk = WireDefault(true.B)
    for (j <- 0 until 6) {
      when (rdValid(j) && !sl.rdSat(j)) { rawOk := false.B }
    }
    for (j <- 0 until 4) {
      when (wrValid(j) && !sl.wrSat(j)) { warOk := false.B }
    }
    // ---- L2c satisfied latches: the entry path only snapshots the targets
    //      (decode -> counter mux -> target register, no subtract).  One cycle
    //      later `initPending` seeds rdSat/wrSat from the registered target +
    //      the done counters; afterwards the per-cycle monotonic set keeps a
    //      target that was not yet met at init waiting.  Readiness only ever
    //      consults the registered sat latches, never the live decode.
    when (sl.initPending) {
      sl.initPending := false.B
      for (j <- 0 until 6) {
        sl.rdSat(j) := rdValid(j) &&
          reached(wrDone(rdRegs(j)), sl.rdTgt(j))
      }
      for (j <- 0 until 4) {
        sl.wrSat(j) := wrValid(j) &&
          reached(rdDone(wrRegs(j)), sl.wrTgt(j))
      }
    } .otherwise {
      for (j <- 0 until 6) {
        when (sl.valid && rdValid(j)) {
          sl.rdSat(j) := sl.rdSat(j) || reached(wrDone(rdRegs(j)), sl.rdTgt(j))
        }
      }
      for (j <- 0 until 4) {
        when (sl.valid && wrValid(j)) {
          sl.wrSat(j) := sl.wrSat(j) || reached(rdDone(wrRegs(j)), sl.wrTgt(j))
        }
      }
    }
    when (sl.valid && sl.isMmaLast) {
      sl.lastSat := sl.lastSat || reached(mmaFedCnt, sl.lastTgt)
    }
    val unitOk = WireDefault(false.B)
    when (sl.unit === DispatchUnit.DMA) {
      unitOk := !dmaQFull
    } .elsewhen (sl.unit === DispatchUnit.MMALU) {
      // Reserve the pending (stage-2) feed slot so the 1-stage feed pipeline
      // can never overflow the FIFO.
      unitOk := (fifoCount + Mux(feedValid, 1.U, 0.U)) < FIFO_DEPTH.U
    } .otherwise {
      unitOk := true.B
    }
    // Ordering: MMAs issue/feed strictly in program order (firstForUnit), and
    // the feed is a 1-cycle register after the issue with no backpressure, so
    // an mma.last can never overtake a prior non-last feed.  The explicit
    // lastSat ordering wait is therefore no longer needed; dropping it removes
    // ~3 idle cycles at every round boundary.
    val gateOk = true.B
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
    // Re-check readiness at the issue (same cycle): slotsReadyR is registered,
    // so the slotsReady gate above is one cycle stale.  Strict program-order
    // firstForUnit covers the mma.last ordering; no boundary gate.
    issueSel(s) := slotsReadyR(s) && slotUnitOk(s) &&
      !slots(s).issued && !slots(s).done && firstForUnit(s)
  }

  // ==========================================================================
  // Hard invariant: MMA feed order == program order, one in-order feed/cycle.
  // `firstForUnit` admits only the oldest not-yet-issued MMA of the unit, so
  // at most one MMA issues per cycle, and the feed is a 1-cycle register with
  // no backpressure — an mma.last therefore can never overtake a prior
  // non-last feed (the ordering the old lastSat gate used to enforce).  These
  // assertions make that contract explicit instead of relying on inspection.
  // ==========================================================================
  val mmaIssueSel = VecInit((0 until W).map(s =>
    issueSel(s) && slots(s).unit === DispatchUnit.MMALU))
  when (PopCount(mmaIssueSel) > 1.U) {
    assert(false.B, "feed-order invariant: more than one MMA issued in a cycle")
  }
  for (s <- 0 until W) {
    when (issueSel(s) && slots(s).unit === DispatchUnit.MMALU) {
      for (i <- 0 until W) {
        when (slots(i).valid && slots(i).unit === DispatchUnit.MMALU &&
              ringDist(i.U) < ringDist(s.U) && !slots(i).issued) {
          assert(false.B, "feed-order invariant: MMA issued out of program order")
        }
      }
    }
  }
  when (feedValid && feedMmaLast) {
    for (i <- 0 until W) {
      when (slots(i).valid && slots(i).unit === DispatchUnit.MMALU &&
            !slots(i).isMmaLast && slots(i).issued && !slots(i).done) {
        assert(false.B, "feed-order invariant: mma.last fed before a prior non-last MMA")
      }
    }
  }

  // ==========================================================================
  // Issue actions
  // ==========================================================================
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
        val isVrStore = isStore && slots(s).memWidth === 2.U
        issueVrStore   := isVrStore
        issueVrStoreVd := slots(s).rd
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
        // Freeze VR[vd] now (the store is RAW-satisfied; the RF read port 0 is
        // muxed to this store's address this cycle).  VX/VE stores keep the
        // normal DMA RF read path.
        dmaQ(dmaQTail).storeDataValid := isVrStore
        for (lane <- 0 until K) {
          dmaQ(dmaQTail).storeData(lane) :=
            Mux(isVrStore, rf.io.vr_r_data(0)(lane), 0.U)
        }
        // P2: the DMA slot retires at dmaQ enqueue; its tag carries the
        // completion obligation (freed at dmaDoneHead).
        slots(s).done := true.B
        dmaQTail := dmaQTail + 1.U
      } .otherwise { slots(s).done := true.B }   // DispatchUnit.NONE: retire defensively
    }
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

  // Per-feed round control (see the feedReset definition): the reset is
  // presented on the mma.last feed, one feed before the round it opens,
  // because the control path is one cycle deeper than the operand path.
  mmalu.io.ctrl.keep      := !feedReset
  mmalu.io.ctrl.busy      := feedValid
  mmalu.io.ctrl.use_accum := feedValid
  mmalu.io.session_reset  := io.session_reset

  for (lane <- 0 until K) {
    // in_a / in_b: read VX[vs1]/VX[vs2] on a normal mma feed; mma_last is a
    // session terminator marker and must inject ZEROS — reading VX[0] there
    // (its encoded vs1=vs2=0) silently feeds stale VX[0] data into the PEs
    // on silicon (sim only stays clean because VX[0] resets to 0).  This is
    // the same zero-forcing the vs3==0 accum path already does.
    mmaluInA(lane)     := Mux(feedValid && !feedMmaLast, rf.io.vx_r_data(0)(lane).asSInt, 0.S(N.W))
    mmaluInB(lane)     := Mux(feedValid && !feedMmaLast, rf.io.vx_r_data(1)(lane).asSInt, 0.S(N.W))
    // accum: read VR[vs3] whenever vs3 != 0, including on mma.last (the last
    // carries the round's C into the final capture).  vs3 == 0 is the
    // "no accumulator" encoding and is zero-forced.  mma.last's C is in its
    // read set (see readSlotsL/readOperands), so the read is ordered.
    mmaluInAccum(lane) := Mux(feedValid && feedVs3 =/= 0.U,
      rf.io.vr_r_data(1)(lane).asSInt, 0.S((4 * N).W))
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
  // The completion (FIFO pop, slot done, tag free, capture/VR write) all move
  // to the delayed tick so the chained vse reads the settled column.
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
  // Capture policy (reduce-only): the regular mmas accumulate in the array and
  // write NOTHING to the RF; only the round's FINAL mma.last clct commits the
  // accumulated column to VR[capVd].  A trailing vse then reads that VR.
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

  // ONLY the round's mma.last commits a capture.  The non-last feeds' clcts
  // still pop the vd FIFO / free tags (completion below), but they leave the
  // output register untouched.
  val capturePush = compValid && compLast

  when (capturePush) {
    capVd := compVd
    val capValue = Wire(Vec(K, SInt((4 * N).W)))
    for (lane <- 0 until K) {
      capValue(lane) := compOut(lane) + accumRd(lane)
      outBuf(lane)   := capValue(lane)
    }
  }

  // The RF write lands one tick after the delayed capture; wrDone is advanced
  // on THAT tick (not on compValid) so a waiting vse observes the write only
  // after the edge that stores outBuf into VR[capVd].
  clctPrev := capturePush

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
  val entIsMmaLast = dec.family === OpFamily.MMA && dec.mma_last
  val entSrmask = readSlotsL(dec.family.asUInt, dec.rd, dec.rs1, dec.rs2,
    dec.mem_width(1, 0), dec.rs3, entIsMmaLast)
  // A VR store (vse32) reads the mma capture directly.  It registers a RAW
  // target but NOT a reader obligation: gating the next round's capture write
  // on this store's whole DMA would serialize the body at the capture latency.
  // Non-VR stores keep the normal reader registration (vle WAR).
  val entIsVrStore = dec.family === OpFamily.ST && dec.mem_width(1, 0) === 2.U
  val entSwmask = writeSlotsL(dec.family.asUInt, dec.rd, dec.mem_width(1, 0),
    entIsMmaLast)
  val (entRdRegs, entRdValid) = readOperands(dec.family.asUInt, dec.rd, dec.rs1,
    dec.rs2, dec.mem_width(1, 0), dec.rs3, entIsMmaLast)
  val (entWrRegs, entWrValid) = writeOperands(dec.family.asUInt, dec.rd,
    dec.mem_width(1, 0), entIsMmaLast)

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
    //      entry forever.  `tagValid(tg)` is set by the net tag update below
    //      (collision-safe with a same-cycle completion clear). ----
    when (dec.family =/= OpFamily.NOP) {
      tagFed(tg)        := false.B   // fresh allocation: not yet fed
      slots(t).tag       := tg
      tagTail := tagTail + 1.U
    }

    // ---- L2c count-scoreboard entry.  Snapshot each operand's target from
    //      the issue counter BEFORE this instruction registers (a reader waits
    //      for the writes issued ahead of it, a writer for the reads ahead).
    //      This is the short entry stage: decode -> counter mux -> target
    //      register, with no `reached` subtract here.  The sat latches start
    //      false; `initPending` seeds them from the registered targets on the
    //      next cycle (see the per-slot readiness loop).
    for (j <- 0 until 6) {
      slots(t).rdTgt(j) := wrIss(entRdRegs(j))
      slots(t).rdSat(j) := false.B
    }
    for (j <- 0 until 4) {
      slots(t).wrTgt(j) := rdIss(entWrRegs(j))
      slots(t).wrSat(j) := false.B
    }
    slots(t).initPending := true.B
    slots(t).lastTgt := mmaIssCnt
    slots(t).lastSat := reached(mmaFedCnt, mmaIssCnt)

    // Scoreboard registration (counters, not slot masks).  A VR capture store
    // registers the RAW read target above but no reader obligation
    // (`rdIss`): waiting for the next capture write on the store's whole DMA
    // would serialize the body at the capture latency.  The store's RF read
    // still starts only after the producer's VR write (RAW).
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
      tagValid(q) := false.B
      tagFed(q)   := false.B
    }
    for (i <- 0 until L) {
      wrIss(i)  := 0.U
      wrDone(i) := 0.U
      rdIss(i)  := 0.U
      rdDone(i) := 0.U
    }
    mmaIssCnt := 0.U
    mmaFedCnt := 0.U
    tagCount := 0.U
    compValid := false.B
    for (q <- 0 until DMAQ_DEPTH) { dmaQ(q).valid := false.B; dmaQ(q).inFlight := false.B }
    for (q <- 0 until FIFO_DEPTH) { fifo(q).isMmaLast := false.B }
    wCount    := 0.U
    wHead     := wTail
    dmaQHead  := dmaQTail
    // Hold the tail so an enqueue that races the flush cannot advance it past
    // the head (that desyncs the occupancy count permanently).
    dmaQTail  := dmaQTail
    dmaQCount := 0.U
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
      slots(s).initPending := false.B
      for (j <- 0 until 6) {
        slots(s).rdTgt(j) := 0.U
        slots(s).rdSat(j) := false.B
      }
      for (j <- 0 until 4) {
        slots(s).wrTgt(j) := 0.U
        slots(s).wrSat(j) := false.B
      }
      slots(s).lastTgt := 0.U
      slots(s).lastSat := false.B
    }
    // L1 tag ring flush
    for (q <- 0 until T) {
      tagValid(q) := false.B
      tagFed(q)   := false.B
    }
    for (i <- 0 until L) {
      wrIss(i)  := 0.U
      wrDone(i) := 0.U
      rdIss(i)  := 0.U
      rdDone(i) := 0.U
    }
    mmaIssCnt := 0.U
    mmaFedCnt := 0.U
    tagCount := 0.U
    compValid := false.B
    for (q <- 0 until DMAQ_DEPTH) { dmaQ(q).valid := false.B; dmaQ(q).inFlight := false.B }
    for (q <- 0 until FIFO_DEPTH) { fifo(q).isMmaLast := false.B }
    wCount      := 0.U
    wHead       := wTail
    dmaQHead    := dmaQTail
    dmaQTail    := dmaQTail
    dmaQCount   := 0.U
    fifoHead    := fifoTail
    fifoCount   := 0.U
    // No inter-round boundary countdown is armed (rounds pipeline).  The
    // session-start PE reset is the idle keep=0 driven before the session's
    // first feed; it lands on that first feed through the control pipeline.
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
  // A VR store carries the snapshot latched at its issue (WAR-safe); VX/VE
  // stores keep the normal combinational RF read path.
  dma.io.req_store_data_valid := dmaHead.storeDataValid
  for (lane <- 0 until K) dma.io.req_store_data(lane) := dmaHead.storeData(lane)

  val dmaAccept = dma.io.req_ready && dmaHead.valid && !dmaHead.inFlight
  when (dmaAccept) { dmaHead.inFlight := true.B }

  io.fetch_fill_done := dma.io.instr_valid && dmaHead.valid && dmaHead.inFlight && dmaHead.dir === 3.U
  io.fetch_fill_data := dma.io.instr_out
  io.fetch_busy      := dma.io.busy

  val dmaDoneHead = dma.io.done && dmaHead.valid && dmaHead.inFlight
  when (dmaDoneHead) {
    // P2: the DMA slot already retired at issue (dmaQ enqueue), so completion
    // no longer touches the window (and never the fetch fill's placeholder
    // entry).  L1 tag free: this DMA completes → free the carried tag.
    // The tagValid clear and the wrDone/rdDone counter increments are handled
    // by the net updates below.  Fetch fills (dir=3) never allocate a tag
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
    for (lane <- 0 until K) dmaQ(dmaQTail).storeData(lane) := 0.U
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

  // ---- L2 count-scoreboard updates.  Entry increments the issue counters
  //      (after the targets were captured); the mma capture VR write (on the
  //      clctPrev tick) / vle-done increment wrDone, MMA feed / vse-read-done
  //      increment rdDone.  The DMA dir=3/fetch exclusion is preserved.  All
  //      sources are folded into one update per counter so a same-cycle entry
  //      + completion net correctly (up to 2 increments).
  val mmaDoneWmask = (0xF.U << (capVd << 2))(L - 1, 0)
  val mmaFeedRmask = readSlotsL(OpFamily.MMA.asUInt, feedRd, feedRs1, feedRs2, 0.U,
    feedVs3, feedMmaLast)
  // A vle of VE/VR writes SEVERAL VX slots (writeSlotsL expands by the load
  // width); clearing only `rfAddr` would leak the wider load's write.
  val dmaVleWmask  = writeSlotsL(OpFamily.LD.asUInt, dmaHead.rfAddr, dmaHead.dmaWidth)
  // P2: the DMA window slot retired at issue, so the vse read mask is read
  // from the queue entry (captured at issue) rather than the (possibly reused)
  // slot.
  val dmaVseSrmask = dmaHead.rdMask
  when (!illegalAny && !io.session_reset) {
    for (i <- 0 until L) {
      // The mma capture write is visible on the RF write tick (clctPrev),
      // NOT on the delayed completion (compValid) one cycle earlier.
      val wrIncMma = clctPrev && mmaDoneWmask(i)
      val wrIncDma = dmaDoneHead && dmaHead.dir === 0.U && dmaVleWmask(i)
      val wrInc    = wrIncMma.asUInt +& wrIncDma.asUInt
      when (wrInc =/= 0.U) { wrDone(i) := wrDone(i) + wrInc }

      val rdIncFeed = feedValid && mmaFeedRmask(i)
      // Only stores that registered a reader (non-VR) advance rdDone; a VR
      // capture store is deliberately not in the WAR reader set, so counting
      // its completion here would let rdDone run ahead of rdIss and starve a
      // later capture writer's `reached` test.
      val rdIncDma  = dmaDoneHead && dmaHead.dir === 1.U &&
        dmaHead.dmaWidth =/= 2.U && dmaVseSrmask(i)
      val rdInc     = rdIncFeed.asUInt +& rdIncDma.asUInt
      when (rdInc =/= 0.U) { rdDone(i) := rdDone(i) + rdInc }

      when (windowEntry && entSwmask(i)) { wrIss(i) := wrIss(i) + 1.U }
      when (windowEntry && entSrmask(i) && !entIsVrStore) {
        rdIss(i) := rdIss(i) + 1.U
      }
    }
    when (windowEntry && dec.family === OpFamily.MMA && !dec.mma_last) {
      mmaIssCnt := mmaIssCnt + 1.U
    }
    when (feedValid && !feedMmaLast) { mmaFedCnt := mmaFedCnt + 1.U }
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
  // Port 0 serves the DMA R2L VR read, except on a VR-store issue cycle where
  // it is redirected to that store's VR[vd] so the dmaQ entry can latch it.
  rf.io.vr_r_addr(0) := Mux(issueVrStore, issueVrStoreVd(VR_ADDR - 1, 0),
    dma.io.rf_r_vr_addr)
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
  // Freeze the 16-cycle collector trajectory at the most recent capture.  The
  // window just before a clct is inside the live collect/feed window, so it
  // shows keep=1 / dat_clct=H / a full cnt rotation (the old boundary-based
  // snapshot no longer exists).
  when (mmalu.io.clct) { trajSnap := traj }
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
  io.dbg_bnd      := 0.U
  io.dbg_dmaq     := dmaQCount
  io.dbg_capq     := 0.U          // capture queue removed (reduce-only VR path)
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
    fifoCount === 0.U && tagCount === 0.U
  io.dbg_traj    := trajSnap
  io.dbg_cycles  := cycleCnt
  io.dbg_mac     := macCnt
}
