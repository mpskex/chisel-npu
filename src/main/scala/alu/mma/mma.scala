// See README.md for license details
package alu.mma
import isa.micro_op._
import alu.pe._

import chisel3.util._
import chisel3._


/**
 * This is the neural core design
 */
 class MMALU(val n: Int = 8, val nbits: Int = 8) extends Module {
    val io = IO(new Bundle {
        val in_a        = Input(Vec(n, UInt(nbits.W)))
        val in_b        = Input(Vec(n, UInt(nbits.W)))
        val ctrl        = Input(new NCoreMMALUBundle())
        val out         = Output(Vec(n, UInt((2 * nbits + 12).W)))
    })

    // Create n x n pe blocks
    val pe_io = VecInit(Seq.fill(n * n) {Module(new PE(nbits)).io})
    val dfeed = Module(new cu.DataFeeder(n, nbits))
    val dclct = Module(new cu.DataCollector(n, 2 * nbits + 12))
    dfeed.io.reg_a_in <> io.in_a
    dfeed.io.reg_b_in <> io.in_b
    dclct.io.cbus_in <> io.ctrl



    // we use systolic array to pipeline the instructions
    // this will avoid bubble and inst complexity 
    // while simplifying design with higher efficiency
    val ctrl_array = Module(new cu.ControlUnit(n))
    ctrl_array.io.cbus_in := io.ctrl

    val sarray = Module(new sa.SystolicArray2D(n, nbits))
    sarray.io.vec_a := dfeed.io.reg_a_out
    sarray.io.vec_b := dfeed.io.reg_b_out

    io.out <> dclct.io.reg_out

    for (i <- 0 until n){
        for (j <- 0 until n) {
            pe_io(n * i + j).in_a := sarray.io.out_a(n * i + j)
            pe_io(n * i + j).in_b := sarray.io.out_b(n * i + j)
            pe_io(n * i + j).ctrl := ctrl_array.io.cbus_out(n * i + j)
            // io.out(n * i + j) := pe_io(n * i + j).out
            dclct.io.reg_in(n * i + j) <> pe_io(n * i + j).out
        }
    }
 }