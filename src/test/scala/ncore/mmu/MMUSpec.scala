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
            for (i <- 0 until 16) {
                for (j <- 0 until _n){
                    dut.io.inc(j).poke(_array(i%_array.length)(j))
                }
                dut.clock.step()
                print_helper.printVectorChisel(dut.io.out, _n)
            }
        }
    }
}