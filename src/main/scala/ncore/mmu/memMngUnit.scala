// See README.md for license details.

package ncore.mmu

import chisel3._
import chisel3.util._
import isa.backend._
import ncore._
import ncore.tcm._

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


class MemoryControlArray(val n: Int = 8) extends Module {
    val io = IO(new Bundle {
        val ctrl_in_a       = Input(Bool())
        val ctrl_in_b       = Input(Bool())
        val offset_inc_in   = Input(Bool())
        val ctrl_out_a      = Output(Vec(n, Bool()))
        val ctrl_out_b      = Output(Vec(n, Bool()))
        val offset_inc_out  = Output(Vec((n-1) * (n-1), Bool()))
    })
    // Assign each element with diagnal control signal
    val reg_inc = RegInit(VecInit(Seq.fill(2*n - 3)(0.B)))
    val reg_a = RegInit(VecInit(Seq.fill(n)(0.B)))
    val reg_b = RegInit(VecInit(Seq.fill(n)(0.B)))

    reg_a(0) := io.ctrl_in_a(0)
    reg_b(0) := io.ctrl_in_b(0)
    for (i <- 1 until n - 1) {
        reg_a(i) := reg_a(i-1)
        reg_b(i) := reg_b(i-1)
    }
    
    for (i <- 0 until n) {
        io.ctrl_out_a(i) := reg_a(i)
        io.ctrl_out_b(i) := reg_b(i)
    }

    reg_inc(0) := io.offset_inc_in
    for (i <- 0 until 2 * n - 3) {
        reg_inc(i) := reg_inc(i - 1)
    }
    for (i <- 0 until n - 1) {
        for (j <- 0 until n - 1) {
            io.offset_inc_out(n * i + j) := reg_inc(i + j)
        }
    }
}

/**
 * This is the neural core design
 */
class MemoryManageUnit(
    val n: Int = 8, 
    val nbits: Int = 8, 
    val word_size: Int = 4, 
    val size: Int = 4096
    ) extends Module {
    val io = IO(new Bundle {
        val base_addr   = Input(UInt(log2Ceil(size).W))
        val ctrl        = Input(Vec(n * n, new MMUBundle()))
        val out_a       = Output(Vec(n * n, UInt(32.W)))
        val out_b       = Output(Vec(n * n, UInt(32.W)))
    })

    val offsetgen_a = new OffsetGenerator(n)
    val offsetgen_b = new OffsetGenerator(n)

    val mem = new DetachableTCM(n, word_size, size, 2)

    // Create 2d register for horizontal & vertical
    val reg_h = RegInit(VecInit(Seq.fill((n - 1) * n)(0.U(nbits.W))))
    val reg_v = RegInit(VecInit(Seq.fill((n - 1) * n)(0.U(nbits.W))))

    for (i <- 0 until n){
        for (j <- 0 until n) {
            // ==== INPUT ====
            // vertical
            if (i==0) {
                mem.io.r_addr(0)(j) := io.base_addr + offsetgen_b.io.out(j)
            } else {
                mem.io.r_addr(0)(n * i + j) := reg_v(n * (i - 1) + j)
            }
            if (i < n - 1 && j < n)
                reg_v(n * i + j) := mem.io.r_addr(0)(n * i + j)

            // horizontal
            if (j==0) {
                mem.io.r_addr(1)(n * i) := io.base_addr + offsetgen_a.io.out(i)
            } else {
                mem.io.r_addr(1)(n * i + j) := reg_h((n - 1) * i + (j - 1))
            }
            if (i < n && j < n - 1)
                reg_h((n - 1) * i + j) := mem.io.r_addr(1)(n * i + j)
        }
    }

}