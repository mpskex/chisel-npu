// See README.md for license details
package ncore.cu

import chisel3._

/**
 * Control unit also uses systolic array to pass instructions
 */
class ControlUnit(val n: Int = 8, val ctrl_width: Int = 8) extends Module {
    val io = IO(new Bundle {
        val cbus_in     = Input(UInt(ctrl_width.W))
        val cbus_out    = Output(Vec(n * n, UInt(ctrl_width.W)))
    })
    // Assign each element with diagnal control signal
    val reg = RegInit(VecInit(Seq.fill(2*n-1)(0.U(ctrl_width.W))))

    // 1D systolic array for control
    reg(0) := io.cbus_in
    for(i<- 1 until 2*n-1){
        reg(i) := reg(i-1)
    }
    // Boardcast to all elements in the array
    for(i <- 0 until n){
        for(j <- 0 until n){
            io.cbus_out(n*i+j) := reg(i+j)
        }
    }
}