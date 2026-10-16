# L1 retire-at-issue scoreboard — execution plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development. Steps use `- [ ]`.

**Goal:** decouple the hazard/completion tag from the `W`-slot issue window so slots retire at issue; raise in-flight MMA capacity `C_mma` from `W=16` to `T=128`. Gate: **reduce-mode body ≥ 80%** (from ~18%).

**Spec:** `docs/superpowers/specs/2026-10-15-engine-macs-per-cycle-design.md` §6 L1; analysis in `docs/superpowers/plans/2026-10-16-engine-ipc-l1.md`.

**Reference model:** `mma/cyc = min(1/I, C_mma/L)`, `L ≈ 2n−2+CAPTURE_DELAY+pipe ≈ 80`, currently `C_mma = f·W = 16`.

## Global Constraints
- K=32, W=16 primary; `top/top.scala` untouched (use `W=4` there; L1 must not regress it).
- **Carry `accum` in the vd FIFO** (decision (a)) — no collector-C change in P2.
- Decisions: tags allocated **at entry**; `T=128` (parameterized); MMA slot retires **at feed**, DMA at **dmaQ enqueue**; **T-bit masks first**, write-tag queue later.
- Preserve the seven determinism fixes (`docs/designs/04.streamed-issuing.md` §13). Engine determinism specs gate every phase.
- `chisel3.simulator.EphemeralSimulator`; `tool/test-specific-spec.sh <Spec>` (each run 1–45 min).
- Do NOT commit `target/`, `test_run_dir/`, `top.sv`.

## Review Focus
1. Behaviour-equivalence at P1 (body must stay ~18%; any drift = tag-logic bug). (P1)
2. Hazard correctness with >W in flight: RAW/WAR via `tagWrMask/tagRdMask & priorSnap`. (P1/P2)
3. Determinism §13: stale-`clctD` gating, fill isolation, `mma.last` ordering under OOO tags. (P2)
4. Queue coupling: vd-FIFO (carry `accum`) and capQ must be `≥ T` or P2 stalls short of 80%. (P2)
5. Tag-ring free under OOO completion (head advances only over completed tags). (P2)

---

### P1a — Tag-ring machinery (SHADOW; no behaviour change)
**File:** `src/main/scala/engine/npuProgramEngine.scala`

- [ ] Add `T=128`, `TAG_IDX=log2Ceil(T)`. To `DispatchSlot` add `tag: UInt(TAG_IDX.W)` and `priorSnap: UInt(T.W)`.
- [ ] Add registers: `tagHead/tagTail` (TAG_IDX), `tagCount` (TAG_IDX+1), `tagValid/tagIsMma/tagIsLast` (`Vec(T,Bool)`), `tagSlot` (`Vec(T, UInt(SLOT_IDX.W))`), `tagWrMask/tagRdMask` (`Vec(L, UInt(T.W))`), `lastWrTag` (`Vec(L, UInt(TAG_IDX.W))`).
- [ ] At entry: allocate `t=tagTail` (stall entry if `tagCount==T`); set `tagValid(t)`, `tagIsMma/IsLast`, `tagSlot(t)`, `slots(t).priorSnap := tagValid`; register `tagRdMask`/`tagWrMask` bits by `t` (mirror of the existing `vxRdMask/vxWrMask` logic, including the cap-store exclusion); set `lastWrTag(i):=t` for writers.
- [ ] Thread the tag: vd-FIFO entry and `DmaEntry` carry `tag` (add fields; keep `slotIdx` for now).
- [ ] At completion (`clctD` and DMA done): clear `tagValid(t)`, clear bit `t` from `tagWrMask/tagRdMask`; advance `tagHead` while `tagValid(tagHead)==false`; `tagCount` net accounting.
- [ ] **Do NOT change readiness, issue, retirement, or the existing `vxWrMask/vxRdMask/vxWrCap` logic yet** — the tag structures run in parallel (shadow).
- [ ] **Verify:** `tool/test-specific-spec.sh "engine.StreamedNSessionSpec engine.StreamedChainedSpec engine.StreamedVX0Spec engine.CaptureDeterminismSpec engine.HeadBodyTailSpec"` all PASS and `HeadBodyTailSpec` reduce/default bodyUtil unchanged. Commit.

### P1b — Route readiness through the tags (still retreat-at-done)
- [ ] Replace `priorMask(s)` with `slots(s).priorSnap` in the readiness loop; use `tagWrMask/tagRdMask`. Replace the `mma.last` `priorMma` window scan with a `tagValid` scan for a prior pending non-last MMA tag `< slots(s).tag`.
- [ ] Replace `vxWrCap`-based `useCapData` detection with `tagIsMma(lastWrTag(i))`.
- [ ] Remove the now-dead `vxWrMask/vxRdMask/vxWrCap/priorMask`. Keep retirement at `done`.
- [ ] **Verify:** same spec set PASS; bodyUtil still ≈18% (equivalence). Commit.

### P2 — Retire at issue + queue resizing
- [ ] Slot `done` set at **feed** (MMA; operands read) / **dmaQ enqueue** (DMA); completion only frees tags/masks (no slot writes).
- [ ] Deepen `FIFO_DEPTH` (vd FIFO) and `CAPQ_DEPTH` to `≥ T` (carry `accum`); update `unitOk` limits.
- [ ] Free tags in-order at the ring head (tolerate OOO completion).
- [ ] Reset/drain: flush tag ring/masks/`lastWrTag`; `drain` adds `tagCount==0`.
- [ ] **Verify:** reduce-mode body **≥ 80%**; full engine set (`Streamed*`, `Capture*`, `LateStoreCapture`, `Traj`, `NpuProgramEngineFrontendSpec`, `ReduceModeSpec`, `BodyPipelineSpec`) PASS; `dma.NpuDmaEngineSpec` PASS. Commit.

### P3 — Optimize / size
- [ ] Re-tune `T`/`CAPTURE_DELAY`; document FPGA (K=16,W=4) sizing; optionally swap T-bit masks for per-register write-tag queues.
- [ ] Commit.

### P4 — Full regression + record
- [ ] `make test`; record head/body/tail in the plan. Commit.

## Measured results (L1 complete)

| Metric | Before L1 | After L1 |
|:--|--:|--:|
| store-sparse reduce-mode body (K=32) | 18.0% | **97.0%** (body 629→66 cyc, 64 feeds) |
| full suite `make test` | 36 suites / 135 | **40 suites / 140 tests, all pass** |

- L1 delivered the Little's-law unlock: `C_mma` went from `W=16` to the tag-ring bound, so
  reduce-mode body is now frontend-limited (`1/I ≈ 1`), not capacity-limited.
- Remaining (default store-per-feed mode): bounded by the per-feed store / DMA rate — see the
  burst/BW work (`docs/superpowers/plans/2026-10-16-burst-dma.md`), not L1.
- Deferred to a future retune: a **generational `priorSnap`** is required before raising `T` or
  lowering `CAPTURE_DELAY` (constraint `maxInFlight < T−W`); FPGA-top (K=16,W=4) sizing unvalidated.
