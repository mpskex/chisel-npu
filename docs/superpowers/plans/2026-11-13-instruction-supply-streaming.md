# Instruction-Supply Streaming Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the NPU front end supply instructions at ~1 cycle/instruction for every program up to 65,535 words, with a flat size-independent slope and a pre-warmed first burst.

**Architecture:** Phase A widens the I-cache line to the full 64-byte DMA beat (16 words). Phase B turns a fill into a 4-beat burst and keeps the fill head bounded relative to the PC (sliding window). Phase P pre-warms the first burst on the `PROG_LEN` write. The DMA `dir=3` path returns/streams 512-bit beats; the frontend commits them; the engine forwards beats and aborts in-flight fills on session reset/illegal halt.

**Tech Stack:** Chisel 6.7.0 / Scala 2.13.12 / sbt 1.9.7 in the `fangruil/chisel-dev:amd64` Docker image; `EphemeralSimulator`; Vivado 2025.2 in the Ubuntu 22.04 runtime container; the `chisel_npu_py` driver on the Alivu9p host (`10.16.0.35`).

**Spec:** `docs/superpowers/specs/2026-11-13-instruction-supply-streaming-design.md`

## Global Constraints

- Branch: `fangrui/refine-dispatch-frontend`. Do not create a new branch.
- Top: `NpuProgramEngineFrontend(K=32, N=8, W=8)`, fabric **100 MHz**.
- 100 MHz timing gate: post-route **WNS ≥ −1.0 ns** (last build +0.003 ns). Re-check after every RTL change.
- Program range: **1…65,535 words**; `PROG_LEN` is 16-bit; CODE is 256 KiB / 4,096 lines; `lastLine` is 13 bits.
- No ISA, ctrl-register-map, driver-API, or external AXI changes.
- Cache invalidation moves to `PROG_LEN` (0x14) and `FRAMES` (0x4); **`CTRL` (0x0) must not invalidate**.
- Frontend unit tests may use `K=16`; the hardware tests use `K=32`.
- Run specs with `./tool/test-specific-spec.sh <fully.qualified.Spec>`; full suite with `make test`.
- Vivado 2025.2 randomly segfaults in `LgbmTree::~LgbmTree`; always use the retry loop. ML estimators stay off (`place.3d.congestionML 0`, `place.disableMlde 1`) and placement is single-threaded.
- Build flow (scripts are local, `build/` is git-ignored):
  - `make build` → `top.sv`
  - `build/latest_synth_opt.tcl` → `hw_platform_opt.dcp`
  - `build/impl_100mhz_nomlcong.tcl` → `ip/vivado/xcvu9p/top_npu_vu9p.bit`
- Hardware flow: `make bringup-fpga BOARD=Alivu9p`, `make py-deploy BOARD=Alivu9p`, `make py-test-hw BOARD=Alivu9p`.
- Commit after every task; keep the design doc and this plan in the repo.

## Review Focus

1. **Long-program size cliff** — a 60 k-instruction straight-line program must run at the same cycles/instruction as a 1 k one; the fill head must never evict an unread line.
2. **Abort/restart with an in-flight fill** — an illegal halt or a new `start` while a fill is queued/in `READ` must not wedge the next session.
3. **Pre-warm races** — `start` during the warm, a new program staged after a run, and back-to-back runs must all fetch the correct CODE.
4. **Tail/partial lines** — program lengths 1, 15, 16, 17, 63, 64, 65, 255, 256, 257 must fetch the right words and never read past `lastLine`.
5. **Shared DMA contention** — 4-beat fetch bursts must not break `vle`/`vse` traffic or the 8 hardware tests.

---

## File Structure

- `src/main/scala/engine/npuFrontend.scala` — cache geometry, fill FSM, pre-warm, IFQ.
- `src/main/scala/dma/npuDmaEngine.scala` — instruction-beat return/burst (`dir=3`).
- `src/main/scala/engine/npuProgramEngine.scala` — `fetch_fill_*` forwarding, `DmaEntry.beats`, `fillPending`, abort/epoch.
- `src/test/scala/engine/NpuProgramEngineFrontendSpec.scala` — frontend fetch tests (reuse `FrontendSpecHarness`; add helpers).
- `src/test/scala/dma/NpuDmaEngineSpec.scala` — DMA instruction-beat/burst tests (reuse `DmaSpecHarness`).
- `drivers/chisel_npu_py/tool/fetch_sweep.py` — repeatable on-board NOP-size / round sweep.
- `docs/implementations/FPGA_VU9P.md` — measured before/after table.

