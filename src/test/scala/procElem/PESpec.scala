// See README.md for license details.

package procElem

import scala.util.Random
import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import chisel3.experimental.BundleLiterals._

/**
  * This is a trivial example of how to run this Specification
  * From within sbt use:
  * {{{
  * testOnly 
  * }}}
  * From a terminal shell use:
  * {{{
  * sbt 'testOnly gcd.GCDSpec'
  * }}}
  * Testing from mill:
  * {{{
  * mill %NAME%.test.testOnly gcd.GCDSpec
  * }}}
  */
class PESpec extends AnyFlatSpec with ChiselScalatestTester {

  "PE" should "output multiplied number from top and left" in {
    test(new PE(16)) { dut =>
      val rand = new Random
      var prod = 0
      for (n <- 1 until 4096) {
        val _top_in_ = rand.between(1, 255)
        val _left_in_ = rand.between(1, 255)
        dut.io.top_in.poke(_top_in_)
        dut.io.left_in.poke(_left_in_)
        dut.io.accum.poke(true)
        dut.clock.step()
        dut.io.bottom_out.expect(_top_in_)
        dut.io.right_out.expect(_left_in_)
        prod = prod + _top_in_ * _left_in_
        dut.io.out.expect(prod)
        println("Result tick @ " + n + ": " + dut.io.out.peekInt() + " with input top: " + _top_in_ + " and left: " + _left_in_)
      }

      prod = 0
      var _top_in_ = rand.between(1, 255)
      var _left_in_ = rand.between(1, 255)
      dut.io.top_in.poke(_top_in_)
      dut.io.left_in.poke(_left_in_)
      dut.io.accum.poke(false)
      dut.clock.step()
      dut.io.bottom_out.expect(_top_in_)
      dut.io.right_out.expect(_left_in_)
      prod = prod + _top_in_ * _left_in_
      dut.io.out.expect(prod)
      println("Result tick @ new: " + dut.io.out.peekInt() + " with input top: " + _top_in_ + " and left: " + _left_in_)

      _top_in_ = rand.between(1, 255)
      _left_in_ = rand.between(1, 255)
      dut.io.top_in.poke(_top_in_)
      dut.io.left_in.poke(_left_in_)
      dut.io.accum.poke(true)
      dut.clock.step()
      dut.io.bottom_out.expect(_top_in_)
      dut.io.right_out.expect(_left_in_)
      prod = prod + _top_in_ * _left_in_
      dut.io.out.expect(prod)
      println("Result tick @ new's next: " + dut.io.out.peekInt() + " with input top: " + _top_in_ + " and left: " + _left_in_)
    }
  }
}