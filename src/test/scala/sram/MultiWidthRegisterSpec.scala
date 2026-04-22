// See README.md for license details.
// Tests for MultiWidthRegisterBlock: VX/VE/VR aliasing and write-back.

package sram.mwreg

import scala.util.Random
import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec

class MultiWidthRegisterSpec extends AnyFlatSpec {
  val L = 32; val K = 8; val N = 8
  val N2 = 2 * N; val N4 = 4 * N
  val rand = new Random(0xBEEF)

  def randBytes(n: Int): Array[Int] = Array.fill(n)(rand.nextInt(256))

  "MultiWidthRegisterBlock" should "write VX and read back via VX" in {
    simulate(new MultiWidthRegisterBlock(L, K, N)) { dut =>
      // default disable everything
      dut.io.vx_w_en(0).poke(false.B)
      dut.io.vx_w_en(1).poke(false.B)
      dut.io.ve_w_en(0).poke(false.B)
      dut.io.vr_w_en(0).poke(false.B)
      dut.io.vr_w_en(1).poke(false.B)
      dut.io.ext_w_en.poke(false.B)
      for (p <- 0 until 4) dut.io.vx_r_addr(p).poke(0.U)
      for (p <- 0 until 2) dut.io.ve_r_addr(p).poke(0.U)
      for (p <- 0 until 2) dut.io.vr_r_addr(p).poke(0.U)
      dut.io.ext_r_addr.poke(0.U)

      val data = randBytes(K)
      val addr = 5

      dut.io.vx_w_en(0).poke(true.B)
      dut.io.vx_w_addr(0).poke(addr.U)
      data.zipWithIndex.foreach { case (v, i) => dut.io.vx_w_data(0)(i).poke(v.U) }
      dut.clock.step()

      dut.io.vx_w_en(0).poke(false.B)
      dut.io.vx_r_addr(0).poke(addr.U)
      for (i <- 0 until K) {
        dut.io.vx_r_data(0)(i).expect(data(i).U, s"VX read lane $i mismatch")
      }
    }
  }

  "MultiWidthRegisterBlock" should "write VX and read back as VE (aliasing)" in {
    simulate(new MultiWidthRegisterBlock(L, K, N)) { dut =>
      dut.io.vx_w_en(0).poke(false.B)
      dut.io.vx_w_en(1).poke(false.B)
      dut.io.ve_w_en(0).poke(false.B)
      dut.io.vr_w_en(0).poke(false.B)
      dut.io.vr_w_en(1).poke(false.B)
      dut.io.ext_w_en.poke(false.B)
      for (p <- 0 until 4) dut.io.vx_r_addr(p).poke(0.U)
      for (p <- 0 until 2) dut.io.ve_r_addr(p).poke(0.U)
      for (p <- 0 until 2) dut.io.vr_r_addr(p).poke(0.U)
      dut.io.ext_r_addr.poke(0.U)

      // VE[2] = VX[4] ∥ VX[5]
      val veIdx = 2
      val vx0Idx = 4; val vx1Idx = 5

      val dataVX0 = randBytes(K)
      val dataVX1 = randBytes(K)

      // Write VX[4]
      dut.io.vx_w_en(0).poke(true.B)
      dut.io.vx_w_addr(0).poke(vx0Idx.U)
      dataVX0.zipWithIndex.foreach { case (v, i) => dut.io.vx_w_data(0)(i).poke(v.U) }
      dut.clock.step()

      // Write VX[5]
      dut.io.vx_w_addr(0).poke(vx1Idx.U)
      dataVX1.zipWithIndex.foreach { case (v, i) => dut.io.vx_w_data(0)(i).poke(v.U) }
      dut.clock.step()
      dut.io.vx_w_en(0).poke(false.B)

      // Read via VE[2]
      dut.io.ve_r_addr(0).poke(veIdx.U)
      for (i <- 0 until K) {
        val loExpected = dataVX0(i)
        val hiExpected = dataVX1(i)
        val veExpected = ((hiExpected & 0xFF) << N) | (loExpected & 0xFF)
        dut.io.ve_r_data(0)(i).expect(veExpected.U,
          s"VE alias lane $i: expected ${veExpected.toHexString} lo=$loExpected hi=$hiExpected")
      }
    }
  }

  "MultiWidthRegisterBlock" should "write VR and read back via VX (aliasing)" in {
    simulate(new MultiWidthRegisterBlock(L, K, N)) { dut =>
      dut.io.vx_w_en(0).poke(false.B)
      dut.io.vx_w_en(1).poke(false.B)
      dut.io.ve_w_en(0).poke(false.B)
      dut.io.vr_w_en(0).poke(false.B)
      dut.io.vr_w_en(1).poke(false.B)
      dut.io.ext_w_en.poke(false.B)
      for (p <- 0 until 4) dut.io.vx_r_addr(p).poke(0.U)
      for (p <- 0 until 2) dut.io.ve_r_addr(p).poke(0.U)
      for (p <- 0 until 2) dut.io.vr_r_addr(p).poke(0.U)
      dut.io.ext_r_addr.poke(0.U)

      // Write VR[1] (= VX[4..7])
      val vrIdx = 1
      val data32 = Array.fill(K)(rand.nextInt(Int.MaxValue) & 0xFFFFFFFFL).map(_.toInt)

      dut.io.vr_w_en(0).poke(true.B)
      dut.io.vr_w_addr(0).poke(vrIdx.U)
      data32.zipWithIndex.foreach { case (v, i) => dut.io.vr_w_data(0)(i).poke(v.U) }
      dut.clock.step()
      dut.io.vr_w_en(0).poke(false.B)

      // Read back via VR
      dut.io.vr_r_addr(0).poke(vrIdx.U)
      for (i <- 0 until K) {
        dut.io.vr_r_data(0)(i).expect((data32(i) & 0xFFFFFFFFL).U,
          s"VR readback lane $i")
      }

      // Read via constituent VX rows
      for (sub <- 0 until 4) {
        val vxRow = vrIdx * 4 + sub
        dut.io.vx_r_addr(0).poke(vxRow.U)
        for (i <- 0 until K) {
          val byteVal = (data32(i) >> (N * sub)) & 0xFF
          dut.io.vx_r_data(0)(i).expect(byteVal.U,
            s"VR→VX alias row $vxRow lane $i sub=$sub")
        }
      }
    }
  }

  "MultiWidthRegisterBlock" should "external write and read" in {
    simulate(new MultiWidthRegisterBlock(L, K, N)) { dut =>
      dut.io.vx_w_en(0).poke(false.B)
      dut.io.vx_w_en(1).poke(false.B)
      dut.io.ve_w_en(0).poke(false.B)
      dut.io.vr_w_en(0).poke(false.B)
      dut.io.vr_w_en(1).poke(false.B)
      for (p <- 0 until 4) dut.io.vx_r_addr(p).poke(0.U)
      for (p <- 0 until 2) dut.io.ve_r_addr(p).poke(0.U)
      for (p <- 0 until 2) dut.io.vr_r_addr(p).poke(0.U)
      dut.io.ext_r_addr.poke(0.U)

      val data = randBytes(K)
      dut.io.ext_w_en.poke(true.B)
      dut.io.ext_w_addr.poke(10.U)
      data.zipWithIndex.foreach { case (v, i) => dut.io.ext_w_data(i).poke(v.U) }
      dut.clock.step()
      dut.io.ext_w_en.poke(false.B)

      dut.io.ext_r_addr.poke(10.U)
      for (i <- 0 until K) {
        dut.io.ext_r_data(i).expect(data(i).U, s"ext read lane $i")
      }
    }
  }
}
