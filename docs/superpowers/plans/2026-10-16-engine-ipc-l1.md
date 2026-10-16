# Engine IPC / Body-at-Peak (Phase 2, L1) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Raise the streamed engine's instruction/MAC throughput — the array-pipeline **body** must sustain 1 feed/cycle (peak `n²` MACs/cyc), so IPC is limited only by the array, not by the dispatcher or a per-feed store.

**Architecture:** two independent levers, sequenced:
1. **Store-sparse reduce mode** (opt-in) — a reduction round feeds M cycles without a per-mma capture/store; only the round's final result is captured. Removes the 128 B-per-feed store roofline (~12% of peak).
2. **L1 reference-count scoreboard** — replace the window-slot bitmask scoreboard (which forces a slot to stay `valid` until `clctD`, capping in-flight at `W=16`) with per-register pending-writer/pending-reader **counters** + completion FIFOs, so a slot retires at issue. Removes the `W/(2n+16)` cap (20%).

**Tech Stack:** Chisel 6.7.0, `EphemeralSimulator`, ScalaTest, Docker toolchain.

**Spec:** `docs/superpowers/specs/2026-10-15-engine-macs-per-cycle-design.md` (§2, §6 L1/L3).

## Global Constraints

- K=32, W=16 primary; `top/top.scala` (K=16, W=4) must not be modified.
- Existing instruction semantics are preserved by default: the new modes are **opt-in** and default OFF, so all current specs must stay green.
- Every non-`mma.last` mma must still eventually be drainable: in store-sparse mode only the round's final capture is queued; `io.drain` still requires `capQCount==0`.
- `VR[i] ≡ VX[4i..4i+3]`; a `vd` ring must avoid operands (`rs1=4`,`rs2=8`).
- Tests use `chisel3.simulator.EphemeralSimulator`; words via `isa.NpuAssembler`; `tool/test-specific-spec.sh <Spec>` (each run 12–45 min — keep loops minimal, run each spec once).
- The seven determinism fixes in `docs/designs/04.streamed-issuing.md` §13 must remain satisfied; the engine specs are the regression net.
- Do NOT commit `target/`, `test_run_dir/`, or `top.sv`.

## Baseline (already committed)

- `9f02244` pipelined MMA issue + vd-FIFO/capQ/DMAQ count-collision fixes; body 1.2% → 6.8%.
- `8fb03ff` head/body/tail tracer (`StreamSpecUtil.traceRun/Phases`) + `engine.HeadBodyTailSpec`.
- Roofline: a `vse32` capture is 128 B over the engine's 128-bit AXI = 8 beats; observed `clctD` ~13 cyc apart → store-per-feed caps the body at ~7–12% of peak.

## Review Focus

1. **Opt-in isolation** — default-OFF modes leave every existing spec bit-identical. (Task 1, Task 2.)
2. **Drain correctness** — `capQCount` must reach 0 in both modes (net push/pop accounting). (Task 1, Task 2.)
3. **Capture correctness** — the stored value is the correct running/final sum. (Task 1, Task 2.)
4. **Reference-count soundness** — a reader waits for preceding writers; a writer waits for preceding readers; no reader/writer deadlock cycle. (Task 2.)
5. **§13 determinism preserved** under retire-at-issue. (Task 2.)

---

### Task 1: Store-sparse reduce mode

**Files:**
- Modify: `src/main/scala/engine/npuProgramEngine.scala`
- Modify: `src/main/scala/engine/npuFrontend.scala` (expose the mode via ctrl_lite)
- Modify: `src/test/scala/engine/StreamHarness.scala` (expose the input)
- Create: `src/test/scala/engine/ReduceModeSpec.scala`

**Interfaces:**
- Produces (engine IO): `val reduce_mode = Input(Bool())`.
- Produces (ctrl_lite): a `REDUCE_MODE` word (e.g. `0x48`, bit0) toggling `core.io.reduce_mode`.
- Produces (harness IO): `val reduce_mode = Input(Bool())` wired to the engine.

