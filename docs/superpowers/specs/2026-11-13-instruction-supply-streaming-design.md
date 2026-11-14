# Instruction-Supply Streaming — I-cache Phase A + Burst Fill Phase B

**Status:** draft for approval
**Date:** 2026-11-13
**Branch:** `fangrui/refine-dispatch-frontend`

## 1. Intent

The engine is **fetch-bound, not execute-bound**. Measured on the current
100 MHz bitstream (`top_npu_vu9p.bit`, WNS +0.003 ns, K=32):

| program | cycles | cycles/instruction |
|:--|--:|--:|
| 256 NOPs | 2,920 | 11.4 |
| 1,024 NOPs | 11,704 | 11.4 |
| 32/64/128-feed MMA chunks | — | 10.0–11.5 |
| 256-round reduce shape | 106,068 | 0.082 IPC, 79 MACs/cyc (7.7 % peak) |

The marginal cost is the same for NOPs and MMAs, so the array and the
80-cycle collector are not the limit. The fetch path is:

* the I-cache line is **4 words (128 bit)**;
* a fill is **one outstanding DMA request** (no pipelining);
* the DMA already reads a **64-byte/512-bit AXI beat** (`dir=3`, one beat)
  but `INSTRW` returns only one 128-bit quarter;
* measured cost is **45.7 cycles per 16-byte line** → 45.7 / 4 =
  **11.4 cycles per instruction**.

Four consecutive fills re-read the same 64-byte DDR beat. Three quarters of
every instruction fetch is discarded.

The goal is to stop the front end from starving the dispatch window, so the
existing 1-instruction/cycle issue path and the array can be used.

## 2. Goals

1. **Phase A** — store the whole 64-byte beat the DMA already fetches
   (16-word I-cache line). Expected: **~2.9 cycles/instruction**, 4× fewer
   fills, same or less DDR traffic.
2. **Phase B** — fetch **4 beats (256 B = 64 instructions) per request** and
   stream the beats into the cache. Expected: **~0.8–1.0 cycle/instruction**
   for linear programs.
3. **Size-agnostic steady state.** For any supported program length, the
   steady-state cycles/instruction must be independent of program size — no
   cache-capacity cliff. The 16-line array is a **sliding window**, not a
   working set; program size changes only the number of window slides, not
   the fill-per-instruction rate. The only size-dependent terms are the
   constant startup (first fill ≈ 46 cycles) and the final-line tail.
   Acceptance is defined on the marginal slope (N ≥ 1 k), not on the average
   of tiny programs.
4. **First-line pre-warm.** The first burst is fetched on the `PROG_LEN`
   write, while the host is still issuing `START`, so the session's first
   instruction is served from the cache. The pre-warm is outside the session
   cycle count and removes the DDR fill latency from small programs.
5. No ISA, driver API, ctrl register map, or external AXI change.
6. Keep the 100 MHz timing gate (WNS ≥ −1.0 ns; target ≥ 0).
7. Keep `py-test-hw` 8/8 and the existing simulation suite green.

## 3. Non-goals

* The MMA feed/issue rate, collector latency, round cadence, or vd/WAR
  scheduling. Those become the next limit after this work and are measured,
  not changed here.
* A branch/jump unit or cache coherence (the ISA is straight-line; CODE is
  staged before `start`).
* Changing the 100 MHz clock or the 250 MHz ctrl/AXI domain.
* A driver-visible fetch API or new sections.
* Programs larger than the platform's existing 16-bit `PROG_LEN` / 256 KiB
  CODE space (> 65,535 words); see §5.1.

## 4. Anti-goals

* Trading correctness for fetch speed (partial lines, stale words after
  `start`, out-of-range reads).
* An unbounded area/timing hit: the line array must stay small (~1 KiB) and
  the 512-bit read mux must close at 10 ns.
* Special-casing the current perf workload; the fetch path must work for any
  `progLen` from 1 to 65,535 words.

## 5. Constraints

* Engine top: `NpuProgramEngineFrontend(K=32, N=8, W=8)`; fabric 100 MHz.
* PC is 16 bits; `PROG_LEN` is 16 bits, so the supported program range is
  **1…65,535 words** (65,536 truncates to 0). CODE is 256 KiB at
  `0x4000_4000` = 4,096 lines of 16 words; `lastLine` is 13 bits.
  Sections are staged by the driver before the start edge.
