package utils.gates

import chisel3._
import chisel3.util._

class ORGate(val n: Int = 8) extends Module {
    val io = IO(new Bundle{
        val in      = Input(Vec(n, Bool()))
        val out     = Output(Bool())
    })
    io.out := io.in.reduce((a, b) => a | b)
} 