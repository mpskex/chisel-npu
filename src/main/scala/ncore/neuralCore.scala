// See README.md for license details
package ncore
import isa.backend._
import pe._

import chisel3._


/**
 * This is the neural core design
 */
 class NeuralCore(val n: Int = 8, val nbits: Int = 8, val sram_size: Int = 4096) extends Module {
    val io = IO(new Bundle {
        val vec_a   = Input(Vec(n, UInt(nbits.W)))  // vector `a` is the left input
        val vec_b   = Input(Vec(n, UInt(nbits.W)))  // vector `b` is the top input
        val ctrl    = Input(new NCoreCUBundle())
        val out     = Output(Vec(n * n, UInt((2 * nbits + 12).W)))
    })

    // Create n x n pe blocks
    val pe_io = VecInit(Seq.fill(n * n) {Module(new pe.PE(nbits)).io})

    // we use systolic array to pipeline the instructions
    // this will avoid bubble and inst complexity 
    // while simplifying design with higher efficiency
    val ctrl_array = Module(new cu.ControlUnit(n, sram_size))
    ctrl_array.io.cbus_in := io.ctrl

    val sarray = Module(new sa.SystolicArray2D(n, nbits))
    sarray.io.vec_a := io.vec_a
    sarray.io.vec_b := io.vec_b

    for (i <- 0 until n){
        for (j <- 0 until n) {
            pe_io(n * i + j).in_a := sarray.io.out_a(n * i + j)
            pe_io(n * i + j).in_b := sarray.io.out_b(n * i + j)
            pe_io(n * i + j).ctrl := ctrl_array.io.cbus_out(n * i + j)
            io.out(n * i + j) := pe_io(n * i + j).out
        }
    }
 }