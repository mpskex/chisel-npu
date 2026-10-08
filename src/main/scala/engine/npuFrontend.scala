// See README.md for license details.
// -----------------------------------------------------------------------------
//  npuFrontend.scala — program frontend for NpuProgramEngine: LRU instruction
//  prefetch cache, program counter, and the ctrl_lite register file.
//
//  Program instructions live in the CODE section (entry at the base).  The
//  frontend fetches 16 B lines (4 words) through the engine's shared DMA
//  (fetch_fill_* ports), caches them in an 8-set × 2-way binary-LRU cache
//  (64 instructions), and streams words to the execution core with
//  pc = 0..PROG_LEN-1.  On io.illegal_out it latches ERR_INFO, sets
//  STATUS.illegal and halts (done asserts).
//
//  ctrl_lite registers (word-addressable via ctrl_addr[3:0]):
//    0x00  start (W, bit0) / done (RO, bit1) / busy (RO, bit2)
//    0x04  FRAMES       (config; reserved)
//    0x08  STATUS       pc[15:0] | frames_done[15:0] | illegal
//    0x0C  ERR_INFO     faulting instruction word
//    0x10  FETCH_STATS  misses[15:0] | prefetches[15:0]
//    0x14  PROG_LEN     instruction count (words after the entry)
// -----------------------------------------------------------------------------

package engine

import chisel3._
import chisel3.util._

class NpuProgramEngineFrontend(val K: Int = 32, val N: Int = 8, val W: Int = 16) extends Module {

  val SETS = 8                  // 8 sets × 2 ways
  val WAYS = 2

  val io = IO(new Bundle {
    // ---- ctrl_lite (word-addressable) ----
    // ctrl_addr is 7 bits so the 0x20..0x2C debug registers are reachable.
    // (A 5-bit bus lets firtool prove `ctrl_addr === 0x20.U` false and DCE
    // the debug map — seen in top.sv when the trajectory map vanished.)
    val ctrl_addr  = Input(UInt(7.W))
    val ctrl_we    = Input(Bool())
    val ctrl_wdata = Input(UInt(32.W))
    val ctrl_rdata = Output(UInt(32.W))

    // ---- AXI4 master (via the execution core's DMA) ----
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

    // ---- Debug (testbench only) ----
    val dbg_pc   = Output(UInt(16.W))
    val dbg_word = Output(UInt(32.W))
    val dbg_run  = Output(UInt(3.W))
    val dbg_startEdge = Output(Bool())
    val dbg_clct = Output(UInt(16.W))
    // Core status passthroughs (wedge debugging: which queue blocks drain)
    val dbg_mma_acc  = Output(UInt(16.W))
    val dbg_dma_done = Output(UInt(16.W))
    val dbg_wcount   = Output(UInt(5.W))
    val dbg_win      = Output(UInt(2.W))
    val dbg_bnd      = Output(UInt(7.W))
    val dbg_drain    = Output(Bool())
    val dbg_dmaq     = Output(UInt(3.W))
    val dbg_capq     = Output(UInt(5.W))
    val dbg_mma_out0 = Output(UInt(32.W))
    val dbg_mma_out1 = Output(UInt(32.W))
    val dbg_cnt      = Output(UInt(4.W))
    val dbg_in_a0    = Output(UInt(8.W))
    val dbg_vx_data  = Output(Vec(K, UInt(N.W)))
    val dbg_feed_rs1 = Output(UInt(5.W))
    val dbg_feed_last = Output(Bool())
    val dbg_slot_rs1  = Output(UInt(5.W))
    val dbg_mma_pend = Output(Bool())
    val dbg_s2_valid = Output(Bool())
    val dbg_s2_done  = Output(Bool())
    val dbg_s2_unit  = Output(UInt(2.W))
    val dbg_s2_last  = Output(Bool())
  })

  // ==========================================================================
  // Execution core
  // ==========================================================================
  val core = Module(new NpuProgramEngine(K, N, W))

  io.m_axi_awaddr  := core.io.m_axi_awaddr
  io.m_axi_awlen   := core.io.m_axi_awlen
  io.m_axi_awsize  := core.io.m_axi_awsize
  io.m_axi_awburst := core.io.m_axi_awburst
  io.m_axi_awvalid := core.io.m_axi_awvalid
  core.io.m_axi_awready := io.m_axi_awready
  io.m_axi_wdata   := core.io.m_axi_wdata
  io.m_axi_wstrb   := core.io.m_axi_wstrb
  io.m_axi_wlast   := core.io.m_axi_wlast
  io.m_axi_wvalid  := core.io.m_axi_wvalid
  core.io.m_axi_wready := io.m_axi_wready
  core.io.m_axi_bvalid := io.m_axi_bvalid
  io.m_axi_bready  := core.io.m_axi_bready
  io.m_axi_araddr  := core.io.m_axi_araddr
  io.m_axi_arlen   := core.io.m_axi_arlen
  io.m_axi_arsize  := core.io.m_axi_arsize
  io.m_axi_arburst := core.io.m_axi_arburst
  io.m_axi_arvalid := core.io.m_axi_arvalid
  core.io.m_axi_arready := io.m_axi_arready
  core.io.m_axi_rdata  := io.m_axi_rdata
  core.io.m_axi_rlast  := io.m_axi_rlast
  core.io.m_axi_rvalid := io.m_axi_rvalid
  io.m_axi_rready  := core.io.m_axi_rready

