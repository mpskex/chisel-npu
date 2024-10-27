// See README.md for license details.

package alu.pe

import scala.util.Random
import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import chisel3.experimental.BundleLiterals._


class MMPESpec extends AnyFlatSpec with ChiselScalatestTester {

  "MMPE" should "output multiplied number from top and left" in {
    test(new MMPE(8)) { dut =>
      val rand = new Random
      var prod = 0
      for (n <- 0 until 128) {
        val _top_in_ = rand.between(-64, 64)
        val _left_in_ = rand.between(-64, 64)
        dut.io.in_a.poke(_top_in_)
        dut.io.in_b.poke(_left_in_)
        dut.io.ctrl.accum.poke(true)
        dut.clock.step()
        prod = prod + _top_in_ * _left_in_
        dut.io.out.expect(prod)
        println("Result tick @ " + n + ": " + dut.io.out.peekInt() + " with input top: " + _top_in_ + " and left: " + _left_in_)
      }

      prod = 0
      var _top_in_ = rand.between(-64, 64)
      var _left_in_ = rand.between(-64, 64)
      dut.io.in_a.poke(_top_in_)
      dut.io.in_b.poke(_left_in_)
      dut.io.ctrl.accum.poke(false)
      dut.clock.step()
      prod = prod + _top_in_ * _left_in_
      dut.io.out.expect(prod)
      println("Result tick @ new: " + dut.io.out.peekInt() + " with input top: " + _top_in_ + " and left: " + _left_in_)

      _top_in_ = rand.between(-64, 64)
      _left_in_ = rand.between(-64, 64)
      dut.io.in_a.poke(_top_in_)
      dut.io.in_b.poke(_left_in_)
      dut.io.ctrl.accum.poke(true)
      dut.clock.step()
      prod = prod + _top_in_ * _left_in_
      dut.io.out.expect(prod)
      println("Result tick @ new's next: " + dut.io.out.peekInt() + " with input top: " + _top_in_ + " and left: " + _left_in_)
    }
  }
}