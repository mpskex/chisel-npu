// See README.md for license details.

package ncore.tcm

import chisel3._
import chisel3.util._
import isa._

class TCMCell(val nbits: Int = 8) extends Module {
    val io = IO(
        new Bundle {
            val d_in    = Input(UInt(nbits.W))
            val d_out   = Output(UInt(nbits.W))
            val en_wr   = Input(Bool())
        }
    )

    val reg = RegInit(0.U(nbits.W))
    io.d_out := reg

    when (io.en_wr) {
        reg := io.d_in
    }
}

class TCMBlock(val n: Int = 8, 
               val size: Int = 4096,
               val r_addr_width: Int = 12,
               val w_addr_width: Int = 12,
               val nbits: Int = 8
) extends Module {
    val io = IO(
        new Bundle {
            val d_in    = Input(Vec(n * n, UInt(nbits.W)))
            val d_out   = Output(Vec(n * n, UInt(nbits.W)))
            val r_addr  = Input(Vec(n * n, UInt(r_addr_width.W)))
            val w_addr  = Input(Vec(n * n, UInt(w_addr_width.W)))
            val en_wr   = Input(Bool())
        }
    )
    val cells_io = VecInit(Seq.fill(size) {Module(new TCMCell(nbits)).io})

    for (i <- 0 until size) {
        cells_io(i).en_wr := false.B.asTypeOf(cells_io(i).en_wr)
        // Need to initialize all wires just in case of not selected.
        cells_io(i).d_in := 0.U.asTypeOf(cells_io(i).d_in)
    }

    //TODO: add range check
    //TODO: add read & write conflict check

    for (i <- 0 until n * n) {
        io.d_out(i) := cells_io(io.r_addr(i)).d_out
        when (io.en_wr) {
            cells_io(io.w_addr(i)).en_wr := io.en_wr
            cells_io(io.w_addr(i)).d_in := io.d_in(i)
        }
    }
}


class DetachableTCM(
    val n: Int = 8, 
    val size: Int = 4096,
    val r_addr_width: Int = 12,
    val w_addr_width: Int = 12,
    val mlayout_width: Int = 6,
) extends Module {
    val io = IO(new Bundle {
        val d_in    = Input(Vec(n * n, UInt(32.W)))
        val d_out   = Output(Vec(n * n, UInt(32.W)))
        // read address will have channel selection for last 2 bits
        val r_addr  = Input(Vec(n * n, UInt((r_addr_width + 2).W)))
        // write address will have channel selection for last 2 bits
        val w_addr  = Input(Vec(n * n, UInt((w_addr_width + 2).W)))
        val mem_ch  = Input(MemChannel())
        val mem_lo  = Input(MemLayout())
        val en_wr   = Input(Bool())
    })

    switch (io.mem_lo) {
        is (MemLayout.bit8) {
            
        }
        is (MemLayout.bit16) {

        }
        is (MemLayout.bit32) {

        }
    }
    
}