# Engine MACs/cycle Benchmark (Phase 0–1) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Instrument `NpuProgramEngine` with per-session cycle/MAC counters and build the benchmark matrix that decomposes the MACs/cycle bottleneck at K=32/W=16.

**Architecture:** Add two counters (`cycleCnt`, `macCnt`) to the engine, exposed both as test-only debug ports and through the frontend's `ctrl_lite` register map. Add measurement helpers to the shared test harness, then add two specs — one driving the engine core directly (`StreamHarness`) and one through the full frontend (`FrontendSpecHarness`) — that run the A1/B1–B5/C1 cases and print a comparison table.

**Tech Stack:** Chisel 6.7.0, Scala 2.13.12, `chisel3.simulator.EphemeralSimulator`, ScalaTest. Docker toolchain (`make test`, `tool/test-specific-spec.sh`).

**Spec:** `docs/superpowers/specs/2026-10-15-engine-macs-per-cycle-design.md`

## Global Constraints

- Primary config: **K=32, W=16** (`new NpuProgramEngine(K, 8)` and `new NpuProgramEngineFrontend(K, 8)` use W default 16). The FPGA top (`K=16, W=4`) is unchanged by this plan.
- **No behavioral change** to instruction semantics: existing `make test` must stay green. Instrumentation only.
- ISA/encoding is fixed; use `isa.NpuAssembler` helpers (`vle8`, `vle32`, `vse32`, `mma`, `mmaLast`) to build words; poke as `(instr.toLong & 0xFFFFFFFFL).U`.
- Tests use `chisel3.simulator.EphemeralSimulator` (NOT chiseltest). Enum outputs cannot be `expect`ed — read via `.asInstanceOf[chisel3.UInt].peek()`.
- Run one spec with `tool/test-specific-spec.sh <fully.qualified.Spec>`; run everything with `make test`.
- Metric definitions (spec §2): `MACs = feeds × n²`, `MACs/cyc = MACs / cycles`, `%peak = feeds / cycles`.
- Do NOT commit changes to `target/`, `test_run_dir/`, or `top.sv`.

## Review Focus

These are the failure modes most likely to bite a user of the benchmark; each is pinned by a test in the owning task.

1. **Stale counters across back-to-back sessions** — `cycleCnt`/`macCnt` must reset exactly on `session_reset`; a second run must not accumulate the first run's counts. (Task 1.)
2. **Never-draining measurement hangs** — a wedged session must time out via a `maxCycles` guard rather than loop forever, and the helper must report the timeout. (Tasks 1, 3.)
3. **`dbg_mac` width** — `UInt(32.W)` at K=32 holds 4,194,303 feeds; the benchmark must not silently wrap (assert feeds are far below the limit). (Task 1.)
4. **Capture-queue drain invariant** — every non-last `mma` pushes a `capQ` entry and `io.drain` requires `capQ == 0`, so each `mma` needs a consuming `vse`; a benchmark that omits it never drains (the `runToDrain` timeout catches it). Also, `VR[i] ≡ VX[4i..4i+3]`, so an `mma`'s `vd` ring must not alias its `rs1`/`rs2` operand registers. (Task 3.)
5. **Core-vs-frontend mismatch** — the C1 frontend program must issue the identical instruction stream as its B counterpart, so the C1−B delta measures only fetch/decode overhead. (Task 6.)

---

### Task 1: Engine cycle/MAC counters + harness exposure

**Files:**
- Modify: `src/main/scala/engine/npuProgramEngine.scala`
- Modify: `src/test/scala/engine/StreamHarness.scala`
- Create: `src/test/scala/engine/EnginePerfCounterSpec.scala`

**Interfaces:**
- Produces (engine IO): `dbg_cycles: Output(UInt(32.W))`, `dbg_mac: Output(UInt(32.W))`.
- Produces (harness IO): `dbg_cycles`, `dbg_mac` mirrored on `StreamHarness.io`.
- Produces (`StreamSpecUtil`): `resetSession(dut)`, `runToDrain(dut, maxCycles)`, `perf(dut, K): Perf`, `Perf(cycles, feeds, macs)` with `macsPerCycle`/`pctPeak`, `printPerf(label, K, p)`.

