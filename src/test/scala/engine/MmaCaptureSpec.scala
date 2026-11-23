// See README.md for license details.
// -----------------------------------------------------------------------------
//  MmaCaptureSpec.scala — the hybrid MMA capture contract.
//
//   * Every `mma`/`mma.last` is a REAL (A,B) feed; `mma.last` additionally
//     marks the round end (PE keep=0 for the next round).
//   * The ISA-visible, deterministic result is the round-final capture: the
//     `mma.last` writes VR[vd] = A[i]*B[K-1] + C[i].
//   * Every `mma`/`mma.last` writes its own VR[vd]; the non-last writes are
//     running-sum partials (no fixed column is asserted for them).
//
//  These are strict RED tests for the pre-fix RTL: the collector snapshots
//  physical column 0 instead of K-1, only the mma.last commits a capture, and
//  the mma.last feed zero-forces A/B.
// -----------------------------------------------------------------------------

package engine

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec
import testUtil._
import isa._
import isa.NpuAssembler._

class MmaCaptureSpec extends AnyFlatSpec {

  // ---- RAM / ctrl helpers (same shape as the other engine specs) -----------

  def preload(dut: FrontendSpecHarness, base: Long, bytes: Seq[Int]): Unit = {
    for ((b, i) <- bytes.grouped(16).zipWithIndex) {
      var w = BigInt(0)
      for ((v, j) <- b.zipWithIndex) w |= (BigInt(v & 0xFF) << (8 * j))
      dut.io.pre_wr_addr.poke((((base + i * 16) >> 4) & 0xFFF).U)
      dut.io.pre_wr_data.poke(w.U)
      dut.io.pre_wr_en.poke(true.B)
      dut.clock.step()
      dut.io.pre_wr_en.poke(false.B)
    }
  }

  def preloadI8(dut: FrontendSpecHarness, base: Long, values: Seq[Int]): Unit =
    preload(dut, base, values.map(_ & 0xFF))

  def preloadI32(dut: FrontendSpecHarness, base: Long, values: Seq[Int]): Unit = {
    val bytes = values.flatMap(v => Seq(v & 0xFF, (v >> 8) & 0xFF, (v >> 16) & 0xFF, (v >> 24) & 0xFF))
    preload(dut, base, bytes)
  }

  def preloadInstrs(dut: FrontendSpecHarness, instrs: Seq[Int]): Unit = {
    val bytes = instrs.flatMap(w => Seq(w & 0xFF, (w >> 8) & 0xFF, (w >> 16) & 0xFF, (w >> 24) & 0xFF))
    preload(dut, NpuSections.CODE_BASE, bytes)
  }

  def readI32(dut: FrontendSpecHarness, base: Long, n: Int): Array[Int] = {
    val out = Array.fill(4 * n)(0)
    for (w <- 0 until 4 * n / 16) {
      dut.io.dbg_rd_addr.poke((((base + 16 * w) >> 4) & 0xFFF).U)
      dut.clock.step(0)
      val got = dut.io.dbg_rd_data.peek().litValue
      for (b <- 0 until 16) out(16 * w + b) = ((got >> (8 * b)) & 0xFF).toInt
    }
    Array.tabulate(n)(i => out(4 * i) | (out(4 * i + 1) << 8) |
      (out(4 * i + 2) << 16) | (out(4 * i + 3) << 24))
  }

  def ctrlWrite(dut: FrontendSpecHarness, addr: Int, data: Int): Unit = {
    dut.io.ctrl_addr.poke(addr.U)
    dut.io.ctrl_we.poke(true.B)
    dut.io.ctrl_wdata.poke((data.toLong & 0xFFFFFFFFL).U)
    dut.clock.step()
    dut.io.ctrl_we.poke(false.B)
  }

  def ctrlRead(dut: FrontendSpecHarness, addr: Int): Long = {
    dut.io.ctrl_addr.poke(addr.U)
    dut.io.ctrl_we.poke(false.B)
    dut.clock.step(0)
    dut.io.ctrl_rdata.peek().litValue.toLong
  }

  def start(dut: FrontendSpecHarness): Unit = {
    ctrlWrite(dut, 0x0, 1)
    ctrlWrite(dut, 0x0, 0)
  }

