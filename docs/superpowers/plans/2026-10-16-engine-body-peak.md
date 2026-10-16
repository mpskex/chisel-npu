# Engine Body-to-Peak (Phase 2) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the array-pipeline **body** sustain 1 feed/cycle (peak = `n²` MACs/cyc) for any body length, and keep the highest utilization when head/tail interleave.

**Architecture:** `NpuProgramEngine` currently issues at most one MMA at a time because `firstForUnit` blocks a later MMA behind an earlier *valid* same-unit slot, and a slot stays `valid` until its `clctD` (~`2n−2+16` = 80 cyc @ K=32). We must let multiple MMAs be in flight while preserving feed order, capture order, and the silicon-verified determinism.

**Tech Stack:** Chisel 6.7.0, `EphemeralSimulator`, ScalaTest, Docker toolchain.

**Spec:** `docs/superpowers/specs/2026-10-15-engine-macs-per-cycle-design.md` (§6 L1/L3; §7).

## Global Constraints

- K=32, W=16 primary; `top/top.scala` (K=16, W=4) must not be modified.
- ISA/encoding unchanged. Instruction semantics unchanged: every non-`mma.last` mma still produces a running-sum capture; one consuming `vse` per non-last mma or the session never drains.
- `VR[i] ≡ VX[4i..4i+3]`; a `vd` ring must avoid operands (`rs1=4`,`rs2=8`).
- Tests use `chisel3.simulator.EphemeralSimulator`; words via `isa.NpuAssembler`; run one spec with `tool/test-specific-spec.sh <Spec>` (~12–45 min each — elaboration dominates; keep loops minimal).
- The seven determinism fixes in `docs/designs/04.streamed-issuing.md` §13 must remain satisfied; the engine specs are the regression net.
- Do NOT commit `target/`, `test_run_dir/`, or `top.sv`.

## Review Focus

1. **Liveness** — a pipelined-issue program must always reach `drain` (the naive `!issued` relaxation wedges on R=128/Vd=4). (Task 1.)
2. **Capture order/correctness** — multiple in-flight MMAs must still push captures in program order and store the correct running-sum column. (Task 1.)
3. **No regression in the determinism specs** — `StreamedNSessionSpec`, `StreamedChainedSpec`, `StreamedVX0Spec`, `LateStoreCaptureSpec`, `CaptureDeterminismSpec`, `CaptureTraceSpec`, `NpuProgramEngineTrajSpec`, `NpuProgramEngineFrontendSpec`. (Task 1.)
4. **WAR removal safety** — not scoring capture-consuming stores as VR readers must not let an mma overwrite a capture before the store pops the queue. (Task 2.)
5. **Queue-depth overflow** — deepening FIFO/capQ must not break the net enq/pop accounting at a coincidence. (Task 3.)

---

## Evidence gathered (Phase 1 + root-cause)

`engine.HeadBodyTailSpec` (array-pipeline segmentation; committed `8fb03ff`):

| case | head | body | tail | bodyUtil |
|:--|--:|--:|--:|--:|
| R=128 Vd=1 | 64 | 12480 | 81 | 1.0% |
| R=512 Vd=1 | 64 | 50112 | 81 | 1.0% |
| R=128 Vd=4 | 64 | 10560 | 81 | 1.2% |
| R=512 Vd=4 | 64 | 42432 | 81 | 1.2% |

Per-cycle trace (R=16): feeds at cyc 66, 164, 262, 360 — **one per `clctD`**, window pinned full at 16, capQ/DMAQ empty. Root cause: `firstForUnit` (npuProgramEngine.scala:416) blocks a later same-unit slot behind any earlier *valid* slot; an issued MMA slot stays valid for ~80 cyc until `clctD`. Relaxing it (`&& !slots(i).issued`, all units, or MMA-only) produces back-to-back bursts on R=16 but **wedges** R=128/Vd=4 (`traceRun did not drain`).

---

### Task 1: Liveness-safe pipelined MMA issue

**Files:**
- Modify: `src/main/scala/engine/npuProgramEngine.scala`
- Create: `src/test/scala/engine/BodyPipelineSpec.scala`

**Interfaces:**
- Consumes: `StreamSpecUtil.{resetSession, preloadI8, traceRun, Phases, printPhases}`.
- Produces: a pipelined MMA issue model; `BodyPipelineSpec` asserting liveness + correctness + `bodyUtil`.

- [ ] **Step 1: Write the failing test** (`BodyPipelineSpec`)

