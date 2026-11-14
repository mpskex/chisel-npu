// See README.md for license details
package alu.mma.cu

import chisel3._
import utils.gates._
import isa.micro_op._

/**
 * Control unit also uses systolic array to pass instructions
 */
class ControlUnit(val n: Int = 8) extends Module {
    val io = IO(new Bundle {
        val cbus_in         = Input(new NCoreMMALUCtrlBundle())
        val cbus_out        = Output(Vec(n * n, new NCoreMMALUCtrlBundle()))
        val cbus_dat_clct   = Output(Bool())
        val cbus_use_accum  = Output(Bool())
        val clct            = Output(Bool())
    })
    // Assign each element with diagnal control signal
    val reg = RegInit(VecInit(Seq.fill(2*n-1)(0.U.asTypeOf(new NCoreMMALUCtrlBundle()))))
    // val clct = Wire(Bool())
    val or_g = Module{new ORGate(2*n-1)}
    io.cbus_dat_clct :<>= or_g.io.out
    io.clct :<>= reg(2 * n - 2).busy
    io.cbus_use_accum :<>= reg(2 * n - 2).use_accum

    // 1D systolic array for control
    reg(0) := io.cbus_in
    // Generate the collect window from the FEED (busy), not the accumulate
    // control (keep).  `keep` is also the PE reset control: a per-round
    // first-feed/reset pulse must not punch a hole in the collector window
    // (doing so reset the collector phase and produced a zero capture).  The
    // window is the OR of busy over the same 2n-1 control pipeline that
    // broadcasts `reg(i+j)` to the array.
    or_g.io.in(0) := io.cbus_in.busy
    for (i <- 0 until 2*n-2){
        or_g.io.in(i+1) := reg(i).busy
    }
    for(i<- 1 until 2*n-1){
        reg(i) := reg(i-1)
    }
    // Boardcast to all elements in the array
    for(i <- 0 until n){
        for(j <- 0 until n){
            io.cbus_out(n*i+j) := reg(i+j)
        }
    }
}