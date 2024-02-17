//// See README.md for license details.

package ncore

import testUtil._
import scala.util.Random
import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import chisel3.experimental.BundleLiterals._

class CoreSpec extends AnyFlatSpec with ChiselScalatestTester {

    "NeuralCore" should "do a normal matrix multiplication" in {
        test(new NeuralCore(4, 8)) { dut =>
            val print_helper = new testUtil.PrintHelper()
            val _n = 4
            val rand = new Random
            val _mat_a = new Array[Int](_n * _n)
            val _mat_b = new Array[Int](_n * _n)
            val _expected = new Array[Int](_n * _n)
            var _res = new Array[Int](_n * _n)

            // random initialize the
            for (i <- 0 until _n * _n) {
                _mat_a(i) = rand.between(0, 255)
                _mat_b(i) = rand.between(0, 255)
            }

            // expected matrix multiplication result
            for (_i <- 0 until _n) {
                for (_j <- 0 until _n) {
                    for (_m <- 0 until _n) {
                        _expected(_i * _n + _j) += _mat_a(_i * _n + _m) * _mat_b(_m * _n + _j)
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
                val _a_in_t = new Array[Int](_n)
                val _b_in_t = new Array[Int](_n)
                // initialize tensor dispatch 
                for (_i <- 0 until _n) {
                    if (i_tick - _i >= 0 && i_tick - _i < _n){
                        // chainsaw layout
                        _a_in_t(_i) = _mat_a(_i * _n + i_tick - _i)
                        // we need to transpose B for exact matmul
                        _b_in_t(_i) = _mat_b((i_tick - _i) * _n + _i)
                    }
                }

                // print input layout for each tick
                var _a_in_str = ""
                var _b_in_str = ""
                for (__i <- 0 until _n) {
                    _a_in_str += _a_in_t(__i).toString() + ","
                    _b_in_str += _b_in_t(__i).toString() + ","
                }
                println("Vector A tick @ " + i_tick + ": [" + _a_in_str + "]")
                println("Vector B tick @ " + i_tick + ": [" + _b_in_str + "]")

                // poke the input vector
                for (_i <- 0 until _n){
                    dut.io.vec_a(_i).poke(_a_in_t(_i))
                    dut.io.vec_b(_i).poke(_b_in_t(_i))
                }

                // Only the first _n ticks need accumlate signal
                // The rest of the control signal will hand over
                // to a dedicated systolic-ish control bus
                if (i_tick < _n && i_tick >= 0)
                    dut.io.ctrl.poke(0x1)
                else
                    dut.io.ctrl.poke(0x0)

                // ideally, the array will give _n (diagnal) results per tick
                dut.clock.step()

                // systolic array will start to spit out after _n - 1 ticks
                if (i_tick >= _n - 1) {
                    for (_i <- 0 until _n) {
                        for (_j <- 0 until _n) {
                            // And the array will give result in 2 * _n - 1 ticks
                            if (_i + _j == i_tick - _n + 1) {
                                dut.io.out(_i * _n + _j).expect(_expected(_i * _n + _j))
                                _res(_i * _n + _j) = dut.io.out(_i * _n + _j).peekInt().toInt
                                println("Tick @ " + i_tick + " producing at location (" + _i + ", " + _j + ")")
                            }
                        }
                    }
                }
            }
            println("+++++ MAT C from HW ++++")
            print_helper.printMatrix(_res, _n)
        }
    }   
}