- [ ] **Step 1: Write the failing test** in `src/test/scala/engine/EnginePerfCounterSpec.scala`

```scala
package engine

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec
import isa._
import isa.NpuAssembler._

class EnginePerfCounterSpec extends AnyFlatSpec {
  "Engine perf counters" should "count cycles and MACs for a resident long round" in {
    val K = 32
    simulate(new StreamHarness(K = K)) { dut =>
      StreamSpecUtil.resetSession(dut)
      val R = 8
      // One vse per mma drains capQ (else the session never drains); the
      // trailing mma.last is a ZERO feed, so it counts in dbg_mma_acc (R+1)
      // but NOT in dbg_mac (which counts only useful K² MACs).
      val prog = Seq(vle8(4, NpuSections.SECT_A, 0), vle8(8, NpuSections.SECT_B, 0)) ++
        (0 until R).flatMap(i => Seq(mma(0, 4, 8, 0), vse32(0, NpuSections.SECT_OUT, (i % 8) * 4 * K))) :+
        mmaLast(0, 0, 0, 0)
      prog.foreach(StreamSpecUtil.issue(dut, _))
      StreamSpecUtil.runToDrain(dut)
      val feeds = dut.io.dbg_mma_acc.peek().litValue
      val macs  = dut.io.dbg_mac.peek().litValue
      val cyc   = dut.io.dbg_cycles.peek().litValue
      assert(feeds == BigInt(R + 1), s"expected ${R + 1} feeds (incl. mma.last), got $feeds")
      assert(macs == BigInt(R * K * K), s"expected ${R * K * K} MACs, got $macs")
      assert(cyc > 0, "cycle counter must have advanced")
      println(s"[perf] R=$R feeds=$feeds macs=$macs cycles=$cyc macs/cyc=${macs.toDouble / cyc}")
    }
  }

  it should "reset counters at session_reset" in {
    simulate(new StreamHarness(K = 32)) { dut =>
      StreamSpecUtil.resetSession(dut)
      StreamSpecUtil.issue(dut, nop)
      StreamSpecUtil.runToDrain(dut)
      val c1 = dut.io.dbg_cycles.peek().litValue
      StreamSpecUtil.resetSession(dut)
      val c2 = dut.io.dbg_cycles.peek().litValue
      assert(c1 > 0 && c2 == 0, s"counters must reset on session_reset (c1=$c1 c2=$c2)")
    }
  }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `tool/test-specific-spec.sh engine.EnginePerfCounterSpec`
Expected: FAIL — compilation error, `dbg_mac`/`dbg_cycles`/`resetSession`/`runToDrain` not found.

- [ ] **Step 3: Add counters to `NpuProgramEngine`**

In `src/main/scala/engine/npuProgramEngine.scala`, add to the `io` bundle (near the other `dbg_*` outputs):

```scala
val dbg_cycles = Output(UInt(32.W))
val dbg_mac    = Output(UInt(32.W))
```

Add the registers (near the other debug counters at the end of the module):

```scala
val cycleCnt = RegInit(0.U(32.W))
val macCnt   = RegInit(0.U(32.W))
when (io.session_reset) {
  cycleCnt := 0.U
  macCnt   := 0.U
}.otherwise {
  cycleCnt := cycleCnt + 1.U
  // only USEFUL feeds: the mma.last feed injects zeros, so exclude it
  when (feedValid && !feedMmaLast) { macCnt := macCnt + (K * K).U }
}
io.dbg_cycles := cycleCnt
io.dbg_mac    := macCnt
```

- [ ] **Step 4: Expose on `StreamHarness`**

In `src/test/scala/engine/StreamHarness.scala`, add `val dbg_cycles = Output(UInt(32.W))` and `val dbg_mac = Output(UInt(32.W))` to `StreamHarness.io`, wire them from `eng.io`, and add the `StreamSpecUtil` helpers:

```scala
def resetSession(dut: StreamHarness): Unit = {
  dut.io.session_reset.poke(true.B); dut.clock.step()
  dut.io.session_reset.poke(false.B)
}
def runToDrain(dut: StreamHarness, maxCycles: Int = 2000000): Unit = {
  var n = 0
  while (!dut.io.dbg_drain.peek().litToBoolean && n < maxCycles) { dut.clock.step(); n += 1 }
  assert(dut.io.dbg_drain.peek().litToBoolean, s"session did not drain within $maxCycles cycles")
}
case class Perf(cycles: Long, feeds: Long, macs: Long) {
  def macsPerCycle: Double = if (cycles == 0) 0.0 else macs.toDouble / cycles
  def pctPeak(K: Int): Double = if (cycles == 0) 0.0 else 100.0 * feeds.toDouble / cycles
}
def perf(dut: StreamHarness, K: Int): Perf = {
  val macs  = dut.io.dbg_mac.peek().litValue.toLong
  val cyc   = dut.io.dbg_cycles.peek().litValue.toLong
  // dbg_mac counts only useful (non-mma.last) K² MACs; derive useful feeds.
  val feeds = macs / (K.toLong * K)
  assert(feeds * K.toLong * K == macs, s"mac/feed mismatch: macs=$macs K=$K")
  Perf(cyc, feeds, macs)
}
def printPerf(label: String, K: Int, p: Perf): Unit =
  println(f"[perf] $label%-28s cycles=${p.cycles}%8d feeds=${p.feeds}%7d " +
    f"macs=${p.macs}%12d macs/cyc=${p.macsPerCycle}%8.1f peak=${p.pctPeak(K)}%6.1f%%")
