
package testUtil

import chisel3._
import chisel3.simulator.EphemeralSimulator._

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

    def printVector(vec: Array[Int], n: Int): Unit = {
        var _row = ""
        for (i <- 0 until n) {
            _row += vec(i).toString() + ", "
        }
        println("[" + _row + "],")
    }

    def printMatrixChisel(mat: chisel3.Vec[chisel3.SInt], n: Int): Unit = {
        println("[")
        for (i <- 0 until n) {
            var _row = ""
            for (j <- 0 until n) {
                _row += mat(i * n + j).peek().litValue.toString() + ", "
            }
            println("[" + _row + "],")
        }
        println("]")
    }

    def printVectorChisel(vec: chisel3.Vec[chisel3.SInt], n: Int): Unit = {
        var _row = ""
        for (i <- 0 until n) {
            _row += vec(i).peek().litValue.toString() + ", "
        }
        println("[" + _row + "]")
    }
}