* The frontend issues fills through the engine's shared DMA queue
  (`dmaQ`, `dir=3`), which is also used by `vle`/`vse`.
* `fetch_fill_*` is an internal engine↔frontend interface (not AXI).
* The current implementation has **no control flow**: `fpc` increments by 1,
  so instruction lines are consumed strictly sequentially and are never
  reused within a session. The cache is invalidated on `PROG_LEN`/`FRAMES`
  writes, **not** on the `CTRL` start write (so the pre-warm survives the
  start edge).

### 5.1 Scalability boundary

Within this design, "any size" means **1…65,535 words**, the existing
16-bit `PROG_LEN` and 256 KiB CODE window. That covers every program the
current host/driver can stage, and the sliding window makes the throughput
independent of size across that whole range.

Supporting programs larger than 65,535 words (e.g., multi-MiB binaries)
requires a separate, wider-address phase: 32-bit `PC`/`fpc`/`PROG_LEN`,
wider `STATUS`/`errPC` fields or a new PC register, a larger CODE window and
driver staging, and updated tests. That is **not** part of this plan unless
explicitly requested; nothing in the fetch design prevents it (the cache and
DMA are already 32-bit addressed).

## 6. Approaches considered

**A. Use the whole 64-byte beat — 16-word line.** Keep the 8-set × 2-way
cache and the single-fill FSM; widen the line and DMA return. 4× fewer
fills; ~2.9 cyc/instr. Low risk, no protocol change.

**B. Burst fill — N beats per request.** Extend `dir=3` to a 4-beat AXI
burst and stream the beats into 4 consecutive lines. ~46+3 cycles per 64
instructions → ~0.8 cyc/instr. Moderate risk: DMA read-buffer/`INSTRW`
protocol and a wider frontend commit path.

**C. Session-start preload into on-chip BRAM (rejected).** Best steady state
(1 instr/cycle), but adds a preload phase, a 4–8 KiB size cap, and a start
protocol. Poor fit for 64 K-word programs and short runs.

**Chosen: A then B.** A is a self-contained 4× win and provides the wider
line storage B needs. C is dropped.

## 7. Phase A — 16-word I-cache line

### 7.0 Prerequisite: fill abort/epoch semantics

An in-flight fill is currently never cancelled. `fillPending` has a single
clear (`io.fetch_fill_done`), and the session-reset/illegal flushes drop the
`dmaQ` entry without clearing it. If an illegal halt (or a new `start`)
arrives while a fill is queued or in `READ`, the completion never comes:
`fillPending` sticks at 1, `fillPush` can never fire again, the frontend
stays in `REQ`, and every later session fetches nothing (host timeout).
Prefetching makes this reachable, so it is fixed as part of this work, not
inherited:

* Engine: clear `fillPending` on `io.session_reset` and `illegalAny`; gate
  `fillPush` with `!io.session_reset && !illegalAny` so a request cannot
  race the flush into `dmaQ(dmaQTail)`.
* Frontend: reset `fillState`/`fillLine`/`fillBeat` on `startEdge`; only
  commit beats while `running`; a stale DMA beat is already gated by the
  engine's `dmaHead.valid && inFlight && dir === 3`.
* If the DMA is still busy with the aborted read, the next fill waits for
  `!fetch_busy` in `DRAIN`; no stale beat can commit.
* Regression: a long program with an illegal instruction mid-stream (so a
  prefetch is in flight at the halt), followed by a valid program in the
  same simulation/session sequence; the second program must fetch and
  complete.

### 7.1 Frontend (`src/main/scala/engine/npuFrontend.scala`)

Replace the `SETS/WAYS/4-word` geometry with:

```
line      = fpc >> 4          // 12 bits (4096 lines of 16 words)
set       = line(2, 0)        // 8 sets
tag       = line(11, 3)       // 9 bits
word      = fpc(3, 0)         // 16 words per line
lineData  : Vec(16, UInt(512.W))
lineTag   : Vec(16, UInt(9.W))
fWord     = (lineData(way) >> (fpc(3,0) << 5))(31, 0)
```

