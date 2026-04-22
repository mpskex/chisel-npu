// See README.md for license details.
// -----------------------------------------------------------------------------
//  multiWidthRegister.scala — Unified multi-width register file
//
//  This module serves as BOTH the working register file (VX[0..31], VE[0..15],
//  VR[0..7] accessed directly by VALU/MMALU via 5-bit instruction fields) AND
//  the bulk storage tier (VX[32..L-1] used by LD/ST/gather/scatter with wider
//  addressing).  There is no separate SPM; the entire storage hierarchy lives
//  here.
//
//  Parameters:
//    L    — total number of VX rows (must be divisible by 4).
//           Default 32 (tests); set larger (e.g. 256, 4096) for production.
//    K    — SIMD lane count per register; default 8.
//    N    — base lane width in bits (N(bits)); VX=N, VE=2N, VR=4N.
//
//  Physical storage: K independent register banks.
//    bank(k) holds L entries of N bits — entry r is lane k of VX row r.
//  This K-banked layout enables fully independent per-lane addressing for
//  gather (K different row reads) and scatter (K different row writes).
//
//  Register-class views (aliased from the same physical banks):
//    VX[0..L-1]     — L  registers of K × N    bits
//    VE[0..L/2-1]   — L/2 registers of K × 2N  bits  (VE[i] = VX[2i] ∥ VX[2i+1])
//    VR[0..L/4-1]   — L/4 registers of K × 4N  bits  (VR[i] = VX[4i..4i+3])
//
//  Read latency: 0 cycles (combinational, RegInit flip-flops).
//  Write latency: 1 clock cycle (synchronous register update).
//
//  Write priority (highest last / last-assignment-wins):
//    scatter < ext < vx_w < ve_w < vr_w
//
//  Gather semantics:
//    gather_r_addr(k)  — row address for lane k
//    gather_r_data(k)  — bank(k)[ gather_r_addr(k) ]
//    i.e. VX[rd][k] = RF[ addr_k ][ k ]  (diagonal: lane k from row addr_k)
//
//  Scatter semantics (inverse of gather):
//    scatter_w_addr(k) — row address for lane k
//    scatter_w_data(k) — value to write into bank(k)[ scatter_w_addr(k) ]
//    i.e. RF[ addr_k ][ k ] = VX[rs2][k]
// -----------------------------------------------------------------------------

package sram.mwreg

import chisel3._
import chisel3.util._

