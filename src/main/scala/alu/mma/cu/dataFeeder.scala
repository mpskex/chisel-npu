// See README.md for license details
package alu.mma.cu

import chisel3._
import chisel3.util._
import isa.micro_op._

/**
 * Data Feeder needs N tickst to boot up
 */
class DataFeeder(val n: Int = 8, val nbits: Int = 8) extends Module {
    val io = IO(new Bundle {
        val cbus_in         = Input(new NCoreMMALUBundle())
        val reg_a_in        = Input(Vec(n * n, UInt(nbits.W)))
        val reg_b_in        = Input(Vec(n * n, UInt(nbits.W)))
        val reg_a_out       = Output(Vec(n, UInt(nbits.W)))
        val reg_b_out       = Output(Vec(n, UInt(nbits.W)))
    })

    val buffer_a = (1 to n map(x => Module(new Queue(UInt(nbits.W), x))))
    val buffer_b = (1 to n map(x => Module(new Queue(UInt(nbits.W), x))))
    val (cnt, counterWrap) = Counter(io.cbus_in.dat_ena, n)

    // chainsaw layout
    for (i <- 0 until n) {
        val ind = Wire(UInt(log2Ceil(n*n).W))
        val ind_trans = Wire(UInt(log2Ceil(n*n).W))
        ind := i.U * n.U + cnt
        ind_trans := n.U * cnt + i.U
        if (i == 0) {
            when (io.cbus_in.dat_trans_a === true.B) {
                io.reg_a_out(i) := io.reg_a_in(ind)
            } .otherwise {
                io.reg_a_out(i) := io.reg_a_in(ind_trans)
            }
            when (io.cbus_in.dat_trans_a === true.B) {
                io.reg_b_out(i) := io.reg_b_in(ind)
            } .otherwise {
                io.reg_b_out(i) := io.reg_b_in(ind_trans)
            }
        } else {
            when (buffer_a(i-1).io.deq.ready) {
                io.reg_a_out(i) := buffer_a(i-1).io.deq.bits
            } .otherwise {
                io.reg_a_out(i) := 0.U
            }
            when (buffer_a(i-1).io.deq.ready) {
                io.reg_b_out(i) := buffer_b(i-1).io.deq.bits
            } .otherwise {
                io.reg_b_out(i) := 0.U
            }

            when (io.cbus_in.dat_trans_a === true.B) {
                buffer_a(i-1).io.enq.bits := io.reg_a_in(ind)
            } .otherwise {
                buffer_a(i-1).io.enq.bits := io.reg_a_in(ind_trans)
            }
            buffer_a(i-1).io.enq.valid := true.B
            when (io.cbus_in.dat_trans_a === true.B) {
                buffer_b(i-1).io.enq.bits := io.reg_b_in(ind)
            } .otherwise {
                buffer_b(i-1).io.enq.bits := io.reg_b_in(ind_trans)
            }
            buffer_b(i-1).io.enq.valid := true.B
        }
    }

}