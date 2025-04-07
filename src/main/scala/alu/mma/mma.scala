// See README.md for license details
package alu.mma
import isa.micro_op._
import alu.pe._

import chisel3.util._
import chisel3._

/**
 * This is a general Matrix Multiplication ALU design
 * You can always implement your own PE module here.
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
    dclct.io.accum_in <> dfeed.io.reg_accum_out



    // we use systolic array to pipeline the instructions
    // this will avoid bubble and inst complexity 
    // while simplifying design with higher efficiency
    val ctrl_array = Module(new cu.ControlUnit(n))
    ctrl_array.io.cbus_in := io.ctrl
    dclct.io.dat_clct <> ctrl_array.io.cbus_dat_clct
    io.clct <> ctrl_array.io.cbus_dat_clct
    dclct.io.use_accum <> ctrl_array.io.cbus_use_accum

    val sarray = Module(new sa.SystolicArray2D(n, nbits))
    sarray.io.vec_a := dfeed.io.reg_a_out
    sarray.io.vec_b := dfeed.io.reg_b_out

    io.out <> dclct.io.reg_out

    for (i <- 0 until n){
        for (j <- 0 until n) {
            pe_io(n * i + j).in_a := sarray.io.out_a(n * i + j)
            pe_io(n * i + j).in_b := sarray.io.out_b(n * i + j)
            pe_io(n * i + j).ctrl := ctrl_array.io.cbus_out(n * i + j)
            dclct.io.reg_in(n * i + j) <> pe_io(n * i + j).out
        }
    }
 }