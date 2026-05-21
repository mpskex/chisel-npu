// See README.md for license details.
// =============================================================================
//  MMALUStreamReduceSpec.scala — verifies that the K×K systolic array natively
//  computes an M×K reduction with M arbitrarily larger than K, by feeding M
//  consecutive cycles with ctrl.keep = true held throughout (no reset between
//  K-sub-passes).
//
//  Motivation: the existing MMALUSpec only exercises the M = K corner case
//  (one K-cycle feed window per result).  The PE accumulator (`res` in
//  alu/pe/procElem.scala) is a permanent register; with `keep = true` held it
//  keeps summing across as many cycles as you feed.  The DataFeeder /
//  ControlUnit are stateless pipes.  Only the existing MMALUSpec protocol
//  (which pokes a single `keep=false` cycle to demarcate K-bursts) caps the
//  reduction at K.
//
//  Timing model (verified analytically before writing this spec):
//  For an M-cycle continuous feed with keep=true, the collector emits a
//  sequence of staircased K×K frames at output cycles
//
//      frame f (f = 1..ceil(M/K))   T ∈ [(f+1)·K − 1, (f+2)·K − 2]
//
//  where the entries of frame f are
//
//      frame_f[i,j] = Σ_{m=0..f·K − 1} A[m, j] · B[m, i]
//
//  (matching the MMALUSpec convention C[i,j] = Σ A[m,j]·B[m,i], i.e. C = BᵀA).
//
//  Using the same "i_tick" convention as MMALUSpec — i_tick = T − 1, i.e. the
//  loop variable poked BEFORE `dut.clock.step()` — frame f lives at
//
//      i_tick ∈ [(f+1)·K − 2, (f+2)·K − 2)         (K iterations)
//
//  Test 1 (M = 2K): verify frame 1 (K-partial) and frame 2 (2K full).
//  Test 2 (M = 3K): verify frames 1, 2, 3.
//  Test 3 (M = 5K): verify frames 1..5 (cnt-mod-K wraps several times).
//  Test 4 (M = 2K with keep=false at i_tick = K-1):
//          inserts the MMALUSpec-style reset and verifies the frame 2 output
//          equals the SECOND K-only sum (not the full 2K sum) — proves the
//          inserted reset is the sole cause of K-granularity.
// =============================================================================

package alu.mma

import alu.pe._
import scala.util.Random
import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec

class MMALUStreamReduceSpec extends AnyFlatSpec {

  // ---------------------------------------------------------------------------
  // Scala reference.
  //
  //   refSum(matA, matB, K, lim)[i, j] = Σ_{m=0..lim-1} matA[m, j] · matB[m, i]
  //
  // matA and matB are stored row-major as Array[Int] of length lim · K, where
  // matA(m*K + j) is the j-th lane of the m-th feed cycle (i.e. what
  // `dut.io.in_a(j).poke(...)` would receive at i_tick = m).
  //
  // The returned array is also row-major: K rows × K columns, ref(i*K + j) is
  // the value the test expects to read from `dut.io.out(j)` at the row-i drain
  // cycle of the matching frame.
  // ---------------------------------------------------------------------------
  private def refSum(matA: Array[Int], matB: Array[Int],
                     K: Int, lim: Int): Array[Int] = {
    val out = Array.fill(K * K)(0)
    for (i <- 0 until K) {
      for (j <- 0 until K) {
        var acc = 0
        for (m <- 0 until lim) {
          acc += matA(m * K + j) * matB(m * K + i)
        }
        out(i * K + j) = acc
      }
    }
    out
  }

  // ---------------------------------------------------------------------------
  // Random M×K matrix of INT8 values.
  // ---------------------------------------------------------------------------
  private def randMat(rand: Random, M: Int, K: Int): Array[Int] =
    Array.fill(M * K)(rand.between(-128, 128))

