// See README.md for license details
package systolicArray

import chisel3._
import procElem._

/**
 * Control bus also uses systolic array to pass instructions
 */
class _ControlArray(val n: Int = 8, val ctrl_width: Int = 8) extends Module {
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

/**
 * This is the systolic array design
 */
 class SystolicArray(val n: Int = 8, val nbits: Int = 8, val ctrl_width: Int = 8) extends Module {
    val io = IO(new Bundle {
        val vec_a   = Input(Vec(n, UInt(nbits.W)))  // vector `a` is the left input
        val vec_b   = Input(Vec(n, UInt(nbits.W)))  // vector `b` is the top input
        val ctrl    = Input(UInt(ctrl_width.W))
        val out     = Output(Vec(n * n, UInt((2 * nbits + 12).W)))
    })

    // Create n x n pe blocks
    val pe_io = VecInit(Seq.fill(n * n) {Module(new PE(nbits)).io})

    // we use systolic array to pipeline the instructions
    // this will avoid bubble and inst complexity 
    // while simplifying design with higher efficiency
    val ctrl_array = Module(new _ControlArray(n, ctrl_width))
    ctrl_array.io.cbus_in := io.ctrl
    // for (i <- 0 until n * n) {
    //     pe_io(i).accum := io.ctrl(0)
    // }

    for (i <- 0 until n){
        for (j <- 0 until n) {
            // ==== OUTPUT ====
            // pe array's output mapped to the matrix position
            io.out(n * i + j) := pe_io(n * i + j).out

            // ==== INPUT ====
            // vertical
            if (i==0) {
                pe_io(j).top_in := io.vec_b(j)
            } else {
                pe_io(n * i + j).top_in := pe_io(n * (i - 1) + j).bottom_out
            }
            // horizontal
            if (j==0) {
                pe_io(n * i).left_in := io.vec_a(i)
            } else {
                pe_io(n * i + j).left_in := pe_io(n * i + (j - 1)).right_out
            }

            // ==== CONTROL ====
            // Currently we only have one bit control
            // which is `ACCUM`
            // TODO:
            // Add ALU control to pe elements
            val ctrl = ctrl_array.io.cbus_out(n * i + j).asBools
            pe_io(n * i + j).accum := ctrl(0)
        }
    }
 }