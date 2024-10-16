//// See README.md for license details.

package alu.mma

import testUtil._
import scala.util.Random
import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import chisel3.experimental.BundleLiterals._

class MMALUSpec extends AnyFlatSpec with ChiselScalatestTester {

    "MMALU" should "do a normal matrix multiplication" in {
        test(new MMALU(4, 8)) { dut =>
            val print_helper = new testUtil.PrintHelper()
            val _n = dut.n
            val rand = new Random
            val _mat_a = new Array[Int](_n * _n)
            val _mat_b = new Array[Int](_n * _n)
            val _expected = new Array[Int](_n * _n)
            var _res = new Array[Int](_n * _n)
            var step = 0

            // random initialize the
            for (i <- 0 until _n * _n) {
                _mat_a(i) = rand.between(0, 255)
                _mat_b(i) = rand.between(0, 255)
            }

            // expected matrix multiplication result
            for (_i <- 0 until _n) {
                for (_j <- 0 until _n) {
                    for (_m <- 0 until _n) {
                        _expected(_i * _n + _j) += _mat_a(_i * _n + _m) * _mat_b(_j * _n + _m)
                    }
                }
            }

            // print the expected results
            println("===== MAT A =====")
            print_helper.printMatrix(_mat_a, _n)
            println("===== MAT B =====")
            print_helper.printMatrix(_mat_b, _n)
            println("+++++ MAT C +++++")
            print_helper.printMatrix(_expected, _n)

            // systolic arrays has latency of 3 * _n - 2
            for (i_tick <- 0 until 3 * _n - 2) {

                // poke the input vector
                // with data feeder the data latency is just n ticks
                // after n ticks the mmalu will have no dependency 
                // on the current register
                if (i_tick < _n) {
                    for (_i <- 0 until _n){
                        dut.io.in_a(_i).poke(_mat_a(_i * _n + i_tick))
                        dut.io.in_b(_i).poke(_mat_b(_i * _n + i_tick))
                    }
                } else {
                    for (_i <- 0 until _n){
                        dut.io.in_a(_i).poke(0)
                        dut.io.in_b(_i).poke(0)
                    }
                }

                // Only the first _n ticks need accumlate signal
                // The rest of the control signal will hand over
                // to a dedicated systolic-ish control bus
                if (i_tick != _n)
                    dut.io.ctrl.accum.poke(true)
                else
                    dut.io.ctrl.accum.poke(false)
                if (i_tick > _n - 1) {
                    dut.io.ctrl.dat_collect.poke(true)
                }

                // ideally, the array will give _n (diagnal) results per tick
                dut.clock.step()

                // systolic array will start to spit out after _n - 1 ticks
                if (i_tick >= 2 * _n - 2) {
                    for (_i <- 0 until _n) {
                        _res(_i * _n + step) = dut.io.out(_i).peekInt().toInt
                        println("Tick @ " + i_tick + " producing at location (" + _i + ", " + step + "): " + _res(_i * _n + step))
                        dut.io.out(_i).expect(_expected(_i * _n + step))
                    }
                    step = step + 1
                }
            }
            println("+++++ MAT C from HW ++++")
            print_helper.printMatrix(_res, _n)
        }
    }   