  // ---------------------------------------------------------------------------
  // Run a continuous M-cycle feed with the contract under test:
  //
  //   - feed cycle i (0 ≤ i < M):  poke real (a, b) row, keep = true, busy = true
  //   - drain cycle i (M ≤ i):     poke zeros, keep = false, busy = false
  //
  // Returns the captured `dut.io.out` for every cycle in the loop, indexed by
  // i_tick (the loop variable used in the existing MMALUSpec).  capture(t)(lane)
  // is the value read AFTER step() at iteration t.
  // ---------------------------------------------------------------------------
  private def runStream(
      dut:       MMALU[_],
      matA:      Array[Int],
      matB:      Array[Int],
      M:         Int,
      iterations: Int,
      // optional: pokes keep=false at this single i_tick within [0, M)
      injectReset: Option[Int] = None
  ): Array[Array[Int]] = {
    val K = dut.n
    val capture = Array.ofDim[Int](iterations, K)

    for (iTick <- 0 until iterations) {
      if (iTick < M) {
        for (lane <- 0 until K) {
          dut.io.in_a(lane).poke(matA(iTick * K + lane))
          dut.io.in_b(lane).poke(matB(iTick * K + lane))
          dut.io.in_accum(lane).poke(0)
        }
      } else {
        for (lane <- 0 until K) {
          dut.io.in_a(lane).poke(0)
          dut.io.in_b(lane).poke(0)
          dut.io.in_accum(lane).poke(0)
        }
      }

      val keepHigh = (iTick < M) && !injectReset.contains(iTick)
      dut.io.ctrl.keep.poke(keepHigh)
      dut.io.ctrl.busy.poke(iTick < M)
      dut.io.ctrl.use_accum.poke(false)

      dut.clock.step()

      for (lane <- 0 until K) {
        capture(iTick)(lane) = dut.io.out(lane).peek().litValue.toInt
      }
    }

    capture
  }

  // ---------------------------------------------------------------------------
  // Compare frame f of capture against a Scala reference matrix.
  //
  // Frame f (1-indexed) lives at i_tick ∈ [(f+1)K − 2, (f+2)K − 2):
  //   row step (0..K-1) = iTick − ((f+1)K − 2)
  //
  // Returns the list of (iTick, lane, got, expected) tuples for any mismatch.
  // ---------------------------------------------------------------------------
  private def checkFrame(
      capture: Array[Array[Int]],
      K:       Int,
      f:       Int,
      ref:     Array[Int],
      label:   String,
      msgs:    StringBuilder
  ): Int = {
    val base = (f + 1) * K - 2
    var failures = 0
    for (step <- 0 until K) {
      val iTick = base + step
      for (lane <- 0 until K) {
        val got = capture(iTick)(lane)
        val exp = ref(step * K + lane)
        if (got != exp) {
          failures += 1
          msgs.append(
            s"[$label] frame=$f i_tick=$iTick step=$step lane=$lane " +
            s"got=$got expected=$exp\n"
          )
        }
      }
    }
    failures
  }

  // ===========================================================================
  // Test 1 — M = 2K: K-partial frame + full 2K-partial frame
  // ===========================================================================
  "MMALU" should
    "expose both the K-partial and the full 2K-partial frame during one continuous feed (M = 2K)" in {
    simulate(new MMALU(new MMPE(8, 32), 4, 8, 32)) { dut =>
      val K = dut.n
      val M = 2 * K
      val rand = new Random(0xABCDL)
      val matA = randMat(rand, M, K)
      val matB = randMat(rand, M, K)

      val expK  = refSum(matA, matB, K, K)
      val exp2K = refSum(matA, matB, K, 2 * K)

      // Feed M, drain enough for frame 2 (which ends at i_tick = (M/K + 2)K - 3 = 4K - 3).
      // Loop one cycle beyond for safety.
      val iterations = M + 2 * K
      val capture = runStream(dut, matA, matB, M, iterations)

      val msgs = new StringBuilder
      val f1 = checkFrame(capture, K, f = 1, ref = expK,  label = "K-partial",       msgs = msgs)
      val f2 = checkFrame(capture, K, f = 2, ref = exp2K, label = "2K-full",         msgs = msgs)

      if (f1 + f2 > 0) {
        println("===== capture (M=2K, no reset) =====")
        for (t <- 0 until iterations) {
          println(s"i_tick=$t out=" + (0 until K).map(capture(t)(_)).mkString(","))
        }
        fail(s"$f1 K-partial and $f2 2K-full mismatches:\n${msgs.toString}")
      }
    }
  }

  // ===========================================================================
  // Test 2 — M = 3K: frames 1, 2, 3
  // ===========================================================================
  it should
    "expose K, 2K, and 3K partial frames during one continuous feed (M = 3K)" in {
    simulate(new MMALU(new MMPE(8, 32), 4, 8, 32)) { dut =>
      val K = dut.n
      val M = 3 * K
      val rand = new Random(0x1234L)
      val matA = randMat(rand, M, K)
      val matB = randMat(rand, M, K)

      val expK  = refSum(matA, matB, K, 1 * K)
      val exp2K = refSum(matA, matB, K, 2 * K)
      val exp3K = refSum(matA, matB, K, 3 * K)

      val iterations = M + 2 * K
      val capture = runStream(dut, matA, matB, M, iterations)

      val msgs = new StringBuilder
      val f1 = checkFrame(capture, K, 1, expK,  "K-partial",  msgs)
      val f2 = checkFrame(capture, K, 2, exp2K, "2K-partial", msgs)
      val f3 = checkFrame(capture, K, 3, exp3K, "3K-full",    msgs)

      if (f1 + f2 + f3 > 0) {
        println("===== capture (M=3K, no reset) =====")
        for (t <- 0 until iterations) {
          println(s"i_tick=$t out=" + (0 until K).map(capture(t)(_)).mkString(","))
        }
        fail(s"$f1 / $f2 / $f3 mismatches at frames 1/2/3:\n${msgs.toString}")
      }
    }
  }

