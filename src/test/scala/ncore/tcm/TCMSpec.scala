// See README.md for license details.

package ncore.tcm

import scala.util.Random
import chisel3._
import testUtil._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import chisel3.experimental.BundleLiterals._


class TCMSpec extends AnyFlatSpec with ChiselScalatestTester {

  "TCM Cells" should "write on signal" in {
    test(new TCMCell(8)) { dut =>
      val rand = new Random
      var _prev = 0
      for (i <- 0 until 10) {
        val _in = rand.between(0, 255)
        dut.io.d_out.expect(_prev)
        dut.io.d_in.poke(_in)
        dut.io.en_wr.poke(true)
        dut.clock.step()
        dut.io.d_in.expect(_in)
        _prev = _in
        println("Result tick @ " + i + ": " + dut.io.d_in.peekInt())
      }
    }
  }

  "TCM Block" should "write on signal and read anytime" in {
    test(new TCMBlock(3, 192)) { dut =>
      val _n = dut.n
      val _cells = dut.size
      val rand = new Random
      val print_helper = new testUtil.PrintHelper()
      val _in_data = new Array[Int](_n * _n)
      for(_i <- 0 until 10){
        val _in_addr = rand.shuffle((0 until _cells).toList).take(_n * _n)
        for (i <- 0 until _n * _n) {
          _in_data(i) = rand.between(0, 255)
          dut.io.d_in(i).poke(_in_data(i))
          dut.io.w_addr(i).poke(_in_addr(i))
        }
        dut.io.en_wr.poke(true)
        dut.clock.step()
        for (i <- 0 until _n * _n) {
          dut.io.r_addr(i).poke(_in_addr(i))
        }
        for (i <- 0 until _n * _n){
          dut.io.d_out(i).expect(_in_data(i))
        }
        println("Result tick @ " + _i + ": ")
        print_helper.printMatrix(_in_data, _n)
        print_helper.printMatrixChisel(dut.io.d_out, _n)
      }
    }
  }

  "TCM Block" should "read anytime" in {
    test(new TCMBlock(2, 64)) { dut =>
      val _n = dut.n
      val _cells = dut.size
      val rand = new Random
      val print_helper = new testUtil.PrintHelper()
      val _data = new Array[Int](_cells)
      for (_i <- 0 until 10) {
        val _in_data = new Array[Int](_n * _n)
        val _in_addr = rand.shuffle((0 until _cells).toList).take(_n * _n)
        for (i <- 0 until _n * _n) {
          _in_data(i) = rand.between(0, 255)
          dut.io.d_in(i).poke(_in_data(i))
          dut.io.w_addr(i).poke(_in_addr(i))
          _data(_in_addr(i)) = _in_data(i)
        }
        dut.io.en_wr.poke(true)
        dut.clock.step()
      }
      for(_i <- 0 until 10){
        val _r_addr = rand.shuffle((0 until _cells).toList).take(_n * _n)
        val _expected = new Array[Int](_n * _n)
        for (i <- 0 until _n * _n) {
          dut.io.r_addr(i).poke(_r_addr(i))
        }
        for (i <- 0 until _n * _n) {
          _expected(i) = _data(_r_addr(i))
        }
        println("Result tick @ " + _i + ": ")
        print_helper.printMatrix(_expected, _n)
        print_helper.printMatrixChisel(dut.io.d_out, _n)
        for (i <- 0 until _n * _n){
          dut.io.d_out(i).expect(_data(_r_addr(i)))
        }
      }
    }
  }

}