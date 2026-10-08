// See README.md for license details
package alu.mma.sa

import chisel3._
import chisel3.util._
import isa.micro_op._

/**
 * Data Collector takes N ticks to collect all data
 * Data Collector takes N - 1 tickes to boot up (with data)
 */
class DataCollector(val n: Int = 8, val nbits: Int = 8) extends Module {
    val io = IO(new Bundle {
        val dat_clct        = Input(Bool())
        val use_accum       = Input(Bool())
        val accum_in        = Input(Vec(n, SInt(nbits.W)))
        val reg_in          = Input(Vec(n * n, SInt(nbits.W)))
        val reg_out         = Output(Vec(n, SInt(nbits.W)))
        val dbg_cnt         = Output(UInt(log2Ceil(n).W))   // collector phase
    })

    val buffer = (0 until n - 1 map(x => Module(new Pipe(SInt(nbits.W), (n - x - 1)))))
    // cnt counts during the collection window (dat_clct=1) and resets to 0 in
    // idle.  It was free-running, so its phase at the capture accumulated
    // across runs and periodically (every n/2 runs) landed on a bad diagonal,
    // giving wrong columns on silicon.  Reset cnt to 0 at the rising edge of
    // the collection window so every feed's capture phase is deterministic.
    val cnt = RegInit(0.U(log2Ceil(n).W))
    val datClctPrev = RegNext(io.dat_clct, init = false.B)
    when (io.dat_clct && !datClctPrev)      { cnt := 0.U }
    .elsewhen (io.dat_clct)                 { cnt := cnt + 1.U }
    .otherwise                              { cnt := 0.U }
    io.dbg_cnt := cnt

    // chainsaw layout
    for (i <- 0 until n) {
        val col = (cnt - i.U) % n.U
        if (i == n - 1) {
            when (io.use_accum){
                io.reg_out(i) := io.reg_in((i * n).U(log2Ceil(n*n).W) + col) +  io.accum_in(i)
            } .otherwise {
                io.reg_out(i) := io.reg_in((i * n).U(log2Ceil(n*n).W) + col)
            }
        } else {
            buffer(i).io.enq.valid := true.B
            buffer(i).io.enq.bits := io.reg_in((i * n).U(log2Ceil(n*n).W) + col)
            when (io.use_accum){
                io.reg_out(i) := buffer(i).io.deq.bits +  io.accum_in(i)
            } .otherwise {
                io.reg_out(i) := buffer(i).io.deq.bits
            }
        }
    }
}