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
    test(new TCMBlock(3, 192, 1)) { dut =>
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
          dut.io.r_addr(0)(i).poke(_in_addr(i))
        }
        for (i <- 0 until _n * _n){
          dut.io.d_out(0)(i).expect(_in_data(i))
        }
        println("Result tick @ " + _i + ": ")
        print_helper.printMatrix(_in_data, _n)
        print_helper.printMatrixChisel(dut.io.d_out(0), _n)
      }
    }
  }

  "TCM Block" should "read anytime" in {
    test(new TCMBlock(2, 64, 1)) { dut =>
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
          dut.io.r_addr(0)(i).poke(_r_addr(i))
        }
        for (i <- 0 until _n * _n) {
          _expected(i) = _data(_r_addr(i))
        }
        println("Result tick @ " + _i + ": ")
        print_helper.printMatrix(_expected, _n)
        print_helper.printMatrixChisel(dut.io.d_out(0), _n)
        for (i <- 0 until _n * _n){
          dut.io.d_out(0)(i).expect(_data(_r_addr(i)))
        }
      }
    }
  }

  "TCM Block" should "read anytime on different channels" in {
    test(new TCMBlock(2, 64, 2)) { dut =>
      val _n = dut.n
      val _cells = dut.size
      val _rd_ch_num = dut.rd_ch_num
      val rand = new Random
      val print_helper = new testUtil.PrintHelper()
      val _data = new Array[Int](_cells)
      for (_i <- 0 until 10) {
        val _in_data = new Array[Int](_rd_ch_num * _n * _n)
        val _in_addr = rand.shuffle((0 until _cells).toList).take(_rd_ch_num * _n * _n)
        for (k <- 0 until _rd_ch_num){
          for (i <- 0 until _n * _n) {
            val _ind = k * _n * _n + i
            _in_data(_ind) = rand.between(0, 255)
            dut.io.d_in(i).poke(_in_data(_ind))
            dut.io.w_addr(i).poke(_in_addr(_ind))
            _data(_in_addr(_ind)) = _in_data(_ind)
          }
          dut.io.en_wr.poke(true)
          dut.clock.step()
        }
      }
      for(_i <- 0 until 10){
        val _r_addr = rand.shuffle((0 until _cells).toList).take(_rd_ch_num * _n * _n)
        val _expected = new Array[Int](_rd_ch_num * _n * _n)
        for (k <- 0 until _rd_ch_num){
          for (i <- 0 until _n * _n) {
            val _ind = k * _n * _n + i
            dut.io.r_addr(k)(i).poke(_r_addr(_ind))
            _expected(_ind) = _data(_r_addr(_ind))
          }
        }
        println("Result tick @ " + _i + ": ")
        for (k <- 0 until _rd_ch_num){
          for (i <- 0 until _n * _n){
            val _ind = k * _n * _n + i
            dut.io.d_out(k)(i).expect(_data(_r_addr(_ind)))
          }
        }
      }
    }
  }

}