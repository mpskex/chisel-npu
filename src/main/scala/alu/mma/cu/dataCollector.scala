// See README.md for license details
package alu.mma.cu

import chisel3._
import chisel3.util._
import isa.micro_op._

/**
 * Data Collector take N ticks to collect all data
 */
class DataCollector(val n: Int = 8, val nbits: Int = 8) extends Module {
    val io = IO(new Bundle {
        val cbus_in         = Input(new NCoreMMALUBundle())
        val reg_in          = Input(Vec(n * n, UInt(nbits.W)))
        val reg_out         = Decoupled(Vec(n, UInt(nbits.W)))
    })

    val buffer = (0 until n - 1 map(x => Module(new Pipe(UInt(nbits.W), (n - x - 1)))))
    val (cnt, counterWrap) = Counter(io.cbus_in.dat_collect, 2 * n-1)

    // chainsaw layout
    for (i <- 0 until n) {
        when (cnt >= i.U) {
            if (i == n - 1) {
                io.reg_out.bits(i) := io.reg_in(i.U * n.U + cnt - i.U)
            } else {
                io.reg_out.bits(i) := buffer(i).io.deq.bits
                buffer(i).io.enq.valid := true.B
                buffer(i).io.enq.bits := io.reg_in(i.U * n.U + cnt - i.U)
            }
            io.reg_out.valid := true.B
        } .otherwise {
            if (i != n - 1) {
                buffer(i).io.enq.valid := true.B
                buffer(i).io.enq.bits := 0.U
            }
            io.reg_out.bits(i) := 0.U
            io.reg_out.valid := false.B
        }
    }
}