---

### Task 1: Abort/epoch for in-flight fills

**Files:**
- Modify: `src/main/scala/engine/npuProgramEngine.scala` (`fillPending`, `fillPush`, reset/illegal blocks)
- Modify: `src/main/scala/engine/npuFrontend.scala` (`startEdge` block, `commitFill`)
- Test: `src/test/scala/engine/NpuProgramEngineFrontendSpec.scala`

**Interfaces:**
- Consumes: `io.session_reset` (engine), `illegalAny` (engine), `startEdge` (frontend), `fillState` (frontend).
- Produces: no new ports. `fillPending` is cleared on `session_reset`/`illegalAny`; `fillPush` is gated by `!session_reset && !illegalAny`; `fillState` resets to `IDLE` at `startEdge`.

- [ ] **Step 1: Write the failing test**

Add to `NpuProgramEngineFrontendSpec.scala`:

```scala
"NpuProgramEngineFrontend" should "abort an in-flight fill and restart cleanly" in {
  simulate(new FrontendSpecHarness(K = 16, readLatency = 40)) { dut =>
    // Long program: the illegal word is far enough in that a prefetch is in
    // flight when the halt is decoded.
    val bad = 0x03 | (0 << 7) | (2 << 12) | (1 << 15) | (2 << 20)
    val long = Seq.fill(200)(nop) :+ bad :+ nop
    preloadInstrs(dut, long)
    ctrlWrite(dut, 0x14, long.length)
    start(dut)
    waitDone(dut, maxCycles = 20000)
    val st = ctrlRead(dut, 0x8)
    assert(((st >> 31) & 1) == 1 && (st & 0xFFFF) == 200, s"illegal at pc 200, got $st")

    // Second session on the same simulator/DUT must fetch and complete.
    val ok = Seq(nop, nop, nop, nop)
    preloadInstrs(dut, ok)
    ctrlWrite(dut, 0x14, ok.length)
    start(dut)
    waitDone(dut, maxCycles = 5000)
    val st2 = ctrlRead(dut, 0x8)
    assert(((st2 >> 31) & 1) == 0, "second session must not inherit the illegal flag")
    assert((st2 & 0xFFFF) == ok.length - 1, s"second session pc, got $st2")
  }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./tool/test-specific-spec.sh engine.NpuProgramEngineFrontendSpec`
Expected: the new test FAILS at `waitDone(... 5000)` ("program did not finish") because `fillPending` is stuck.

- [ ] **Step 3: Implement the abort/epoch fix**

- Engine: in both the `when (io.session_reset)` block and the `when (illegalAny)` block, add `fillPending := false.B`.
- Engine: `val fillPush = io.fetch_fill_req && !fillPending && !dmaQFull && !dmaIssueAny && !vrPendingValid && !io.session_reset && !illegalAny`.
- Frontend: in `RunState.RUN_IDLE`'s `when (startEdge)`, add `fillState := FillState.IDLE` (and later `fillBeat := 0.U`, `fillLine := 0.U`).
- Keep `commitFill = fillState === FillState.REQ && core.io.fetch_fill_done`; the `REQ` guard already rejects a stale beat when `fillState` is `IDLE`.

- [ ] **Step 4: Run the test to verify it passes**

