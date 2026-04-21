// See README.md for license details.
// -----------------------------------------------------------------------------
//  multiWidthRegister.scala — Multi-width aliased register file
//
//  Parameters:
//    L    — number of base VX registers (must be divisible by 4); default 32
//    K    — SIMD lane count per register; default 8
//    N    — base lane width in bits (N(bits)); default 8
//
//  Physical storage: L × K bytes (one N-bit byte per lane per VX entry).
//
//  Register-class views (byte-level aliasing):
//    VX[0..L-1]     — L  registers of K × N    bits  (one VX entry per storage row)
//    VE[0..L/2-1]   — 16 registers of K × 2N   bits  (VE[i] = VX[2i] ∥ VX[2i+1])
//    VR[0..L/4-1]   — 8  registers of K × 4N   bits  (VR[i] = VX[4i..4i+3])
//
//  Writing VR[i] atomically updates the four underlying VX rows.
//  Conflict resolution: last writer wins per row (software responsibility).
//
//  Addressing:
//    VX addr: 5 bits (log2Ceil(L))
//    VE addr: 4 bits (log2Ceil(L/2))
//    VR addr: 3 bits (log2Ceil(L/4))
//
//  Port layout:
//    VX: vx_rd read ports, vx_wr write ports (for INT8 VALU results)
//    VE: ve_rd read ports, ve_wr write ports (for INT16 VALU results)
//    VR: vr_rd read ports, vr_wr write ports (for INT32/FP32; includes MMALU direct)
//    ext_rd / ext_wr: external test-harness ports (VX width)
// -----------------------------------------------------------------------------

package sram.mwreg

import chisel3._
import chisel3.util._