* `fetch_fill_addr = CODE_BASE + (fillLine << 6)`; `fillLine` is 12 bits.
* Last valid line: `lastLine = (progLenReg +& 15) >> 4` (widen the add;
  13 bits, 0…4096); prefetch only when `nextFL < lastLine` (avoids the
  16-bit `(nextFL << 4)` overflow).
* `lineValid`/`mru`/victim logic is unchanged.
* `fillLine` capture and cache invalidation are unchanged.
* The IFQ (8 deep) and the fetch→IFQ enqueue are unchanged: one word per
  cycle from the cache hit path.

The 16-word line is held in registers (16 × 512 = 8 Kbit). If placement or
the 512-bit 16:1 read mux hurts timing, the fallback is a 512-bit-wide
`SyncReadMem` (1-cycle read) plus a one-word lookahead register in the IFQ
path. That is a builder call, not a scope change.

### 7.2 DMA (`src/main/scala/dma/npuDmaEngine.scala`)

* `instr_out` widens from 128 to **512 bits** and returns the whole
  `buf(0)` beat (the fetch address is 64-byte aligned, so no `rdOff` quarter
  select is needed for `dir=3`).
* `beatsOf(dir=3)` stays **1**; ARLEN/SIZE unchanged.
* `fetch_fill_data` widens to 512 in `NpuProgramEngine`'s fetch interface
  and in the frontend harness.

### 7.3 Engine (`src/main/scala/engine/npuProgramEngine.scala`)

Only the `fetch_fill_data` width changes (128 → 512). The `fillPush` path,
`dir=3` queue entry, `fetch_fill_done`, `fillPending`, and the DMA request
wiring are unchanged.

### 7.4 Expected result

* 1,024 NOPs: **64 fills** (was 256), cycles ≈ 64 × 45.7 ≈ **2.9 k**
  (11.7 k today), ≈2.9 cyc/instr.
* `FETCH_STATS` miss/prefetch deltas drop by 4×.

## 8. Phase B — 4-beat burst fill

### 8.1 Request/beat protocol

Add a burst length to the internal fill interface:

* Frontend → engine: `fetch_fill_beats` (2 bits, 1 or 4).
* Engine → DMA: a `beats` field in `DmaEntry` (3 bits) forwarded as
  `dma.io.req_beats`.
* DMA: `beatsOf(dir=3) := reqBeats`; `MAX_BEATS` grows to 4; `buf` holds 4
  beats.
* DMA `INSTRW` streams: `instrBeat` (0..N−1), `instr_valid` per beat,
  `instr_out = buf(instrBeat)`, `instr_last` on beat N−1, then `DONE`.
* Engine → frontend: `fetch_fill_valid` (per beat), `fetch_fill_last`,
  `fetch_fill_data[511:0]`. `fillPending` clears on `last`, not on the first
  beat.

### 8.2 Frontend commit

* On `fetch_fill_valid`, commit the 512-bit beat into line
  `fillLine + fillBeat`; `fillBeat` increments 0..N−1.
* Tag/set/victim are computed from the absolute line index; consecutive
  lines map to 4 distinct sets, so a burst never conflicts with itself.
* Update `mru` per committed line; after the last beat go to `DRAIN` (wait
  for `!fetch_busy` before the next request), as today.
* `fillLine` advances by `beats` after a completed burst.

### 8.3 Tail, bounded lookahead, and prefetch policy

* The stream is straight-line, so prefetch is sequential. Keep a fill-head
  line pointer `fillNext` (initialised to 0 at `start`).
* **Bounded runahead is mandatory.** The 16-line, 2-way cache holds at most
  two lines per set; letting `fillNext` run to `lastLine` would evict lines
  the PC has not read on any program longer than the cache, turning the
  steady state back into demand fills. Define:

  ```
  LEAD = 8 lines (128 instructions)
  start a prefetch burst only when
      fillNext + BEATS <= fpcLine + LEAD          (BEATS = 4)
  ```

  With `BEATS = 4` that means the burst starts when the fill head is at most
  4 lines ahead of the PC. The runway is 4 lines = 64 words = 64 cycles; the
  burst takes ~46 + 4 = 50 cycles, so it completes before the PC needs it,
  and the last line written is at most `fpcLine + 7` (inside the safe lead).
  The invariant is `fillNext − fpcLine <= LEAD`.
