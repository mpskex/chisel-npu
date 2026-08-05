// See README.md for license details
package alu.mma
import isa.micro_op._
import alu.pe._

import chisel3.util._
import chisel3._

/**
 * This is a general Matrix Multiplication ALU design
 * You can always implement your own PE module here.
 *
 * Timing note (2026-07-30):
 *   The critical path at 200 MHz ran from SystolicArray2D horizontal
 *   registers (reg_h) through 13 logic levels (8×CARRY4 + 5×LUT) into
 *   the PE multiply-accumulate chain, causing WNS = -0.151 ns.
 *   Fix: pipeline registers were inserted between the systolic array
 *   outputs and the PE inputs (pipe_a, pipe_b, pipe_ctrl), and between
 *   the ControlUnit and DataCollector (pipe_dat_clct, pipe_use_accum).
 *   This adds 1 cycle to the MMALU latency (3n-1 instead of 3n-2)
 *   but breaks the long combinatorial path into two 6-7 level segments.
 */
 class MMALU[T <: BasePE](pe_gen: => BasePE, val n: Int = 8, val nbits: Int = 8, val accum_nbits: Int = 32) extends Module {
    val io = IO(new Bundle {
        val in_a        = Input(Vec(n, SInt(nbits.W)))
        val in_b        = Input(Vec(n, SInt(nbits.W)))
        val in_accum    = Input(Vec(n, SInt(accum_nbits.W)))
        val ctrl        = Input(new NCoreMMALUCtrlBundle())
        val out         = Output(Vec(n, SInt(accum_nbits.W)))
        val clct        = Output(Bool())
    })

    // Create n x n pe blocks
    val pe_io = VecInit(Seq.fill(n * n) {Module(pe_gen).io})
    val dfeed = Module(new sa.DataFeeder(n, nbits, accum_nbits))
    val dclct = Module(new sa.DataCollector(n, accum_nbits))
    dfeed.io.reg_a_in <> io.in_a
    dfeed.io.reg_b_in <> io.in_b
    dfeed.io.reg_accum_in <> io.in_accum

    // we use systolic array to pipeline the instructions
    // this will avoid bubble and inst complexity 
    // while simplifying design with higher efficiency
    val ctrl_array = Module(new cu.ControlUnit(n))
    ctrl_array.io.cbus_in := io.ctrl

    // Pipeline registers between SA→PE and CU→Collector to break
    // the long combinatorial path (reg_h → PE mul/acc) that caused
    // WNS = -0.151 ns at 200 MHz on xc7k480t (K=32).
    // These are single-cycle registered pipelines; the +1 latency
    // shift is consistent across data and control paths.
    val pipe_a       = RegInit(VecInit(Seq.fill(n * n)(0.S(nbits.W))))
    val pipe_b       = RegInit(VecInit(Seq.fill(n * n)(0.S(nbits.W))))
    val pipe_ctrl    = RegInit(VecInit(Seq.fill(n * n)(0.U.asTypeOf(new NCoreMMALUCtrlBundle()))))
    val pipe_accum   = RegInit(VecInit(Seq.fill(n)(0.S(accum_nbits.W))))
    val pipe_dat_clct = RegNext(false.B)
    val pipe_use_accum = RegNext(false.B)
    val pipe_clct    = RegNext(false.B)

    for (i <- 0 until n) {
        pipe_accum(i)           := dfeed.io.reg_accum_out(i)
        dclct.io.accum_in(i)    := pipe_accum(i)
    }
    dclct.io.dat_clct  := pipe_dat_clct
    dclct.io.use_accum := pipe_use_accum
    io.clct            := pipe_clct

    val sarray = Module(new sa.SystolicArray2D(n, nbits))
    sarray.io.vec_a := dfeed.io.reg_a_out
    sarray.io.vec_b := dfeed.io.reg_b_out

    io.out <> dclct.io.reg_out

    for (i <- 0 until n){
        for (j <- 0 until n) {
            pipe_a(n * i + j)    := sarray.io.out_a(n * i + j)
            pipe_b(n * i + j)    := sarray.io.out_b(n * i + j)
            pipe_ctrl(n * i + j) := ctrl_array.io.cbus_out(n * i + j)
            pe_io(n * i + j).in_a  := pipe_a(n * i + j)
            pe_io(n * i + j).in_b  := pipe_b(n * i + j)
            pe_io(n * i + j).ctrl  := pipe_ctrl(n * i + j)
            dclct.io.reg_in(n * i + j) <> pe_io(n * i + j).out
        }
    }

    pipe_dat_clct  := ctrl_array.io.cbus_dat_clct
    pipe_use_accum := ctrl_array.io.cbus_use_accum
    pipe_clct      := ctrl_array.io.clct
 }
