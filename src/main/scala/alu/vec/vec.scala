// See README.md for license details

import chisel3.util._
import chisel3._
import isa.micro_op._

/**
 * This is the neural core design
 */
 class VALU(val n: Int = 8, val nbits: Int = 8) extends Module {
    val io = IO(new Bundle {
        val in_a        = Input(Vec(n, UInt(nbits.W)))
        val in_b        = Input(Vec(n, UInt(nbits.W)))
        val ctrl        = Input(new NCoreVALUBundle())
        val out         = Output(Vec(n, UInt((nbits).W)))
    })

    
 }