// See README.md for license details.

package sram

import chisel3._
import chisel3.util._

class SRAMCell(val nbits: Int = 8) extends Module {
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

class SRAMBlock(val n: Int = 8, 
               val size: Int = 4096,
               val rd_ch_num: Int = 2,
               val nbits: Int = 8
) extends Module {
    val io = IO(
        new Bundle {
            val d_in    = Input(Vec(n * n, UInt(nbits.W)))
            val d_out   = Output(Vec(rd_ch_num, Vec(n * n, UInt(nbits.W))))
            val r_addr  = Input(Vec(rd_ch_num, Vec(n * n, UInt(log2Ceil(size).W))))
            val w_addr  = Input(Vec(n * n, UInt(log2Ceil(size).W)))
            val en_wr   = Input(Bool())
        }
    )
    val cells_io = VecInit(Seq.fill(size) {Module(new SRAMCell(nbits)).io})

    for (i <- 0 until size) {
        cells_io(i).en_wr := false.B.asTypeOf(cells_io(i).en_wr)
        // Need to initialize all wires just in case of not selected.
        cells_io(i).d_in := 0.U.asTypeOf(cells_io(i).d_in)
    }

    //TODO: add range check
    //TODO: add read & write conflict check

    for (i <- 0 until n * n) {
        for (k <- 0 until rd_ch_num) {
            io.d_out(k)(i) := cells_io(io.r_addr(k)(i)).d_out
        }
        when (io.en_wr) {
            cells_io(io.w_addr(i)).en_wr := io.en_wr
            cells_io(io.w_addr(i)).d_in := io.d_in(i)
        }
    }
}


class SRAM(
    val n: Int = 8,
    val nblocks: Int = 4,
    val size: Int = 4096,
    val rd_ch_num: Int = 2,
) extends Module {
    val io = IO(new Bundle {
        val d_in        = Input(Vec(n * n, Vec(nblocks, UInt(8.W))))
        val d_out       = Output(Vec(rd_ch_num, Vec(n * n, Vec(nblocks, UInt(8.W)))))
        val r_addr      = Input(Vec(rd_ch_num, Vec(n * n, UInt(log2Ceil(size).W))))
        val w_addr      = Input(Vec(n * n, UInt(log2Ceil(size).W)))
        val en_wr       = Input(Bool())
    })

    val sram_blocks_io  = VecInit(Seq.fill(nblocks) {
        Module(new SRAMBlock(n, size, rd_ch_num, 8)).io})
    
    for (i <- 0 until nblocks) {
        sram_blocks_io(i).en_wr := io.en_wr
        for (j <- 0 until n) {
            for (k <- 0 until rd_ch_num) {
                sram_blocks_io(i).r_addr(k)(j) := io.r_addr(k)(j)
            }
            sram_blocks_io(i).w_addr(j) := io.w_addr(j)
            sram_blocks_io(i).d_in(j) := io.d_in(j)(i)
            io.d_out(j)(i) := sram_blocks_io(i).d_out(j)
        }
    }
    
}