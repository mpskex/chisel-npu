// See README.md for license details.

package sram.register

import scala.util.Random
import chisel3._
import testUtil._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec
import chisel3.experimental.BundleLiterals._
import testUtil._


class RegisterSpec extends AnyFlatSpec {

  "Register Cells" should "write on signal" in {
    simulate(new RegisterCell(2, 8)) { dut =>
      val print_helper = new testUtil.PrintHelper()
      val rand = new Random
      var _prev = new Array[Int](dut.n)
      for (i <- 0 until 10) {
        val _in = new Array[Int](dut.n)
        for (_i <- 0 until dut.n) {
          _in(_i) = rand.between(-128, 128)
        }
        for (_i <- 0 until dut.n) {
          dut.io.d_out(_i).expect(_prev(_i))
        }
        for (_i <- 0 until dut.n) {
          dut.io.d_in(_i).poke(_in(_i))
        }
        dut.io.en_wr.poke(true)
        dut.clock.step()
        for (_i <- 0 until dut.n) {
          dut.io.d_in(_i).expect(_in(_i))
        }
        for (_i <- 0 until dut.n) {
          _prev(_i) = _in(_i)
        }
        println("Result tick @ " + i + ": ")
        print_helper.printVectorChisel(dut.io.d_in, dut.n)
      }
    }
  }

  "Register Block" should "write on signal and read anytime" in {
    simulate(new RegisterBlock(2, 3, 3, 32, 8)) { dut =>
      val _rd_banks = dut.rd_banks
      val _wr_banks = dut.wr_banks
      val _cells = dut.size
      val rand = new Random
      val print_helper = new testUtil.PrintHelper()
      val _in_data = new Array[Int](_wr_banks * dut.n)
      for(_i <- 0 until 10){
        val _in_addr = rand.shuffle((0 until _cells).toList).take(_wr_banks)
        for (i <- 0 until _wr_banks) {
          for (__i <- 0 until dut.n) {
            _in_data(i * dut.n + __i) = rand.between(-128, 128)
            dut.io.d_in(i)(__i).poke(_in_data(i * dut.n + __i))
          }
          dut.io.w_addr(i).poke(_in_addr(i))
          dut.io.en_wr(i).poke(true)
        }
        dut.clock.step()
        for (i <- 0 until _wr_banks) {
          dut.io.r_addr(i).poke(_in_addr(i))
        }
        for (i <- 0 until _wr_banks){
          for (__i <- 0 until dut.n) {
            dut.io.d_out(i)(__i).expect(_in_data(i * dut.n + __i))
          }
        }
        for (b <- 0 until _wr_banks) {
          println("Result tick @ " + _i + " @ bank " + b + ": ")
          print_helper.printVectorChisel(dut.io.d_out(b), dut.n)
        }
      }
    }
  }

  "Register Block" should "read anytime" in {
    simulate(new RegisterBlock(2, 3, 3, 32, 8)) { dut =>
      val _rd_banks = dut.rd_banks
      val _wr_banks = dut.wr_banks
      val _cells = dut.size
      val rand = new Random
      val print_helper = new testUtil.PrintHelper()
      val _data = new Array[Int](_cells * dut.n)
      // write random data
      for (_i <- 0 until 100) {
        val _in_data = new Array[Int](_wr_banks * dut.n)
        val _in_addr = rand.shuffle((0 until _cells).toList).take(_wr_banks)
        for (i <- 0 until _wr_banks) {
          for (__i <- 0 until dut.n) {
            _in_data(__i * _wr_banks + i) = rand.between(-128, 128)
            dut.io.d_in(i)(__i).poke(_in_data(i * dut.n + __i))
          }
          dut.io.w_addr(i).poke(_in_addr(i))
          for (__i <- 0 until dut.n) {
            _data(_in_addr(i) * dut.n + __i) = _in_data(i * dut.n + __i)
          }
          dut.io.en_wr(i).poke(true)
        }
        dut.clock.step()
      }
      // read random data
      for(_i <- 0 until 10){
        val _r_addr = rand.shuffle((0 until _cells).toList).take(_rd_banks)
        val _expected = new Array[Int](_rd_banks * dut.n)
        for (i <- 0 until _rd_banks) {
          dut.io.r_addr(i).poke(_r_addr(i))
        }
        for (i <- 0 until _rd_banks) {
          for (__i <- 0 until dut.n) {
            _expected(i * dut.n + __i) = _data(_r_addr(i) * dut.n + __i)
          }
        }
        for (b <- 0 until _rd_banks) {
          println("Result tick @ " + _i + " @ bank " + b + ": ")
          print_helper.printVectorChisel(dut.io.d_out(b), dut.n)
        }
        for (i <- 0 until _rd_banks){
          for (__i <- 0 until dut.n) {
            dut.io.d_out(i)(__i).expect(_data(_r_addr(i) * dut.n + __i))
          }
        }
      }
    }
  }

  "Register Block" should "read anytime on different read banks" in {
    simulate(new RegisterBlock(2, 2, 2, 32, 8)) { dut =>
      val _rd_banks = dut.rd_banks
      val _wr_banks = dut.wr_banks
      val _cells = dut.size
      val rand = new Random
      val print_helper = new testUtil.PrintHelper()
      val _data = new Array[Int](_cells * dut.n)
      for (_i <- 0 until 10) {
        val _in_data = new Array[Int](_wr_banks * dut.n)
        val _in_addr = rand.shuffle((0 until _cells).toList).take(_wr_banks)
        for (i <- 0 until _wr_banks) {
          for (__i <- 0 until dut.n) {
            _in_data(i * dut.n + __i) = rand.between(-128, 128)
            dut.io.d_in(i)(__i).poke(_in_data(i * dut.n + __i))
          }
          dut.io.w_addr(i).poke(_in_addr(i))
          for (__i <- 0 until dut.n) {
            _data(_in_addr(i) * dut.n + __i) = _in_data(i * dut.n + __i)
          }
          dut.io.en_wr(i).poke(true)
        }
        dut.clock.step()
      }
      for(_i <- 0 until 10){
        val _r_addr = rand.shuffle((0 until _cells).toList).take(_rd_banks)
        val _expected = new Array[Int](_rd_banks * dut.n)
        for (i <- 0 until _rd_banks) {
          dut.io.r_addr(i).poke(_r_addr(i))
          for (__i <- 0 until dut.n) {
            _expected(i * dut.n + __i) = _data(_r_addr(i) * dut.n + __i)
          }
        }
        for (i <- 0 until _rd_banks){
          for (__i <- 0 until dut.n) {
            dut.io.d_out(i)(__i).expect(_data(_r_addr(i) * dut.n + __i))
          }
        }
      }
    }
  }

}