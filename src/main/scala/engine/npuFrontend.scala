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

class NpuProgramEngineFrontend(val K: Int = 32, val N: Int = 8) extends Module {

  val SETS = 8                  // 8 sets × 2 ways
  val WAYS = 2

  val io = IO(new Bundle {
    // ---- ctrl_lite (word-addressable) ----
    val ctrl_addr  = Input(UInt(5.W))
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
  })

  // ==========================================================================
  // Execution core
  // ==========================================================================
  val core = Module(new NpuProgramEngine(K, N))

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

  core.io.dbg_vx_addr := 0.U

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
  val lineTag   = RegInit(VecInit(Seq.fill(SETS * WAYS)(0.U(6.W))))
  val lineData  = RegInit(VecInit(Seq.fill(SETS * WAYS)(0.U(128.W))))
  val mru       = RegInit(VecInit(Seq.fill(SETS)(false.B)))   // true = way1 most recent

  val curL   = pc >> 2                       // line index
  val curSet = curL(2, 0)
  val curTag = curL(7, 3)
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
    (lineValid(nextL(2, 0) ## 0.U(1.W)) && lineTag(nextL(2, 0) ## 0.U(1.W)) === nextL(7, 3)) ||
    (lineValid(nextL(2, 0) ## 1.U(1.W)) && lineTag(nextL(2, 0) ## 1.U(1.W)) === nextL(7, 3)))

  // ==========================================================================
  // Run FSM
  // ==========================================================================
  object RunState extends ChiselEnum {
    val RUN_IDLE, RUN_FETCH, RUN_ISSUE, RUN_WAIT, RUN_DONE = Value
  }
  val runState = RegInit(RunState.RUN_IDLE)
  val busy = runState =/= RunState.RUN_IDLE

  val runIllegalSeen = RegInit(false.B)

  // ==========================================================================
  // Fill FSM (demand fills win; prefetches run during core execution)
  // ==========================================================================
  object FillState extends ChiselEnum {
    val IDLE, REQ, WAIT, DRAIN = Value
  }
  val fillState = RegInit(FillState.IDLE)
  val fillLine  = RegInit(0.U(8.W))
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
      lineTag(i)   := fillLine(7, 3)
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
        } .otherwise {
          when (isMmaLast) { framesDone := framesDone + 1.U }
          when (pc === progLenReg - 1.U) {
            doneReg  := true.B
            runState := RunState.RUN_DONE
          } .otherwise {
            pc        := pc + 1.U
            runState  := RunState.RUN_FETCH
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

  io.ctrl_rdata := MuxLookup(io.ctrl_addr, 0.U(32.W))(Seq(
    // bit0 = start (W), bit1 = done (RO), bit2 = busy (RO) — matches ctrl.py
    0x0.U -> Cat(0.U(29.W), busy, doneReg, startReg),
    0x4.U -> framesReg,
    // STATUS: illegal@31 | frames_done[30:16] | pc[15:0]
    0x8.U -> Cat(statusIllegal, framesDone(14, 0), pc),
    0xC.U -> errInstr,
    0x10.U -> Cat(prefetchCnt, missCnt),
    0x14.U -> progLenReg,
  ))
}