```

- [ ] **Step 5: Run to verify it passes**

Run: `tool/test-specific-spec.sh engine.EnginePerfCounterSpec`
Expected: PASS; the printed `macs/cyc` line is present.

- [ ] **Step 6: Confirm no regression**

Run: `tool/test-specific-spec.sh engine.StreamedNSessionSpec`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add src/main/scala/engine/npuProgramEngine.scala src/test/scala/engine/StreamHarness.scala src/test/scala/engine/EnginePerfCounterSpec.scala
git commit -m "feat(engine): per-session cycle/MAC counters + benchmark helpers"
```

---

### Task 2: Expose perf counters through `ctrl_lite`

**Files:**
- Modify: `src/main/scala/engine/npuFrontend.scala`
- Create: `src/test/scala/engine/FrontendPerfCounterSpec.scala`

**Interfaces:**
- Consumes: `core.io.dbg_cycles`, `core.io.dbg_mac` (Task 1).
- Produces: `ctrl_lite` words `0x40` = cycles, `0x44` = MACs.

- [ ] **Step 1: Write the failing test** in `src/test/scala/engine/FrontendPerfCounterSpec.scala`

```scala
package engine

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec
import isa._
import isa.NpuAssembler._
import testUtil._

class FrontendPerfCounterSpec extends AnyFlatSpec {
  "Frontend perf counters" should "be readable at ctrl 0x40/0x44" in {
    val K = 32
    simulate(new FrontendSpecHarness(K = K)) { dut =>
      // stage a resident 8-mma round (operands prefetched, then re-used)
      val a = Array.fill(K)(3); val b = Array.fill(K)(5)
      val preloadBytes = (base: Long, bytes: Seq[Int]) => {
        for ((bb, i) <- bytes.grouped(16).zipWithIndex) {
          var w = BigInt(0); for ((v, j) <- bb.zipWithIndex) w |= BigInt(v & 0xFF) << (8 * j)
          dut.io.pre_wr_addr.poke((((base + i * 16) >> 4) & 0xFFF).U)
          dut.io.pre_wr_data.poke(w.U); dut.io.pre_wr_en.poke(true.B)
          dut.clock.step(); dut.io.pre_wr_en.poke(false.B)
        }
      }
      preloadBytes(NpuSections.A, a)
      preloadBytes(NpuSections.B, b)
      val prog = Seq(vle8(4, NpuSections.SECT_A, 0), vle8(8, NpuSections.SECT_B, 0)) ++
        (0 until 8).flatMap(i => Seq(mma(0, 4, 8, 0), vse32(0, NpuSections.SECT_OUT, (i % 8) * 4 * K))) :+
        mmaLast(0, 0, 0, 0)
      val bytes = prog.flatMap(w => Seq(w & 0xFF, (w >> 8) & 0xFF, (w >> 16) & 0xFF, (w >> 24) & 0xFF))
      preloadBytes(NpuSections.CODE_BASE, bytes)
      dut.io.ctrl_addr.poke(0x14.U); dut.io.ctrl_we.poke(true.B)
      dut.io.ctrl_wdata.poke(prog.length.U); dut.clock.step(); dut.io.ctrl_we.poke(false.B)
      dut.io.ctrl_addr.poke(0x0.U); dut.io.ctrl_we.poke(true.B)
      dut.io.ctrl_wdata.poke(1.U); dut.clock.step()
      dut.io.ctrl_wdata.poke(0.U); dut.clock.step(); dut.io.ctrl_we.poke(false.B)
      var n = 0
      while (((dut.io.ctrl_rdata.peek().litValue >> 1) & 1) == 0 && n < 500000) {
        dut.io.ctrl_addr.poke(0x0.U); dut.clock.step(); n += 1
      }
      dut.io.ctrl_addr.poke(0x44.U); dut.clock.step(0)
      val macs = dut.io.ctrl_rdata.peek().litValue
      dut.io.ctrl_addr.poke(0x40.U); dut.clock.step(0)
      val cyc = dut.io.ctrl_rdata.peek().litValue
      assert(macs == BigInt(8 * K * K), s"0x44 should report 8*K^2 MACs, got $macs")
      assert(cyc > 0, "0x40 cycle count must be non-zero")
      println(s"[perf/fe] macs=$macs cycles=$cyc macs/cyc=${macs.toDouble / cyc}")
    }
  }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `tool/test-specific-spec.sh engine.FrontendPerfCounterSpec`
Expected: FAIL — `0x40`/`0x44` read 0 (the MuxLookup default), so `macs == 8*K^2` fails.

- [ ] **Step 3: Add the ctrl map entries**

In `src/main/scala/engine/npuFrontend.scala`, in the `io.ctrl_rdata := MuxLookup(...)` map, add:

```scala
0x40.U -> core.io.dbg_cycles,
0x44.U -> core.io.dbg_mac,
```

Update the ctrl_lite comment block (top of file) to list `0x40 CYCLES` and `0x44 MACS`.

- [ ] **Step 4: Run to verify it passes**

Run: `tool/test-specific-spec.sh engine.FrontendPerfCounterSpec`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/scala/engine/npuFrontend.scala src/test/scala/engine/FrontendPerfCounterSpec.scala
git commit -m "feat(engine): expose cycle/MAC counters via ctrl_lite 0x40/0x44"
```

