// See README.md for license details.

package ncore.mmu

import chisel3._
import chisel3.util._
import isa.backend._
import ncore._

class MMUBundle extends Bundle {
    val mem_ch  = MemChannel()
    val mem_lo  = MemLayout()
}

class OffsetGenerator(val n: Int = 8) extends Module {
    val io = IO(new Bundle {
        val inc     = Input(Vec(n, Bool()))
        val out     = Output(Vec(n, UInt(log2Ceil(n * n).W)))
    })
    val init_value = Seq.tabulate(n)(i => (n * i).U(log2Ceil(n * n).W))
    val regs = RegInit(VecInit(init_value))

    for (i <- 0 until n){
        when (io.inc(i)) {
            regs(i) := (regs(i) + 1.U) % (n * n).U
        }.otherwise {
            regs(i) := init_value(i)
        }
        io.out(i) := regs(i)
    }
}

/**
 * This is the neural core design
 */
class MemoryManageUnit(
    val n: Int = 8, val nbits: Int = 8, val addr_width: Int = 24
    ) extends Module {
    val io = IO(new Bundle {
        val base_addr   = Input(Vec(n, UInt(24.W)))
        val ctrl        = Input(Vec(n * n, new MMUBundle()))
        val out_a       = Output(Vec(n * n, UInt(32.W)))
        val out_b       = Output(Vec(n * n, UInt(32.W)))
    })

    val offsetgen_a = new OffsetGenerator(n)
    val offsetgen_b = new OffsetGenerator(n)
}