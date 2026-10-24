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
 * On the cycle the wave reaches PE(i,0), that PE still holds the previous
 * round's complete running sum (the reset result is registered and becomes
 * visible on the next cycle).  We therefore latch column 0 row-by-row as the
 * reset wave sweeps (`keep_d` is PE(0,0)-aligned; row i is latched i cycles
 * later), then present the completed column at the engine's delayed capture
 * point (clct + CAPTURE_DELAY).  The normal chainsaw output is used at all
 * other cycles so per-feed streaming captures are unchanged.
 */
class DataCollector(val n: Int = 8, val nbits: Int = 8,
                    val captureDelay: Int = 16) extends Module {
    val io = IO(new Bundle {
        val dat_clct        = Input(Bool())
        val use_accum       = Input(Bool())
        // PE(0,0)-aligned `keep` (pipe_ctrl(0).keep).  A 1->0 edge marks the
        // diagonal reset wave that ends a round.
        val keep_d          = Input(Bool())
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
    // Per-round column snapshot (column 0, row-by-row on the reset wave).
    // ==========================================================================
    val SNAP_DEPTH = 4
    val QDLY_BITS  = log2Ceil(2 * n + 16)
    // Engine capture point: clct (busy delayed 2n) + CAPTURE_DELAY (16).
    // The reset wave starts at PE(0,0) two cycles after the mma.last feed;
    // rows are captured over the following n cycles, so a completed snapshot
    // must be delayed (n + 13) cycles after it is pushed to land exactly on
    // clct+16.
    val SNAP_DLY   = n + 13

    val snapReg  = RegInit(VecInit(Seq.fill(n)(0.S(nbits.W))))
    val keepPrev = RegNext(io.keep_d, true.B)
    // A round only ends on a keep falling edge while the collect window is
    // live; the idle keep=0 used for the session-start reset (dat_clct low)
    // must not produce a snapshot.
    val snapStart = keepPrev && !io.keep_d && io.dat_clct
    val snapActive = RegInit(false.B)
    val snapWr     = RegInit(0.U(log2Ceil(n + 1).W))

    when (snapStart) {
        snapReg(0) := io.reg_in(0)
        snapWr     := 1.U
        snapActive := true.B
    } .elsewhen (snapActive) {
        snapReg(snapWr) := io.reg_in(snapWr * n.U)
        when (snapWr === (n - 1).U) { snapActive := false.B }
        .otherwise                  { snapWr := snapWr + 1.U }
    }
    val snapPush = RegNext(snapActive && snapWr === (n - 1).U, false.B)

    // Small snapshot FIFO.  In a dense stream the next round's snapshot can
    // complete before the previous one is sampled (round period K+1 while the
    // snapshot->clct+16 latency is ~2n), so completed columns are buffered.
    val qData  = Reg(Vec(SNAP_DEPTH, Vec(n, SInt(nbits.W))))
    val qDly   = RegInit(VecInit(Seq.fill(SNAP_DEPTH)(0.U(QDLY_BITS.W))))
    val qValid = RegInit(VecInit(Seq.fill(SNAP_DEPTH)(false.B)))
    val qHead  = RegInit(0.U(log2Ceil(SNAP_DEPTH).W))
    val qTail  = RegInit(0.U(log2Ceil(SNAP_DEPTH).W))
    val qCount = RegInit(0.U((log2Ceil(SNAP_DEPTH) + 1).W))

    for (k <- 0 until SNAP_DEPTH) {
        when (qValid(k) && qDly(k) =/= 0.U) { qDly(k) := qDly(k) - 1.U }
    }
    if (SNAP_DEPTH >= 2) {
        when (snapPush) {
            qData(qTail)  := snapReg
            qDly(qTail)   := SNAP_DLY.U
            qValid(qTail) := true.B
            qTail         := qTail + 1.U
        }
        when (qValid(qHead) && qDly(qHead) === 0.U) {
            qValid(qHead) := false.B
            qHead         := qHead + 1.U
        }
        when (snapPush && qValid(qHead) && qDly(qHead) === 0.U) {
            qCount := qCount
        } .elsewhen (snapPush) {
            qCount := qCount + 1.U
        } .elsewhen (qValid(qHead) && qDly(qHead) === 0.U) {
            qCount := qCount - 1.U
        }
    }

    val snapOut = qValid(qHead) && qDly(qHead) === 0.U


    // Safety net: a session that aborts before its capture point pops would
    // leave a stale snapshot in the queue.  A long collect-idle window (far
    // longer than any snapshot's lifetime) clears it.
    val idleCnt = RegInit(0.U(16.W))
    when (io.dat_clct) { idleCnt := 0.U }
    .elsewhen (idleCnt =/= 0xFFFF.U) { idleCnt := idleCnt + 1.U }
    val autoFlush = idleCnt === (4 * n + 32).U
    when (autoFlush) {
        for (k <- 0 until SNAP_DEPTH) { qValid(k) := false.B }
        qCount := 0.U
        qHead  := qTail
    }

    for (lane <- 0 until n) {
        io.reg_out(lane) := Mux(snapOut, qData(qHead)(lane), normalOut(lane))
    }
}