---

### Task 3: `EngineThroughputSpec` — A1 ceiling, B1 window cap, B2 boundary

**Files:**
- Create: `src/test/scala/engine/EngineThroughputSpec.scala`

**Interfaces:**
- Consumes: `StreamSpecUtil.{resetSession, issue, runToDrain, perf, printPerf, Perf}` (Task 1); `NpuSections`.
- Produces: reusable `residentRound(R)` / `shortRounds(N)` program builders and a `report(label, K) { ... }` wrapper other tasks extend.

- [ ] **Step 1: Write A1 (MMALU direct ceiling) as a failing test**

```scala
package engine

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec
import alu.mma._
import alu.pe._
import isa._
import isa.NpuAssembler._

class EngineThroughputSpec extends AnyFlatSpec {
  val K = 32

  "Array ceiling (A1)" should "reach 1 feed/cycle with a continuous keep=1 feed" in {
    simulate(new MMALU(new MMPE(8, 32), K, 8, 32)) { dut =>
      val M = 2 * K
      var cycles = 0
      for (m <- 0 until M) {
        dut.io.ctrl.keep.poke(true.B); dut.io.ctrl.busy.poke(true.B)
        dut.io.in_a.poke(VecInit(Seq.fill(K)(1.S(8.W))))
        dut.io.in_b.poke(VecInit(Seq.fill(K)(1.S(8.W))))
        dut.io.in_accum.poke(VecInit(Seq.fill(K)(0.S(32.W))))
        dut.io.ctrl.use_accum.poke(false.B)
        dut.clock.step(); cycles += 1
      }
      dut.io.ctrl.busy.poke(false.B); dut.io.ctrl.keep.poke(false.B)
      for (_ <- 0 until 4 * K) { dut.clock.step(); cycles += 1 }
      // 2K feeds × K² MACs in `cycles`; the array itself is fed every cycle.
      val p = StreamSpecUtil.Perf(cycles.toLong, M.toLong, M.toLong * K * K)
      StreamSpecUtil.printPerf(s"A1 MMALU M=$M", K, p)
      assert(p.pctPeak(K) > 0.0)
    }
  }
}
```

