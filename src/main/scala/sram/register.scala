// See README.md for license details.

package sram.register

import chisel3._
import chisel3.util._

class RegisterCell(val n: Int = 8, val nbits: Int = 8) extends Module {
    val io = IO(
        new Bundle {
            val d_in    = Input(Vec(n, SInt(nbits.W)))
            val d_out   = Output(Vec(n, SInt(nbits.W)))
            val en_wr   = Input(Bool())
        }
    )

    val reg = RegInit(VecInit(Seq.fill(n)(0.S(nbits.W))))
    io.d_out := reg

    when (io.en_wr) {
        reg := io.d_in
    }
}

class RegisterBlock(
    val n: Int = 8,
    val rd_banks: Int = 8,
    val wr_banks: Int = 4,
    val size: Int = 32,
    val nbits: Int = 8
) extends Module {
    val io = IO(
        new Bundle {
            val d_in    = Input(Vec(wr_banks, Vec(n, SInt(nbits.W))))
            val d_out   = Output(Vec(rd_banks, Vec(n, SInt(nbits.W))))
            val r_addr  = Input(Vec(rd_banks, UInt(log2Ceil(size).W)))
            val w_addr  = Input(Vec(rd_banks, UInt(log2Ceil(size).W)))
            val en_wr   = Input(Vec(wr_banks, Bool()))
        }
    )
    val cells_io = VecInit(Seq.fill(size) {Module(new RegisterCell(n, nbits)).io})

    for (i <- 0 until size) {
        cells_io(i).en_wr := false.B.asTypeOf(cells_io(i).en_wr)
        // Need to initialize all wires just in case of not selected.
        for (_i <- 0 until n){
          cells_io(i).d_in(_i) := 0.U.asTypeOf(cells_io(i).d_in(_i))
        }
    }

    //TODO: add range check
    //TODO: add write conflict check
    for (i <- 0 until rd_banks) {
        io.d_out(i) := cells_io(io.r_addr(i)).d_out
    }

    for (i <- 0 until wr_banks) {
        cells_io(io.w_addr(i)).en_wr := io.en_wr(i)
        cells_io(io.w_addr(i)).d_in := io.d_in(i)
    }
}
