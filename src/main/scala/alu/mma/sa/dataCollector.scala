// See README.md for license details
package alu.mma.sa

import chisel3._
import chisel3.util._
import isa.micro_op._

/**
 * Data Collector takes N ticks to collect all data
 * Data Collector takes N - 1 tickes to boot up (with data)
 *
 * Per-round snapshot
 * ------------------
 * The continuous chainsaw output (the `cnt` walk) un-skews a *streaming*
 * feed: it needs the per-row diagonal to stay resident for ~n cycles.  With
 * back-to-back matmul rounds the next round's first feed (the PE reset) lands
 * one cycle after the previous round's last feed, so the resident window is
 * one cycle per PE and the continuous output can no longer assemble a full
 * column before the reset destroys it.
 *
 * A round's final PE values are, however, available one diagonal at a time:
 * the reset `keep=0` pulse is a diagonal wave through the control pipeline.
 * On the cycle the wave reaches PE(i,j), that PE still holds the previous
 * round's complete running sum (the reset result is registered and becomes
 * visible on the next cycle).  The ISA-visible result is the round-final
 * column `n-1` (the `B[K-1]` lane), whose row-i value is final when the wave
 * reaches PE(i,n-1) = snapStart + (n-1) + i.  We therefore wait `n-1` cycles
 * after the wave starts, then latch column n-1 row-by-row as the wave sweeps
 * down, and present the completed column at the engine's delayed capture
 * point (clct + CAPTURE_DELAY).  At all other cycles, including the non-last
 * per-mma completions, the normal FIFO-aligned chainsaw output is used; the
 * compiler orders the MMA sequence, and only the round-final column is
 * ISA-defined, so no extra column is latched.
 */