- [ ] **Step 2: Add B1 (resident drain, vd-ring sweep) and B2 (short rounds) tests**

The `vd` ring holds the `mma` output registers; each is read by a chained
`vse` (the `capQ` consumer). The ring must avoid the operand registers
(`rs1=4`, `rs2=8`): `VR[1]=VX[4..7]` and `VR[2]=VX[8..11]` overlap, so the
allowed ring is `{0, 3, 4, 5}` (Vd=4) or `{0}` (Vd=1). Each `vse` writes a
distinct `OUT` column offset (`(i % 8) * 4 * K`) to stay inside the 4 KiB
`OUT` window.

```scala
  val VdRings: Seq[Seq[Int]] = Seq(Seq(0), Seq(0, 3, 4, 5))

  def residentDrain(R: Int, ring: Seq[Int]): Seq[Int] =
    Seq(vle8(4, NpuSections.SECT_A, 0), vle8(8, NpuSections.SECT_B, 0)) ++
      (0 until R).flatMap { i =>
        val v = ring(i % ring.length)
        Seq(mma(v, 4, 8, 0), vse32(v, NpuSections.SECT_OUT, (i % 8) * 4 * K))
      } :+ mmaLast(0, 0, 0, 0)

  def shortRounds(N: Int): Seq[Int] =
    Seq(vle8(4, NpuSections.SECT_A, 0), vle8(8, NpuSections.SECT_B, 0)) ++
      (0 until N).flatMap(_ => Seq(
        mma(0, 4, 8, 0), mmaLast(0, 0, 0, 0), vse32(0, NpuSections.SECT_OUT, 0)))

  "Window / vd-ring cap (B1)" should "report MACs/cycle for resident drained rounds" in {
    for (ring <- VdRings; R <- Seq(32, 128, 512)) {
      simulate(new StreamHarness(K = K)) { dut =>
        StreamSpecUtil.resetSession(dut)
        residentDrain(R, ring).foreach(StreamSpecUtil.issue(dut, _))
        StreamSpecUtil.runToDrain(dut)
        StreamSpecUtil.printPerf(s"B1 resident R=$R Vd=${ring.length}", K, StreamSpecUtil.perf(dut, K))
      }
    }
  }

  "Boundary overhead (B2)" should "report MACs/cycle for short rounds" in {
    for (N <- Seq(4, 16, 64)) {
      simulate(new StreamHarness(K = K)) { dut =>
        StreamSpecUtil.resetSession(dut)
        shortRounds(N).foreach(StreamSpecUtil.issue(dut, _))
        StreamSpecUtil.runToDrain(dut)
        StreamSpecUtil.printPerf(s"B2 short N=$N", K, StreamSpecUtil.perf(dut, K))
      }
    }
  }
```

