// See README.md for license details.

package sram.register

import scala.util.Random
import chisel3._
import testUtil._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import chisel3.experimental.BundleLiterals._


class RegisterSpec extends AnyFlatSpec with ChiselScalatestTester {

  "Register Cells" should "write on signal" in {
    test(new RegisterCell(8)) { dut =>
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

  "Register Block" should "write on signal and read anytime" in {
    test(new RegisterBlock(5, 5, 192, 8)) { dut =>
      val _rd_banks = dut.rd_banks
      val _wr_banks = dut.wr_banks
      val _cells = dut.size
      val rand = new Random
      val print_helper = new testUtil.PrintHelper()
      val _in_data = new Array[Int](_wr_banks)
      for(_i <- 0 until 10){
        val _in_addr = rand.shuffle((0 until _cells).toList).take(_wr_banks)
        for (i <- 0 until _wr_banks) {
          _in_data(i) = rand.between(0, 255)
          dut.io.d_in(i).poke(_in_data(i))
          dut.io.w_addr(i).poke(_in_addr(i))
          dut.io.en_wr(i).poke(true)
        }
        dut.clock.step()
        for (i <- 0 until _wr_banks) {
          dut.io.r_addr(i).poke(_in_addr(i))
        }
        for (i <- 0 until _wr_banks){
          dut.io.d_out(i).expect(_in_data(i))
        }
        println("Result tick @ " + _i + ": ")
        print_helper.printVectorChisel(dut.io.d_out, _rd_banks)
      }
    }
  }

  "Register Block" should "read anytime" in {
    test(new RegisterBlock(5, 5, 64, 8)) { dut =>
      val _rd_banks = dut.rd_banks
      val _wr_banks = dut.wr_banks
      val _cells = dut.size
      val rand = new Random
      val print_helper = new testUtil.PrintHelper()
      val _data = new Array[Int](_cells)
      // write random data
      for (_i <- 0 until 100) {
        val _in_data = new Array[Int](_wr_banks)
        val _in_addr = rand.shuffle((0 until _cells).toList).take(_wr_banks)
        for (i <- 0 until _wr_banks) {
          _in_data(i) = rand.between(0, 255)
          dut.io.d_in(i).poke(_in_data(i))
          dut.io.w_addr(i).poke(_in_addr(i))
          _data(_in_addr(i)) = _in_data(i)
          dut.io.en_wr(i).poke(true)
        }
        dut.clock.step()
      }
      // read random data
      for(_i <- 0 until 10){
        val _r_addr = rand.shuffle((0 until _cells).toList).take(_rd_banks)
        val _expected = new Array[Int](_rd_banks)
        for (i <- 0 until _rd_banks) {
          dut.io.r_addr(i).poke(_r_addr(i))
        }
        for (i <- 0 until _rd_banks) {
          _expected(i) = _data(_r_addr(i))
        }
        println("Result tick @ " + _i + ": ")
        print_helper.printVectorChisel(dut.io.d_out, _rd_banks)
        for (i <- 0 until _rd_banks){
          dut.io.d_out(i).expect(_data(_r_addr(i)))
          println("bit: " + i + " Addr: " + _r_addr(i) + " out: " + dut.io.d_out(i).peekInt().toInt + " expected: " + _data(_r_addr(i)))
        }
      }
    }
  }

  "Register Block" should "read anytime on different read banks" in {
    test(new RegisterBlock(2, 2, 64, 8)) { dut =>
      val _rd_banks = dut.rd_banks
      val _wr_banks = dut.wr_banks
      val _cells = dut.size
      val rand = new Random
      val print_helper = new testUtil.PrintHelper()
      val _data = new Array[Int](_cells)
      for (_i <- 0 until 10) {
        val _in_data = new Array[Int](_wr_banks)
        val _in_addr = rand.shuffle((0 until _cells).toList).take(_wr_banks)
        for (i <- 0 until _wr_banks) {
          _in_data(i) = rand.between(0, 255)
          dut.io.d_in(i).poke(_in_data(i))
          dut.io.w_addr(i).poke(_in_addr(i))
          _data(_in_addr(i)) = _in_data(i)
          dut.io.en_wr(i).poke(true)
        }
        dut.clock.step()
      }
      for(_i <- 0 until 10){
        val _r_addr = rand.shuffle((0 until _cells).toList).take(_rd_banks)
        val _expected = new Array[Int](_rd_banks)
        for (i <- 0 until _rd_banks) {
          dut.io.r_addr(i).poke(_r_addr(i))
          _expected(i) = _data(_r_addr(i))
        }
        println("Result tick @ " + _i + ": ")
        for (i <- 0 until _rd_banks){
          dut.io.d_out(i).expect(_data(_r_addr(i)))
        }
      }
    }
  }

}