- [ ] **Step 1: Write the failing test** `ReduceModeSpec`: set `reduce_mode=true`; program = `vle8(4,A,0), vle8(8,B,0), R× mma(0,4,8,0), mmaLast(0,0,0,0), vse32(0,OUT,0)` (no per-mma store). Assert: drains; the stored capture is a column of the R-term sum; `bodyUtil >= 0.15` (well above 6.8%; the ideal with no stores is the window/issue cap, ~20%+).
- [ ] **Step 2: Run** `tool/test-specific-spec.sh engine.ReduceModeSpec` — FAIL (`reduce_mode` doesn't exist / no drain).
- [ ] **Step 3: Implement**
  - Add `io.reduce_mode`.
  - In the completion block, replace the unconditional `when (!isMmaLast) { push capQ }` with a mode-aware capture: in `reduce_mode`, push only when `isMmaLast`; otherwise push only when `!isMmaLast`. Update `capPush` (the net-count term) to the SAME expression.
  - Expose `0x48` bit0 in `npuFrontend.scala` → `core.io.reduce_mode`.
  - Wire the harness input.
- [ ] **Step 4: Run** `ReduceModeSpec` PASS, then verify no default-mode regression: `engine.StreamedNSessionSpec`, `engine.StreamedChainedSpec`, `engine.HeadBodyTailSpec` (should be unchanged).
- [ ] **Step 5: Commit.**

---

### Task 2: L1 reference-count scoreboard (retire-at-issue)

**Files:**
- Modify: `src/main/scala/engine/npuProgramEngine.scala`
- Create: `src/test/scala/engine/L1ScoreboardSpec.scala`

**Design (reference-count scoreboard):**
- Per VX slot `i`: `wrPending(i)` (count of in-flight writers) and `rdPending(i)` (count of in-flight readers), each `log2(W+1)`-bit.
- **Window entry (in program order):** a reader registers `rdPending(i)++` for each `i` it reads. (Writers do NOT register at entry.)
- **Readiness:** a reader is ready when `wrPending(i)==0` for every `i` it reads; a writer is ready when `rdPending(i)==0` for every `i` it writes.
- **Issue:** a writer registers `wrPending(i)++` for each `i` it writes (after passing the readiness check). The op is then placed in a **completion FIFO** carrying its write/read register mask; the window slot may retire immediately.
- **Completion:** MMA writer completes at `clctD` → `wrPending(i)--` for its mask; DMA writer (vle) completes at DMA done; a reader's `rdPending--` completes at the MMA feed (reads done) or DMA read done.
- Rationale: readers-register-at-entry + writers-register-at-issue makes the counts reflect only *preceding* ops (issue is per-unit in program order; a later writer cannot register before an earlier reader's read completes, by the WAR check) — so no reader/writer deadlock cycle.

**Interfaces:**
- Consumes: Task 1's `reduce_mode` and `StreamSpecUtil.traceRun/Phases`.
- Produces: `wrPending`/`rdPending` + completion FIFOs replacing `vxWrMask/vxRdMask/vxWrCap` and `priorMask`.

- [ ] **Step 1: Write the failing test** `L1ScoreboardSpec`: `reduce_mode=true`, R=512 round; assert `bodyUtil >= 0.5` and correct capture; also a mixed mode (`reduce_mode=false`) case with per-mma stores, assert `bodyUtil` improves over the 6.8% baseline.
- [ ] **Step 2: Run** — FAIL.
- [ ] **Step 3: Implement** the reference-count scoreboard + completion FIFOs, preserving the §13 determinism fixes (stale-`clctD` gate, mma.last ordering, fill isolation, in-order retirement semantics). This is the hard step: if a small change cannot preserve liveness/correctness, report BLOCKED with a precise diagnosis.
- [ ] **Step 4: Verify** `L1ScoreboardSpec` + the full engine regression set (`StreamedNSessionSpec`, `StreamedChainedSpec`, `StreamedVX0Spec`, `LateStoreCaptureSpec`, `CaptureDeterminismSpec`, `CaptureTraceSpec`, `NpuProgramEngineTrajSpec`, `NpuProgramEngineFrontendSpec`) — all PASS.
- [ ] **Step 5: Commit.**

---

### Task 3: Measure IPC + full regression

- [ ] **Step 1:** Run `engine.HeadBodyTailSpec`; record head/body/tail and bodyUtil for both modes.
- [ ] **Step 2:** Run `make test` (full suite). All green.
- [ ] **Step 3:** Append measured results here; commit.

## Follow-on

- **Phase 3** — K=32/W=16 vu9p bitstream bring-up, gated on W=16 closing at 200 MHz.
