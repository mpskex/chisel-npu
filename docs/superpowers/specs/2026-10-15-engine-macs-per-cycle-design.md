# Design — MACs/cycle benchmarking, dispatch/pipeline optimization, and K=32/W=16 vu9p bring-up

Status: approved (2026-10-15). This is the spec for the work; the implementation
plan is produced separately by the `writing-plans` skill.

Target artifact: `engine/NpuProgramEngine` at **K=32, W=16** (test default),
followed by a **K=32/W=16** bitstream bring-up on the **xcvu9p** (Alivu9p)
board.

## 1. Objective

Drive the sustained **MACs/cycle** of the streamed program engine toward its
theoretical peak, and make the whole **instruction-decode → final output** path
sustain that rate. Concretely:

1. Measure — build a benchmark matrix that decomposes the bottleneck.
2. Optimize — change the hardware dispatch/instruction pipeline based on the
   measurements (scope decided from data).
3. Bring up — build and validate **K=32/W=16** on the vu9p, with **W=16
   closing timing at 200 MHz** as a hard requirement.

## 2. Theoretical bound and metric

The `K×K` systolic array (`SystolicArray2D(n=K)`) has `n²` PEs, each performing
one multiply-accumulate per cycle, so the peak is:

```
peak = n² MACs/cycle           (256 @ K=16, 1024 @ K=32)
```

One *feed* (one MMALU issue tick) injects one `A` column and one `B` row and
performs `n²` MACs. The engine issues at most one MMA feed per cycle
(`firstForUnit` in `NpuProgramEngine`), so peak is reachable only if the
dispatcher keeps the array continuously fed.

Per-benchmark metrics:

| Metric | Definition |
|:--|:--|
| `cycles` | clock ticks from session start to `drain` |
| `feeds` | MMA feeds issued (`io.dbg_mma_acc`) |
| `MACs` | `feeds × n²` |
| `MACs/cyc` | `MACs / cycles` |
| `%peak` | `MACs/cyc / n²` = `feeds / cycles` |
| occupancy | window (`wCount`), vd FIFO, capQ, DMAQ, boundary countdown |

The benchmark asserts *measured* values; it does not assert a target percentage
(yet) — it prints a comparison table and asserts correctness of the captures so
a regression is visible.

## 3. Baseline analysis (to be confirmed by measurement)

Four structural caps, in expected order of impact:

1. **Window depth `W` caps in-flight MMAs.** A window slot is the scoreboard
   writer tag and stays `valid` until the MMA's `clctD`. With
   `clct = 2n−2` and `CAPTURE_DELAY = 16`, `clctD ≈ 2n+16 ≈ 80` cycles @ K=32
   (measured from the feed, including the ~2-cycle issue/feed pipeline).
   Sustained feeds ≤ `W / (2n+16) = 16/80 = 0.2/cyc` → ~205 MACs/cyc (~20% peak).
2. **Boundary countdown `6K−1`** (=191 @ K=32) clamps MMA issue to zero after
   every `mma.last`; its cost is per-round and dominates short rounds.
3. **Operand DMA serialization** — `vle → mma` RAW chains make the feed cadence
   equal the operand-arrival cadence at DDR latency (100–200 cyc).
4. **No throughput instrumentation** — only `dbg_mma_acc`, `dbg_clct`,
   `dbg_dma_done`, and frontend FETCH_STATS exist; no cycle or MAC counter.

## 4. Phase 0 — Instrumentation

### 4.1 RTL (`engine/npuProgramEngine.scala`)
- Add `cycleCnt` (`RegInit(0.U(32.W))`), incremented every cycle, reset by
  `io.session_reset` (the frontend pulses it at every start edge). This makes
  it a per-session cycle count.
- Add `macCnt` — accumulate `n²` on each `feedValid`, reset by `session_reset`.
  (Equivalently expose `feeds`; `macCnt = feeds × n²` is computed in the RTL so
  the counters are useful on silicon without knowing `n`.)
- Add debug outputs `dbg_cycles: UInt(32.W)` and `dbg_mac: UInt(32.W)` (or a
  `dbg_perf` bundle).

### 4.2 `ctrl_lite` (`engine/npuFrontend.scala`)
- Expose `dbg_cycles` at a new word (e.g. `0x40`) and `dbg_mac` at `0x44`;
  pass through from the core. Keep `ctrl_addr` 7-bit (already).
- Update `docs` comment block listing ctrl_lite registers.

### 4.3 Test harness (`test/scala/engine/StreamHarness.scala`)
- Expose `dbg_cycles` / `dbg_mac` (or `feeds`) on `StreamHarness.io`.
- Add helpers: `runResidentLongRound(R)`, `runShortRounds(N)`,
  `runReloaded(...)`, `macsPerCycle(feeds, cycles)`.
- A `measure { ... }` helper that runs a program to `drain` and returns
  `(cycles, feeds, macs, macsPerCycle)`.

### 4.4 Acceptance
- `make test` stays green (no behavioral change).
- Counters reset exactly at `session_reset`; a one-shot session reports
  `feeds` equal to the number of `mma` instructions issued.

## 5. Phase 1 — Benchmark matrix

New specs:
- `test/scala/engine/EngineThroughputSpec.scala` — core driven directly through
  `StreamHarness` (no fetch).
- `test/scala/engine/FrontendThroughputSpec.scala` — via
  `NpuProgramEngineFrontend` (fetch → IFQ → decode → issue).