```scala
package engine
import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec
import isa._
import isa.NpuAssembler._

class BodyPipelineSpec extends AnyFlatSpec {
  val K = 32
  def round(R: Int, ring: Seq[Int]): Seq[Int] =
    Seq(vle8(4, NpuSections.SECT_A, 0), vle8(8, NpuSections.SECT_B, 0)) ++
      (0 until R).flatMap { i => val v = ring(i % ring.length)
        Seq(mma(v, 4, 8, 0), vse32(v, NpuSections.SECT_OUT, (i % 8) * 4 * K)) } :+
      mmaLast(0, 0, 0, 0)

  "Pipelined MMA issue" should "keep the body at peak without wedging" in {
    simulate(new StreamHarness(K = K)) { dut =>
      StreamSpecUtil.resetSession(dut)
      StreamSpecUtil.preloadI8(dut, NpuSections.A, Seq.fill(K)(3))
      StreamSpecUtil.preloadI8(dut, NpuSections.SECT_B, Seq.fill(K)(5))
      val ph = StreamSpecUtil.traceRun(dut, round(128, Seq(0, 3, 4, 5)))
      StreamSpecUtil.printPhases("pipeline R=128 Vd=4", ph)
      assert(ph.bodyUtil >= 0.5, f"body at ${ph.bodyUtil * 100}%.1f%% of peak, want >=50%%")
    }
  }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `tool/test-specific-spec.sh engine.BodyPipelineSpec`
Expected: FAIL — `bodyUtil` ≈ 1.2% (< 50%).

- [ ] **Step 3: Diagnose the wedge before fixing**

Re-apply the MMA-only relaxation (`!slots(i).issued || !isMma` in `firstForUnit`) and run `BodyPipelineSpec`. It wedges (`traceRun did not drain`). Find the deadlock source by tracing: which slot never reaches `done`, which mask never clears. Candidate suspects (verify, don't assume): `slotsReady`/`priorMask` computed for slots behind an issued slot; reader-bit clearing at `feedValid`; the vd-FIFO/capQ enq-pop ordering; in-order retirement at `wHead`. Document the exact cycle/state of the stall in the report.

- [ ] **Step 4: Implement the liveness-safe fix**

Only after Step 3 identifies the deadlock. Preserve: MMA feed order = program order (the 2-stage feed FIFO gives this), capture order, the §13 determinism fixes. If a small issue-model change is insufficient, stop and report BLOCKED with the diagnosis (a scoreboard rework may be required).

- [ ] **Step 5: Verify**

Run `tool/test-specific-spec.sh engine.BodyPipelineSpec` (PASS, `bodyUtil ≥ 0.5`), then the regression set:
`engine.StreamedNSessionSpec`, `engine.StreamedChainedSpec`, `engine.StreamedVX0Spec`, `engine.LateStoreCaptureSpec`, `engine.CaptureDeterminismSpec`, `engine.NpuProgramEngineFrontendSpec`.
All must PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/scala/engine/npuProgramEngine.scala src/test/scala/engine/BodyPipelineSpec.scala
git commit -m "feat(engine): liveness-safe pipelined MMA issue"
```

---

### Task 2: Drop the spurious WAR on capture-consuming stores

**Files:**
- Modify: `src/main/scala/engine/npuProgramEngine.scala`
- Modify: `src/test/scala/engine/BodyPipelineSpec.scala`

**Interfaces:**
- Consumes: Task 1's pipelined issue.
- Produces: capture-consuming stores (`useCapData`) are not scored as VR readers.

- [ ] **Step 1: Write the failing test** — add a case asserting `bodyUtil ≥ 0.9` for `Vd=1` (a single vd), which only reaches peak if the store no longer blocks the next mma's write.

- [ ] **Step 2: Run to verify it fails** (expected: Vd=1 stays ~1–10%).

- [ ] **Step 3: Implement** — in the window-entry scoreboard registration and the per-slot `readSlots` used for RAW/WAR, treat a `useCapData` store as reading **no** RF slots (it reads the capture queue). Keep its RAW wait on the mma's writer bit (so it still waits for the capture), and keep its reader-bit clearing behavior consistent.

- [ ] **Step 4: Run** `engine.BodyPipelineSpec` + the regression set from Task 1. PASS.

- [ ] **Step 5: Commit**

---

### Task 3: Size completion queues to the completion latency

**Files:**
- Modify: `src/main/scala/engine/npuProgramEngine.scala` (parameterize `FIFO_DEPTH`/`CAPQ_DEPTH`)
- Modify: `src/test/scala/engine/BodyPipelineSpec.scala`

- [ ] **Step 1:** Add a test asserting `bodyUtil ≥ 0.9` for `R=512` (long body) — if the queues cap in-flight feeds below the latency, this fails.
- [ ] **Step 2:** Deepen `FIFO_DEPTH`/`CAPQ_DEPTH` to `≥ 2n+16` (≈80 → e.g. 128), or reduce the completion latency, whichever the data supports. Keep the net enq/pop accounting correct.
- [ ] **Step 3:** Run `engine.BodyPipelineSpec` + regression set. PASS.
- [ ] **Step 4:** Commit.

---

### Task 4: Measure head/body/tail + full regression

- [ ] **Step 1:** Run `engine.HeadBodyTailSpec`; record the new phases (head constant, body at peak).
- [ ] **Step 2:** Run `make test` (full suite). All green.
- [ ] **Step 3:** Append measured results to this plan; commit.

## Follow-on

- **Phase 3** — K=32/W=16 vu9p bitstream bring-up, gated on W=16 closing at 200 MHz.