  core.io.dbg_vx_addr := 4.U

  // ==========================================================================
  // ctrl_lite registers
  // ==========================================================================
  val startReg    = RegInit(false.B)
  val doneReg     = RegInit(false.B)
  val framesReg   = RegInit(0.U(16.W))
  val progLenReg  = RegInit(0.U(16.W))
  val statusIllegal = RegInit(false.B)
  val errInstr    = RegInit(0.U(32.W))
  val errPc       = RegInit(0.U(16.W))
  val missCnt     = RegInit(0.U(16.W))
  val prefetchCnt = RegInit(0.U(16.W))

  val startPrev = RegNext(startReg, init = false.B)
  val startEdge = startReg && !startPrev

  // clear the core's streaming state at every session start
  core.io.session_reset := startEdge

  when (io.ctrl_we && io.ctrl_addr === 0x0.U)  { startReg := io.ctrl_wdata(0) }
  when (io.ctrl_we && io.ctrl_addr === 0x4.U)  { framesReg := io.ctrl_wdata(15, 0) }
  when (io.ctrl_we && io.ctrl_addr === 0x14.U) { progLenReg := io.ctrl_wdata(15, 0) }
  when (startEdge) { doneReg := false.B }

  // ==========================================================================
  // Program counter + frames-done counter
  // ==========================================================================
  val pc = RegInit(0.U(16.W))
  val framesDone = RegInit(0.U(16.W))
  val issuedWord = RegInit(0.U(32.W))   // word currently executing

  val isMmaLast = issuedWord(6, 0) === 0x03.U && issuedWord(14, 12) === 1.U

  // ==========================================================================
  // Instruction cache: 8 sets × 2 ways × 4-word lines
  // ==========================================================================
  val lineValid = RegInit(VecInit(Seq.fill(SETS * WAYS)(false.B)))
  val lineTag   = RegInit(VecInit(Seq.fill(SETS * WAYS)(0.U(11.W))))
  val lineData  = RegInit(VecInit(Seq.fill(SETS * WAYS)(0.U(128.W))))
  val mru       = RegInit(VecInit(Seq.fill(SETS)(false.B)))   // true = way1 most recent