class DataCollector(val n: Int = 8, val nbits: Int = 8,
                    val captureDelay: Int = 16) extends Module {
    // The snapshot delay is derived from the engine's capture point, not a
    // magic constant.  The keep that starts a round is driven at feed F and
    // reaches PE(0,0) at F+2 (pipe_ctrl(0)); the row latch then waits n-1
    // cycles and reads one row per cycle, so the snapshot is pushed at
    // F+2+2n-1.  It is observed one cycle after its countdown reaches 0,
    // i.e. at F+2n+SNAP_DLY (= F+2+2n-1+SNAP_DLY+1).  The engine samples the
    // capture at clct + captureDelay = F+2n+captureDelay.  Equating the two
    // gives SNAP_DLY = captureDelay - 2 (the n-1 wait replaces the old
    // column-0 walk that needed n+...-3).
    require(captureDelay >= 2,
      s"DataCollector: captureDelay=$captureDelay must be >= 2 for the snapshot timing")

    val io = IO(new Bundle {
        val dat_clct        = Input(Bool())
        val use_accum       = Input(Bool())
        // Round-end reset-wave start, PE(0,0)-aligned (the mma.last feed's
        // busy=1/keep=0 pair delayed by the ControlUnit + pipe_ctrl, i.e. the
        // cycle PE(0,0) starts the diagonal PE reset that ends a round).  A
        // ctrl-driven strobe rather than a keep 1->0 edge: a session whose
        // first feed is the mma.last has an idle keep=0 merging into the
        // round's keep=0, so no falling edge exists there.  This is the
        // existing ctrl-bundle keep bit (no new ISA field), tapped at PE(0,0)
        // because the collector has no other view of the reset wave.
        val round_end       = Input(Bool())
        // Session reset: flush the snapshot FIFO (an aborted session must not
        // leave stale columns for the next one).
        val session_reset   = Input(Bool())
        val accum_in        = Input(Vec(n, SInt(nbits.W)))
        val reg_in          = Input(Vec(n * n, SInt(nbits.W)))
        val reg_out         = Output(Vec(n, SInt(nbits.W)))
        val dbg_cnt         = Output(UInt(log2Ceil(n).W))   // collector phase
    })

    val buffer = (0 until n - 1 map(x => Module(new Pipe(SInt(nbits.W), (n - x - 1)))))
    // `dat_clct` is OR(busy) over the 2n-1 control pipeline: the collection
    // window is the feed itself, not the accumulate/reset control.  The engine
    // samples the settled column `captureDelay` cycles after the last feed's
    // clct, i.e. after the raw 2n-1 window has already closed.  Keep the
    // chainsaw running for captureDelay+1 more cycles so that delayed sample
    // still sees a fully un-skewed column (the old OR(keep) window happened to
    // stay open through the idle keep=1, which hid this).
    val EXT = captureDelay + 1
    val extCnt = RegInit(0.U(log2Ceil(EXT + 2).W))
    val datExt = io.dat_clct || extCnt =/= 0.U
    when (io.dat_clct) { extCnt := EXT.U }
    .elsewhen (extCnt =/= 0.U) { extCnt := extCnt - 1.U }
    // cnt counts during the collection window and resets to 0 when the window
    // (including the settle extension) is idle, giving a deterministic capture
    // phase per collection window (the previous free-running counter wrapped
    // onto a bad diagonal every ~n/2 runs on silicon).
    val cnt = RegInit(0.U(log2Ceil(n).W))
    when (datExt) { cnt := cnt + 1.U }
    .otherwise    { cnt := 0.U }
    io.dbg_cnt := cnt

    // ---- normal chainsaw output (streaming / per-feed captures) ----
    val normalOut = Wire(Vec(n, SInt(nbits.W)))
    // chainsaw layout
    for (i <- 0 until n) {
        val col = (cnt - i.U) % n.U
        if (i == n - 1) {
            when (io.use_accum){
                normalOut(i) := io.reg_in((i * n).U(log2Ceil(n*n).W) + col) +  io.accum_in(i)
            } .otherwise {
                normalOut(i) := io.reg_in((i * n).U(log2Ceil(n*n).W) + col)
            }
        } else {
            buffer(i).io.enq.valid := true.B
            buffer(i).io.enq.bits := io.reg_in((i * n).U(log2Ceil(n*n).W) + col)
            when (io.use_accum){
                normalOut(i) := buffer(i).io.deq.bits +  io.accum_in(i)
            } .otherwise {
                normalOut(i) := buffer(i).io.deq.bits
            }
        }
    }

    // ==========================================================================
    // Per-round column snapshots, latched row-by-row on the reset wave.
    //
    //   * column n-1 (the ISA B[K-1] lane) is the round-final capture: it is
    //     presented at the engine's delayed capture tick (clct+CAPTURE_DELAY)
    //     and is consumed by the mma.last's completion -> VR[vd].
    //
    // The non-last per-mma completions use the normal FIFO-aligned chainsaw
    // output (one full column per cycle).  Their column is issue-timing
    // dependent and not part of the ISA contract; the compiler orders the MMA
    // sequence, and only the round-final mma.last write is ISA-defined.
    //
    // The row latches run *concurrently* for consecutive pipelined rounds:
    // round r reads its column n-1 rows over [snapStart_r+n-1, snapStart_r+2n-2]
    // while the next round's wave starts at snapStart_{r+1} = snapStart_r+K+2
    // (inside that window for K < n), so a single shared row register would be
    // pre-empted.  Each round therefore ALLOCATES a queue entry at its wave
    // start and fills that entry's rows in place while the wave sweeps.
    // ==========================================================================
    val SNAP_DEPTH = 4
    val QDLY_BITS  = log2Ceil(2 * n + captureDelay)
    // Engine capture point: clct (busy delayed 2n) + captureDelay.
    // The reset wave starts at PE(0,0) two cycles after the mma.last feed, so
    // snapStart = feed+2, and the fill completes (column n-1 row n-1) at
    // snapStart+2n-2 = feed+2n.  It is presented one cycle after its countdown
    // reaches 0, i.e. at feed+2n+SNAP_DLY+1, which must equal the engine's
    // clct+CAPTURE_DELAY = feed+2n+CAPTURE_DELAY -> SNAP_DLY = captureDelay-1.
    val SNAP_DLY   = captureDelay - 1

    // The round-end strobe already implies a live feed (busy=1/keep=0), so it
    // arms the snapshots directly; gating it on dat_clct dropped snapshots
    // whenever a store bubble landed in the collect window.
    val snapStart = io.round_end
    val lastCol   = (n - 1).U

    // Small snapshot FIFO.  An entry is allocated (qTail) at the wave start,
    // filled row-by-row, validated when the fill completes, held for SNAP_DLY
    // cycles and popped at the head (qHead) when it is presented.
    val qData  = Reg(Vec(SNAP_DEPTH, Vec(n, SInt(nbits.W))))
    val qDly   = RegInit(VecInit(Seq.fill(SNAP_DEPTH)(0.U(QDLY_BITS.W))))
    val qValid = RegInit(VecInit(Seq.fill(SNAP_DEPTH)(false.B)))
    val qHead  = RegInit(0.U(log2Ceil(SNAP_DEPTH).W))
    val qTail  = RegInit(0.U(log2Ceil(SNAP_DEPTH).W))
    val qCount = RegInit(0.U((log2Ceil(SNAP_DEPTH) + 1).W))

    // Row-latch phase, in cycles from the wave start:
    //   ph in [n-1, 2n-2]   -> column n-1 row (ph-(n-1)) is final at
    //                          snapStart+ph (the reset wave reaches PE(row,n-1)).
    // The latch takes n cycles while the next round's wave can arrive n cycles
    // later, so TWO ping-pong latch engines run concurrently (rounds alternate).
    val NENG      = 2
    val lastPh    = (2 * n - 2).U
    val preLastPh = (n - 1).U
    val latchOn   = RegInit(VecInit(Seq.fill(NENG)(false.B)))
    val latchSlot = Reg(Vec(NENG, UInt(log2Ceil(SNAP_DEPTH).W)))
    val latchPh   = RegInit(VecInit(Seq.fill(NENG)(0.U(log2Ceil(2 * n + 1).W))))
    val latchSel  = RegInit(0.U(log2Ceil(NENG).W))

    for (k <- 0 until SNAP_DEPTH) {
        when (qValid(k) && qDly(k) =/= 0.U) { qDly(k) := qDly(k) - 1.U }
    }

    when (snapStart) {
        // Allocate this round's queue entry and start the next latch engine.
        latchOn(latchSel)   := true.B
        latchPh(latchSel)   := 0.U
        latchSlot(latchSel) := qTail
        qTail               := qTail + 1.U
        latchSel            := latchSel + 1.U
    }
    for (e <- 0 until NENG) {
        when (latchOn(e)) {
            val ph = latchPh(e) + 1.U
            latchPh(e) := ph
            // io.reg_in(row*n + col) is the PE register output, which still
            // holds the round's running sum on the cycle the reset wave
            // reaches it (the reset result appears on the next cycle).
            when (ph >= preLastPh) {
                val row = ph - preLastPh
                qData(latchSlot(e))(row) := io.reg_in(row * n.U + lastCol)
                when (ph === lastPh) {
                    latchOn(e)          := false.B
                    qValid(latchSlot(e)) := true.B
                    qDly(latchSlot(e))   := SNAP_DLY.U
                }
            }
        }
    }

    when (qValid(qHead) && qDly(qHead) === 0.U) {
        qValid(qHead) := false.B
        qHead         := qHead + 1.U
    }
    // Occupancy: allocated-but-not-yet-popped entries.  Only informational
    // (dbg/drain use the completed state); kept net-correct for both edges.
    val allocEvent = snapStart
    val popEvent   = qValid(qHead) && qDly(qHead) === 0.U
    when (allocEvent && popEvent) { qCount := qCount }
    .elsewhen (allocEvent)        { qCount := qCount + 1.U }
    .elsewhen (popEvent)          { qCount := qCount - 1.U }

    // Round-final tick: the ISA B[K-1] column.  Before it (the head entry is
    // still counting down) the pre-reduce column 0 is presented so the round's
    // non-last completions latch a settled running-sum column.
    val snapOut = qValid(qHead) && qDly(qHead) === 0.U


    // Safety net: a session that aborts before its capture point pops would
    // leave a stale snapshot in the queue.  A long collect-idle window (far
    // longer than any snapshot's lifetime) clears it, and the engine's
    // session_reset flushes it immediately at the next session start.
    val idleCnt = RegInit(0.U(16.W))
    when (io.dat_clct) { idleCnt := 0.U }
    .elsewhen (idleCnt =/= 0xFFFF.U) { idleCnt := idleCnt + 1.U }
    val autoFlush = idleCnt === (4 * n + 32).U
    when (autoFlush || io.session_reset) {
        for (k <- 0 until SNAP_DEPTH) { qValid(k) := false.B }
        qCount := 0.U
        qHead  := qTail
        // Also drop any in-progress row latch: a long idle window (or a new
        // session) must not leave a half-collected snapshot waiting.
        for (e <- 0 until NENG) { latchOn(e) := false.B; latchPh(e) := 0.U }
    }

    for (lane <- 0 until n) {
        io.reg_out(lane) := Mux(snapOut, qData(qHead)(lane), normalOut(lane))
    }

}