- [ ] **Step 3: Run to verify it fails, then passes**

Run: `tool/test-specific-spec.sh engine.EngineThroughputSpec`
Expected: initially FAIL only if helpers are missing; after Task 1 is merged it should PASS. If it passes on the first run, re-run to confirm no flake.

- [ ] **Step 4: Commit**

```bash
git add src/test/scala/engine/EngineThroughputSpec.scala
git commit -m "test(engine): throughput matrix A1/B1/B2"
```

---

### Task 4: `EngineThroughputSpec` — B3 (DMA-bound) and B4 (RAW chain)

**Files:**
- Modify: `src/test/scala/engine/EngineThroughputSpec.scala`

**Interfaces:**
- Consumes: existing `residentRound`, `shortRounds`, `StreamSpecUtil`.
- Produces: `reloadedFeed(latency)` and `chainedReduction(n)` builders.

- [ ] **Step 1: Add B3 and B4 tests**

```scala
  "DMA-bound cadence (B3)" should "sweep DDR read latency for reloaded operands" in {
    for (lat <- Seq(1, 100, 200)) {
      simulate(new StreamHarness(K = K, readLatency = lat)) { dut =>
        StreamSpecUtil.resetSession(dut)
        val prog = Seq(vle8(4, NpuSections.SECT_A, 0), vle8(8, NpuSections.SECT_B, 0)) ++
          (0 until 16).flatMap(m => Seq(
            vle8(4, NpuSections.SECT_A, m * K), vle8(8, NpuSections.SECT_B, m * K),
            mma(0, 4, 8, 0), vse32(0, NpuSections.SECT_OUT, (m % 8) * 4 * K))) :+ mmaLast(0, 0, 0, 0)
        prog.foreach(StreamSpecUtil.issue(dut, _))
        StreamSpecUtil.runToDrain(dut)
        StreamSpecUtil.printPerf(s"B3 reload lat=$lat", K, StreamSpecUtil.perf(dut, K))
      }
    }
  }

  "RAW serialization (B4)" should "report MACs/cycle for a chained C reduction" in {
    for (n <- Seq(8, 32)) {
      simulate(new StreamHarness(K = K)) { dut =>
        StreamSpecUtil.resetSession(dut)
        // rd=3, vs3=3: VR[3]=VX[12..15] is disjoint from rs1=4/rs2=8; each mma
        // reads the previous mma's VR[3] via C -> RAW chain. One vse per mma
        // drains capQ (single vd, so the vse->mma WAR also serializes).
        val prog = Seq(vle8(4, NpuSections.SECT_A, 0), vle8(8, NpuSections.SECT_B, 0)) ++
          (0 until n).flatMap(i => Seq(
            mma(3, 4, 8, 3), vse32(3, NpuSections.SECT_OUT, (i % 8) * 4 * K))) :+ mmaLast(0, 0, 0, 0)
        prog.foreach(StreamSpecUtil.issue(dut, _))
        StreamSpecUtil.runToDrain(dut)
        StreamSpecUtil.printPerf(s"B4 chain n=$n", K, StreamSpecUtil.perf(dut, K))
      }
    }
  }
```

- [ ] **Step 2: Run**

Run: `tool/test-specific-spec.sh engine.EngineThroughputSpec`
Expected: PASS; four new `[perf]` rows printed.

- [ ] **Step 3: Commit**

```bash
git add src/test/scala/engine/EngineThroughputSpec.scala
git commit -m "test(engine): throughput matrix B3/B4"
```

---

### Task 5: `EngineThroughputSpec` — B5 realistic tile

**Files:**
- Modify: `src/test/scala/engine/EngineThroughputSpec.scala`

**Interfaces:**
- Consumes: `StreamSpecUtil`, `NpuSections`.

