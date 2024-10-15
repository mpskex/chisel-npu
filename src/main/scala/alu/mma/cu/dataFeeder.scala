// See README.md for license details
package alu.mma.cu

import chisel3._
import chisel3.util._
import isa.micro_op._

/**
 * Data Feeder takes N ticks to consume all data
 */
class DataFeeder(val n: Int = 8, val nbits: Int = 8) extends Module {
    val io = IO(new Bundle {
        val cbus_in         = Input(new NCoreMMALUBundle())
        val reg_a_in        = Input(Vec(n * n, UInt(nbits.W)))
        val reg_b_in        = Input(Vec(n * n, UInt(nbits.W)))
        val reg_a_out       = Output(Vec(n, UInt(nbits.W)))
        val reg_b_out       = Output(Vec(n, UInt(nbits.W)))
    })

    val buffer_a = (1 until n map(x => Module(new Pipe(UInt(nbits.W), x))))
    val buffer_b = (1 until n map(x => Module(new Pipe(UInt(nbits.W), x))))
    val (cnt, counterWrap) = Counter(true.B, n)

    for (i <- 0 until n - 1) {
        // buffer_a(i).io.deq.ready := true.B
        // buffer_b(i).io.deq.ready := true.B
        buffer_a(i).io.enq.valid := true.B
        buffer_b(i).io.enq.valid := true.B
    }

    // chainsaw layout
    for (i <- 0 until n) {
        val ind = Wire(UInt(log2Ceil(n*n).W))
        val ind_trans = Wire(UInt(log2Ceil(n*n).W))
        ind := i.U * n.U + cnt
        ind_trans := n.U * cnt + i.U
        if (i == 0) {
            io.reg_a_out(i) := Mux(io.cbus_in.dat_trans_a, 
                io.reg_a_in(ind_trans), io.reg_a_in(ind))
            io.reg_b_out(i) := Mux(io.cbus_in.dat_trans_b, 
                io.reg_b_in(ind_trans), io.reg_b_in(ind))
        } else {
            io.reg_a_out(i) := buffer_a(i-1).io.deq.bits
            io.reg_b_out(i) := buffer_b(i-1).io.deq.bits
            
            buffer_a(i-1).io.enq.bits := Mux(io.cbus_in.dat_trans_a,
                io.reg_a_in(ind_trans), io.reg_a_in(ind))
            buffer_b(i-1).io.enq.bits := Mux(io.cbus_in.dat_trans_b,
                io.reg_b_in(ind_trans), io.reg_b_in(ind))
        }
    }

}