class MultiWidthRegisterBlock(
    val L:      Int = 32,   // number of VX registers; must be divisible by 4
    val K:      Int = 8,    // SIMD lane count per register
    val N:      Int = 8,    // base lane width in bits; VX=N, VE=2N, VR=4N
    val vx_rd:  Int = 4,    // VX read ports
    val vx_wr:  Int = 2,    // VX write ports
    val ve_rd:  Int = 2,    // VE read ports
    val ve_wr:  Int = 1,    // VE write ports
    val vr_rd:  Int = 2,    // VR read ports
    val vr_wr:  Int = 2,    // VR write ports (port 0=VALU, port 1=MMALU direct)
) extends Module {

  require(L % 4 == 0, s"MultiWidthRegisterBlock: L=$L must be divisible by 4")
  require(N > 0 && K > 0 && L > 0)

  val VE_SIZE = L / 2
  val VR_SIZE = L / 4

  val VX_ADDR = log2Ceil(L)
  val VE_ADDR = log2Ceil(VE_SIZE)
  val VR_ADDR = log2Ceil(VR_SIZE)

  val io = IO(new Bundle {
    // ---- VX ports (K lanes of N bits each) ----
    val vx_r_addr = Input(Vec(vx_rd, UInt(VX_ADDR.W)))
    val vx_r_data = Output(Vec(vx_rd, Vec(K, UInt(N.W))))
    val vx_w_addr = Input(Vec(vx_wr, UInt(VX_ADDR.W)))
    val vx_w_data = Input(Vec(vx_wr, Vec(K, UInt(N.W))))
    val vx_w_en   = Input(Vec(vx_wr, Bool()))

    // ---- VE ports (K lanes of 2N bits each) ----
    val ve_r_addr = Input(Vec(ve_rd, UInt(VE_ADDR.W)))
    val ve_r_data = Output(Vec(ve_rd, Vec(K, UInt((2*N).W))))
    val ve_w_addr = Input(Vec(ve_wr, UInt(VE_ADDR.W)))
    val ve_w_data = Input(Vec(ve_wr, Vec(K, UInt((2*N).W))))
    val ve_w_en   = Input(Vec(ve_wr, Bool()))

    // ---- VR ports (K lanes of 4N bits each) ----
    val vr_r_addr = Input(Vec(vr_rd, UInt(VR_ADDR.W)))
    val vr_r_data = Output(Vec(vr_rd, Vec(K, UInt((4*N).W))))
    val vr_w_addr = Input(Vec(vr_wr, UInt(VR_ADDR.W)))
    val vr_w_data = Input(Vec(vr_wr, Vec(K, UInt((4*N).W))))
    val vr_w_en   = Input(Vec(vr_wr, Bool()))

    // ---- External test-harness ports (VX width) ----
    val ext_r_addr = Input(UInt(VX_ADDR.W))
    val ext_r_data = Output(Vec(K, UInt(N.W)))
    val ext_w_addr = Input(UInt(VX_ADDR.W))
    val ext_w_data = Input(Vec(K, UInt(N.W)))
    val ext_w_en   = Input(Bool())
  })

  // ---------- Physical storage: L rows of K lanes of N bits ----------
  // Each row corresponds to one VX register.
  val mem = RegInit(VecInit(Seq.fill(L)(VecInit(Seq.fill(K)(0.U(N.W))))))

  // ---------- Async read: VX ----------
  for (p <- 0 until vx_rd) {
    for (lane <- 0 until K) {
      io.vx_r_data(p)(lane) := mem(io.vx_r_addr(p))(lane)
    }
  }

  // ---------- Async read: VE (pack 2 consecutive VX rows) ----------
  // VE[i] row j: mem[2i][lane] in low N bits, mem[2i+1][lane] in high N bits
  for (p <- 0 until ve_rd) {
    val baseRow = io.ve_r_addr(p) ## 0.U(1.W)  // *2
    for (lane <- 0 until K) {
      val lo = mem(baseRow)(lane)
      val hi = mem(baseRow + 1.U)(lane)
      io.ve_r_data(p)(lane) := Cat(hi, lo)
    }
  }

  // ---------- Async read: VR (pack 4 consecutive VX rows) ----------
  for (p <- 0 until vr_rd) {
    val baseRow = io.vr_r_addr(p) ## 0.U(2.W)  // *4
    for (lane <- 0 until K) {
      val b0 = mem(baseRow + 0.U)(lane)
      val b1 = mem(baseRow + 1.U)(lane)
      val b2 = mem(baseRow + 2.U)(lane)
      val b3 = mem(baseRow + 3.U)(lane)
      io.vr_r_data(p)(lane) := Cat(b3, b2, b1, b0)
    }
  }

  // ---------- External read ----------
  for (lane <- 0 until K) {
    io.ext_r_data(lane) := mem(io.ext_r_addr)(lane)
  }

  // ---------- Write: default all rows disabled each cycle ----------
  // We compute a merged write per row using a priority scheme:
  // VR > VE > VX > ext (highest priority wins for each row)
  // This avoids inferring multi-driven registers.

  val wr_data = Wire(Vec(L, Vec(K, UInt(N.W))))
  val wr_en   = Wire(Vec(L, Bool()))

  // Start with all disabled
  for (row <- 0 until L) {
    wr_en(row)  := false.B
    for (lane <- 0 until K) wr_data(row)(lane) := 0.U
  }

  // External write (lowest priority)
  when (io.ext_w_en) {
    wr_en(io.ext_w_addr)  := true.B
    for (lane <- 0 until K) wr_data(io.ext_w_addr)(lane) := io.ext_w_data(lane)
  }

  // VX writes
  for (p <- 0 until vx_wr) {
    when (io.vx_w_en(p)) {
      wr_en(io.vx_w_addr(p))  := true.B
      for (lane <- 0 until K) wr_data(io.vx_w_addr(p))(lane) := io.vx_w_data(p)(lane)
    }
  }

  // VE writes (split each 2N-bit lane into two N-bit rows)
  for (p <- 0 until ve_wr) {
    when (io.ve_w_en(p)) {
      val base = io.ve_w_addr(p) ## 0.U(1.W)
      val r0 = base
      val r1 = base + 1.U
      wr_en(r0) := true.B
      wr_en(r1) := true.B
      for (lane <- 0 until K) {
        wr_data(r0)(lane) := io.ve_w_data(p)(lane)(N-1, 0)
        wr_data(r1)(lane) := io.ve_w_data(p)(lane)(2*N-1, N)
      }
    }
  }

  // VR writes (split each 4N-bit lane into four N-bit rows) — highest priority
  for (p <- 0 until vr_wr) {
    when (io.vr_w_en(p)) {
      val base = io.vr_w_addr(p) ## 0.U(2.W)
      for (sub <- 0 until 4) {
        val row = base + sub.U
        wr_en(row) := true.B
        for (lane <- 0 until K) {
          wr_data(row)(lane) := io.vr_w_data(p)(lane)(N*(sub+1)-1, N*sub)
        }
      }
    }
  }

  // Clock-edge write
  for (row <- 0 until L) {
    when (wr_en(row)) {
      mem(row) := wr_data(row)
    }
  }
}
