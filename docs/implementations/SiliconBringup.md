# Silicon bring-up log — chisel-npu engine on xc7k480t

This is the working log of bringing the streamed-dispatch engine
(`NpuProgramEngine`, K=16, N=8; the current FPGA build uses window depth
**W=4**) to **deterministic** behaviour on the
xc7k480tffg1156-2 board (XDMA 4.2 PCIe, dual-channel MIG DDR3), culminating
in the fixes documented in `docs/designs/04.streamed-issuing.md`.

## Final verified state (2026-08-20)

| Check | Result |
|:---|:---|
| Fabric clock | 100 MHz (timing-closed, WNS ≈ +0.03 ns) |
| One-shot mma session | **100/100 correct captures, 0 wedges** |
| 3-mma + mma.last session | **50/50 correct** (per-capture running sums) |
| HW pytest (`test_program_engine.py`) | **4/4 PASS** (one-shot, multi-column, illegal-halt, status) |
| Simulation regression suite | **all green**, incl. `CaptureDeterminismSpec` (200 back-to-back runs) |

## Symptom timeline and root causes

### A. The engine wedge (`NPUTimeoutError`, `busy=1`, pc=4)

The first blocker: the run() never asserted `done`; the engine sat at
pc=4 (the `vse32`) with `busy=1` and `drain` deasserted, ~60% of the time.

ILA probing of the DMA FSM showed the store's write **beats were accepted
but the BRESP (`bvalid`) was dropped** on the MIG/clkconv path, wedging the
DMA in WRESP — intermittent, independent of clock/timing (a timing-clean
100 MHz build wedged identically), which proved it was a functional bug, not
marginal timing.

Root cause chain (each fix removed one failure mode):

1. **`useCapData` detection required a pending prior producer.**  When the
   mma's `clctD` retired the producer before the store word entered the
   window (variable MIG read latency), the store missed the capture: it read
   the register file instead of popping the capture queue, the mma's capture
   sat in capQ forever, and `drain` never asserted.  *Fix:* detect capture
   consumers by `vxWrCap` alone (it survives producer retirement).
2. **No session reset.**  A wedged run's leftover queue state contaminated
   the next run, and a spurious capQ pop underflowed the counters
   (`wcount=0xF`, `capq=0x1F` observed in the drain-state registers).
   *Fix:* a full session reset (all slot fields, scoreboard masks, queues,
   boundary) on the frontend start edge.

### B. The wrong captures (clean scalar multiples `a·R`, R ∉ b)

Even timing-clean, ~12–16% of captures were `a·R` with R outside the staged
`b` vector (doubles like 2·114, small/garbage values, or all-zero).  The
simulation reproduced it deterministically (iter 6 of a back-to-back loop),
which allowed full trace visibility:

1. **Collector `cnt` phase accumulated across runs.**  The free-running
   counter wrapped on a bad diagonal every ~8 runs.  *Fix:* deterministic
   `cnt` reset at each collection window.
2. **The `keep=0` PE reset fired before the mma's capture.**  With the
   `4K−1` boundary countdown, the reset landed one tick before the mma's
   clct, zeroing the array before the capture read it.  *Fix:* `6K−1`
   countdown so the reset fires after the capture's `clctD`.
3. **`mma.last` could overtake the mma.**  It has no operand dependencies,
   so it could issue first, start the boundary countdown, and gate the mma
   out entirely (the mma never fed → zero array).  *Fix:* `mma.last` waits
   for prior pending non-last MMAs.
4. **The fetch fill's placeholder `slotIdx=0` retired a live slot.**  The
   fetch-fill DMA completion unconditionally marked `slots(0).done`, which
   spuriously retired whichever real instruction owned ring slot 0 (the
   mma) — the mma never fed → zero array.  *Fix:* fetch fills never touch
   window slots.
5. **A stale clct from a previous session retired a live slot.**  The
   `clctD` completion acted even when the vd FIFO was empty, reading a stale
   FIFO entry's slotIdx.  *Fix:* gate completions on `fifoCount != 0`.

### C. Environment quirks encountered along the way

* **Never-written DDR reads stall** (c2h transfer timeout).  Reading a DDR
  region that was never written stalls the XDMA transfer; once written, it
  reads fine.  The driver now zero-clears the OUT window before each run so
  the read-back never touches unwritten memory.
* **FPGA-side XDMA/DMA state survives host reboot.**  A wedged engine or a
  hung XDMA SG transfer is only cleared by re-programming the FPGA (JTAG
  SRAM load), not by a host reboot.
* **Build placement-seed lottery.**  The design's timing at 200 MHz was
  marginal (WNS ranged +3.1 ns to −4.1 ns across identical builds).  The
  100 MHz fabric clock closes deterministically and makes the ILA builds
  practical (~1.5 h vs many hours of router grinding).

## Tooling added

* **Debug registers** in ctrl_lite: `0x30` (drain/bnd/wcount/win/dmaq/capq),
  `0x34` (dma-done), `0x38` (clct) — read the stuck queue while `busy=1`.
* **ILA probe builds** for the DMA FSM (`state` + write-handshake shadows)
  and the systolic b-path (`reg_v` chain + collector `cnt`), and the capture
  of a wrong run's feed.
* **`CaptureDeterminismSpec`** — 200 back-to-back runs asserting every
  capture is a valid b-column (the regression that first reproduced and now
  guards the fix).
* **`LateStoreCaptureSpec`** — the late-store capture-consumer regression.

## Current RTL additions (beyond the original design)

* `session_reset` input on the engine, pulsed at the frontend start edge.
* `dbg_*` status/debug outputs (mma out, feed rs1, slot state, collector
  cnt) wired through the frontend for bring-up.
* `CAPTURE_DELAY = 16` (was 4).
* Boundary countdown `6K−1` (was `4K−1`).

## How to reproduce / verify

```bash
# simulation regression
sbt "testOnly engine.CaptureDeterminismSpec engine.StreamedChainedSpec"

# hardware (after flashing ip/vivado/xc7k480t/top_npu_engine.bit, 100 MHz)
.venv/bin/python -m pytest tests/test_program_engine.py -v
```