  def waitDone(dut: FrontendSpecHarness, maxCycles: Int = 500000): Unit = {
    var n = 0
    while (((ctrlRead(dut, 0x0) >> 1) & 1) == 0 && n < maxCycles) { dut.clock.step(); n += 1 }
    assert(((ctrlRead(dut, 0x0) >> 1) & 1) == 1, s"program did not finish within $maxCycles cycles")
  }

  def run(dut: FrontendSpecHarness, prog: Seq[Int]): Unit = {
    preloadInstrs(dut, prog)
    ctrlWrite(dut, 0x14, prog.length)   // PROG_LEN
    start(dut)
    waitDone(dut)
    val st = ctrlRead(dut, 0x8)
    assert(((st >> 31) & 1) == 0, "no illegal instruction expected")
  }

  /** ISA one-shot at a given dispatch-window depth: ONE real mma.last carrying
    * A, B and C.  VR[vd] must be exactly A[i]*B[K-1] + C[i]. */
  def roundFinal(W: Int): Unit = simulate(new FrontendSpecHarness(K = 16, W = W)) { dut =>
    val k = 16
    val a = Array.tabulate(k)(i => i + 1)              // distinct, 1..16
    val b = Array.tabulate(k)(i => 3 * i + 1)          // distinct, 1..46
    val c = Array.tabulate(k)(i => 500 + i)
    preloadI8(dut, NpuSections.A, a)
    preloadI8(dut, NpuSections.B, b)
    preloadI32(dut, NpuSections.ACCUM, c)

    // A = VX[4], B = VX[8], C = VR[3], result = VR[0].
    val prog = Seq(
      vle8(rd = 4, sect = NpuSections.SECT_A, off = 0),
      vle8(rd = 8, sect = NpuSections.SECT_B, off = 0),
      vle32(rd = 3, sect = NpuSections.SECT_ACCUM, off = 0),
      mmaLast(rd = 0, vs1 = 4, vs2 = 8, vs3 = 3),
      vse32(src = 0, sect = NpuSections.SECT_OUT, off = 0),
    )
    run(dut, prog)

    val out = readI32(dut, NpuSections.OUT, k)
    println(s"[mma-capture] W=$W round-final out = ${out.toList}")
    for (i <- 0 until k) {
      val want = a(i) * b(k - 1) + c(i)
      assert(out(i) == want,
        s"W=$W round-final lane $i: got ${out(i)}, want A[i]*B[K-1]+C[i] = $want " +
        s"(A=${a(i)} B[K-1]=${b(k - 1)} C=${c(i)}); a*B[0]+C would be " +
        s"${a(i) * b(0) + c(i)}: ${out.toList}")
    }
  }

  "MmaCaptureSpec" should "capture the ISA B[K-1] column of a one-shot mma.last at W=4" in {
    roundFinal(W = 4)
  }

  "MmaCaptureSpec" should "capture the ISA B[K-1] column of a one-shot mma.last at W=8" in {
    roundFinal(W = 8)
  }

