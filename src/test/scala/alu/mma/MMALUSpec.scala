//// See README.md for license details.

package alu.mma

import alu.pe._
import testUtil._
import scala.util.Random
import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec
import chisel3.experimental.BundleLiterals._

class MMALUSpec extends AnyFlatSpec {

    "MMALU" should "do a normal matrix multiplication" in {
        simulate(new MMALU(new MMPE(8, 32), 4, 8, 32)) { dut =>
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
                _mat_a(i) = rand.between(-128, 128)
                _mat_b(i) = rand.between(-128, 128)
            }

            // expected matrix multiplication result
            for (_i <- 0 until _n) {
                for (_j <- 0 until _n) {
                    for (_m <- 0 until _n) {
                        _expected(_i * _n + _j) += _mat_a(_m * _n + _j) * _mat_b(_m * _n + _i)
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
                        dut.io.in_a(_i).poke(_mat_a(i_tick * _n + _i))
                        dut.io.in_b(_i).poke(_mat_b(i_tick * _n + _i))
                        dut.io.in_accum(_i).poke(0)
                    }
                } else {
                    for (_i <- 0 until _n){
                        dut.io.in_a(_i).poke(0)
                        dut.io.in_b(_i).poke(0)
                        dut.io.in_accum(_i).poke(0)
                    }
                }

                // Only the first _n ticks need accumlate signal
                // The rest of the control signal will hand over
                // to a dedicated systolic-ish control bus
                if (i_tick != _n)
                    dut.io.ctrl.keep.poke(true)
                else
                    dut.io.ctrl.keep.poke(false)
                dut.io.ctrl.use_accum.poke(false)

                // ideally, the array will give _n (diagnal) results per tick
                dut.clock.step()

                // systolic array will start to spit out after _n - 1 ticks
                if (i_tick >= 2 * _n - 2) {
                    for (_i <- 0 until _n) {
                        _res(step * _n + _i) = dut.io.out(_i).peek().litValue.toInt
                        println("Tick @ " + i_tick + " producing at location (" + _i + ", " + step + "): " + _res(step * _n + _i))
                        dut.io.out(_i).expect(_expected(step * _n + _i))
                    }
                    step = step + 1
                }
            }
            println("+++++ MAT C from HW ++++")
            print_helper.printMatrix(_res, _n)
        }
    }   

    "MMALU" should "do a normal matrix multiplication in stream" in {
        simulate(new MMALU(new MMPE(8, 32), 4, 8, 32)) { dut =>
            val print_helper = new testUtil.PrintHelper()
            val _n = dut.n
            val rand = new Random
            val _mat_a = new Array[Int](_n * _n)
            val _mat_b = new Array[Int](_n * _n)
            val _vec = new Array[Int](_n)
            val _mat_d = new Array[Int](_n * _n)
            val _mat_e = new Array[Int](_n * _n)
            val _expected_c = new Array[Int](_n * _n)
            val _expected_f = new Array[Int](_n * _n)
            var _res_c = new Array[Int](_n * _n)
            var _res_f = new Array[Int](_n * _n)
            var step = 0

            // random initialize the
            for (i <- 0 until _n * _n) {
                _mat_a(i) = rand.between(-128, 128)
                _mat_b(i) = rand.between(-128, 128)
                _mat_d(i) = rand.between(-128, 128)
                _mat_e(i) = rand.between(-128, 128)
            }
            for (i <- 0 until _n)
                _vec(i) = rand.between(-128, 128)

            // expected matrix multiplication result
            for (_i <- 0 until _n) {
                for (_j <- 0 until _n) {
                    for (_m <- 0 until _n) {
                        _expected_c(_i * _n + _j) += _mat_a(_m * _n + _j) * _mat_b(_m * _n + _i)
                        _expected_f(_i * _n + _j) += _mat_d(_m * _n + _j) * _mat_e(_m * _n + _i)
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
                        dut.io.in_a(_i).poke(_mat_a(i_tick * _n + _i))
                        dut.io.in_b(_i).poke(_mat_b(i_tick * _n + _i))
                        dut.io.in_accum(_i).poke(0)
                    }
                } else if (i_tick < 2 * _n) {
                    // println("Tick @ " + i_tick + " Reading Reg C & D")
                    for (_i <- 0 until _n){
                        dut.io.in_a(_i).poke(_mat_d((i_tick % _n) * _n + _i))
                        dut.io.in_b(_i).poke(_mat_e((i_tick % _n) * _n + _i))
                        dut.io.in_accum(_i).poke(0)
                    }
                } else {
                    for (_i <- 0 until _n){
                        dut.io.in_a(_i).poke(0)
                        dut.io.in_b(_i).poke(0)
                        dut.io.in_accum(_i).poke(0)
                    }
                }

                // Only the first _n ticks need accumlate signal
                // The rest of the control signal will hand over
                // to a dedicated systolic-ish control bus
                if (i_tick != _n - 1 && i_tick != 2 * _n )
                    dut.io.ctrl.keep.poke(true)
                else
                    dut.io.ctrl.keep.poke(false)
                dut.io.ctrl.use_accum.poke(false)

                // ideally, the array will give _n (diagnal) results per tick
                dut.clock.step()

                // systolic array will start to spit out after _n - 1 ticks for mat_c
                println("Tick @ " + i_tick + " clct signal " + dut.io.clct.peek().litValue.toInt)
                if (i_tick >= 2 * _n - 2 && i_tick < 3 * _n - 2) {
                    for (_i <- 0 until _n) {
                        _res_c(step * _n + _i) = dut.io.out(_i).peek().litValue.toInt
                        println("Tick @ " + i_tick + " Mat C producing at location (" + _i + ", " + step + "): " + _res_c(step * _n + _i))
                        dut.io.out(_i).expect(_expected_c(step * _n + _i))
                    }
                    step = step + 1
                } 
                // systolic array will start to generate again after 3 * _n - 2 ticks
                if (i_tick >= 3 * _n - 2 && i_tick < 4 * _n - 2){
                    for (_i <- 0 until _n) {
                        _res_f((step % _n) * _n + _i) = dut.io.out(_i).peek().litValue.toInt
                        println("OUT Tick @ " + i_tick + " Mat F producing at location (" + _i + ", " + step % _n + "): " + _res_f((step % _n) * _n + _i))
                        dut.io.out(_i).expect(_expected_f((step % _n) * _n + _i))
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

    "MMALU" should "do a generic matrix multiplication" in {
       simulate(new MMALU(new MMPE(8, 32), 4, 8, 32)) { dut =>
            val print_helper = new testUtil.PrintHelper()
            val _n = dut.n
            val rand = new Random
            val _mat_a = new Array[Int](_n * _n)
            val _mat_b = new Array[Int](_n * _n)
            val _vec = new Array[Int](_n)
            val _expected = new Array[Int](_n * _n)
            var _res = new Array[Int](_n * _n)
            var step = 0

            // random initialize the
            for (i <- 0 until _n * _n) {
                _mat_a(i) = rand.between(-128, 128)
                _mat_b(i) = rand.between(-128, 128)
            }
            for (i <- 0 until _n)
                _vec(i) = rand.between(-128, 128)

            // expected matrix multiplication result
            for (_i <- 0 until _n) {
                for (_j <- 0 until _n) {
                    for (_m <- 0 until _n) {
                        _expected(_i * _n + _j) += _mat_a(_m * _n + _j) * _mat_b(_m * _n + _i)
                    }
                    _expected(_i * _n + _j) += _vec(_j)
                }
            }

            // print the expected results
            println("===== MAT A =====")
            print_helper.printMatrix(_mat_a, _n)
            println("===== MAT B =====")
            print_helper.printMatrix(_mat_b, _n)
            println("===== Vec Bias =====")
            print_helper.printVector(_vec, _n)
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
                        dut.io.in_a(_i).poke(_mat_a(i_tick * _n + _i))
                        dut.io.in_b(_i).poke(_mat_b(i_tick * _n + _i))
                        dut.io.in_accum(_i).poke(_vec(_i))
                    }
                } else {
                    for (_i <- 0 until _n){
                        dut.io.in_a(_i).poke(0)
                        dut.io.in_b(_i).poke(0)
                        dut.io.in_accum(_i).poke(0)
                    }
                }

                // Only the first _n ticks need accumlate signal
                // The rest of the control signal will hand over
                // to a dedicated systolic-ish control bus
                if (i_tick != _n)
                    dut.io.ctrl.keep.poke(true)
                else
                    dut.io.ctrl.keep.poke(false)
                if (i_tick < _n)
                    dut.io.ctrl.use_accum.poke(true)
                else
                    dut.io.ctrl.use_accum.poke(false)

                // ideally, the array will give _n (diagnal) results per tick
                dut.clock.step()

                // systolic array will start to spit out after _n - 1 ticks
                if (i_tick >= 2 * _n - 2) {
                    for (_i <- 0 until _n) {
                        _res(step * _n + _i) = dut.io.out(_i).peek().litValue.toInt
                        println("Tick @ " + i_tick + " producing at location (" + _i + ", " + step + "): " + _res(step * _n + _i))
                        dut.io.out(_i).expect(_expected(step * _n + _i))
                    }
                    step = step + 1
                }
            }
            println("+++++ MAT C from HW ++++")
            print_helper.printMatrix(_res, _n)
        } 
    }
}