* **Why this is size-agnostic.** The 16 lines are a sliding window over a
  straight-line stream, not a working set. With the lead bounded by 8 lines,
  the victim of each write is at least 8 lines behind the PC (2-way, 8-set
  eviction picks a line ≥ 16 behind the fill head), so only already-consumed
  lines are evicted. Program size changes only how many times the window
  slides; the fill-per-instruction rate and the steady-state cycles per
  instruction are constant. The first fill (~46 cycles) and the final-line
  tail are the only size-dependent terms. This is verified by a size sweep,
  not assumed.
* Full burst only when `fillLine + 4 <= lastLine`; otherwise request
  **1 beat**. This prevents reads past the program and keeps the tail
  correct for `progLen` 1…65,535 words.
* Demand fills bypass the lead gate (they are already at the PC) but still
  respect the 4-line tail clamp.
* `progLenReg == 0` fetches nothing.
* The 16-line (256-instruction) cache covers 4 bursts; the IFQ stays 8.

### 8.4 Expected result

* 4 beats per request, ~46 + 3 cycles per 64 instructions →
  **~0.8–1.0 cycle/instruction** in steady state, *provided the lead gate
  above keeps the fill head from overrunning the cache*.
* 1,024 NOPs: **16 requests / 64 beats**, ~1.0–1.3 k cycles.
* 256-round reduce shape (≈8.7 k words): the bounded head must sustain the
  same ~1 cycle/instruction; the acceptance explicitly measures this long
  program, not only the 1 k NOP case.
* Fetch is then no longer dominant; expect MACs/cycle to rise from 79
  toward the round-cadence limit (~300–450 MACs/cyc, ~30–45 % peak) with the
  measured value recorded, not assumed.

### 8.5 First-line pre-warm

**Intent.** The session cycle counter starts at the start edge, and the
driver sequence is `stage CODE → PROG_LEN → START(1) → START(0)`. The ctrl
writes cross the CDC and the host MMIO gap is far longer than a fill
(~46 cycles), so the frontend can fetch the first burst during the
`PROG_LEN → START` window and have line 0 valid before the session begins.
The pre-warm costs nothing in the counted session.

**Trigger.** On `ctrl_we && addr === 0x14` (PROG_LEN): invalidate the cache
(as today), latch `progLenReg`, reset the fill FSM, and set `warmValid`.
While `warmValid`, the fill FSM may issue a normal fill **without
`running`**: `min(BEATS, lastLine)` beats starting at line 0. On the last
beat, `warmValid := false`; `fillState → DRAIN/IDLE`. No IFQ enqueue or
instruction issue occurs before `running`.

**Start and invalidation.** `cacheInvalidate` no longer includes `0x0`
(CTRL); it keeps `0x14` (PROG_LEN) and `0x4` (FRAMES). `startEdge` still
resets `pc/fpc/IFQ`; if line 0 is valid, the first word is enqueued on the
first running cycle. If `startEdge` arrives while the warm fill is in
flight, the engine's session flush aborts it via the §7.0 abort/epoch fix;
lines already committed remain valid (they belong to the current
`PROG_LEN`), and the session demand-fills only what is missing. Stale beats
from the aborted request are ignored by the engine's
`dmaHead.valid && inFlight && dir === 3` gate, and a new fill queues behind
the still-busy DMA, so the no-stale-beat property does not depend on timing.
The warm is best-effort, never a correctness dependency.

**Why not invalidate on `start`.** `start` does not change the program. The
host must stage CODE and write `PROG_LEN` for a new program; that is the
invalidation point. A restart without `PROG_LEN` reuses the same program and
may demand-fill (safe).

**Warm depth.** One 4-beat burst (4 lines = 64 instructions) by default: it
fits the PROG_LEN→START window and covers the first 64 instructions of any
program; the normal prefetch takes over before they are consumed. The depth
is a parameter if measurements show a larger host gap; keep 4 unless it
hurts.

**Memory ordering.** The driver's CODE staging DMA completes before the ctrl
`PROG_LEN` write (the host waits for the transfer), so the warm reads
committed DDR. `fetch_busy` may still be high at the start edge; the
engine's issue/drain waits naturally.

## 9. Correctness invariants

