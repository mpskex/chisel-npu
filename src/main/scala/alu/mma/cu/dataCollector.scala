// See README.md for license details
package alu.mma.cu

import chisel3._
import chisel3.util._
import isa.micro_op._

/**
 * Data Collector takes N ticks to collect all data
 * Data Collector takes N - 1 tickes to boot up (with data)
 */
class DataCollector(val n: Int = 8, val nbits: Int = 8) extends Module {
    val io = IO(new Bundle {
        val cbus_in         = Input(new NCoreMMALUBundle())
        val reg_in          = Input(Vec(n * n, UInt(nbits.W)))
        val reg_out         = Output(Vec(n, UInt(nbits.W)))
    })

    val buffer = (0 until n - 1 map(x => Module(new Pipe(UInt(nbits.W), (n - x - 1)))))
    val (cnt, counterWrap) = Counter(0 until n, true.B, !io.cbus_in.dat_collect)

    // chainsaw layout
    for (i <- 0 until n) {
        val col = (cnt - i.U) % n.U
        if (i == n - 1) {
            io.reg_out(i) := io.reg_in((i * n).U + col)
        } else {
            buffer(i).io.enq.valid := true.B
            io.reg_out(i) := buffer(i).io.deq.bits
            buffer(i).io.enq.bits := io.reg_in((i * n).U + col)
        }
    }
}