  // ===========================================================================
  // Test 3 — M = 5K: five staircased frames (cnt mod K wraps multiple times)
  // ===========================================================================
  it should
    "expose 5 staircased partial frames during one continuous feed (M = 5K)" in {
    simulate(new MMALU(new MMPE(8, 32), 4, 8, 32)) { dut =>
      val K = dut.n
      val M = 5 * K
      val rand = new Random(0xDEADBEEFL)
      val matA = randMat(rand, M, K)
      val matB = randMat(rand, M, K)

      val refs = (1 to 5).map(k => refSum(matA, matB, K, k * K))

      val iterations = M + 2 * K
      val capture = runStream(dut, matA, matB, M, iterations)

      val msgs = new StringBuilder
      var totalFailures = 0
      for (f <- 1 to 5) {
        totalFailures += checkFrame(capture, K, f, refs(f - 1),
                                    label = s"${f}K-partial", msgs = msgs)
      }

      if (totalFailures > 0) {
        println("===== capture (M=5K, no reset) =====")
        for (t <- 0 until iterations) {
          println(s"i_tick=$t out=" + (0 until K).map(capture(t)(_)).mkString(","))
        }
        fail(s"$totalFailures mismatches across 5 frames:\n${msgs.toString}")
      }
    }
  }

  // ===========================================================================
  // Test 4 — keep=false at i_tick = K-1 collapses M=2K to two disjoint K-results
  //
  // This is the existing MMALUSpec "in stream" protocol rebuilt as a unit
  // test.  By injecting a single keep=false cycle in the middle of the M-cycle
  // feed, the PE accumulators reset on the second K-window, so frame 2 should
  // carry only the SECOND K rows, not the full 2K-sum.
  //
  // Contrast with Test 1 (same data, no reset) ⇒ proves the reset is the sole
  // cause of K-granularity, and removing it gives the ?×K behaviour.
  // ===========================================================================
  it should
    "fall back to two disjoint K-results when keep=false is injected at i_tick = K-1" in {
    simulate(new MMALU(new MMPE(8, 32), 4, 8, 32)) { dut =>
      val K = dut.n
      val M = 2 * K
      val rand = new Random(0xC0FFEEL)
      val matA = randMat(rand, M, K)
      val matB = randMat(rand, M, K)

      // Split into first/second halves for the K-only references.
      val matA1 = matA.slice(0,         K * K)
      val matB1 = matB.slice(0,         K * K)
      val matA2 = matA.slice(K * K, 2 * K * K)
      val matB2 = matB.slice(K * K, 2 * K * K)

      val expK1  = refSum(matA1, matB1, K, K)
      val expK2  = refSum(matA2, matB2, K, K)
      val exp2K  = refSum(matA,  matB,  K, 2 * K)

      val iterations = M + 2 * K
      val capture = runStream(dut, matA, matB, M, iterations,
                              injectReset = Some(K - 1))

      val msgs = new StringBuilder
      val f1 = checkFrame(capture, K, 1, expK1, "first-K", msgs)
      val f2 = checkFrame(capture, K, 2, expK2, "second-K-after-reset", msgs)

      if (f1 + f2 > 0) {
        println("===== capture (M=2K, keep=false at i_tick=K-1) =====")
        for (t <- 0 until iterations) {
          println(s"i_tick=$t out=" + (0 until K).map(capture(t)(_)).mkString(","))
        }
        fail(s"$f1 first-K and $f2 second-K mismatches:\n${msgs.toString}")
      }

      // Contrast assertion: frame 2 must NOT match the full 2K-sum, otherwise
      // the test would not distinguish the two protocols.
      val base = 3 * K - 2  // frame 2 first i_tick
      var matchesFull = true
      var lanesDiffer = 0
      for (step <- 0 until K; lane <- 0 until K) {
        if (capture(base + step)(lane) != exp2K(step * K + lane)) {
          matchesFull = false
          lanesDiffer += 1
        }
      }
      assert(!matchesFull,
        "Frame 2 unexpectedly equals the full 2K-sum — the injected reset had no effect, " +
        "which would invalidate the contrast against Test 1.")
      assert(lanesDiffer > 0,
        s"No lane in frame 2 differed from the full 2K reference — same problem.")
    }
  }
}