  "MmaCaptureSpec" should "write every mma's own VR[vv] (partials) and the exact round-final K-1" in {
    simulate(new FrontendSpecHarness(K = 16, W = 8)) { dut =>
      val k = 16
      val vdA = 0    // VR[0] = VX[0..3]   (no operand alias)
      val vdB = 3    // VR[3] = VX[12..15] (no operand alias)
      val a = Array.tabulate(k)(i => i + 1)
      val b = Array.tabulate(k)(i => 3 * i + 1)
      preloadI8(dut, NpuSections.A, a)
      preloadI8(dut, NpuSections.B, b)

      // K-1 real mma feeds writing the partial register, then ONE real
      // mma.last writing the round-final register, C = 0.
      val prog = Seq(
        vle8(rd = 4, sect = NpuSections.SECT_A, off = 0),
        vle8(rd = 8, sect = NpuSections.SECT_B, off = 0),
      ) ++ (0 until k - 1).map(_ => mma(rd = vdA, vs1 = 4, vs2 = 8, vs3 = 0)) ++ Seq(
        mmaLast(rd = vdB, vs1 = 4, vs2 = 8, vs3 = 0),
        vse32(src = vdA, sect = NpuSections.SECT_OUT, off = 0),
        vse32(src = vdB, sect = NpuSections.SECT_OUT, off = k * 4),
      )
      run(dut, prog)

      val out = readI32(dut, NpuSections.OUT, 2 * k)
      val partial = out.slice(0, k)
      val finalv  = out.slice(k, 2 * k)
      println(s"[mma-capture] per-mma partial VR[$vdA] = ${partial.toList}")
      println(s"[mma-capture] per-mma final   VR[$vdB] = ${finalv.toList}")

      // The round-final register is exactly K * A[i] * B[K-1] (C = 0).
      for (i <- 0 until k) {
        val want = k * a(i) * b(k - 1)
        assert(finalv(i) == want,
          s"round-final VR[$vdB] lane $i: got ${finalv(i)}, want K*A[i]*B[K-1] = $want: ${finalv.toList}")
      }
      // The non-last register WAS written (a running-sum partial), but we do
      // not assert which physical column it captured.
      assert(partial.exists(_ != 0),
        s"the non-last mma never wrote VR[$vdA] (all zero): ${partial.toList}")
      assert(!partial.sameElements(finalv),
        s"the non-last mma wrote the final value instead of a partial: ${partial.toList}")
    }
  }

  "MmaCaptureSpec" should "capture two grouped output columns (NK=4) bit-exactly" in {
    simulate(new FrontendSpecHarness(K = 16, W = 8)) { dut =>
      val k  = 16
      val nk = 4
      // Output column j is one round of NK feeds; feed t carries A[:,t] and a
      // B vector with B[t][j] at ISA lane K-1.
      val a = Array.tabulate(k, nk)((i, t) => ((i * 7 + t * 3 + 1) & 0x7F))
      val b = Array.tabulate(nk, 2)((t, j) => ((t * 11 + j * 5 + 3) & 0x7F))

      // A section: column-major A[:,t] vectors (K bytes each).
      val aBytes = (0 until nk).flatMap(t => (0 until k).map(i => a(i)(t)))
      // B section: per t a 32-byte block holding the two 16-byte vectors
      // (col 0 at +0, col 1 at +16); only lane K-1 is non-zero.
      val bBytes = Array.fill(nk * 32)(0)
      for (t <- 0 until nk; j <- 0 until 2) bBytes(t * 32 + j * 16 + (k - 1)) = b(t)(j)
      preloadI8(dut, NpuSections.A, aBytes)
      preloadI8(dut, NpuSections.B, bBytes)

      val vds = Seq(0, 3)   // VR[0] = VX[0..3], VR[3] = VX[12..15]
      val prog = (0 until 2).flatMap { j =>
        val vd = vds(j)
        (0 until nk - 1).flatMap { t =>
          Seq(vle8(rd = 4, sect = NpuSections.SECT_A, off = t * k),
              vle8(rd = 8, sect = NpuSections.SECT_B, off = t * 32 + j * 16),
              mma(rd = vd, vs1 = 4, vs2 = 8, vs3 = 0))
        } ++ Seq(
          vle8(rd = 4, sect = NpuSections.SECT_A, off = (nk - 1) * k),
          vle8(rd = 8, sect = NpuSections.SECT_B, off = (nk - 1) * 32 + j * 16),
          mmaLast(rd = vd, vs1 = 4, vs2 = 8, vs3 = 0),
          vse32(src = vd, sect = NpuSections.SECT_OUT, off = j * k * 4),
        )
      }
      run(dut, prog)

      val out = readI32(dut, NpuSections.OUT, 2 * k)
      println(s"[mma-capture] grouped col0 = ${out.slice(0, k).toList}")
      println(s"[mma-capture] grouped col1 = ${out.slice(k, 2 * k).toList}")
      for (j <- 0 until 2; i <- 0 until k) {
        val want = (0 until nk).map(t => a(i)(t) * b(t)(j)).sum
        assert(out(j * k + i) == want,
          s"grouped column $j lane $i: got ${out(j * k + i)}, want Σ_t A[i][t]*B[t][$j] " +
          s"= $want: ${out.toList}")
      }
    }
  }
}
