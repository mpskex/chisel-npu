// See README.md for license details.

package alu.pe

import scala.util.Random
import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec
import chisel3.experimental.BundleLiterals._


class MMPESpec extends AnyFlatSpec {

  "MMPE" should "output multiplied number from top and left" in {
    simulate(new MMPE(8, 32)) { dut =>
      val rand = new Random
      var prod = 0
      for (n <- 0 until 128) {
        val _top_in_ = rand.between(-64, 64)
        val _left_in_ = rand.between(-64, 64)
        dut.io.in_a.poke(_top_in_)
        dut.io.in_b.poke(_left_in_)
        dut.io.ctrl.keep.poke(true)
        dut.clock.step()
        prod = prod + _top_in_ * _left_in_
        dut.io.out.expect(prod)
        println("Result tick @ " + n + ": " + dut.io.out.peek().litValue + " with input top: " + _top_in_ + " and left: " + _left_in_)
      }

      prod = 0
      var _top_in_ = rand.between(-64, 64)
      var _left_in_ = rand.between(-64, 64)
      dut.io.in_a.poke(_top_in_)
      dut.io.in_b.poke(_left_in_)
      dut.io.ctrl.keep.poke(false)
      dut.clock.step()
      prod = prod + _top_in_ * _left_in_
      dut.io.out.expect(prod)
      println("Result tick @ new: " + dut.io.out.peek().litValue + " with input top: " + _top_in_ + " and left: " + _left_in_)

      _top_in_ = rand.between(-64, 64)
      _left_in_ = rand.between(-64, 64)
      dut.io.in_a.poke(_top_in_)
      dut.io.in_b.poke(_left_in_)
      dut.io.ctrl.keep.poke(true)
      dut.clock.step()
      prod = prod + _top_in_ * _left_in_
      dut.io.out.expect(prod)
      println("Result tick @ new's next: " + dut.io.out.peek().litValue + " with input top: " + _top_in_ + " and left: " + _left_in_)
    }
  }
}