Run: `./tool/test-specific-spec.sh engine.NpuProgramEngineFrontendSpec`
Expected: all tests PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/scala/engine/npuProgramEngine.scala src/main/scala/engine/npuFrontend.scala src/test/scala/engine/NpuProgramEngineFrontendSpec.scala
git commit -m "fix(engine): abort in-flight fetch fills on session reset or illegal halt"
```

---

### Task 2: Phase A — 16-word line + 512-bit DMA return

**Files:**
- Modify: `src/main/scala/dma/npuDmaEngine.scala` (`instr_out` 512, `INSTRW`)
- Modify: `src/main/scala/engine/npuProgramEngine.scala` (`fetch_fill_data` 512)
- Modify: `src/main/scala/engine/npuFrontend.scala` (cache geometry)
- Test: `src/test/scala/dma/NpuDmaEngineSpec.scala`, `src/test/scala/engine/NpuProgramEngineFrontendSpec.scala`

**Interfaces:**
- Consumes: `NpuDmaEngine.io.instr_valid`/`instr_out`; `NpuProgramEngine.io.fetch_fill_data`.
- Produces: `instr_out: UInt(512.W)`; `fetch_fill_data: UInt(512.W)`; frontend line constants `LINE_WORDS = 16`, `lineData: Vec(16, UInt(512.W))`, `lineTag: Vec(16, UInt(9.W))`, `fillLine: UInt(12.W)`, `lastLine: UInt(13.W)`.

- [ ] **Step 1: Write the failing DMA test**

Replace the existing `"fetch a 16 B instruction line (dir=3)"` test with:

```scala
"NpuDmaEngine" should "fetch a full 64 B instruction beat (dir=3)" in {
  simulate(new DmaSpecHarness(readTimeout = 8)) { dut =>
    val pat = pattern(0x600, 64)
    preload(dut, RAM_ADDR, pat)          // RAM_ADDR is 64 B aligned
    issue(dut, dir = 3, width = 0, rfAddr = 0, l3Addr = RAM_ADDR)
    var captured = false
    var n = 0
    while (!dut.io.done.peek().litToBoolean && n < 2000) {
      if (!captured && dut.io.instr_valid.peek().litToBoolean) {
        val got = dut.io.instr_out.peek().litValue
        for (b <- 0 until 64) {
          val exp = BigInt(pat(b) & 0xFF)
          val act = (got >> (8 * b)) & 0xFF
          assert(act == exp, s"fetch byte $b: got $act want $exp")
        }
        captured = true
      }
      dut.clock.step(); n += 1
    }
    assert(dut.io.done.peek().litToBoolean && captured, "fetch did not finish")
  }
}
```

- [ ] **Step 2: Run the DMA test to verify it fails**

Run: `./tool/test-specific-spec.sh dma.NpuDmaEngineSpec`
Expected: compile/elaboration error or byte assertions fail above byte 15 (the current `instr_out` is 128 bits and selects one quarter).

- [ ] **Step 3: Implement the 512-bit instruction return**

- `NpuDmaEngine`: `val instr_out = Output(UInt(DW.W))` (512); in `INSTRW`, `io.instr_out := buf(0)` (no `rdOff` quarter select).
- `NpuProgramEngine`: `val fetch_fill_data = Output(UInt(512.W))`; the DMA connection is unchanged.
- No change to `beatsOf` (`dir=3` is still 1 beat).

- [ ] **Step 4: Run the DMA test to verify it passes**

Run: `./tool/test-specific-spec.sh dma.NpuDmaEngineSpec`
Expected: all DMA tests PASS.

- [ ] **Step 5: Write the failing frontend line test**

Add to `NpuProgramEngineFrontendSpec.scala` (make `waitDone` return the step count so the test can assert cycles):

```scala
"NpuProgramEngineFrontend" should "fetch 1024 NOPs in 64 line fills" in {
  simulate(new FrontendSpecHarness(K = 16, readLatency = 40)) { dut =>
    val prog = Seq.fill(1024)(nop)
    preloadInstrs(dut, prog)
    ctrlWrite(dut, 0x14, prog.length)
    val s0 = ctrlRead(dut, 0x10)
    start(dut)
    val cycles = waitDone(dut, maxCycles = 20000)
    val s1 = ctrlRead(dut, 0x10)
    val fills = (((s1 >> 16) & 0xFFFF) - ((s0 >> 16) & 0xFFFF) & 0xFFFF) +
                ((s1 & 0xFFFF) - (s0 & 0xFFFF) & 0xFFFF)
    assert(fills == 64, s"expected 64 line fills, got $fills")
    assert(cycles < 3500, s"1024 NOPs took $cycles cycles")
  }
}
```

Also add a line-boundary test:

```scala
"NpuProgramEngineFrontend" should "fetch across a 16-word line boundary" in {
  simulate(new FrontendSpecHarness(K = 16, readLatency = 40)) { dut =>
    val bad = 0x03 | (0 << 7) | (2 << 12) | (1 << 15) | (2 << 20)
    val prog = Seq.fill(64)(nop) :+ bad
    preloadInstrs(dut, prog)
    ctrlWrite(dut, 0x14, prog.length)
    start(dut)
    waitDone(dut, maxCycles = 20000)
    val st = ctrlRead(dut, 0x8)
    assert(((st >> 31) & 1) == 1 && (st & 0xFFFF) == 64, s"illegal at pc 64, got $st")
  }
}
```

- [ ] **Step 6: Run the frontend tests to verify they fail**

Run: `./tool/test-specific-spec.sh engine.NpuProgramEngineFrontendSpec`
Expected: the two new tests FAIL (fills = 256, or cycles > 3500).

- [ ] **Step 7: Implement the 16-word line**

In `npuFrontend.scala`, apply the §7.1 geometry:

- `fL = fpc >> 4`; `fSet = fL(2,0)`; `fTag = fL(11,3)`; `fWord = (lineData(way) >> (fpc(3,0) << 5))(31,0)`.
- `lineData: Vec(SETS*WAYS, UInt(512.W))`; `lineTag: Vec(SETS*WAYS, UInt(9.W))`.
- `fillLine: UInt(12.W)`; `fetch_fill_addr := CODE_BASE + (fillLine << 6)`.
- `lastLine = (progLenReg +& 15) >> 4` (13 bits); `prefetchValid` uses `nextFL < lastLine`.
- Update the cache-commit data to the 512-bit `core.io.fetch_fill_data`.
- Update the frontend harness only if its `dbg_*` wiring needs the new widths (no new ports).

- [ ] **Step 8: Run the frontend tests to verify they pass**

Run: `./tool/test-specific-spec.sh engine.NpuProgramEngineFrontendSpec`
Expected: all tests PASS; 1024 NOPs = 64 fills, cycles < 3500.

- [ ] **Step 9: Commit**

```bash
git add src/main/scala/dma/npuDmaEngine.scala src/main/scala/engine/npuProgramEngine.scala src/main/scala/engine/npuFrontend.scala src/test/scala/dma/NpuDmaEngineSpec.scala src/test/scala/engine/NpuProgramEngineFrontendSpec.scala
git commit -m "feat(engine): 16-word instruction lines from the full 64-byte fetch beat"
```

---

### Task 3: Phase B — 4-beat burst fill with bounded lead

**Files:**
- Modify: `src/main/scala/dma/npuDmaEngine.scala`
- Modify: `src/main/scala/engine/npuProgramEngine.scala`
- Modify: `src/main/scala/engine/npuFrontend.scala`
- Test: `src/test/scala/dma/NpuDmaEngineSpec.scala`, `src/test/scala/engine/NpuProgramEngineFrontendSpec.scala`
- Test harness: `src/test/scala/dma/NpuDmaEngineSpec.scala` (`DmaSpecHarness` add `req_beats`, `instr_last`)

**Interfaces:**
- `NpuDmaEngine`: `io.req_beats: Input(UInt(3.W))` (1…4); `io.instr_last: Output(Bool())`; `instr_out` stays 512.
- `NpuProgramEngine`: `io.fetch_fill_beats: Input(UInt(3.W))`; `io.fetch_fill_valid: Output(Bool())`; `io.fetch_fill_last: Output(Bool())`; `fetch_fill_data` stays 512. `DmaEntry.beats: UInt(3.W)`; `dma.io.req_beats := dmaHead.beats`.
- `NpuProgramEngineFrontend`: internal `fillBeat: UInt(3.W)`, `fillNext: UInt(13.W)`; line commits use `fillLine + fillBeat`; the request uses `fillBeats` = 4 when `fillLine + 4 <= lastLine`, else 1.

- [ ] **Step 1: Write the failing DMA burst test**

Add to `NpuDmaEngineSpec.scala`:

```scala
"NpuDmaEngine" should "stream four instruction beats per request (dir=3)" in {
  simulate(new DmaSpecHarness(readTimeout = 8)) { dut =>
    val pat = pattern(0x700, 256)
    preload(dut, RAM_ADDR, pat)          // 64 B aligned; 4 beats
    issue(dut, dir = 3, width = 0, rfAddr = 0, l3Addr = RAM_ADDR, beats = 4)
    var beats = 0
    var n = 0
    while (!dut.io.done.peek().litToBoolean && n < 2000) {
      if (dut.io.instr_valid.peek().litToBoolean) {
        val got = dut.io.instr_out.peek().litValue
        for (b <- 0 until 64) {
          val exp = BigInt(pat(beats * 64 + b) & 0xFF)
          val act = (got >> (8 * b)) & 0xFF
          assert(act == exp, s"beat $beats byte $b")
        }
        assert(dut.io.instr_last.peek().litToBoolean == (beats == 3), s"instr_last at beat $beats")
        beats += 1
      }
      dut.clock.step(); n += 1
    }
    assert(dut.io.done.peek().litToBoolean, "burst fetch did not finish")
    assert(beats == 4, s"expected 4 beats, got $beats")
  }
}
```

Extend `DmaSpecHarness.issue(...)` with a `beats: Int = 1` parameter that pokes `req_beats`.

- [ ] **Step 2: Run the DMA test to verify it fails**

Run: `./tool/test-specific-spec.sh dma.NpuDmaEngineSpec`
Expected: compile/elaboration error (`req_beats`/`instr_last` do not exist).

- [ ] **Step 3: Implement the burst protocol in the DMA**

- `beatsOf(w)`: `Mux(reqDir === 3.U, reqBeats, ...)`; `MAX_BEATS = 4` (or `max(4, ...)`).
- `buf` is `Vec(MAX_BEATS, UInt(DW.W))`; `beatCnt` already writes `buf(beatCnt)`.
- `INSTRW`: `instrBeat` counter 0…N−1; `instr_valid := true.B`; `instr_out := buf(instrBeat)`; `instr_last := instrBeat === (reqBeats - 1).U`; increment; go `DONE` after the last beat.
- Keep `done` as the one-cycle completion pulse after the last beat.

- [ ] **Step 4: Run the DMA test to verify it passes**

Run: `./tool/test-specific-spec.sh dma.NpuDmaEngineSpec`
Expected: all DMA tests PASS (including the 1-beat `dir=3` test with `beats = 1`).

- [ ] **Step 5: Write the failing frontend burst tests**

Add to `NpuProgramEngineFrontendSpec.scala`:

```scala
"NpuProgramEngineFrontend" should "fetch 1024 NOPs in 16 four-beat requests" in {
  simulate(new FrontendSpecHarness(K = 16, readLatency = 40)) { dut =>
    val prog = Seq.fill(1024)(nop)
    preloadInstrs(dut, prog)
    ctrlWrite(dut, 0x14, prog.length)
    val s0 = ctrlRead(dut, 0x10)
    start(dut)
    val cycles = waitDone(dut, maxCycles = 20000)
    val s1 = ctrlRead(dut, 0x10)
    val fills = (((s1 >> 16) & 0xFFFF) - ((s0 >> 16) & 0xFFFF) & 0xFFFF) +
                ((s1 & 0xFFFF) - (s0 & 0xFFFF) & 0xFFFF)
    assert(fills == 16, s"expected 16 requests, got $fills")
    assert(cycles < 1500, s"1024 NOPs took $cycles cycles")
  }
}