- [ ] **Step 1: Add the B5 test**

```scala
  def s8(v: Int): Int = { val b = v & 0xFF; if (b >= 128) b - 256 else b }

  "Realistic tile (B5)" should "report MACs/cycle for vle/mma/vse tiles" in {
    val T = 16
    val aVals = Array.tabulate(T, K)((m, i) => s8(m * 3 + i + 1))
    val bVals = Array.tabulate(K)(j => s8(50 + j))
    simulate(new StreamHarness(K = K, readLatency = 100)) { dut =>
      StreamSpecUtil.resetSession(dut)
      StreamSpecUtil.preloadI8(dut, NpuSections.A, aVals.flatten.map(_ & 0xFF))
      StreamSpecUtil.preloadI8(dut, NpuSections.B, bVals.map(_ & 0xFF))
      val prog = Seq(vle8(8, NpuSections.SECT_B, 0)) ++
        (0 until T).flatMap(m => Seq(
          vle8(4, NpuSections.SECT_A, m * K),
          mma(0, 4, 8, 0),
          vse32(0, NpuSections.SECT_OUT, m * 4 * K))) :+ mmaLast(0, 0, 0, 0)
      prog.foreach(StreamSpecUtil.issue(dut, _))
      StreamSpecUtil.runToDrain(dut)
      StreamSpecUtil.printPerf(s"B5 tile T=$T", K, StreamSpecUtil.perf(dut, K))
      // the LAST stored capture must be a column of the running sum Σ_{t≤T-1} a_t·b
      val out = StreamSpecUtil.readI32(dut, NpuSections.OUT, T * K)
      val g = out.slice((T - 1) * K, T * K)
      val pe = Array.tabulate(K, K)((i, j) => (0 until T).map(t => aVals(t)(i) * bVals(j)).sum)
      assert(StreamSpecUtil.columnsOf(g, pe).nonEmpty,
        s"B5 final capture is not a running-sum column: ${g.toList}")
    }
  }
```

- [ ] **Step 2: Run**

Run: `tool/test-specific-spec.sh engine.EngineThroughputSpec`
Expected: PASS; `B5 tile T=16` row printed.

- [ ] **Step 3: Commit**

```bash
git add src/test/scala/engine/EngineThroughputSpec.scala
git commit -m "test(engine): throughput matrix B5 realistic tile"
```

---

### Task 6: `FrontendThroughputSpec` — C1 fetch/decode overhead

**Files:**
- Create: `src/test/scala/engine/FrontendThroughputSpec.scala`

**Interfaces:**
- Consumes: `FrontendSpecHarness` (defined top-level in `NpuProgramEngineFrontendSpec.scala`), `NpuSections`, `ctrl_lite` perf words (Task 2).
- Produces: `runFrontend(prog): (macs, cycles)` helper.

- [ ] **Step 1: Write the C1 test**

