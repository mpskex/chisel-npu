
package backend
import chisel3._
import chisel3.util._

import alu.mma._
import alu.pe._
import isa._
import sram.register._

class NCoreBackend(
    n: Int = 8, 
    nbits: Int = 8,
    num_reg_file: Int = 32,
) extends Module {
    val io = IO(
        new Bundle {
            val micro_op = Input(new NeuralCoreMicroOp())
        }
    )

    val reg_files = Module{ new RegisterBlock(2, 1, num_reg_file * n * nbits, n * nbits)}
    val mmalu_1 = Module{ new MMALU(new MMPE(nbits), n, nbits)}
    mmalu_1.io.in_a <> reg_files.io.d_out(0)
    mmalu_1.io.in_b <> reg_files.io.d_out(1)
    reg_files.io.d_in(0) <> mmalu_1.io.out 
    when (io.micro_op.opcode === _OpCode.mma) {
         
    }
}
