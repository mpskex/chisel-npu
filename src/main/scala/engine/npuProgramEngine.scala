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
  // Sized for the xc7k480t: the dispatch state (window, queues) is
  // the dominant logic; keep it compact for routability.
  val FIFO_DEPTH = K          // in-flight feeds (≤ ~K at the DMA cadence)
  val CAPQ_DEPTH = FIFO_DEPTH
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
  }

  class DmaEntry extends Bundle {
    val valid      = Bool()
    val inFlight   = Bool()         // accepted by the DMA, awaiting done
    val slotIdx    = UInt(SLOT_IDX.W)
    val dir        = UInt(2.W)      // 0 = vle, 1 = vse, 3 = fetch fill
    val dmaWidth   = UInt(2.W)
    val rfAddr     = UInt(5.W)
    val l3Addr     = UInt(32.W)
    val storeDataValid = Bool()
    val storeData = Vec(K, UInt((4 * N).W))
  }

  class CapEntry extends Bundle {
    val valid = Bool()
    val data  = Vec(K, UInt((4 * N).W))
  }

  // ==========================================================================
  // Scoreboard: per-VX-slot pending-writer mask + in-flight reader mask.
  // VE[i] → VX[2i..2i+1]; VR[i] → VX[4i..4i+3] (alias-aware, uniform).
  // A reader waits for the pending writers that PRECEDE it in the stream
  // (mask ∩ prior); a writer's own registration never masks the earlier
  // producers it must wait for.
  // ==========================================================================
  val vxWrMask = RegInit(VecInit(Seq.fill(L)(0.U(W.W))))   // pending writers
  val vxWrCap  = RegInit(VecInit(Seq.fill(L)(false.B)))    // last writer type
  val vxRdMask = RegInit(VecInit(Seq.fill(L)(0.U(W.W))))   // in-flight readers

  // The VX-slot sets an instruction reads/writes (L-bit masks).
  def readSlotsL(family: UInt, rd: UInt, rs1: UInt, rs2: UInt, memWidth: UInt,
                 vs3: UInt = 0.U): UInt = {
    val m = WireDefault(0.U(L.W))
    when (family === OpFamily.MMA.asUInt) {
      m := (1.U << rs1) | (1.U << rs2) |
        Mux(vs3 === 0.U, 0.U(L.W), (0xF.U << (vs3 << 2))(L - 1, 0))
    } .elsewhen (family === OpFamily.ST.asUInt) {
      when (memWidth === 0.U) {
        m := 1.U << rd
      } .elsewhen (memWidth === 1.U) {
        m := (1.U << (rd ## 0.U(1.W))) | (1.U << (rd ## 1.U(1.W)))
      } .otherwise {
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
      } .otherwise {
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

  // The valid slots strictly before s in the stream (W-bit mask).
  val priorMask = Wire(Vec(W, UInt(W.W)))
  for (s <- 0 until W) {
    val bits = Wire(Vec(W, Bool()))
    for (i <- 0 until W) {
      bits(i) := slots(i).valid && (i.U =/= s.U) && ringDist(i.U) < ringDist(s.U)
    }
    priorMask(s) := bits.asUInt
  }

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
  // vd FIFO: {slotIdx, vd} per issued mma, popped by each clct.
  // ==========================================================================
  class VdEntry extends Bundle {
    val slotIdx  = UInt(SLOT_IDX.W)
    val vd       = UInt(5.W)
    val isMmaLast = Bool()
    val accum    = Vec(K, SInt((4 * N).W))
  }
  val fifo     = RegInit(VecInit(Seq.fill(FIFO_DEPTH)(0.U.asTypeOf(new VdEntry))))
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
  // The clct delayed by the capture-settle margin (see the completion block).
  val CAPTURE_DELAY = 16
  val clctD = ShiftRegister(mmalu.io.clct, CAPTURE_DELAY, false.B, true.B)
  val mmaluInA = Wire(Vec(K, SInt(N.W)))
  val mmaluInB = Wire(Vec(K, SInt(N.W)))
  val mmaluInAccum = Wire(Vec(K, SInt((4 * N).W)))

  // ==========================================================================
  // Per-slot readiness (combinational)
  // ==========================================================================
  // The mma_last must wait for any pending non-last MMA that PRECEDES it in
  // the window (priorMask), or it can overtake the mma (it has no operand
  // deps), start the boundary countdown, and gate the mma out entirely
  // (silicon: the mma never fed, the array stayed 0).  Only PRIOR slots count
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
  val feedValid    = RegNext(issueMmaV,    false.B)
  val feedSlot     = RegNext(issueMmaSlot, 0.U)
  val feedRd       = RegNext(issueMmaRd,   0.U)
  val feedRs1      = RegNext(issueMmaRs1,  0.U)
  val feedRs2      = RegNext(issueMmaRs2,  0.U)
  val feedVs3      = RegNext(issueMmaVs3,  0.U)
  val feedMmaLast  = RegNext(issueMmaLast, false.B)

  for (s <- 0 until W) {
    val sl = slots(s)
    val rmask = readSlotsL(sl.family, sl.rd, sl.rs1, sl.rs2, sl.memWidth, sl.vs3)
    val wmask = writeSlotsL(sl.family, sl.rd, sl.memWidth)
    val rawOk = WireDefault(true.B)
    val warOk = WireDefault(true.B)
    for (i <- 0 until L) {
      when (rmask(i)) {
        when ((vxWrMask(i) & priorMask(s)) =/= 0.U) { rawOk := false.B }
      }
      when (wmask(i)) {
        when ((vxRdMask(i) & priorMask(s)) =/= 0.U) { warOk := false.B }
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
    for (i <- 0 until W) {
      when (slots(i).valid && !slots(i).done &&
            slots(i).unit === DispatchUnit.MMALU && !slots(i).isMmaLast &&
            priorMask(s)(i)) {
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
    for (i <- 0 until W) {
      when (slots(i).valid && slots(i).unit === slots(s).unit &&
            ringDist(i.U) < ringDist(s.U)) {
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
      } .elsewhen (slots(s).unit === DispatchUnit.DMA) {
        val isStore = slots(s).isStore
        when (isStore && slots(s).useCapData && capQCount =/= 0.U) {
          vsePopCap := true.B
        }
        dmaQ(dmaQTail).valid := true.B
        dmaQ(dmaQTail).inFlight := false.B
        dmaQ(dmaQTail).slotIdx := s.U
        dmaQ(dmaQTail).dir := Mux(isStore, 1.U, 0.U)
        dmaQ(dmaQTail).dmaWidth := slots(s).memWidth
        dmaQ(dmaQTail).rfAddr := slots(s).rd
        dmaQ(dmaQTail).l3Addr := MuxLookup(slots(s).sect, 0.U(32.W))(Seq(
          NpuSections.SECT_A.U(2.W)     -> NpuSections.A.U(32.W),
          NpuSections.SECT_B.U(2.W)     -> NpuSections.B.U(32.W),
          NpuSections.SECT_ACCUM.U(2.W) -> NpuSections.ACCUM.U(32.W),
          NpuSections.SECT_OUT.U(2.W)   -> NpuSections.OUT.U(32.W),
        )) + slots(s).off
        dmaQ(dmaQTail).storeDataValid := isStore && slots(s).useCapData
        for (lane <- 0 until K) dmaQ(dmaQTail).storeData(lane) :=
          Mux(isStore && slots(s).useCapData, vsePopData(lane), 0.U)
        dmaQTail := dmaQTail + 1.U
        dmaQCount := dmaQCount + 1.U
      }
    }
  }

  when (vsePopCap) {
    capQHead  := capQHead + 1.U
    capQCount := capQCount - 1.U
  }

  // ---- The MMALU feed + the vd FIFO enq at the feed ----
  // The pop (clctD) must not fire when the FIFO is empty: a stale clctD from
  // a previous session (still in the ShiftRegister) would underflow fifoCount
  // and, via the completion block, spuriously retire a live slot.  The enq
  // always writes its entry; a same-cycle pop nets the count (head/tail both
  // advance) instead of dropping the write.
  when (feedValid) {
    fifo(fifoHead).slotIdx := feedSlot
    fifo(fifoHead).vd      := feedRd
    fifo(fifoHead).isMmaLast := feedMmaLast
    for (lane <- 0 until K) fifo(fifoHead).accum(lane) := mmaluInAccum(lane)
    fifoHead := fifoHead + 1.U
    when (clctD && fifoCount =/= 0.U) { fifoCount := fifoCount }
    .otherwise                          { fifoCount := fifoCount + 1.U }
  } .otherwise {
    when (clctD && fifoCount =/= 0.U) {
      fifoTail  := fifoTail + 1.U
      fifoCount := fifoCount - 1.U
    }
  }

  when (feedValid) {
    when (feedMmaLast) { boundaryCnt := (6 * K - 1).U }
    // the feed read its operands: clear its reader bits
    val frmask = readSlotsL(OpFamily.MMA.asUInt, feedRd, feedRs1, feedRs2, 0.U, feedVs3)
    for (i <- 0 until L) {
      when (frmask(i)) { vxRdMask(i) := vxRdMask(i) & ~(1.U << feedSlot)(W - 1, 0) }
    }
  }

  // ==========================================================================
  // MMALU / RF wiring (the feed drives the operand reads + the ctrl)
  // ==========================================================================
  rf.io.vx_r_addr(0) := Mux(feedValid, feedRs1, 0.U)
  rf.io.vx_r_addr(1) := Mux(feedValid, feedRs2, 0.U)
  rf.io.vr_r_addr(1) := Mux(feedValid, feedVs3, 0.U)

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
    mmaluInAccum(lane) := Mux(feedValid,
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
  // the FIFO empty the old code still marked a STALE fifo(fifoTail).slotIdx
  // done — spuriously retiring the mma slot, so the mma never fed and the
  // capture read a zero array (the intermittent [0,0,0,0] wrong captures).
  when (clctD && fifoCount =/= 0.U) {
    val sIdx = fifo(fifoTail).slotIdx
    val cvd  = fifo(fifoTail).vd
    val isMmaLast = fifo(fifoTail).isMmaLast
    capVd := cvd
    for (lane <- 0 until K) outBuf(lane) := mmalu.io.out(lane) + fifo(fifoTail).accum(lane)
    slots(sIdx).done := true.B
    val wm = (0xF.U << (cvd << 2))(L - 1, 0)
    for (i <- 0 until L) {
      when (wm(i)) { vxWrMask(i) := vxWrMask(i) & ~(1.U << sIdx).pad(W) }
    }
    // push the capture value for the chained vse — the mma_last is a session
    // terminator whose "capture" is the same array state, not a per-feed
    // column; it must not enter the capture chain (the next round's first
    // vse would otherwise pop it).
    when (!isMmaLast) {
      capQ(capQTail).valid := true.B
      for (lane <- 0 until K) capQ(capQTail).data(lane) :=
        (mmalu.io.out(lane) + fifo(fifoTail).accum(lane)).asUInt
      capQTail  := capQTail + 1.U
      capQCount := capQCount + 1.U
    }
  }

  clctPrev := clctD

  // the VR collect write one tick after the delayed capture
  rf.io.vr_w_en(1)   := clctPrev
  rf.io.vr_w_addr(1) := capVd(VR_ADDR - 1, 0)
  for (lane <- 0 until K) rf.io.vr_w_data(1)(lane) := outBuf(lane).asUInt

  // ==========================================================================
  // Window entry (the frontend streams into the window)
  // ==========================================================================
  val illegalCond =
    decoder.io.illegal ||
    (dec.family === OpFamily.MMA && dec.mma_reset) ||
    ((dec.family === OpFamily.LD || dec.family === OpFamily.ST) && dec.rs1 > 3.U) ||
    (dec.family =/= OpFamily.NOP && dec.family =/= OpFamily.LD &&
     dec.family =/= OpFamily.ST && dec.family =/= OpFamily.MMA)

  io.instr_ready := !wFull && !illegalAny

  when (io.instr_valid && !wFull && !illegalAny) {
    val t = wTail
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

    // vse capture-consumer detection: the source register's last writer was
    // an mma capture (vxWrCap = the last writer's type).  NOTE: this must NOT
    // require the producer to still be a pending prior slot (the old
    // `vxWrMask & priorMask` check).  On silicon the mma's clctD can retire
    // the producer before the store word streams into the window (the MIG's
    // variable latency vs. the frontend's fetch cadence), so the store would
    // silently miss the capture: it would read the RF instead of popping the
    // capQ, the mma's capture entry would sit in capQ forever, and `drain`
    // would never assert (the intermittent pc=4 wedge).  `vxWrCap(i)` alone
    // records that an mma produced VX[i] and survives the producer's retire,
    // so a store reading VX[i] must consume that capture.
    val srmask = readSlotsL(dec.family.asUInt, dec.rd, dec.rs1, dec.rs2, dec.mem_width(1, 0), dec.rs3)
    val isCapSource = WireDefault(false.B)
    for (i <- 0 until L) {
      when (srmask(i) && vxWrCap(i)) {
        isCapSource := true.B
      }
    }
    slots(t).useCapData := dec.family === OpFamily.ST && isCapSource

    // scoreboard registration: readers + writers (mask, not single tag)
    for (i <- 0 until L) {
      when (srmask(i)) { vxRdMask(i) := vxRdMask(i) | (1.U << t)(W - 1, 0) }
    }
    val swmask = writeSlotsL(dec.family.asUInt, dec.rd, dec.mem_width(1, 0))
    for (i <- 0 until L) {
      when (swmask(i)) {
        vxWrMask(i) := vxWrMask(i) | (1.U << t)(W - 1, 0)
        vxWrCap(i)  := dec.family === OpFamily.MMA
      }
    }
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
  val windowEntry  = io.instr_valid && !wFull && !illegalAny
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
    for (i <- 0 until L) {
      vxWrMask(i) := 0.U
      vxWrCap(i)  := false.B
      vxRdMask(i) := 0.U
    }
    for (q <- 0 until DMAQ_DEPTH) { dmaQ(q).valid := false.B; dmaQ(q).inFlight := false.B }
    for (q <- 0 until CAPQ_DEPTH) capQ(q).valid := false.B
    for (q <- 0 until FIFO_DEPTH) { fifo(q).slotIdx := 0.U; fifo(q).isMmaLast := false.B }
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
    for (i <- 0 until L) {
      vxWrMask(i) := 0.U
      vxWrCap(i)  := false.B
      vxRdMask(i) := 0.U
    }
    for (q <- 0 until DMAQ_DEPTH) { dmaQ(q).valid := false.B; dmaQ(q).inFlight := false.B }
    for (q <- 0 until CAPQ_DEPTH) capQ(q).valid := false.B
    for (q <- 0 until FIFO_DEPTH) { fifo(q).slotIdx := 0.U; fifo(q).isMmaLast := false.B }
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
    // Fetch fills (dir=3) carry a placeholder slotIdx=0 (no window slot);
    // marking slots(0).done here would spuriously retire whichever real
    // instruction currently owns ring slot 0 (seen on silicon: the fetch fill
    // completing before the mma fed marked the mma done → the mma never fed,
    // capture read a zero array).  Only vle/vse completions touch slots.
    when (dmaHead.dir =/= 3.U) {
      slots(dmaHead.slotIdx).done := true.B
    }
    when (dmaHead.dir === 0.U) {
      // vle: the RF write completed → clear the producer bit
      vxWrMask(dmaHead.rfAddr) := vxWrMask(dmaHead.rfAddr) & ~(1.U << dmaHead.slotIdx)(W - 1, 0)
      when (vxWrMask(dmaHead.rfAddr) === 0.U) { vxWrCap(dmaHead.rfAddr) := false.B }
    } .elsewhen (dmaHead.dir === 1.U && !dmaHead.storeDataValid) {
      // vse (RF source): its read completed → clear its reader bits
      val sl = slots(dmaHead.slotIdx)
      val srmask = readSlotsL(sl.family, sl.rd, sl.rs1, sl.rs2, sl.memWidth, sl.vs3)
      for (i <- 0 until L) {
        when (srmask(i)) { vxRdMask(i) := vxRdMask(i) & ~(1.U << dmaHead.slotIdx)(W - 1, 0) }
      }
    }
    dmaHead.valid := false.B
    dmaQHead  := dmaQHead + 1.U
    dmaQCount := dmaQCount - 1.U
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
    dmaQ(dmaQTail).slotIdx := 0.U
    dmaQ(dmaQTail).dir := 3.U
    dmaQ(dmaQTail).dmaWidth := 0.U
    dmaQ(dmaQTail).rfAddr := 0.U
    dmaQ(dmaQTail).l3Addr := io.fetch_fill_addr
    dmaQ(dmaQTail).storeDataValid := false.B
    dmaQTail  := dmaQTail + 1.U
    dmaQCount := dmaQCount + 1.U
    fillPending := true.B
  }
  when (io.fetch_fill_done) { fillPending := false.B }

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
  when (feedValid)                                { mmaAccCnt := mmaAccCnt + 1.U }
  when (mmalu.io.clct)                            { clctCnt := clctCnt + 1.U }
  when (dmaDoneHead)                              { dmaDoneCnt := dmaDoneCnt + 1.U }
  for (i <- 0 until 15) traj(i) := traj(i + 1)
  traj(15) := Cat(mmalu.io.ctrl.keep, mmalu.io.dbg_dat_clct, mmalu.io.dbg_cnt)
  when (boundaryPulse) { trajSnap := traj }
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
    fifoCount === 0.U && capQCount === 0.U && boundaryCnt === 0.U
  io.dbg_traj    := trajSnap
}
