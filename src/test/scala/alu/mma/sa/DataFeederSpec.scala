//// See README.md for license details.

package alu.mma.sa

import testUtil._
import scala.util.Random
import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec
import chisel3.experimental.BundleLiterals._

class DataFeederSpec extends AnyFlatSpec {

    "SA Data Feeder" should "generate correct matrix pattern" in {
        simulate(new DataFeeder(4)) { dut =>
            val print_helper = new testUtil.PrintHelper()
            val _n = dut.n
            val rand = new Random
            val _mat_a = new Array[Int](_n * _n)
            val _mat_b = new Array[Int](_n * _n)
            val _vec = new Array[Int](_n)
            val _a_in_t = new Array[Int](_n)
            val _b_in_t = new Array[Int](_n)
            
            for (cnt <- 0 until 2) {
                
                // random initialize the
                for (i <- 0 until _n * _n) {
                    _mat_a(i) = rand.between(-128, 128)
                    _mat_b(i) = rand.between(-128, 128)
                }
                for (i <- 0 until _n) 
                    _vec(i) = rand.between(-128, 128)
    
                println("===== MAT A =====")
                print_helper.printMatrix(_mat_a, _n)
                println("===== MAT B =====")
                print_helper.printMatrix(_mat_b, _n)
                println("===== Vec =====")
                print_helper.printVector(_vec, _n)
    
                for (i_tick <- 0 until 3 * _n - 2) {
                    // initialize tensor dispatch 
                    for (_i <- 0 until _n) {
                        if (i_tick - _i >= 0 && i_tick - _i < _n){
                            // chainsaw layout
                            _a_in_t(_i) = _mat_a(_i * _n + i_tick - _i)
                            // transpose will be done by ld/st module
                            _b_in_t(_i) = _mat_b(_i * _n + i_tick - _i)
                        }
                    }
                    // print input layout for each tick
                    println("Expect Vector A tick @ " + i_tick + ":")
                    print_helper.printVector(_a_in_t, _n)
                    println("Expect Vector B tick @ " + i_tick + ":")
                    print_helper.printVector(_b_in_t, _n)

                    // poke the input vector
                    for (_i <- 0 until (_n)){
                        if (i_tick < _n){
                            dut.io.reg_a_in(_i).poke(_mat_a(_i * _n + i_tick))
                            dut.io.reg_b_in(_i).poke(_mat_b(_i * _n + i_tick))
                        } else {
                            dut.io.reg_a_in(_i).poke(0)
                            dut.io.reg_b_in(_i).poke(0)
                        }
                    }
                    for (_i <- 0 until _n) 
                        if (i_tick < _n)
                            dut.io.reg_accum_in(_i).poke(_vec(_i))
                        else
                            dut.io.reg_accum_in(_i).poke(0)
                    
                    // show the output
                    var _a_in_str_out = ""
                    var _b_in_str_out = ""
                    var _c_in_str_out = ""
                    for (__i <- 0 until _n) {
                        _a_in_str_out += dut.io.reg_a_out(__i).peek().litValue.toInt.toString() + ","
                        _b_in_str_out += dut.io.reg_b_out(__i).peek().litValue.toInt.toString() + ","
                        _c_in_str_out += dut.io.reg_accum_out(__i).peek().litValue.toInt.toString() + ","
                    }
                    println("Output Vector A tick @ " + i_tick + ": [" + _a_in_str_out + "]")
                    println("Output Vector B tick @ " + i_tick + ": [" + _b_in_str_out + "]")
                    println("Output Vector Accum tick @ " + i_tick + ": [" + _c_in_str_out + "]")

                    // evaluate output
                    for (__i <- 0 until _n) {
                        if (i_tick - __i >= 0 && i_tick - __i < _n){
                            dut.io.reg_a_out(__i).expect(_a_in_t(__i))
                            dut.io.reg_b_out(__i).expect(_b_in_t(__i))
                        }
                    }

                    dut.clock.step()

                    for (__i <- 0 until _n) {
                        if (i_tick >= 2 * _n - 2) {
                            dut.io.reg_accum_out(__i).expect(_vec(__i))
                        } else {
                            dut.io.reg_accum_out(__i).expect(0)
                        }
                    }
                }
                
            }
        }
    }

   
}