1. A cache line is valid only after all 16 words (or the clamped tail beats)
   have been written.
2. `start` and config writes invalidate the cache; no stale line may survive
   into a new session.
3. The fill address is 64-byte aligned; `dir=3` AR uses SIZE=6 and
   `arlen = beats−1`; beats are written in AXI order.
4. No fetch is issued for `fpc ≥ progLenReg`; the tail never reads past
   `lastLine`.
5. The IFQ only enqueues hit words for `fpc < progLenReg`; a cache miss
   stalls the enqueue, never emits a wrong word.
6. Existing `pc`, `issuedWord`, `errPc`, `errInstr`, `framesDone`, `done`,
   and `drain` semantics are unchanged.
7. **Bounded runahead:** `fillNext − fpcLine <= LEAD` (8 lines), and a burst
   never writes a line at or beyond `fpcLine + LEAD`; otherwise unread lines
   are evicted on long programs.
8. **Abort/epoch:** an in-flight fill at `start` or an illegal halt cannot
   leave `fillPending`/`fillState` stuck; the next session starts with an
   empty, invalidated cache and can fetch.
9. **Size independence:** for a straight-line program of any supported
   length, fills ≈ `words/16 + O(1)` and the steady-state cycles per
   instruction is constant; no working-set/capacity cliff.
10. **Pre-warm validity:** a cache line is valid only for the currently
    latched `PROG_LEN`; `PROG_LEN`/`FRAMES` writes invalidate, `start` does
    not.
11. **Warm is best-effort:** if a warm fill is aborted by `start`, no
    partial/stale line is marked valid and the session demand-fills.

## 10. Verification

### 10.1 Simulation (red→green)

