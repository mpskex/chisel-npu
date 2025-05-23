// See README.md for license details
package alu.mma.sa

import chisel3._
import chisel3.util._
import isa.micro_op._

/**
 * Data Feeder takes N ticks to consume all data
 */
class DataFeeder(val n: Int = 8, val nbits: Int = 8, val accum_nbits: Int = 32) extends Module {
    val io = IO(new Bundle {
        val reg_a_in        = Input(Vec(n, SInt(nbits.W)))
        val reg_b_in        = Input(Vec(n, SInt(nbits.W)))
        val reg_accum_in    = Input(Vec(n, SInt(accum_nbits.W)))
        val reg_a_out       = Output(Vec(n, SInt(nbits.W)))
        val reg_b_out       = Output(Vec(n, SInt(nbits.W)))
        val reg_accum_out   = Output(Vec(n, SInt(accum_nbits.W)))
    })

    val buffer_a = (1 until n map(x => Module(new Pipe(SInt(nbits.W), x))))
    val buffer_b = (1 until n map(x => Module(new Pipe(SInt(nbits.W), x))))
    val buffer_accum = Module(new Pipe(Vec(n, SInt(accum_nbits.W)), 2 * n - 1))

    // TODO: replace the enqueue as io port
    for (i <- 0 until n - 1) {
        buffer_a(i).io.enq.valid := true.B
        buffer_b(i).io.enq.valid := true.B
    }
    buffer_accum.io.enq.valid := true.B
    buffer_accum.io.enq.bits :<>= io.reg_accum_in
    io.reg_accum_out :<>= buffer_accum.io.deq.bits

    // chainsaw layout
    for (i <- 0 until n) {
        if (i == 0) {
            io.reg_a_out(i) := io.reg_a_in(i)
            io.reg_b_out(i) := io.reg_b_in(i)
        } else {
            io.reg_a_out(i) := buffer_a(i-1).io.deq.bits
            io.reg_b_out(i) := buffer_b(i-1).io.deq.bits
            
            buffer_a(i-1).io.enq.bits := io.reg_a_in(i)
            buffer_b(i-1).io.enq.bits := io.reg_b_in(i)
        }
    }

}