    "MMALU" should "do a normal matrix multiplication in stream" in {
        test(new MMALU(4, 8)) { dut =>
            val print_helper = new testUtil.PrintHelper()
            val _n = dut.n
            val rand = new Random
            val _mat_a = new Array[Int](_n * _n)
            val _mat_b = new Array[Int](_n * _n)
            val _mat_d = new Array[Int](_n * _n)
            val _mat_e = new Array[Int](_n * _n)
            val _expected_c = new Array[Int](_n * _n)
            val _expected_f = new Array[Int](_n * _n)
            var _res_c = new Array[Int](_n * _n)
            var _res_f = new Array[Int](_n * _n)
            var step = 0

            // random initialize the
            for (i <- 0 until _n * _n) {
                _mat_a(i) = rand.between(0, 255)
                _mat_b(i) = rand.between(0, 255)
                _mat_d(i) = rand.between(0, 255)
                _mat_e(i) = rand.between(0, 255)
            }

            // expected matrix multiplication result
            for (_i <- 0 until _n) {
                for (_j <- 0 until _n) {
                    for (_m <- 0 until _n) {
                        _expected_c(_i * _n + _j) += _mat_a(_i * _n + _m) * _mat_b(_j * _n + _m)
                        _expected_f(_i * _n + _j) += _mat_d(_i * _n + _m) * _mat_e(_j * _n + _m)
                    }
                }
            }

            // print the expected results
            println("===== MAT A =====")
            print_helper.printMatrix(_mat_a, _n)
            println("===== MAT B =====")
            print_helper.printMatrix(_mat_b, _n)
            println("+++++ MAT C +++++")
            print_helper.printMatrix(_expected_c, _n)
            println("===== MAT D =====")
            print_helper.printMatrix(_mat_d, _n)
            println("===== MAT E =====")
            print_helper.printMatrix(_mat_e, _n)
            println("+++++ MAT F +++++")
            print_helper.printMatrix(_expected_f, _n)

            // systolic arrays has latency of 3 * _n - 2
            // and the second work period is 2 * n - 1
            for (i_tick <- 0 until 3 * _n - 2 + _n) {

                // poke the input vector
                // with data feeder the data latency is just n ticks
                // after n ticks the mmalu will have no dependency 
                // on the current register
                if (i_tick < _n) {
                    // println("Tick @ " + i_tick + " Reading Reg A & B")
                    for (_i <- 0 until _n){
                        dut.io.in_a(_i).poke(_mat_a(_i * _n + i_tick))
                        dut.io.in_b(_i).poke(_mat_b(_i * _n + i_tick))
                    }
                } else if (i_tick < 2 * _n) {
                    // println("Tick @ " + i_tick + " Reading Reg C & D")
                    for (_i <- 0 until _n){
                        dut.io.in_a(_i).poke(_mat_d(_i * _n + i_tick % _n))
                        dut.io.in_b(_i).poke(_mat_e(_i * _n + i_tick % _n))
                    }
                } else {
                    for (_i <- 0 until _n){
                        dut.io.in_a(_i).poke(0)
                        dut.io.in_b(_i).poke(0)
                    }
                }

                // Only the first _n ticks need accumlate signal
                // The rest of the control signal will hand over
                // to a dedicated systolic-ish control bus
                if (i_tick != _n - 1 && i_tick != 2 * _n )
                    dut.io.ctrl.accum.poke(true)
                else
                    dut.io.ctrl.accum.poke(false)
                if (i_tick > _n - 1)
                    dut.io.ctrl.dat_collect.poke(true)
                else
                    dut.io.ctrl.dat_collect.poke(false)

                // ideally, the array will give _n (diagnal) results per tick
                dut.clock.step()

                // systolic array will start to spit out after _n - 1 ticks for mat_c
                if (i_tick >= 2 * _n - 2 && i_tick < 3 * _n - 2) {
                    for (_i <- 0 until _n) {
                        _res_c(_i * _n + step) = dut.io.out(_i).peekInt().toInt
                        println("Tick @ " + i_tick + " Mat C producing at location (" + _i + ", " + step + "): " + _res_c(_i * _n + step))
                        // dut.io.out(_i).expect(_expected_c(_i * _n + step))
                    }
                    step = step + 1
                } 
                // systolic array will start to generate again after 3 * _n - 2 ticks
                if (i_tick >= 3 * _n - 2 && i_tick < 4 * _n - 2){
                    for (_i <- 0 until _n) {
                        _res_f(_i * _n + step % _n) = dut.io.out(_i).peekInt().toInt
                        println("OUT Tick @ " + i_tick + " Mat F producing at location (" + _i + ", " + step % _n + "): " + _res_f(_i * _n + step % _n))
                        dut.io.out(_i).expect(_expected_f(_i * _n + step % _n))
                    }
                    step = step + 1
                }
                
            }
            println("+++++ MAT C from HW ++++")
            print_helper.printMatrix(_res_c, _n)
            println("+++++ MAT F from HW ++++")
            print_helper.printMatrix(_res_f, _n)
        }
    }   
}