"NpuProgramEngineFrontend" should "keep a flat slope on a 4096-NOP program" in {
  simulate(new FrontendSpecHarness(K = 16, readLatency = 40)) { dut =>
    val prog = Seq.fill(4096)(nop)
    preloadInstrs(dut, prog)
    ctrlWrite(dut, 0x14, prog.length)
    start(dut)
    val cycles = waitDone(dut, maxCycles = 40000)
    assert(cycles < 1.5 * 4096, s"4096 NOPs took $cycles cycles (slope > 1.5)")
  }
}
```

Also add the tail test:

```scala
"NpuProgramEngineFrontend" should "use a 1-beat tail for a 70-word program" in {
  simulate(new FrontendSpecHarness(K = 16, readLatency = 40)) { dut =>
    val bad = 0x03 | (0 << 7) | (2 << 12) | (1 << 15) | (2 << 20)
    val prog = Seq.fill(69)(nop) :+ bad   // 70 words: 4-line burst + 1-line tail + 6 words
    preloadInstrs(dut, prog)
    ctrlWrite(dut, 0x14, prog.length)
    start(dut)
    waitDone(dut, maxCycles = 20000)
    val st = ctrlRead(dut, 0x8)
    assert(((st >> 31) & 1) == 1 && (st & 0xFFFF) == 69, s"illegal at pc 69, got $st")
  }
}
```

- [ ] **Step 6: Run the frontend tests to verify they fail**

Run: `./tool/test-specific-spec.sh engine.NpuProgramEngineFrontendSpec`
Expected: the 16-request/flat-slope/tail tests FAIL (still 64 requests; the tail path does not exist).

- [ ] **Step 7: Implement the engine/forwarding changes**

- `DmaEntry.beats: UInt(3.W)`; `fillPush` sets `beats := io.fetch_fill_beats`; the DMA request port gets `dmaHead.beats`.
- `io.fetch_fill_beats = Input(UInt(3.W))`, `io.fetch_fill_valid = Output(Bool())`, `io.fetch_fill_last = Output(Bool())`.
- `fetch_fill_valid := dma.io.instr_valid && dmaHead.valid && dmaHead.inFlight && dmaHead.dir === 3.U`; `fetch_fill_last := fetch_fill_valid && dma.io.instr_last`; `fetch_fill_data := dma.io.instr_out`.
- `fillPending` clears on `fetch_fill_last` (and the Task-1 abort conditions); `fillPush` is otherwise unchanged.
- Remove/retire the old `fetch_fill_done` (the frontend is the only consumer).

- [ ] **Step 8: Implement the frontend burst/commit/lead**

- `fillBeat` 0…beats−1; **at `startFill`**, latch `fillLine := targetLine` and clear `fillBeat := 0`; the commit line is `fillLine + fillBeat`.
- On `startEdge`, reset `fillBeat := 0` and `fillNext := 0` (Task 1 already resets `fillState`); the first running cycle demand-fills line 0.
- On `fetch_fill_valid`, commit `core.io.fetch_fill_data` into line `fillLine + fillBeat`, set/update tag, `lineValid`, `mru`; increment `fillBeat`; on `fetch_fill_last`, `fillState := DRAIN`, `fillNext := fillLine +& fillBeats`.
- Request beats: `fillBeats = Mux(fillLine + 4.U <= lastLine, 4.U, 1.U)`; clamp to `lastLine` for the tail.
- Lead gate: `doPrefetch` only when `fillNext + fillBeats <= fpcLine + 8.U`; `fpcLine = fpc >> 4`. Demand fills start at `fL` and bypass the lead gate.
- `fetch_fill_beats := fillBeats` (for both demand and prefetch).

- [ ] **Step 9: Run the frontend tests to verify they pass**

Run: `./tool/test-specific-spec.sh engine.NpuProgramEngineFrontendSpec`
Expected: all tests PASS (16 requests, <1500 cycles at 1024; <1.5 cyc/instr at 4096; tail correct).

- [ ] **Step 10: Commit**

```bash
git add src/main/scala/dma/npuDmaEngine.scala src/main/scala/engine/npuProgramEngine.scala src/main/scala/engine/npuFrontend.scala src/test/scala/dma/NpuDmaEngineSpec.scala src/test/scala/engine/NpuProgramEngineFrontendSpec.scala
git commit -m "feat(engine): 4-beat burst instruction fetch with a bounded sliding window"
```

---

### Task 4: Phase P — first-burst pre-warm on PROG_LEN

**Files:**
- Modify: `src/main/scala/engine/npuFrontend.scala` (`cacheInvalidate`, warm FSM)
- Modify: `src/main/scala/engine/npuProgramEngine.scala` (only if the warm request needs a `fillPush` qualifier; expected: no change beyond Task 1)
- Test: `src/test/scala/engine/NpuProgramEngineFrontendSpec.scala`

**Interfaces:**
- Consumes: `ctrl_we`/`ctrl_addr` 0x14, `progLenReg`, `lastLine`, `fillBeats`.
- Produces: `warmValid: Bool`, `warmLine: UInt(12.W)`; `cacheInvalidate` no longer includes 0x0; `commitFill` may commit while `warmValid || running`.

- [ ] **Step 1: Write the failing pre-warm tests**

Add to `NpuProgramEngineFrontendSpec.scala`:

```scala
"NpuProgramEngineFrontend" should "pre-warm the first burst on PROG_LEN" in {
  simulate(new FrontendSpecHarness(K = 16, readLatency = 40)) { dut =>
    val prog = Seq.fill(256)(nop)
    preloadInstrs(dut, prog)
    val s0 = ctrlRead(dut, 0x10)
    ctrlWrite(dut, 0x14, prog.length)
    dut.clock.step(200)                    // host gap before START
    val s1 = ctrlRead(dut, 0x10)
    val fills = (((s1 >> 16) & 0xFFFF) - ((s0 >> 16) & 0xFFFF) & 0xFFFF) +
                ((s1 & 0xFFFF) - (s0 & 0xFFFF) & 0xFFFF)
    assert(fills == 1, s"PROG_LEN should issue one warm burst, got $fills")
    assert(dut.io.dbg_run.peek().litValue == 0, "warm must not start the session")
    assert(dut.io.dbg_pc.peek().litValue == 0, "warm must not issue instructions")

    val s2 = ctrlRead(dut, 0x10)
    start(dut)
    val cycles = waitDone(dut, maxCycles = 20000)
    val s3 = ctrlRead(dut, 0x10)
    val missDelta = ((s3 & 0xFFFF) - (s2 & 0xFFFF)) & 0xFFFF
    assert(missDelta == 0, s"line 0 should hit the warm cache, got $missDelta misses")
    assert(cycles < 20000, "session did not complete")
  }
}

