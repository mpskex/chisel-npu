//// See README.md for license details.

package alu.mma.cu

import testUtil._
import scala.util.Random
import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec
import chisel3.experimental.BundleLiterals._

class CUSpec extends AnyFlatSpec {

    "CU" should "send control to 2D systolic array" in {
        simulate(new ControlUnit(4)) { dut =>
            val print_helper = new testUtil.PrintHelper()
            val _n = dut.n
            val rand = new Random
            var history = new Array[Int](2 * _n - 1)
            var prod = 0
            for (n <- 0 until 16) {
                val _cbus_in = rand.between(0, 2)
                history +:= _cbus_in
                dut.io.cbus_in.keep.poke(_cbus_in)
                dut.clock.step()
                history = history.slice(0, 2 * _n - 1)
                println("Input tick @ " + n + ": " + _cbus_in)
                for(i: Int <- 0 until _n){
                    for(j:Int <- 0 until _n) {
                        dut.io.cbus_out(_n * i + j).keep.expect(history(i + j))
                    }
                }
                println("Control tick @ " + n + " : ")
                // print_helper.printMatrixChisel(dut.io.cbus_out, _n)
            }
        }
    }
}