// See README.md for license details.
// -----------------------------------------------------------------------------
//  spm.scala — Scratch-Pad Memory (SPM)
//
//  A simple synchronous SRAM with one K-wide read port and one K-wide write
//  port.  The data bus width equals one VX register row: K lanes × N bits.
//
//  Parameters:
//    K        — SIMD lane count (= MultiWidthRegisterBlock.K)
//    N        — Base lane width in bits / N(bits)
//    SPM_ROWS — Number of rows.  Total bytes = SPM_ROWS × K × N / 8.
//               Default 4096 rows → 32 KiB with K=8, N=8.
//
//  Addressing:
//    Row address is (log2Ceil(SPM_ROWS)) bits wide.
//    LD/ST instructions compute the row as:  rs1_raw + sext(imm)
//    where rs1_raw is the 5-bit rs1 field (0..31) treated as a page base,
//    and imm is the signed 12-bit row offset.
//    For SPM_ROWS ≤ 2048 the rs1 field can stay 0; imm alone suffices.
//
//  Read latency: 1 clock cycle (SyncReadMem).
//  Write latency: 0 clocks (synchronous write, data visible next cycle).
//
//  Ports:
//    rd_*   : read port (used by LD instructions and the PAG)
//    wr_*   : write port (used by ST instructions and the host/DMA loader)
//    ext_*  : external port — identical to wr_* but named separately so
//             the test harness and future DMA can both be wired.
// -----------------------------------------------------------------------------

package sram.spm

import chisel3._
import chisel3.util._

class SPM(
    val K:        Int = 8,     // lane count
    val N:        Int = 8,     // lane width in bits (N(bits))
    val SPM_ROWS: Int = 4096,  // number of rows; default = 32 KiB at K=8,N=8
) extends Module {

  require(SPM_ROWS > 0 && isPow2(SPM_ROWS), s"SPM_ROWS=$SPM_ROWS must be a power of 2")
  require(N > 0 && K > 0)

  val BYTES_PER_ROW = K * N / 8   // number of bytes in one VX row
  val ROW_ADDR_W    = log2Ceil(SPM_ROWS)

  val io = IO(new Bundle {

    // ---- Read port (LD instruction, PAG) ----
    // Issue rd_en on cycle T; data arrives on rd_data on cycle T+1.
    // rd_valid is the registered version of rd_en (one cycle delayed).
    val rd_addr  = Input(UInt(ROW_ADDR_W.W))
    val rd_en    = Input(Bool())
    val rd_data  = Output(Vec(K, UInt(N.W)))
    val rd_valid = Output(Bool())    // pulses one cycle after rd_en

    // ---- Write port (ST instruction) ----
    val wr_addr  = Input(UInt(ROW_ADDR_W.W))
    val wr_en    = Input(Bool())
    val wr_data  = Input(Vec(K, UInt(N.W)))

    // ---- External / DMA port (test harness and future host DMA) ----
    // Identical semantics to wr_*; merged via OR in hardware.
    val ext_wr_addr = Input(UInt(ROW_ADDR_W.W))
    val ext_wr_en   = Input(Bool())
    val ext_wr_data = Input(Vec(K, UInt(N.W)))
  })

  // ---- Physical SRAM ----
  val mem = SyncReadMem(SPM_ROWS, Vec(K, UInt(N.W)))

  // ---- Read (1-cycle latency) ----
  io.rd_data  := mem.read(io.rd_addr, io.rd_en)
  io.rd_valid := RegNext(io.rd_en, false.B)

  // ---- Write (ST port has priority over ext; last-write-wins if same addr) ----
  when (io.wr_en) {
    mem.write(io.wr_addr, io.wr_data)
  }
  when (io.ext_wr_en) {
    mem.write(io.ext_wr_addr, io.ext_wr_data)
  }
}