class MultiWidthRegisterBlock(
    val L:      Int = 32,   // total VX rows; must be divisible by 4
    val K:      Int = 8,    // SIMD lane count per register
    val N:      Int = 8,    // base lane width in bits; VX=N, VE=2N, VR=4N
    val vx_rd:  Int = 4,    // VX read ports
    val vx_wr:  Int = 2,    // VX write ports
    val ve_rd:  Int = 2,    // VE read ports
    val ve_wr:  Int = 1,    // VE write ports
    val vr_rd:  Int = 2,    // VR read ports
    val vr_wr:  Int = 2,    // VR write ports
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

    // ---- External test-harness / DMA ports (VX width, full address range) ----
    val ext_r_addr = Input(UInt(VX_ADDR.W))
    val ext_r_data = Output(Vec(K, UInt(N.W)))
    val ext_w_addr = Input(UInt(VX_ADDR.W))
    val ext_w_data = Input(Vec(K, UInt(N.W)))
    val ext_w_en   = Input(Bool())

    // ---- Gather read port — K independent row addresses → K lane values ----
    // gather_r_data(k) = bank(k)[ gather_r_addr(k) ]
    // i.e. VX[rd][k] = RF[ addr_k ][ k ]   (diagonal gather)
    val gather_r_addr = Input(Vec(K, UInt(VX_ADDR.W)))
    val gather_r_data = Output(Vec(K, UInt(N.W)))

    // ---- Scatter write port — K independent row addresses ← K lane values ----
    // bank(k)[ scatter_w_addr(k) ] := scatter_w_data(k)
    // i.e. RF[ addr_k ][ k ] = VX[rs2][k]  (diagonal scatter)
    val scatter_w_addr = Input(Vec(K, UInt(VX_ADDR.W)))
    val scatter_w_data = Input(Vec(K, UInt(N.W)))
    val scatter_w_en   = Input(Bool())
  })

  // ==========================================================================
  // Physical storage: K banks, bank(k) holds L entries of N bits
  //   bank(k)(row) = lane k of VX row 'row'
  // ==========================================================================
  // Using a Vec-of-Vec RegInit so each bank is independently indexable.
  val bank = RegInit(VecInit(Seq.fill(K)(VecInit(Seq.fill(L)(0.U(N.W))))))

  // ==========================================================================
  // Combinational reads
  // ==========================================================================

  // ---- VX reads ----
  for (p <- 0 until vx_rd; k <- 0 until K) {
    io.vx_r_data(p)(k) := bank(k)(io.vx_r_addr(p))
  }

  // ---- VE reads: VE[i][k] = Cat( bank(k)(2i+1), bank(k)(2i) ) ----
  for (p <- 0 until ve_rd) {
    val baseRow = io.ve_r_addr(p) ## 0.U(1.W)   // *2
    for (k <- 0 until K) {
      io.ve_r_data(p)(k) := Cat(bank(k)(baseRow + 1.U), bank(k)(baseRow))
    }
  }

  // ---- VR reads: VR[i][k] = Cat( bank(k)(4i+3..4i) ) ----
  for (p <- 0 until vr_rd) {
    val baseRow = io.vr_r_addr(p) ## 0.U(2.W)   // *4
    for (k <- 0 until K) {
      io.vr_r_data(p)(k) := Cat(
        bank(k)(baseRow + 3.U),
        bank(k)(baseRow + 2.U),
        bank(k)(baseRow + 1.U),
        bank(k)(baseRow)
      )
    }
  }

  // ---- External read ----
  for (k <- 0 until K) {
    io.ext_r_data(k) := bank(k)(io.ext_r_addr)
  }

  // ---- Gather read: bank(k)[ gather_r_addr(k) ] ----
  for (k <- 0 until K) {
    io.gather_r_data(k) := bank(k)(io.gather_r_addr(k))
  }

  // ==========================================================================
  // Synchronous writes (last-assignment-wins = highest priority)
  //
  // Priority order (lowest → highest): scatter < ext < vx_w < ve_w < vr_w
  //
  // Because Chisel assigns the LAST matching `when` in source order, we list
  // lower-priority writes first and higher-priority writes last.
  // ==========================================================================

  // ---- Scatter write (lowest priority) ----
  when (io.scatter_w_en) {
    for (k <- 0 until K) {
      bank(k)(io.scatter_w_addr(k)) := io.scatter_w_data(k)
    }
  }

  // ---- External write ----
  when (io.ext_w_en) {
    for (k <- 0 until K) {
      bank(k)(io.ext_w_addr) := io.ext_w_data(k)
    }
  }

  // ---- VX writes ----
  for (p <- 0 until vx_wr) {
    when (io.vx_w_en(p)) {
      for (k <- 0 until K) {
        bank(k)(io.vx_w_addr(p)) := io.vx_w_data(p)(k)
      }
    }
  }

  // ---- VE writes: split each 2N-bit lane into lo/hi N-bit rows ----
  for (p <- 0 until ve_wr) {
    when (io.ve_w_en(p)) {
      val base = io.ve_w_addr(p) ## 0.U(1.W)
      for (k <- 0 until K) {
        bank(k)(base)       := io.ve_w_data(p)(k)(N-1,   0)
        bank(k)(base + 1.U) := io.ve_w_data(p)(k)(2*N-1, N)
      }
    }
  }

  // ---- VR writes: split each 4N-bit lane into four N-bit rows (highest priority) ----
  for (p <- 0 until vr_wr) {
    when (io.vr_w_en(p)) {
      val base = io.vr_w_addr(p) ## 0.U(2.W)
      for (sub <- 0 until 4; k <- 0 until K) {
        bank(k)(base + sub.U) := io.vr_w_data(p)(k)(N*(sub+1)-1, N*sub)
      }
    }
  }
}
