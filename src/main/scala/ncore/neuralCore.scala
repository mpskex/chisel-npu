// See README.md for license details
package ncore

import chisel3._

/**
 * This is the neural core design
 */
 class NeuralCoreforTest(val n: Int = 8, val nbits: Int = 8, val ctrl_width: Int = 8) extends Module {
    val io = IO(new Bundle {
        val vec_a   = Input(Vec(n, UInt(nbits.W)))  // vector `a` is the left input
        val vec_b   = Input(Vec(n, UInt(nbits.W)))  // vector `b` is the top input
        val ctrl    = Input(UInt(ctrl_width.W))
        val out     = Output(Vec(n * n, UInt((2 * nbits + 12).W)))
    })

    // Create n x n pe blocks
    val pe_io = VecInit(Seq.fill(n * n) {Module(new pe.PE(nbits)).io})
    // Create 2d register for horizontal & vertical
    val pe_reg_h = RegInit(VecInit(Seq.fill((n - 1) * n)(0.U(nbits.W))))
    val pe_reg_v = RegInit(VecInit(Seq.fill((n - 1) * n)(0.U(nbits.W))))

    // we use systolic array to pipeline the instructions
    // this will avoid bubble and inst complexity 
    // while simplifying design with higher efficiency
    val ctrl_array = Module(new cu.ControlUnit(n, ctrl_width))
    ctrl_array.io.cbus_in := io.ctrl

    for (i <- 0 until n){
        for (j <- 0 until n) {
            // ==== OUTPUT ====
            // pe array's output mapped to the matrix position
            io.out(n * i + j) := pe_io(n * i + j).out

            // ==== INPUT ====
            // vertical
            if (i==0) {
                pe_io(j).in_b := io.vec_b(j)
            } else {
                pe_io(n * i + j).in_b := pe_reg_v(n * (i - 1) + j)
            }
            if (i < n - 1 && j < n)
                pe_reg_v(n * i + j) := pe_io(n * i + j).in_b

            // horizontal
            if (j==0) {
                pe_io(n * i).in_a := io.vec_a(i)
            } else {
                pe_io(n * i + j).in_a := pe_reg_h((n - 1) * i + (j - 1))
            }
            if (i < n && j < n - 1)
                pe_reg_h((n - 1) * i + j) := pe_io(n * i + j).in_a

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