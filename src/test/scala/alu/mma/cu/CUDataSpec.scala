// //// See README.md for license details.

// package alu.mma.cu

// import testUtil._
// import scala.util.Random
// import chisel3._
// import chiseltest._
// import org.scalatest.flatspec.AnyFlatSpec
// import chisel3.experimental.BundleLiterals._

// class CUDataSpec extends AnyFlatSpec with ChiselScalatestTester {

//     "CU" should "send control to 2D systolic array" in {
//         test(new DataFeeder(4)) { dut =>
//             val print_helper = new testUtil.PrintHelper()
//             val _n = dut.n
//             val rand = new Random
//             val _mat_a = new Array[Int](_n * _n)
//             val _mat_b = new Array[Int](_n * _n)
//             val _a_in_t = new Array[Int](_n)
//             val _b_in_t = new Array[Int](_n)

//             // random initialize the
//             for (i <- 0 until _n * _n) {
//                 _mat_a(i) = rand.between(0, 255)
//                 _mat_b(i) = rand.between(0, 255)
//             }

//             for (i_tick <- 0 until 2 * _n - 1) {
//                 val _a_in_t = new Array[Int](_n)
//                 val _b_in_t = new Array[Int](_n)
//                 // initialize tensor dispatch 
//                 for (_i <- 0 until _n) {
//                     if (i_tick - _i >= 0 && i_tick - _i < _n){
//                         // chainsaw layout
//                         _a_in_t(_i) = _mat_a(_i * _n + i_tick - _i)
//                         // we need to transpose B for exact matmul
//                         _b_in_t(_i) = _mat_b((i_tick - _i) * _n + _i)
//                     }
//                 }
//                 // print input layout for each tick
//                 var _a_in_str = ""
//                 var _b_in_str = ""
//                 for (__i <- 0 until _n) {
//                     _a_in_str += _a_in_t(__i).toString() + ","
//                     _b_in_str += _b_in_t(__i).toString() + ","
//                 }
//                 println("Vector A tick @ " + i_tick + ": [" + _a_in_str + "]")
//                 println("Vector B tick @ " + i_tick + ": [" + _b_in_str + "]")
//             }

//             // poke the input vector
//             for (_i <- 0 until (_n * _n)){
//                 dut.io.reg_a_in(_i).poke(_mat_a(_i))
//                 dut.io.reg_b_in(_i).poke(_mat_b(_i))
//             }
//         }
//     }
// }