| Case | Setup | Isolates |
|:--|:--|:--|
| **A1** | MMALU directly, `keep=1` continuous feed | array ceiling `n²/cyc` |
| **B1** | resident operands: `vle` once, then R× `mma` (`vs3=0`), one `mma.last`; R ∈ {16,64,256,1024} | window-depth cap + feed cadence |
| **B2** | resident operands, `mma + mma.last` × N | `6K−1` boundary overhead |
| **B3** | reloaded operands: `vle, mma` per feed; latency ∈ {1,100,200} | DMA-bound cadence |
| **B4** | chained reduction `vs3 = prev vd` | RAW serialization |
| **B5** | realistic tile: `vle A, vle B, mma, vse` loop | combined realistic path |
| **C1** | B1/B2/B5 through the full frontend | frontend/decode overhead |

Notes:
- B1's resident operands mean the mmas are independent (no RAW), so the window
  cap and the completion latency are isolated from operand DMA.
- Each case prints one table row and asserts the captures are correct (the
  existing `columnsOf` running-sum search).
- Run at K=32/W=16 (primary). Optionally re-run key cases at K=16/W=4 to
  separate architectural scaling from parameters.

## 6. Phase 2 — Data-driven dispatch/pipeline optimization

Scope is chosen from Phase 1 data. All candidate changes are **ISA-preserving**
(instruction encoding and current spec semantics unchanged). Candidate levers:

- **L1 (primary): decouple completion from window occupancy.** Today a slot is
  both the scheduling entry and the scoreboard writer tag, so it cannot retire
  until `clctD`. Move writer-tag tracking into a **completion FIFO + per-register
  pending-owner** (a `vd`-tag FIFO popped at `clctD`, with the scoreboard's RAW/
  WAR masks keyed to that tag instead of the window slot index). Then a slot can
  retire at/after issue while many MMAs remain in flight, removing the
  `W/(2n+17)` cap without growing W. Must preserve the seven determinism fixes
  in `04.streamed-issuing.md` §13 (stale-clctD gating, prior-MMA ordering,
  fetch-fill slot isolation, …).
- **L2: shrink the scoreboard/issue cone.** Pipeline/broadcast the per-slot
  readiness scan (`W×L`) so larger `W` closes at 200 MHz. Required to meet the
  Phase-3 `W=16 @ 200 MHz` criterion if L1 alone is insufficient.
- **L3: reduce completion latency.** Revisit `clct = 2n−2` and
  `CAPTURE_DELAY = 16`; if the array data settles sooner, shorten both.
- **L4: overlap the boundary countdown.** Keep the `6K−1` semantics (PE reset
  correctness) but allow the next round's operand loads / non-MMA work to
  proceed during it, and start MMA issue as early as provably safe.
- **L5: hide DDR latency.** Deepen `DMAQ`, prefetch operands, or start the
  `vle` for feed `m+1` while feed `m` executes.

Each change is gated by:
- full `make test` green;
- the 200-run back-to-back determinism regression green;
- a timing report (`WNS ≥ 0`, 0 failing endpoints) at the Phase-3 parameters.

## 7. Phase 3 — K=32/W=16 bring-up on vu9p

1. `top/top.scala` → `NpuProgramEngineFrontend(K = 32, N = 8, W = 16)`.
2. `drivers/chisel_npu_py/src/chisel_npu_py/config.py` `default_config()` →
   `K = 32` (updates `nop_wait`, `vector_bytes`, `col_bytes`; section bases are
   unchanged and fit K=32's `int8[K×K] = 1 KiB` A/B exactly).
3. `make build` → `top.sv`; then `ip/vivado/xcvu9p/scripts/build_npu_vu9p.tcl`
   (after the one-time `setup_platform.tcl`) → `top_npu_vu9p.bit`.
4. Program over `hw_server` (`10.16.0.35:3121`), warm reboot so PCIe enumerates,
   load the out-of-tree `xdma.ko`, then `FPGA_HOST=10.16.0.35 make py-deploy`
   and `make py-test-hw`.
5. Read the new perf counters over ctrl_lite and record on-HW MACs/cycle.

**Hard requirement:** the engine fabric domain closes at **200 MHz** with
`W=16`. Mitigations if it misses: Phase-2 L1/L2; `ExtraTimingOpt` placement
(already used); optionally register the multiply result in the PE.

## 8. Verification

- `make test` green throughout (existing engine + MMALU + backend specs).
- New throughput specs pass and print the comparison table.
- The 200-run determinism regression green after any completion-path change.
- Phase 3: timing `WNS ≥ 0` / 0 failing endpoints; `make py-test-hw` 7/7;
  on-HW perf counters consistent with simulation.

## 9. Risks

- **Timing closure at W=16/200 MHz** is the critical risk (W=8 already failed
  for K=16). Phase 2 L1/L2 are the mitigations.
- **Completion-semantics changes** can reintroduce the seven documented
  determinism bugs; the regression suite is mandatory.
- **Resource growth** at K=32 (4× PEs, 2× RF) — acceptable on vu9p; not
  targeted at xc7k480t in this work.
- **Driver drift** — section bases are hardcoded in `NpuSections`; they fit
  K=32, but this is a latent trap to note in the plan.

## 10. Assumptions

- "Theoretical upper bound to compute" = `n²` MACs/cycle (one MAC per PE per
  cycle). If a different bound was intended, revisit §2.
- The instruction encoding and the `mma.last`/boundary semantics are fixed;
  Phase 2 is microarchitecture-only unless the data says otherwise.
