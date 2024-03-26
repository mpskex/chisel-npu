//// See README.md for license details.

package ncore.mmu

import testUtil._
import scala.util.Random
import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import chisel3.experimental.BundleLiterals._

class MMUSpec extends AnyFlatSpec with ChiselScalatestTester {

    "OffsetGenerator" should "provide correct offset" in {
        test(new OffsetGenerator(4)) { dut =>
            val print_helper = new testUtil.PrintHelper()
            val _n = dut.n
            val _array = List(List(false, false, false, false),
                              List(true, false, false, false),  
                              List(true, true, false, false), 
                              List(true, true, true, false), 
                              List(false, true, true, true), 
                              List(true, false, true, true), 
                              List(false, true, false, true), 
                              List(false, false, true, false), 
                              List(false, false, false, true),
                              )
            val _expected = List(List(0, 4, 8, 12),
                                List(1, 4, 8, 12),
                                List(2, 5, 8, 12),
                                List(3, 6, 9, 12),
                                List(0, 7, 10, 13),
                                List(1, 4, 11, 14),
                                List(0, 5, 8, 15),
                                List(0, 4, 9, 12),
                                List(0, 4, 8, 13),
            )
            for (i <- 0 until _array.length) {
                for (j <- 0 until _n) {
                    dut.io.inc(j).poke(_array(i%_array.length)(j))
                }
                dut.clock.step()
                for (j <- 0 until _n) {
                    dut.io.out(j).expect(_expected(i)(j))
                }
                print_helper.printVectorChisel(dut.io.out, _n)
            }
        }
    }
}