  val curL   = pc >> 2                       // line index (up to 16383 lines)
  val curSet = curL(2, 0)
  val curTag = curL(13, 3)                   // 11 bits: covers the full 16-bit pc
  val hit0   = lineValid(curSet ## 0.U(1.W)) && lineTag(curSet ## 0.U(1.W)) === curTag
  val hit1   = lineValid(curSet ## 1.U(1.W)) && lineTag(curSet ## 1.U(1.W)) === curTag
  val lineHit = hit0 || hit1
  val hitWay = Mux(hit1, 1.U, 0.U)

  val wi     = pc(1, 0)              // word index within the line
  val curWord = Mux(lineHit,
    (lineData(Mux(hit1, curSet ## 1.U(1.W), curSet ## 0.U(1.W))) >> (wi << 5.U))(31, 0),
    0.U)

  // Prefetch target: the line after the current one (if any word of it is in range)
  val nextL     = curL + 1.U
  val prefetchValid = (nextL << 2.U) < progLenReg && !(
    (lineValid(nextL(2, 0) ## 0.U(1.W)) && lineTag(nextL(2, 0) ## 0.U(1.W)) === nextL(13, 3)) ||
    (lineValid(nextL(2, 0) ## 1.U(1.W)) && lineTag(nextL(2, 0) ## 1.U(1.W)) === nextL(13, 3)))

  // ==========================================================================
  // Run FSM
  // ==========================================================================
  object RunState extends ChiselEnum {
    val RUN_IDLE, RUN_FETCH, RUN_ISSUE, RUN_WAIT, RUN_DONE = Value
  }
  val runState = RegInit(RunState.RUN_IDLE)
  val busy = runState =/= RunState.RUN_IDLE

  val runIllegalSeen = RegInit(false.B)
  val mmaLastCounted = RegInit(false.B)   // one-shot frames_done count per word

  // ==========================================================================
  // Fill FSM (demand fills win; prefetches run during core execution)
  // ==========================================================================
  object FillState extends ChiselEnum {
    val IDLE, REQ, WAIT, DRAIN = Value
  }
  val fillState = RegInit(FillState.IDLE)
  val fillLine  = RegInit(0.U(14.W))
  val fillWay   = RegInit(0.U(1.W))

  val demandFill = runState === RunState.RUN_FETCH && !lineHit
  val doPrefetch = fillState === FillState.IDLE &&
                   runState === RunState.RUN_WAIT &&
                   !core.io.instr_ready &&       // core busy → DMA free
                   prefetchValid

  val startFill = (fillState === FillState.IDLE) && (demandFill || doPrefetch)

  // Config/start writes invalidate the cache.  NOTE: keep this in a single
  // conditional chain with the line commit — an isolated `when (...) := false`
  // next to the commit's `:= true` was merged by firtool into a set-only OR,
  // silently dropping the invalidation from top.sv (stale-line execution on
  // silicon).
  val cacheInvalidate = io.ctrl_we &&
    (io.ctrl_addr === 0x0.U || io.ctrl_addr === 0x4.U || io.ctrl_addr === 0x14.U)
  val commitFill = fillState === FillState.REQ && core.io.fetch_fill_done

  // Victim: LRU way of the set (mru=way1 → victim way0, and vice versa)
  val victimWay = Mux(mru(curSet), 0.U, 1.U)

  when (startFill) {
    fillState := FillState.REQ
    fillLine  := Mux(demandFill, curL, nextL)
    fillWay   := victimWay   // demand fills only happen on a miss
    when (demandFill) { missCnt := missCnt + 1.U }
    .otherwise        { prefetchCnt := prefetchCnt + 1.U }
  }

  core.io.fetch_fill_req  := fillState === FillState.REQ
  core.io.fetch_fill_addr := NpuSections.CODE_BASE.U(32.W) + (fillLine << 4.U)

  // lineValid: invalidate clears, commit sets (single chain per entry)
  for (i <- 0 until SETS * WAYS) {
    when (cacheInvalidate) {
      lineValid(i) := false.B
    } .elsewhen (commitFill && i.U === (fillLine(2, 0) ## fillWay)) {
      lineValid(i) := true.B
      lineTag(i)   := fillLine(13, 3)
      lineData(i)  := core.io.fetch_fill_data
    }
  }
  when (commitFill) {
    mru(fillLine(2, 0)) := fillWay === 1.U
    fillState           := FillState.DRAIN
  }

  when (fillState === FillState.DRAIN && !core.io.fetch_busy) {
    fillState := FillState.IDLE
  }

  // Update MRU on cache hits (way accessed last)
  when (runState === RunState.RUN_FETCH && lineHit) {
    mru(curSet) := hitWay === 1.U
  }

  // ==========================================================================
  // Run FSM transitions
  // ==========================================================================
  core.io.instr       := issuedWord
  core.io.instr_valid := runState === RunState.RUN_ISSUE

  switch (runState) {
    is (RunState.RUN_IDLE) {
      when (startEdge) {
        pc            := 0.U
        framesDone    := 0.U
        statusIllegal := false.B
        runIllegalSeen := false.B
        mmaLastCounted := false.B
        runState      := RunState.RUN_FETCH
      }
    }

    is (RunState.RUN_FETCH) {
      // wait for the current line (demandFill drives the fill FSM)
      when (lineHit) {
        issuedWord := curWord
        runState   := RunState.RUN_ISSUE
      }
    }

    is (RunState.RUN_ISSUE) {
      when (core.io.instr_ready) {
        runState := RunState.RUN_WAIT
      }
    }

    is (RunState.RUN_WAIT) {
      when (core.io.illegal_out) { runIllegalSeen := true.B }
      when (core.io.instr_ready) {
        when (runIllegalSeen) {
          errInstr      := issuedWord
          errPc         := pc
          statusIllegal := true.B
          doneReg       := true.B
          runState      := RunState.RUN_DONE
        }         .otherwise {
          when (isMmaLast && !mmaLastCounted) {
            framesDone := framesDone + 1.U
            mmaLastCounted := true.B
          }
          when (pc === progLenReg - 1.U) {
            // the streamed dispatch retires the last word asynchronously;
            // the program is only "done" once the engine pipeline drains
            // (window empty + DMA + captures + boundary countdown).
            when (core.io.drain) {
              doneReg  := true.B
              runState := RunState.RUN_DONE
            }
          } .otherwise {
            pc       := pc + 1.U
            runState := RunState.RUN_FETCH
          }
        }
      }
    }

    is (RunState.RUN_DONE) {
      runState := RunState.RUN_IDLE
    }
  }

  // ==========================================================================
  // Register file readback (word-addressable)
  // ==========================================================================
  io.dbg_pc   := pc
  io.dbg_word := issuedWord
  io.dbg_run  := runState.asUInt
  io.dbg_startEdge := startEdge
  io.dbg_clct := core.io.dbg_clct
  io.dbg_mma_acc  := core.io.dbg_mma_acc
  io.dbg_dma_done := core.io.dbg_dma_done
  io.dbg_wcount   := core.io.dbg_wcount
  io.dbg_win      := core.io.dbg_win
  io.dbg_bnd      := core.io.dbg_bnd
  io.dbg_drain    := core.io.drain
  io.dbg_dmaq     := core.io.dbg_dmaq
  io.dbg_capq     := core.io.dbg_capq
  io.dbg_mma_out0 := core.io.dbg_mma_out0
  io.dbg_mma_out1 := core.io.dbg_mma_out1
  io.dbg_cnt      := core.io.dbg_cnt
  io.dbg_in_a0    := core.io.dbg_in_a0
  io.dbg_vx_data  := core.io.dbg_vx_data
  io.dbg_feed_rs1 := core.io.dbg_feed_rs1
  io.dbg_feed_last := core.io.dbg_feed_last
  io.dbg_slot_rs1  := core.io.dbg_slot_rs1
  io.dbg_mma_pend := core.io.dbg_mma_pend
  io.dbg_s2_valid := core.io.dbg_s2_valid
  io.dbg_s2_done  := core.io.dbg_s2_done
  io.dbg_s2_unit  := core.io.dbg_s2_unit
  io.dbg_s2_last  := core.io.dbg_s2_last

  io.ctrl_rdata := MuxLookup(io.ctrl_addr, 0.U(32.W))(Seq(
    // bit0 = start (W), bit1 = done (RO), bit2 = busy (RO) — matches ctrl.py
    0x0.U -> Cat(0.U(29.W), busy, doneReg, startReg),
    0x4.U -> framesReg,
    // STATUS: illegal@31 | frames_done[30:16] | pc[15:0]
    0x8.U -> Cat(statusIllegal, framesDone(14, 0), pc),
    0xC.U -> errInstr,
    0x10.U -> Cat(prefetchCnt, missCnt),
    0x14.U -> progLenReg,
    // Debug: 0x18 = clct[15:0]; 0x1C = mma_acc[15:0];
    // 0x20/0x24/0x28 = collector {keep, dat_clct, cnt} trajectory (last 16
    // ticks, 6 bits each, oldest in the low bits of 0x20), frozen at the
    // session's last capture; 0x2C carries entry[15] at [31:26].
    // Decode: entry(i) = (reg >> (26 - 6 * (i % 5))) & 0x3F for i = 0..14,
    // entry(15) = (0x2C >> 26) & 0x3F.  All branches are exactly 32 bits —
    // a wider Cat would truncate the MSBs (traj(10) / keep) at the port.
     0x18.U -> Cat(0.U(16.W), core.io.dbg_clct),
     0x1C.U -> Cat(0.U(16.W), core.io.dbg_mma_acc),
     // Drain/watchdog: 0x30 = drain@31 | boundaryCnt[30:24] | wcount[23:19] |
     // fifoLow[18:17] | dmaQ[16:14] | capQ[13:9] | 0[8:0].  Read these while
     // busy=1 to see which queue blocks `drain` on a wedge.
     0x30.U -> Cat(core.io.drain, core.io.dbg_bnd, core.io.dbg_wcount,
                   core.io.dbg_win, core.io.dbg_dmaq, core.io.dbg_capq,
                   0.U(9.W)),
     0x34.U -> core.io.dbg_dma_done,
     0x38.U -> core.io.dbg_clct,
    0x20.U -> Cat(core.io.dbg_traj(0), core.io.dbg_traj(1),
                  core.io.dbg_traj(2), core.io.dbg_traj(3),
                  core.io.dbg_traj(4),
                  0.U(2.W)),
    0x24.U -> Cat(core.io.dbg_traj(5), core.io.dbg_traj(6),
                  core.io.dbg_traj(7), core.io.dbg_traj(8),
                  core.io.dbg_traj(9),
                  0.U(2.W)),
    0x28.U -> Cat(core.io.dbg_traj(10), core.io.dbg_traj(11),
                  core.io.dbg_traj(12), core.io.dbg_traj(13),
                  core.io.dbg_traj(14),
                  0.U(2.W)),
     0x2C.U -> Cat(core.io.dbg_traj(15), 0.U(26.W)),
  ))
}
