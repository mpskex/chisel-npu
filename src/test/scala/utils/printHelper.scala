
package testUtil

import chisel3._
import chiseltest._

class PrintHelper(){
    def printMatrix(mat: Array[Int], n: Int): Unit = {
        println("[")
        for (i <- 0 until n) {
            var _row = ""
            for (j <- 0 until n) {
                _row += mat(i * n + j).toString() + ", "
            }
            println("[" + _row + "],")
        }
        println("]")
    }

    def printMatrixChisel(mat: chisel3.Vec[chisel3.UInt], n: Int): Unit = {
        println("[")
        for (i <- 0 until n) {
            var _row = ""
            for (j <- 0 until n) {
                _row += mat(i * n + j).peekInt().toString() + ", "
            }
            println("[" + _row + "],")
        }
        println("]")
    }

    def printVectorChisel(vec: chisel3.Vec[chisel3.UInt], n: Int): Unit = {
        var _row = ""
        for (i <- 0 until n) {
            _row += vec(i).peekInt().toString() + ", "
        }
        println("[" + _row + "]")
    }
}