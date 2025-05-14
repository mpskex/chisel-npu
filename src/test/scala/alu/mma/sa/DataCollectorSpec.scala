//// See README.md for license details.

package alu.mma.sa

import testUtil._
import scala.util.Random
import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec
import chisel3.experimental.BundleLiterals._

class DataCollectorSpec extends AnyFlatSpec{

    "SA Data Collector" should "collect correct matrix pattern" in {
        simulate(new DataCollector(4)) { dut =>
            val print_helper = new testUtil.PrintHelper()
            val _n = dut.n
            val rand = new Random
            val _mat = new Array[Int](_n * _n)
            val _in_t = new Array[Int](_n)
            var valid_cnt = 0

            // random initialize the
            for (i <- 0 until _n * _n) {
                _mat(i) = rand.between(-128, 128)
            }

            println("===== MAT =====")
            print_helper.printMatrix(_mat, _n)
    
            for (i_tick <- 0 until 4 * _n) {
                // initialize tensor dispatch 
                for (_i <- 0 until _n) {
                    _in_t(_i) = _mat(_i * _n + valid_cnt)
                }
                // print input layout for each tick
                var _in_str = ""
                for (__i <- 0 until _n) {
                    _in_str += _in_t(__i).toString() + ","
                }
                println("Expect Vector A tick @ " + i_tick + ": [" + _in_str + "]")

                // poke the input vector
                dut.io.dat_clct.poke(true)
                for (_i <- 0 until (_n * _n)){
                    dut.io.reg_in(_i).poke(_mat(_i))
                }

                
                // show the output
                var _in_str_out = ""
                for (__i <- 0 until _n) {
                    _in_str_out += dut.io.reg_out(__i).peek().litValue.toInt.toString() + ","
                }
                println("Output Vector A tick @ " + i_tick + ": [" + _in_str_out + "]")

                // evaluate output
                if (i_tick >= _n - 1) {
                    for (__i <- 0 until _n) {
                        dut.io.reg_out(__i).expect(_in_t(__i))
                    }
                    valid_cnt = (valid_cnt + 1) % _n
                }

                dut.clock.step()
            }
        }
        
    }

   
}