* **Frontend fetch geometry** (`NpuProgramEngineFrontendSpec` harness, with
  `readLatency = 40` to model DDR):
  * 1,024-NOP program: assert fill requests = 64 (A) / 16 (B);
  * program lengths 1, 15, 16, 17, 63, 64, 65, 255, 256, 257 words — verify
    results and that no read crosses `lastLine`;
  * two different programs back-to-back in one simulation — second program
    must not see stale cache words;
  * illegal instruction on a line boundary and in the second burst —
    `errPc`/`errInstr` correct;
  * **illegal mid-stream then restart**: a long program with an illegal
    instruction after ≥ 2 bursts (prefetch in flight at the halt), followed
    by a valid program; the second session must complete (abort/epoch);
  * **start with a fill outstanding** (new `start` while the previous
    session's fill is queued/in `READ`), then a valid program must complete;
  * **long program**: ≈4 k words with `readLatency = 40` (simulation budget);
    assert cycles ≤ ~1.5 × words and that fills stay ≤ words/16 + constant,
    i.e. the bounded lead does not thrash. The hardware sweep covers the
    ≈8.7 k, 32 k and 60 k cases.
  * **size sweep**: 256, 1,024, 2,048 and 4,096 words; assert fills ≈
    words/16 + O(1) and the marginal cycles/instruction between sizes is
    within ±10% (flat slope, no capacity cliff).
  * **pre-warm**: write PROG_LEN without `start` — assert a warm fill
    commits lines 0..3 and no instruction is issued; then `start` — line 0
    is a cache hit on the first fetch cycle; stage a new program and verify
    the warm uses the new CODE; issue `start` immediately after PROG_LEN
    (warm aborted) — execution is still correct via demand fill.
* **DMA** (`NpuDmaEngineSpec`): `dir=3` 1-beat and 4-beat reads; beat order,
  `instr_valid`/`instr_last` count, `done` timing, ARLEN.
* **Engine regressions**: `NpuProgramEngineFrontendSpec`,
  `EnginePerfCounterSpec`, `EngineThroughputSpec`, `Streamed*`,
  `Capture*`, `CrossMatmulPipelineSpec` — all green.

### 10.2 Hardware

1. `make build` → `top.sv`; synth+opt, ML-off place/route at 100 MHz with
   the existing retry loop; WNS ≥ −1.0 ns.
2. Program, `py-deploy` selftest, `py-test-hw` 8/8.
3. Perf sweep: NOP 256/1024, MMA chunk 32/64/128, reduce rounds
   1/4/16/64/256, and **program-size sweep** 1 k / 8 k / 32 k / 60 k NOPs.
4. Acceptance:
   * Phase A: 1,024 NOPs ≤ 3.5 k cycles; 64 fills.
   * Phase B: 1,024 NOPs ≤ 1.5 k cycles; 16 requests; the ≈8.7 k-word
     program must also sustain ≤ ~1.5 cycles/instruction (bounded lead),
     and the 256-round shape MACs/cycle ≥ 250 (record actual).
   * **Size independence**: for the 1 k…60 k NOP sweep, fills ≈ words/16 +
     O(1), and average cycles/instruction at 32 k and 60 k within 10 % of
     the 1 k value (the constant startup/tail is the only size term).
   * **Pre-warm**: the 36-instruction program's cycles drop by ≥ 35 vs
     Phase B without warm (record actual); a 1-instruction program loses the
     fill latency but remains drain/handshake-bound; the 1 k…60 k slope is
     unchanged.

## 11. Risks and mitigations

| Risk | Mitigation |
|:--|:--|
| 512-bit 16:1 read mux hurts the +0.003 ns timing | Registers→LUTRAM/BRAM with a 1-cycle read + lookahead; re-run timing; gate −1.0 ns |
| 6 K extra FFs increase congestion | Measure utilization; the device has ~1.2 M FFs; drop to BRAM if needed |
| DMA 4-beat protocol bug corrupts code | DMA spec + frontend edge-case tests; FETCH_STATS in sim and HW |
| Unbounded prefetch evicts unread lines on long programs | `fillNext + BEATS <= fpcLine + LEAD` gate; ≈4 k-word sim + 1 k…60 k HW sweep |
| Program-size cliff (cache capacity) | Sliding-window lead gate + §9 invariant 9 + 1 k…60 k size sweep; no working-set assumption |
| In-flight fill at `start`/illegal halt wedges the next session | §7.0 abort/epoch fix + illegal-then-restart and start-with-fill-outstanding tests |
| Tail burst reads out of range | `fillLine + 4 <= lastLine` gate; 1-beat tail |
| Warm fill aborted by `start` | Best-effort warm + §7.0 abort; demand fill; start-immediately-after-PROG_LEN test |
| Warm reads stale CODE after a program change | Invalidate on PROG_LEN/FRAMES; host protocol stages CODE before PROG_LEN; new-program test |
| Warm DMA contends with a very early start | One 4-beat burst; normal MMIO gap covers it; fallback is a demand fill |
| Stale cache after a new `start` | Invalidation moved to PROG_LEN/FRAMES; back-to-back-program test |
| Vivado LGBM crash (random) | Build retry loop; per-attempt logs |
| Phase B slows the shared data DMA | Fill requests are short bursts; measure py-test-hw + data-path regressions |

## 12. Rollout

1. **Commit A**: frontend line widening + DMA 512-bit instruction return +
   tests + docs. Build, measure, record.
2. **Commit B**: burst protocol + 4-beat fill + tests + docs. Build,
   measure, record.
3. **Commit P**: pre-warm on `PROG_LEN` + invalidation move + tests. Build,
   measure (small-program cycles and the 1 k…60 k slope), record.
4. Update `docs/implementations/FPGA_VU9P.md` with the measured before/after
   table (cycles, fills, IPC, MACs/cycle, pre-warm delta).
5. No driver/ISA changes; `test_perf.py` stays valid (per-session feeds).
6. Programs > 65,535 words are out of scope (§5.1); a wide-PC phase can
   follow without reworking this fetch design.

## 13. Left to the builder

* Register vs LUTRAM/BRAM implementation of the 16 × 512-bit line array and
  the exact read-mux structure.
* Fill FSM/beat-counter coding details and whether `WAIT`/`DRAIN` are
  collapsed.
* How the warm trigger shares the normal fill FSM (a small `WARM` state vs
  a `warmValid` qualifier) and the exact `PROG_LEN`-write pulse handling
  through the ctrl CDC.
* Whether to raise the IFQ depth (8) after Phase B; not required by the
  measured budget.
* Exact simulation thresholds for the fetch-count assertions and the
  warm-hit check.
* Whether to expose a beat counter in the debug map (0x10 already exposes
  prefetch/miss; no new ctrl register is required).
