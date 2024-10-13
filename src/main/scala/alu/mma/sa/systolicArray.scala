// See README.md for license details
package alu.mma.sa

import chisel3._


/**
 * This is the neural core design
 */
 class SystolicArray2D(val n: Int = 8, val nbits: Int = 8) extends Module {
    val io = IO(new Bundle {
        val vec_a       = Input(Vec(n, UInt(nbits.W)))  // vector `a` is the left input
        val vec_b       = Input(Vec(n, UInt(nbits.W)))  // vector `b` is the top input
        val out_a       = Output(Vec(n * n, UInt(nbits.W)))
        val out_b       = Output(Vec(n * n, UInt(nbits.W)))

    })

    // Create 2d register for horizontal & vertical
    val reg_h = RegInit(VecInit(Seq.fill((n - 1) * n)(0.U(nbits.W))))
    val reg_v = RegInit(VecInit(Seq.fill((n - 1) * n)(0.U(nbits.W))))

    for (i <- 0 until n){
        for (j <- 0 until n) {

            // ==== INPUT ====
            // vertical
            if (i==0) {
                io.out_b(j) := io.vec_b(j)
            } else {
                io.out_b(n * i + j) := reg_v(n * (i - 1) + j)
            }
            if (i < n - 1 && j < n)
                reg_v(n * i + j) := io.out_b(n * i + j)

            // horizontal
            if (j==0) {
                io.out_a(n * i) := io.vec_a(i)
            } else {
                io.out_a(n * i + j) := reg_h((n - 1) * i + (j - 1))
            }
            if (i < n && j < n - 1)
                reg_h((n - 1) * i + j) := io.out_a(n * i + j)
        }
    }
 }