```scala
package engine

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec
import isa._
import isa.NpuAssembler._

class FrontendThroughputSpec extends AnyFlatSpec {
  val K = 32

  def bytes(words: Seq[Int]): Seq[Int] =
    words.flatMap(w => Seq(w & 0xFF, (w >> 8) & 0xFF, (w >> 16) & 0xFF, (w >> 24) & 0xFF))

  def preload(dut: FrontendSpecHarness, base: Long, bs: Seq[Int]): Unit =
    for ((bb, i) <- bs.grouped(16).zipWithIndex) {
      var w = BigInt(0); for ((v, j) <- bb.zipWithIndex) w |= BigInt(v & 0xFF) << (8 * j)
      dut.io.pre_wr_addr.poke((((base + i * 16) >> 4) & 0xFFF).U)
      dut.io.pre_wr_data.poke(w.U); dut.io.pre_wr_en.poke(true.B)
      dut.clock.step(); dut.io.pre_wr_en.poke(false.B)
    }

  def runFrontend(dut: FrontendSpecHarness, prog: Seq[Int]): (Long, Long) = {
    preload(dut, NpuSections.CODE_BASE, bytes(prog))
    def w(addr: Int, d: Long): Unit = {
      dut.io.ctrl_addr.poke(addr.U); dut.io.ctrl_we.poke(true.B)
      dut.io.ctrl_wdata.poke(d.U); dut.clock.step(); dut.io.ctrl_we.poke(false.B)
    }
    w(0x14, prog.length); w(0x0, 1); w(0x0, 0)
    var n = 0
    while (((dut.io.ctrl_rdata.peek().litValue >> 1) & 1) == 0 && n < 2000000) {
      dut.io.ctrl_addr.poke(0x0.U); dut.clock.step(); n += 1
    }
    dut.io.ctrl_addr.poke(0x44.U); dut.clock.step(0)
    val macs = dut.io.ctrl_rdata.peek().litValue.toLong
    dut.io.ctrl_addr.poke(0x40.U); dut.clock.step(0)
    val cyc = dut.io.ctrl_rdata.peek().litValue.toLong
    (macs, cyc)
  }

  "Frontend throughput (C1)" should "match B1's resident long round" in {
    val R = 64
    val prog = Seq(vle8(4, NpuSections.SECT_A, 0), vle8(8, NpuSections.SECT_B, 0)) ++
      (0 until R).flatMap(i => Seq(mma(0, 4, 8, 0), vse32(0, NpuSections.SECT_OUT, (i % 8) * 4 * K))) :+
      mmaLast(0, 0, 0, 0)
    simulate(new FrontendSpecHarness(K = K)) { dut =>
      val a = Array.fill(K)(3); val b = Array.fill(K)(5)
      preload(dut, NpuSections.A, a); preload(dut, NpuSections.B, b)
      val (macs, cyc) = runFrontend(dut, prog)
      assert(macs == BigInt(R * K * K), s"frontend feeds should equal R*K^2, got $macs")
      println(f"[perf/fe] C1 R=$R cycles=$cyc macs=$macs macs/cyc=${macs.toDouble / cyc}%8.1f " +
        f"peak=${100.0 * (macs / (K.toLong * K)).toDouble / cyc}%6.1f%%")
    }
  }
}
```

- [ ] **Step 2: Run**

Run: `tool/test-specific-spec.sh engine.FrontendThroughputSpec`
Expected: PASS; `[perf/fe] C1 R=64` row printed.

- [ ] **Step 3: Commit**

```bash
git add src/test/scala/engine/FrontendThroughputSpec.scala
git commit -m "test(engine): C1 frontend throughput (fetch/decode overhead)"
```

---

### Task 7: Full-suite confirmation

**Files:** none (verification only).

- [ ] **Step 1: Run the full suite**

Run: `make test`
Expected: all specs PASS, including the four new ones.

- [ ] **Step 2: Capture the benchmark table**

Run: `tool/test-specific-spec.sh engine.EngineThroughputSpec` and
`tool/test-specific-spec.sh engine.FrontendThroughputSpec`
Expected: the printed `[perf]` rows form the A1/B1–B5/C1 table → hand to the
Phase-2 design discussion.

- [ ] **Step 3: Record the results** in
`docs/superpowers/plans/2026-10-15-engine-macs-per-cycle-benchmark.md`
below this task (append a "## Measured results (Phase 1)" section with the
captured rows).

- [ ] **Step 4: Commit**

```bash
git add docs/superpowers/plans/2026-10-15-engine-macs-per-cycle-benchmark.md
git commit -m "docs: Phase 1 benchmark results"
```

---

## Follow-on (not in this plan)

- **Phase 2** — dispatch/pipeline optimization selected from the Phase-1 table
  (spec §6: L1 completion decoupling, L2 scoreboard cone, L3 latency, L4
  boundary overlap, L5 DMA hiding). Each requires its own spec+plan.
- **Phase 3** — K=32/W=16 vu9p bitstream bring-up (spec §7), gated on W=16
  closing at 200 MHz.