"NpuProgramEngineFrontend" should "warm the new CODE after a program change" in {
  simulate(new FrontendSpecHarness(K = 16, readLatency = 40)) { dut =>
    val badA = 0x03 | (0 << 7) | (2 << 12) | (1 << 15) | (2 << 20)
    val progA = Seq.fill(32)(nop) :+ badA
    preloadInstrs(dut, progA)
    ctrlWrite(dut, 0x14, progA.length)
    start(dut)
    waitDone(dut, maxCycles = 20000)

    val badB = 0x03 | (0 << 7) | (1 << 12) | (1 << 15) | (2 << 20)
    val progB = Seq.fill(8)(nop) :+ badB   // line 0 of B differs
    preloadInstrs(dut, progB)
    ctrlWrite(dut, 0x14, progB.length)
    start(dut)
    waitDone(dut, maxCycles = 20000)
    val err = ctrlRead(dut, 0xC)
    assert(err == (badB.toLong & 0xFFFFFFFFL), s"warm read stale CODE: $err")
  }
}
```

Update the existing `"re-fill after start invalidation with stable misses"` test: `start` no longer invalidates. Rename it to `"PROG_LEN invalidates and re-warms; a second start reuses the cache"` and assert that after re-writing `PROG_LEN` the second run's demand misses are 0 (warm hit), not increasing.

Also add the immediate-start race test:

```scala
"NpuProgramEngineFrontend" should "start immediately after PROG_LEN without a warm" in {
  simulate(new FrontendSpecHarness(K = 16, readLatency = 40)) { dut =>
    val prog = Seq.fill(64)(nop)
    preloadInstrs(dut, prog)
    ctrlWrite(dut, 0x14, prog.length)   // no extra gap
    start(dut)
    waitDone(dut, maxCycles = 20000)
    val st = ctrlRead(dut, 0x8)
    assert(((st >> 31) & 1) == 0 && (st & 0xFFFF) == prog.length - 1, s"race start, got $st")
  }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./tool/test-specific-spec.sh engine.NpuProgramEngineFrontendSpec`
Expected: the warm tests FAIL (no fill on PROG_LEN; start still invalidates).

- [ ] **Step 3: Implement the pre-warm**

- `cacheInvalidate := io.ctrl_we && (addr === 0x4 || addr === 0x14)` (remove 0x0).
- `val progLenWrite = io.ctrl_we && io.ctrl_addr === 0x14.U`; on it: `warmValid := true.B`, `warmLine := 0.U`, `fillState := FillState.IDLE`, `fillBeat := 0.U`.
- Fill trigger: `startFill = fillState === IDLE && (demandFill || doPrefetch || warmValid)`; target line `Mux(demandFill, fL, Mux(warmValid, warmLine, fillNext))`; at `startFill` latch `fillLine := targetLine` and clear `fillBeat`; beats `Mux(warmValid, Mux(lastLine >= 4.U, 4.U, lastLine), fillBeats)`.
- On the last beat of the warm, `warmValid := false`; the same completion logic sets `fillNext := fillLine +& fillBeats` (so the first post-start prefetch targets line 4, not 0).
- `commitFill` permits commits while `warmValid` (not only `running`); the IFQ enqueue stays `running && !ifqFull && fLineHit`, so no instruction issues before `start`.
- On `startEdge`, do **not** invalidate; reset `pc/fpc/IFQ` only, plus `fillState := IDLE` and `warmValid := false` (an in-flight warm is abandoned; committed lines stay valid and are demand-hittable).

- [ ] **Step 4: Run the frontend tests to verify they pass**

Run: `./tool/test-specific-spec.sh engine.NpuProgramEngineFrontendSpec`
Expected: all tests PASS (warm = 1 request; first run demand misses = 0; new CODE correct; start-during-warm still completes).

- [ ] **Step 5: Commit**

```bash
git add src/main/scala/engine/npuFrontend.scala src/main/scala/engine/npuProgramEngine.scala src/test/scala/engine/NpuProgramEngineFrontendSpec.scala
git commit -m "feat(engine): pre-warm the first instruction burst on PROG_LEN"
```

---

### Task 5: Build, hardware measurement, and docs

**Files:**
- Create: `drivers/chisel_npu_py/tool/fetch_sweep.py`
- Modify: `docs/implementations/FPGA_VU9P.md`

**Interfaces:**
- `fetch_sweep.py` uses `ChiselNPU`, `config.default_config()`, `isa`, and `npu.dev.ctrl_read_reg` for `FETCH_STATS` (0x10). It prints one line per program size and one per round count.

- [ ] **Step 0: Run the full simulation suite**

Run: `make test`
Expected: all suites pass (about 1 h 20 m). This is the gate before spending a bitstream build.

- [ ] **Step 1: Write the sweep tool**

`drivers/chisel_npu_py/tool/fetch_sweep.py`:

- `run_nops(npu, n)` → `npu.run([isa.NOP] * n)["perf"]`, plus the `FETCH_STATS` delta around the run (fills = prefetch+miss delta).
- `run_rounds(npu, r)` → the reduce-round program from `tests/test_perf.py` repeated `r` times with `vse32(0, SECT_OUT, 0)` per round.
- Print columns: `n, cycles, cycles/instr, fills, ipc, macs/cyc`.
- Sizes: 1024, 8192, 32768, 60000, 65535 (the max). Rounds: 1, 4, 16, 64, 256.
- No new dependencies; run on the FPGA host with `~/chisel_npu_py/.venv/bin/python`.

- [ ] **Step 2: Build the final A+B+P bitstream**

```bash
make build
# synth + opt via build/latest_synth_opt.tcl (per-attempt logs, retry on LgbmTree segfault)
# impl via build/impl_100mhz_nomlcong.tcl (retry; check POSTROUTE_WNS >= -1.0)
make bringup-fpga BOARD=Alivu9p
make py-deploy BOARD=Alivu9p
make py-test-hw BOARD=Alivu9p          # expect 8/8
```

Expected: 0 unrouted nets, WNS >= -1.0 ns, `py-test-hw` 8/8.

- [ ] **Step 3: Measure the final bitstream**

Run the sweep on the host:
`ssh -i ~/.ssh/id_vu9p mpsk@10.16.0.35 'cd ~/chisel_npu_py && .venv/bin/python tool/fetch_sweep.py'`

Expected: 1024 NOPs = 16 requests / ~1.2 k cycles; 8 k / 32 k / 60 k NOP sizes within ±10% of the same cycles/instruction; the 36-instruction perf program shows the pre-warm benefit (≈ one fill latency less than Phase B); the 256-round shape reaches ≥ 250 MACs/cyc (record the actual).

- [ ] **Step 4: Optional per-phase attribution**

To report Phase A (64 fills / ~3 k cycles at 1 k NOPs) and Phase B (16 requests) separately, build and program at the Task-2 and Task-3 commits. Each extra bitstream costs ~1.5 h with retries; it is not required for the final acceptance, because the final A+B+P bitstream must meet the Phase B/P numbers.

- [ ] **Step 5: Record the results**

In `docs/implementations/FPGA_VU9P.md`, add a table with, for each phase: bitstream WNS, 1024-NOP cycles/fills, 60 k-NOP cycles/instruction, 256-round MACs/cycle, and the pre-warm delta. Note the Vivado retry count.

- [ ] **Step 6: Commit**

```bash
git add drivers/chisel_npu_py/tool/fetch_sweep.py docs/implementations/FPGA_VU9P.md
git commit -m "test(hw): instruction-fetch size sweep and measured streaming results"
```

---

## Plan Self-Review

- **Spec coverage:** Phase A (§7) → Task 2; Phase B (§8) → Task 3; pre-warm (§8.5) → Task 4; abort/epoch (§7.0) → Task 1; size independence (§2.3, §9.9) → Task 3 tests + Task 5 sweep; invalidation policy (§5, §8.5) → Task 4; tail/edge lengths (§8.3, §9) → Tasks 2/3; DMA protocol (§8.1) → Tasks 2/3; verification (§10) → Tasks 1–5; risks (§11) → tests in Tasks 1/3/4.
- **Types:** `req_beats: UInt(3.W)`, `fetch_fill_beats: UInt(3.W)`, `fetch_fill_valid/last: Bool`, `fetch_fill_data: UInt(512.W)`, `lineData: Vec(16, UInt(512.W))`, `lineTag: Vec(16, UInt(9.W))`, `fillLine: UInt(12.W)`, `lastLine: UInt(13.W)` — consistent across tasks.
- **Review focus tests:** long-program slope (Task 3/5), abort/restart (Task 1), pre-warm races (Task 4), tail sizes (Tasks 2/3), DMA contention (Task 5 `py-test-hw`).
