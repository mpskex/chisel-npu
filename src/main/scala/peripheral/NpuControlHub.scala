// See README.md for license details.
// -----------------------------------------------------------------------------
//  NpuControlHub.scala — NPUCTL v1 control hub.
//
//  Owns the standard register file, the start/stop/reset session FSM, the
//  interrupt status/enable logic, the watchdog, the trace FIFO, and the
//  segment base/limit table.  The core-facing side is the NPU's native
//  control/status plus the segment table; the AXI data translation lives in
//  NpuPeripheral.
//
//  The ISA and the instruction program model are deliberately untouched:
//  this module only relocates the existing sections and adds control.
// -----------------------------------------------------------------------------

package peripheral

import chisel3._
import chisel3.util._

class NpuControlHub(val K: Int = 32, val N: Int = 8, val L: Int = 32,
                    val W: Int = 8, val NUM_SEG: Int = NpuCtrlProtocol.NUM_ACTIVE_SEG,
                    val TRACE_DEPTH: Int = 256) extends Module {
  require(NUM_SEG >= 1 && NUM_SEG <= NpuCtrlProtocol.NUM_SEG,
    s"NpuControlHub: NUM_SEG=$NUM_SEG must be 1..${NpuCtrlProtocol.NUM_SEG}")
  require(TRACE_DEPTH >= 2 && (TRACE_DEPTH & (TRACE_DEPTH - 1)) == 0,
    "NpuControlHub: TRACE_DEPTH must be a power of two")

  val TRACE_A = log2Ceil(TRACE_DEPTH)
  val TRACE_CW = log2Ceil(TRACE_DEPTH + 1)

  val io = IO(new Bundle {
    // ---- NPUCTL control bus ----------------------------------------------
    val ctrl = new NpuCtrlSlave

    // ---- core control -----------------------------------------------------
    val core_start    = Output(Bool())
    val core_abort    = Output(Bool())
    val core_prog_len = Output(UInt(16.W))
    val core_entry_pc = Output(UInt(16.W))
    val core_seg_limit = Output(Vec(NUM_SEG, UInt(32.W)))

    // ---- core status ------------------------------------------------------
    val core_busy       = Input(Bool())
    val core_done       = Input(Bool())
    val core_illegal    = Input(Bool())
    val core_mem_fault  = Input(Bool())
    val core_fault_seg  = Input(UInt(3.W))
    val core_fault_addr = Input(UInt(32.W))
    val core_pc         = Input(UInt(16.W))
    val core_err_instr  = Input(UInt(32.W))
    val core_cycles     = Input(UInt(32.W))
    val core_macs       = Input(UInt(32.W))
    val core_fetch_stats = Input(UInt(32.W))
    val core_trace_retire = Input(Bool())
    val core_trace_pc     = Input(UInt(16.W))

    // ---- segment table to the translator ---------------------------------
    val seg_base  = Output(Vec(NUM_SEG, UInt(64.W)))
    val seg_limit = Output(Vec(NUM_SEG, UInt(32.W)))

    // ---- guard / bus error from the translator ---------------------------
    val bus_error = Input(Bool())

    // ---- trace ------------------------------------------------------------
    val trace_valid = Output(Bool())
    val trace_ready = Input(Bool())
    val trace_data  = Output(UInt(64.W))

    // ---- interrupt --------------------------------------------------------
    val irq = Output(Bool())
  })

  // ==========================================================================
  // Register storage
  // ==========================================================================
  val scratch0 = RegInit(0.U(32.W))
  val scratch1 = RegInit(0.U(32.W))
  val scratch2 = RegInit(0.U(32.W))
  val scratch3 = RegInit(0.U(32.W))

  val irqEnable = RegInit(0.U(4.W))
  val irqStatus = RegInit(0.U(4.W))

  val progLen   = RegInit(0.U(16.W))
  val entryPc   = RegInit(0.U(16.W))
  val sessionId = RegInit(0.U(32.W))
  val watchdog  = RegInit(0.U(32.W))

  val segBaseLo = RegInit(VecInit(Seq.fill(NUM_SEG)(0.U(32.W))))
  val segBaseHi = RegInit(VecInit(Seq.fill(NUM_SEG)(0.U(32.W))))
  val segLimit  = RegInit(VecInit(Seq.fill(NUM_SEG)(0.U(32.W))))

  val errorCode = RegInit(NpuCtrlProtocol.Error.NONE.U(4.W))
  val errorPc   = RegInit(0.U(16.W))
  val errorInfo = RegInit(0.U(32.W))
  val errorAddr = RegInit(0.U(32.W))

  val latchedCycles = RegInit(0.U(32.W))
  val latchedMacs   = RegInit(0.U(32.W))
  val latchedFetch  = RegInit(0.U(32.W))

  val traceCtrl  = RegInit(0.U(16.W))
  val traceDrops = RegInit(0.U(32.W))
  val traceOvf   = RegInit(false.B)

  val timestamp = RegInit(0.U(32.W))
  timestamp := timestamp + 1.U

  // Active shadows latched at START.
  val activeProgLen  = RegInit(0.U(16.W))
  val activeEntryPc  = RegInit(0.U(16.W))
  val activeWatchdog = RegInit(0.U(32.W))
  val activeBase  = RegInit(VecInit(Seq.fill(NUM_SEG)(0.U(64.W))))
  val activeLimit = RegInit(VecInit(Seq.fill(NUM_SEG)(0.U(32.W))))

  // ==========================================================================
  // Control bus request / response
  // ==========================================================================
  val reqFire  = io.ctrl.req.valid && io.ctrl.req.ready
  val reqWrite = io.ctrl.req.bits.write
  val reqAddr  = io.ctrl.req.bits.addr
  val reqWdata = io.ctrl.req.bits.wdata
  val reqWstrb = io.ctrl.req.bits.wstrb

  val respValid = RegInit(false.B)
  val respRdata = RegInit(0.U(32.W))
  val respError = RegInit(false.B)

  io.ctrl.req.ready       := !respValid
  io.ctrl.resp.valid      := respValid
  io.ctrl.resp.bits.rdata := respRdata
  io.ctrl.resp.bits.error := respError

  when (respValid && io.ctrl.resp.ready) { respValid := false.B }

  // Byte-strobe merge helper.
  def merge(old: UInt, neu: UInt, strb: UInt): UInt = {
    val o = old.pad(32)
    val n = neu.pad(32)
    Cat((0 until 4).reverse.map { i =>
      Mux(strb(i), n(8 * i + 7, 8 * i), o(8 * i + 7, 8 * i))
    })
  }

  // ==========================================================================
  // Session FSM
  // ==========================================================================
  object HubState extends ChiselEnum {
    val IDLE, RUN, DONE = Value
  }
  val state = RegInit(HubState.IDLE)

  val doneSticky    = RegInit(false.B)
  val errorSticky   = RegInit(false.B)
  val abortedSticky = RegInit(false.B)

  val ctrlWrite = reqFire && reqWrite && reqAddr === NpuCtrlProtocol.CONTROL.U &&
                  reqWstrb(0)
  val startPulse     = ctrlWrite && reqWdata(NpuCtrlProtocol.CTRL_START)
  val stopPulse      = ctrlWrite && reqWdata(NpuCtrlProtocol.CTRL_STOP)
  val softResetPulse = ctrlWrite && reqWdata(NpuCtrlProtocol.CTRL_SOFT_RESET)

  // Default windows for SEG_LIMIT == 0.
  val defaultLimits = VecInit(Seq(
    0x400L, 0x400L, 0x80L, 0x3780L, 0x40000L
  ).padTo(NUM_SEG, 0L).map(_.U(32.W)))

  val effLimit = VecInit((0 until NUM_SEG).map { i =>
    Mux(segLimit(i) === 0.U, defaultLimits(i), segLimit(i))
  })

  val segBaseWord = VecInit((0 until NUM_SEG).map { i =>
    Cat(segBaseHi(i), segBaseLo(i))
  })

  val segAligned = VecInit((0 until NUM_SEG).map { i =>
    (segBaseLo(i)(5, 0) === 0.U) && (effLimit(i)(5, 0) === 0.U)
  }).reduce(_ && _)

  val endWord   = entryPc +& progLen
  val codeBytes = ((endWord +& 15.U) >> 4) << 6
  val codeFits  = codeBytes <= effLimit(NpuCtrlProtocol.Seg.CODE)
  val wordFits  = endWord <= 0xFFFF.U
  val segNoOverflow = (0 until NUM_SEG).map { i =>
    val sum = segBaseWord(i) +& effLimit(i)
    !sum(64)
  }.reduce(_ && _)
  val canStartCfg = progLen =/= 0.U && segAligned && segNoOverflow && codeFits && wordFits
  val startAttempt = (state === HubState.IDLE || state === HubState.DONE) &&
                     !io.core_busy && startPulse
  val startAccept  = startAttempt && canStartCfg
  val badConfigEvt = startAttempt && !canStartCfg

  // Watchdog cycle counter.
  val watchdogCnt = RegInit(0.U(32.W))
  when (state === HubState.RUN) {
    watchdogCnt := watchdogCnt + 1.U
  } .otherwise {
    watchdogCnt := 0.U
  }

  val watchdogExpired = (state === HubState.RUN) && (activeWatchdog =/= 0.U) &&
                        (watchdogCnt >= activeWatchdog - 1.U)

  val illegalEvt  = (state === HubState.RUN) && io.core_illegal
  val memFaultEvt = (state === HubState.RUN) && io.core_mem_fault
  val busErrEvt   = (state === HubState.RUN) && io.bus_error
  val doneEvt     = (state === HubState.RUN) && io.core_done
  val abortEvt    = (state === HubState.RUN) && stopPulse

  val fsErrorEvt = illegalEvt || memFaultEvt || watchdogExpired || busErrEvt ||
                   abortEvt || badConfigEvt

  // Core abort pulse.
  val coreAbort = (state === HubState.RUN) &&
                  (stopPulse || watchdogExpired || busErrEvt || io.core_mem_fault) ||
                  softResetPulse
  io.core_abort := coreAbort

  when (startAccept) {
    state          := HubState.RUN
    doneSticky     := false.B
    errorSticky    := false.B
    abortedSticky  := false.B
    errorCode      := NpuCtrlProtocol.Error.NONE.U
    errorPc        := 0.U
    errorInfo      := 0.U
    errorAddr      := 0.U
    activeProgLen  := progLen
    activeEntryPc  := entryPc
    activeWatchdog := watchdog
    for (i <- 0 until NUM_SEG) {
      activeBase(i)  := segBaseWord(i)
      activeLimit(i) := effLimit(i)
    }
  }

  io.core_start := startAccept

  when (state === HubState.RUN) {
    when (illegalEvt) {
      state       := HubState.IDLE
      errorSticky := true.B
      errorCode   := NpuCtrlProtocol.Error.ILLEGAL.U
      errorPc     := io.core_pc
      errorInfo   := io.core_err_instr
      latchedCycles := io.core_cycles
      latchedMacs   := io.core_macs
      latchedFetch  := io.core_fetch_stats
    } .elsewhen (memFaultEvt) {
      state       := HubState.IDLE
      errorSticky := true.B
      errorCode   := NpuCtrlProtocol.Error.MEM_FAULT.U
      errorPc     := io.core_pc
      errorAddr   := Cat(io.core_fault_seg, io.core_fault_addr(28, 0))
      latchedCycles := io.core_cycles
      latchedMacs   := io.core_macs
      latchedFetch  := io.core_fetch_stats
    } .elsewhen (watchdogExpired) {
      state       := HubState.IDLE
      errorSticky := true.B
      errorCode   := NpuCtrlProtocol.Error.WATCHDOG.U
      errorPc     := io.core_pc
      latchedCycles := io.core_cycles
      latchedMacs   := io.core_macs
      latchedFetch  := io.core_fetch_stats
    } .elsewhen (busErrEvt) {
      state       := HubState.IDLE
      errorSticky := true.B
      errorCode   := NpuCtrlProtocol.Error.BUS_ERROR.U
      errorPc     := io.core_pc
      latchedCycles := io.core_cycles
      latchedMacs   := io.core_macs
      latchedFetch  := io.core_fetch_stats
    } .elsewhen (abortEvt) {
      state          := HubState.IDLE
      abortedSticky  := true.B
      errorSticky    := true.B
      errorCode      := NpuCtrlProtocol.Error.ABORTED.U
      errorPc        := io.core_pc
      latchedCycles  := io.core_cycles
      latchedMacs    := io.core_macs
      latchedFetch   := io.core_fetch_stats
    } .elsewhen (doneEvt) {
      state        := HubState.DONE
      doneSticky   := true.B
      latchedCycles := io.core_cycles
      latchedMacs   := io.core_macs
      latchedFetch  := io.core_fetch_stats
    }
  }

  when (badConfigEvt) {
    doneSticky    := false.B
    abortedSticky := false.B
    errorSticky   := true.B
    errorCode     := NpuCtrlProtocol.Error.BAD_CONFIG.U
  }

  when (softResetPulse) {
    state          := HubState.IDLE
    doneSticky     := false.B
    errorSticky    := false.B
    abortedSticky  := false.B
    errorCode      := NpuCtrlProtocol.Error.NONE.U
    errorPc        := 0.U
    errorInfo      := 0.U
    errorAddr      := 0.U
  }

  // ==========================================================================
  // Interrupts
  // ==========================================================================
  val traceOvfEvt = RegInit(false.B) // one-cycle sticky event for IRQ
  val irqSet = Cat(0.U(28.W), busErrEvt, traceOvfEvt, fsErrorEvt, doneEvt)
  val irqClr = Mux(reqFire && reqWrite && reqAddr === NpuCtrlProtocol.IRQ_STATUS.U,
    merge(0.U(32.W), reqWdata, reqWstrb)(3, 0), 0.U(4.W))
  val irqClearAll = startAccept || softResetPulse

  when (irqClearAll) {
    irqStatus := 0.U
  } .otherwise {
    irqStatus := (irqStatus & ~irqClr(3, 0)) | irqSet(3, 0)
  }

  when (reqFire && reqWrite && reqAddr === NpuCtrlProtocol.IRQ_ENABLE.U) {
    irqEnable := merge(irqEnable, reqWdata, reqWstrb)(3, 0)
  }

  // ==========================================================================
  // Trace FIFO
  // ==========================================================================
  val traceMem   = RegInit(VecInit(Seq.fill(TRACE_DEPTH)(0.U(64.W))))
  val traceHead  = RegInit(0.U(TRACE_A.W))
  val traceTail  = RegInit(0.U(TRACE_A.W))
  val traceCount = RegInit(0.U(TRACE_CW.W))
  val traceHalf  = RegInit(false.B)

  val traceEmpty = traceCount === 0.U
  val traceFull  = traceCount === TRACE_DEPTH.U

  val traceMode = traceCtrl(3, 1)
  val traceEn   = traceCtrl(0) && traceMode =/= 0.U

  val retireEvt = io.core_trace_retire && (traceMode === 2.U || traceMode === 3.U)
  val errNow = MuxCase(NpuCtrlProtocol.Error.NONE.U(4.W), Seq(
    illegalEvt  -> NpuCtrlProtocol.Error.ILLEGAL.U(4.W),
    memFaultEvt -> NpuCtrlProtocol.Error.MEM_FAULT.U(4.W),
    watchdogExpired -> NpuCtrlProtocol.Error.WATCHDOG.U(4.W),
    busErrEvt   -> NpuCtrlProtocol.Error.BUS_ERROR.U(4.W),
    abortEvt    -> NpuCtrlProtocol.Error.ABORTED.U(4.W),
    badConfigEvt -> NpuCtrlProtocol.Error.BAD_CONFIG.U(4.W)
  ))

  val traceEvtValid = traceEn && (startAccept || doneEvt || fsErrorEvt || retireEvt)
  val traceEvt = MuxCase(NpuCtrlProtocol.Event.RETIRE.U(8.W), Seq(
    watchdogExpired -> NpuCtrlProtocol.Event.WATCHDOG.U(8.W),
    abortEvt        -> NpuCtrlProtocol.Event.ABORT.U(8.W),
    startAccept     -> NpuCtrlProtocol.Event.START.U(8.W),
    doneEvt         -> NpuCtrlProtocol.Event.DONE.U(8.W),
    fsErrorEvt      -> NpuCtrlProtocol.Event.ERROR.U(8.W),
    retireEvt       -> NpuCtrlProtocol.Event.RETIRE.U(8.W)
  ))
  val tracePc = Mux(retireEvt, io.core_trace_pc, io.core_pc)
  val tracePayload = Mux(fsErrorEvt, errNow, Mux(startAccept, sessionId(7, 0), 0.U(8.W)))
  val traceRecord = Cat(timestamp, tracePc, traceEvt, tracePayload)

  val traceRead = reqFire && !reqWrite && reqAddr === NpuCtrlProtocol.TRACE_DATA.U
  val traceValid = !traceEmpty && traceEn
  val tracePopData   = traceRead && traceHalf && !traceEmpty
  val tracePopStream = traceValid && io.trace_ready && !traceHalf && !traceRead
  val tracePop = tracePopData || tracePopStream
  val tracePush = traceEvtValid && (!traceFull || tracePop)
  val traceDrop = traceEvtValid && !tracePush

  when (tracePush) {
    traceMem(traceTail) := traceRecord
    traceTail := traceTail + 1.U
  }
  when (tracePop) {
    traceHead := traceHead + 1.U
  }
  when (tracePush && tracePop) {
    traceCount := traceCount
  } .elsewhen (tracePush) {
    traceCount := traceCount + 1.U
  } .elsewhen (tracePop) {
    traceCount := traceCount - 1.U
  }
  when (traceDrop) {
    traceDrops := traceDrops + 1.U
    traceOvf   := true.B
  }

  when (traceOvfEvt) { traceOvfEvt := false.B }
  when (traceDrop)   { traceOvfEvt := true.B }

  when (traceRead) {
    when (traceEmpty) { traceHalf := false.B }
    .otherwise        { traceHalf := !traceHalf }
  }

  io.trace_valid := traceValid
  io.trace_data  := traceMem(traceHead)

  when (reqFire && reqWrite && reqAddr === NpuCtrlProtocol.TRACE_CTRL.U) {
    traceCtrl := merge(traceCtrl, reqWdata, reqWstrb)(15, 0)
  }

  // ==========================================================================
  // Core-facing segment values
  // ==========================================================================
  val activeNow = (state === HubState.RUN) || io.core_busy
  for (i <- 0 until NUM_SEG) {
    io.core_seg_limit(i) := Mux(activeNow, activeLimit(i), effLimit(i))
    io.seg_base(i)       := Mux(activeNow, activeBase(i), segBaseWord(i))
    io.seg_limit(i)      := Mux(activeNow, activeLimit(i), effLimit(i))
  }
  io.core_prog_len := Mux(activeNow, activeProgLen, progLen)
  io.core_entry_pc := Mux(activeNow, activeEntryPc, entryPc)

  // ==========================================================================
  // Register writes
  // ==========================================================================
  when (reqFire && reqWrite) {
    switch (reqAddr) {
      is (NpuCtrlProtocol.IRQ_ENABLE.U)   { /* handled above */ }
      is (NpuCtrlProtocol.IRQ_STATUS.U)   { /* handled above */ }
      is (NpuCtrlProtocol.CONTROL.U)      { /* pulses handled above */ }
      is (NpuCtrlProtocol.PROG_LEN.U)     { progLen   := merge(progLen, reqWdata, reqWstrb)(15, 0) }
      is (NpuCtrlProtocol.ENTRY_PC.U)     { entryPc   := merge(entryPc, reqWdata, reqWstrb)(15, 0) }
      is (NpuCtrlProtocol.SESSION_ID.U)   { sessionId := merge(sessionId, reqWdata, reqWstrb) }
      is (NpuCtrlProtocol.WATCHDOG.U)     { watchdog  := merge(watchdog, reqWdata, reqWstrb) }
      is (NpuCtrlProtocol.SCRATCH.U)      { scratch0  := merge(scratch0, reqWdata, reqWstrb) }
      is ((NpuCtrlProtocol.SCRATCH + 4).U){ scratch1  := merge(scratch1, reqWdata, reqWstrb) }
      is ((NpuCtrlProtocol.SCRATCH + 8).U){ scratch2  := merge(scratch2, reqWdata, reqWstrb) }
      is ((NpuCtrlProtocol.SCRATCH + 12).U){ scratch3 := merge(scratch3, reqWdata, reqWstrb) }
    }
    for (i <- 0 until NUM_SEG) {
      when (reqAddr === NpuCtrlProtocol.segBaseLo(i).U) {
        segBaseLo(i) := merge(segBaseLo(i), reqWdata, reqWstrb)
      }
      when (reqAddr === NpuCtrlProtocol.segBaseHi(i).U) {
        segBaseHi(i) := merge(segBaseHi(i), reqWdata, reqWstrb)
      }
      when (reqAddr === NpuCtrlProtocol.segLimit(i).U) {
        segLimit(i) := merge(segLimit(i), reqWdata, reqWstrb)
      }
    }
  }

  // ==========================================================================
  // Register reads
  // ==========================================================================
  val caps = Cat(
    6.U(4.W),                                // [31:28] AXI data width log2(bytes): 512b = 64B
    0.U(4.W),                                // [27:24] reserved
    log2Ceil(TRACE_DEPTH).U(8.W),            // [23:16] trace depth log2
    1.U(4.W),                                // [15:12] local map version
    NUM_SEG.U(4.W),                          // [11:8]  number of active segments
    0.U(4.W),                                // [7:4]   reserved
    1.U(1.W),                                // [3]     AXI64
    1.U(1.W),                                // [2]     watchdog
    1.U(1.W),                                // [1]     trace
    1.U(1.W)                                 // [0]     IRQ
  )

  val version = Cat(
    NpuCtrlProtocol.VERSION_MAJOR.U(8.W),
    NpuCtrlProtocol.VERSION_MINOR.U(8.W),
    NpuCtrlProtocol.VERSION_PATCH.U(8.W),
    0.U(8.W)
  )

  val config = Cat(
    W.U(8.W), L.U(8.W), N.U(8.W), K.U(8.W)
  )

  val statusWord = Cat(
    io.irq,
    0.U(23.W),
    0.U(2.W), state.asUInt,
    abortedSticky, errorSticky, doneSticky,
    state === HubState.RUN || io.core_busy
  )

  val readTable = Seq(
    NpuCtrlProtocol.ID.U           -> NpuCtrlProtocol.ID_MAGIC.U(32.W),
    NpuCtrlProtocol.VERSION.U      -> version,
    NpuCtrlProtocol.CAPS.U         -> caps,
    NpuCtrlProtocol.CONFIG.U       -> config,
    NpuCtrlProtocol.CONTROL.U      -> 0.U(32.W),
    NpuCtrlProtocol.STATUS.U       -> statusWord,
    NpuCtrlProtocol.IRQ_STATUS.U   -> Cat(0.U(28.W), irqStatus),
    NpuCtrlProtocol.IRQ_ENABLE.U   -> Cat(0.U(28.W), irqEnable),
    NpuCtrlProtocol.ERROR_CODE.U   -> Cat(0.U(28.W), errorCode),
    NpuCtrlProtocol.ERROR_PC.U     -> Cat(0.U(16.W), errorPc),
    NpuCtrlProtocol.ERROR_INFO.U   -> errorInfo,
    NpuCtrlProtocol.ERROR_ADDR.U   -> errorAddr,
    NpuCtrlProtocol.PROG_LEN.U     -> Cat(0.U(16.W), progLen),
    NpuCtrlProtocol.ENTRY_PC.U     -> Cat(0.U(16.W), entryPc),
    NpuCtrlProtocol.SESSION_ID.U   -> sessionId,
    NpuCtrlProtocol.WATCHDOG.U     -> watchdog,
    NpuCtrlProtocol.CYCLES.U       -> latchedCycles,
    NpuCtrlProtocol.MACS.U         -> latchedMacs,
    NpuCtrlProtocol.FETCH_STATS.U  -> latchedFetch,
    NpuCtrlProtocol.TIMESTAMP.U    -> timestamp,
    NpuCtrlProtocol.TRACE_CTRL.U   -> Cat(0.U(16.W), traceCtrl),
    NpuCtrlProtocol.TRACE_STATUS.U -> Cat(0.U(14.W), traceFull, traceOvf, 0.U(7.W), traceCount),
    NpuCtrlProtocol.TRACE_DATA.U   -> Mux(traceEmpty, 0.U(32.W),
                                            Mux(traceHalf, traceMem(traceHead)(63, 32),
                                                           traceMem(traceHead)(31, 0))),
    NpuCtrlProtocol.TRACE_DROPS.U  -> traceDrops,
    NpuCtrlProtocol.SCRATCH.U      -> scratch0,
    (NpuCtrlProtocol.SCRATCH + 4).U -> scratch1,
    (NpuCtrlProtocol.SCRATCH + 8).U -> scratch2,
    (NpuCtrlProtocol.SCRATCH + 12).U -> scratch3
  ) ++ (0 until NUM_SEG).flatMap { i =>
    Seq(
      NpuCtrlProtocol.segBaseLo(i).U -> segBaseLo(i),
      NpuCtrlProtocol.segBaseHi(i).U -> segBaseHi(i),
      NpuCtrlProtocol.segLimit(i).U  -> segLimit(i)
    )
  }

  when (reqFire) {
    respValid := true.B
    respError := false.B
    when (!reqWrite) { respRdata := MuxLookup(reqAddr, 0.U(32.W))(readTable) }
    .otherwise       { respRdata := 0.U }
  }

  // Interrupt output.
  io.irq := (irqStatus & irqEnable).orR
}
