# NPU Bring-up on Virtex UltraScale+ (`xcvu9p` / Alivu9p)

Worked example of bringing the Chisel program engine up on a **Virtex
UltraScale+ `xcvu9p-flgb2104-2-e`** board (the "Alivu9p" platform) and
programming it **remotely** over a networked `hw_server` — no local JTAG.

The Chisel RTL (`top.sv`, the K=16 program engine at window depth **W=4**) and
the [`chisel_npu_py`](https://github.com/mpskex/chisel-npu/blob/main/drivers/chisel_npu_py/README.md) userspace driver
are the same as the Kintex-7 platform; only the FPGA wrapper differs.

Backing design notes: [`ip/vivado/xcvu9p/README.md`](https://github.com/mpskex/chisel-npu/blob/main/ip/vivado/xcvu9p/README.md)
(full build/remote-programming recipe).

## Platform

| Item | Value |
|:-----|:------|
| FPGA | `xcvu9p-flgb2104-2-e` |
| PCIe | XDMA v4.2, Gen3 ×8 (8 GT/s), BDF `10:00.0` (root port `00:01.1`) |
| VID:DID | `10ee:903f`, subsystem `0007` |
| DDR4 | 4× MIG UltraScale+ (`MT40A1G16WBU-083E`, 72-bit, ECC), 300 MHz ui_clk |
| Fabric clock | 200 MHz (`clk_wiz_fabric` MMCM from `axi_aclk` 250 MHz) |
| JTAG | Digilent cable on the server, `hw_server` at `10.16.0.35:3121` |

## Architecture

The NPU is the **S01** user master of the reference DDR4 crossbar; the XDMA
host path is S00:

```
XDMA M_AXI (512b @250) ─────────────────────────────► ddr_top S00 ─► 4× DDR4
XDMA M_AXI_BYPASS (512b @250)
  └► byp_dw (512→32 @250) ─► byp_pc (AXI4→Lite @250) ─► ctrl_lite @250
                                                          └► npu_ctrl_cdc (toggle/ack) ─► engine ctrl @100
NPU engine (128b @100)
  └► npu_cc (100→250) ─► npu_dw (128→512 @250) ─────► ddr_top S01 ─► DDR4 xbar
```

The engine runs at **100 MHz**; at K=32 / W=8 it needs the full 10 ns period
(the 200 MHz build closed at WNS −3.567 ns with ~98k failing endpoints).
The 250 MHz `axi_aclk` carries `ctrl_lite` and the AXI bypass; ctrl register
accesses cross into the engine through `npu_ctrl_cdc` (see fix 5).

## The integration fixes

### 1. DDR4 MIG rejected narrow bursts

The engine's AXI master is **128-bit** and therefore issues **16-byte** beats.
The DDR4 MIG defaults to `AXI_Narrow_Burst=false` (full-width 512-bit bursts
only). With narrow bursts disabled:

- the engine's `illegal instruction at pc=65535 (word=0xffffffff)` was the
  instruction **fetch** reading back `0xFFFFFFFF` (the MIG dropped the
  sub-512-bit read);
- multi-beat vector transfers (`vle16/32`, `vse16/32`) were truncated to their
  first 16 bytes — the "only 4 of 16 lanes moved" symptom.

**Fix**: `CONFIG.C0.DDR4_AxiNarrowBurst = true` on the `ddr4_0` MIG, then
re-synthesize its OOC checkpoint. The `128→512` converter then stays unpacked
(`PACKING_LEVEL=1`).

A tempting but **wrong** workaround is to force the converter to pack
(`PACKING_LEVEL=2`): a single 16-byte request cannot pack into a 512-bit beat,
so it re-breaks the fetch.

### 2. Engine clock: 100 MHz for the K=32 / W=8 engine

The current top (`NpuProgramEngineFrontend(K=32, N=8, W=8)`) does not close
timing at 200 MHz: the store-data path (fifoCount → slots_done → dmaQ →
register file → `npu_cc`) reported WNS −3.567 ns with 97,774 failing
endpoints, which also produced the "wrong upper lanes" hardware failures.
At 100 MHz the same design closes (WNS +0.05 ns).

An earlier 100 MHz attempt appeared to break instruction fetch
(`illegal instruction at pc=65535`).  That was not the engine clock: the
AXI4-Lite **write response** was crossing the 250→100 MHz `byp_cc`
converter and hanging, so `PROG_LEN`/`START` never reached the engine.
Fix 5 removes that crossing; the engine now runs correctly at 100 MHz.

### 3. Stale incremental synthesis silently ignored `top.sv`

`synth_1` had **auto-incremental synthesis** enabled with a reference checkpoint
under `prj.srcs/utils_1/imports/synth_1/`.  After regenerating `top.sv`, the
tool reused ~99.9996% of a previous engine netlist (the log shows
`NpuProgramEngineFrontend__GC0_#REUSE#`), so RTL changes never reached the
bitstream.  `build_npu_vu9p.tcl` now clears `AUTO_INCREMENTAL_CHECKPOINT` and
forces a re-read of `top.sv` (remove + re-add).

### 4. Fabric reset-synchronizer recovery violation

`hw_platform.v` used the high-fanout XDMA `axi_aresetn` (`axi_aclk` domain,
fo ≈ 1600) as the async reset of the `clk_fabric` reset synchronizer, giving a
−0.87 ns recovery violation across 82 endpoints.  Fix: register a local
`ASYNC_REG` copy (`axi_aresetn_loc`) so the cross-domain path is short, and
`set_false_path` the synchronizer's async reset (`pin.xdc`) — its 2-FF chain
resolves the CDC.  Combined with the placement directive below, the design now
meets all user timing constraints.

### 5. AXI-Lite ctrl write response hung across the 250→100 MHz converter

After moving the engine to 100 MHz, ctrl **reads** worked but a ctrl **write**
never returned a response: the XDMA bypass AXI-Lite write (`aw`/`w`) crossed
`byp_cc` 250→100, while the `b` response had to cross back 100→250.  The
converter dropped the response, so the host blocked in `pwrite` and the
engine saw no `PROG_LEN`/`START` (fetch then read `0xFFFFFFFF`).

Fix (option 2): move the ctrl slave into the 250 MHz domain and cross only the
register access itself:

* `npu_engine_ctrl_lite.v` now runs on `axi_aclk` (250 MHz) and issues
  `wr_addr/wr_en/wr_data` and `rd_addr/rd_en` requests; the read FSM waits for
  a CDC ack before returning `rdata`.
* `npu_ctrl_cdc.v` (new) is a toggle/ack bridge: writes latch addr+data and
  flip a toggle (the 100 MHz side sees the edge and pulses `ctrl_we` with
  stable data); reads flip a request toggle and the engine flips an ack
  toggle after sampling the combinational `ctrl_rdata`.
* `hw_platform.v` deletes `byp_cc`; `byp_pc` now runs at `axi_aclk` fed
  directly by `byp32_*`, and `u_npu` gets `aclk_ctrl`/`aresetn_ctrl`.
* `cdc.xdc` adds `set_clock_groups -asynchronous` between
  `clk_out1_clk_wiz_fabric` and `axi_aclk`.  The hand-written CDC is not an
  IP with built-in clock groups, and without this the 100→250 data path was
  timed and failed (WNS −1.733 ns).

The ctrl-fix build closed at **WNS +0.054 ns**; the final build (with the
per-session feed-counter clear) closed at **WNS +0.003 ns**, 0 unconnected
nets, and the selftest plus the full hardware suite pass.

### Placement / timing note (multi-SLR)

`xcvu9p` is a multi-SLR device. The extra NPU buses make the GTY/PCIe-left-edge
region congested under the default placer, so the build uses
`place_design -directive ExtraTimingOpt` (set in `build_npu_vu9p.tcl`).
A Pblock around the NPU does **not** help — it forces SLR (Laguna) crossings and
the routing blows up; the engine was made to fit by reducing it (W=4 + the
2-stage feed pipeline), not by floorplanning.

With that, the engine fabric domain closes at 100 MHz (WNS +0.054 ns on the
ctrl-CDC build, +0.003 ns on the feed-counter build; 0 failing endpoints /
0 unrouted nets).  Implementation runs with the ML estimators disabled
(`place.3d.congestionML 0`, `place.disableMlde 1`) and single-threaded
placement — Vivado 2025.2 still segfaults randomly in
`LgbmTree::~LgbmTree` (`HCMBoostTreeEstimator` destructor), so the flow
retries the implementation.  Timing must be re-checked after every RTL change
because the margin is sub-0.1 ns.

## Instruction-supply streaming (A+B+P)

The engine was **fetch-bound, not execute-bound**: a fill requested one 64-byte
DMA beat but `INSTRW` returned only one 16-byte **quarter** (4 words) of it, at
~45.7 cycles per quarter (≈11.4 cycles/instruction). Three quarters of every
fetched beat were discarded. The front end now streams instructions:

* **Phase A — 16-word I-cache line.** The whole 64-byte DMA beat is stored as a
  **line** of 16 words (tag/set/victim from the absolute line index;
  **8 sets × 2 ways = 16 lines**, a sliding window). All 16 instructions of the
  beat land in the cache, and the next three 16-byte quarters (4 words each)
  are served from that line instead of re-reading the same DDR beat.
* **Phase B — 4-beat burst fill.** A fill requests **4 consecutive beats**
  (256 B = 64 instructions) and streams them into consecutive lines. The fill
  head `fillNext` leads the fetch PC by a **bounded** lookahead so the array
  stays a sliding window, not a working set — steady-state
  cycles/instruction is independent of program size; only the final-line tail
  depends on `progLen`.
* **Phase P — first-line pre-warm.** The first burst is fetched on the
  `PROG_LEN` write (`0x14`), while the host is still issuing `START`; the
  pre-warm is outside the counted session, so a small program's first
  instruction is already cached. Invalidation moved off `CTRL` (`0x0`) onto
  `PROG_LEN`/`FRAMES`, so the warm line survives `START`.
* **Abort/epoch fix.** `fillPending` is cleared on `session_reset` and on
  `illegalAny`, the frontend resets its fill FSM on the start edge, and
  `fillPush` is gated by `!session_reset && !illegalAny`. An in-flight fill at
  a reset or illegal halt can therefore no longer wedge every later session.
  Stale beats are rejected in the engine (`dmaHead.valid && inFlight &&
  dir === 3`), and a new fill queues behind a still-busy DMA.

Design note: [`docs/superpowers/specs/2026-11-13-instruction-supply-streaming-design.md`](../superpowers/specs/2026-11-13-instruction-supply-streaming-design.md).

## Build

```bash
make build                                   # Chisel -> top.sv

# one-time IP setup: DDR4 narrow burst, XDMA bypass, 100 MHz MMCM, converters
vivado -mode batch -source ip/vivado/xcvu9p/scripts/setup_platform.tcl

# current flow (Vivado 2025.2 in the Ubuntu 22.04 runtime container):
#   synth + opt_design -> hw_platform_opt.dcp
#   ML-off place/route at 100 MHz, reads src/cdc.xdc, WNS gate -1.0 ns
vivado -mode batch -source build/latest_synth_opt.tcl
vivado -mode batch -source build/impl_100mhz_nomlcong.tcl
# -> ip/vivado/xcvu9p/top_npu_vu9p.bit
```

`ip/vivado/xcvu9p/scripts/build_npu_vu9p.tcl` is the legacy single-shot
200 MHz flow (kept for reference).

## Remote programming (no local JTAG)

The board is programmed from the **dev host** through the server's
`hw_server`; `xsdb` cannot enumerate pure-FPGA targets, so use Vivado:

```tcl
open_hw_manager
connect_hw_server -url 10.16.0.35:3121 -allow_non_jtag
current_hw_target [lindex [get_hw_targets] 0]
open_hw_target
current_hw_device [lindex [get_hw_devices xcvu9p_0] 0]
set_property PROGRAM.FILE {ip/vivado/xcvu9p/top_npu_vu9p.bit} [current_hw_device]
program_hw_devices [current_hw_device]
```

## Host bring-up (`10.16.0.35`)

The AMD FCH does not enumerate the card after a hot JTAG load:

1. JTAG-load the bitstream (above).
2. `sudo /sbin/reboot` — FPGA SRAM survives a warm reboot; PCIe then trains.
3. Load the **out-of-tree** Xilinx XDMA driver. The in-kernel `xdma.ko` is a
   dmaengine-only driver with **no** `/dev/xdma0_*` char nodes:
   ```bash
   git clone https://github.com/Xilinx/dma_ip_drivers
   cd dma_ip_drivers/XDMA/linux-kernel/xdma
   cp -n ../include/libxdma_api.h .    # modern kbuild ignores EXTRA_CFLAGS
   make
   sudo rmmod xdma 2>/dev/null; sudo insmod xdma.ko
   ```
4. Install udev/permissions — `make py-deploy` does this. Reload the module
   after every reboot.

### Branch-specific bring-up (`fangrui/refine-dispatch-frontend`)

This branch does **not** carry the `feat/general-fpga-clean-flow` board
targets (no `make bringup-fpga BOARD=...`), and `.env.sh` defaults
`FPGA_HOST` to `10.16.0.31`. Address the Alivu9p host explicitly:

```bash
FPGA_HOST=10.16.0.35 SSH_IDENTITY=~/.ssh/id_vu9p make py-deploy
FPGA_HOST=10.16.0.35 SSH_IDENTITY=~/.ssh/id_vu9p make py-test-hw
```

Two board-specific gotchas:

* **JTAG-load from the Ubuntu 22.04 Vivado container.** The host's own Vivado
  crashes; run the container image and program `top_npu_vu9p.bit` from there.
* **Warm-reboot the host after a JTAG load.** PCIe only re-enumerates on
  reboot; otherwise probe fails with `map_bars: Failed to detect XDMA config
  BAR` (driver probe `-22`) and no `/dev/xdma0_*` nodes appear. Once the host
  is back, re-run the bring-up without touching the fabric:

  ```bash
  python3 tool/hw/bringup_ssh.py --host 10.16.0.35 \
      --identity ~/.ssh/id_vu9p --skip-flash --skip-jtag --skip-pytest \
      ip/vivado/xcvu9p/top_npu_vu9p.bit
  ```

## Validation

```bash
FPGA_HOST=10.16.0.35 make py-deploy     # rsync + build + udev + selftest
FPGA_HOST=10.16.0.35 make py-test-hw    # 8/8 PASS
```

Selftest: section round-trip + program run `pc=7 OK`. Hardware suite:

| Test | Result |
|:-----|:-------|
| `test_copy_program_roundtrip` | PASS |
| `test_vr_roundtrip_through_run` | PASS |
| `test_transfer_validation` | PASS |
| `test_ipc_perf_counters` | PASS |
| `test_one_shot_session` | PASS |
| `test_session_captures_columns` | PASS |
| `test_illegal_instruction_halts` | PASS |
| `test_status_after_program` | PASS |

Perf registers (ctrl_lite, read via `ChiselNPU.run()["perf"]`):

| Addr | Name | Meaning |
|:-----|:-----|:--------|
| `0x40` | CYCLES | session cycle count, latched at done |
| `0x44` | MACS | useful K² MACs (excludes `mma.last`), latched at done |
| `0x1C` | DBG_MMA | accepted MMA feeds incl. `mma.last`, cleared at session start |

`mmaAccCnt` (0x1C) is cleared by `session_reset`; before that fix the live
counter accumulated +K+1 per run, so a second session on the same bitstream
reported 264 instead of 33 feeds.  `EnginePerfCounterSpec` and the rerun check
in `test_perf.py` guard this.

Measured on the final A+B+P bitstream (`ip/vivado/xcvu9p/top_npu_vu9p.bit`,
built 2026-11-13 22:56, `POSTROUTE_WNS` **+0.056 ns**, 0 unrouted nets; `make
py-test-hw` 8/8 PASS, selftest PASS). Scope: both tables below are this final
A+B+P bitstream; the per-phase A/B/P attribution was **not** separately built
(that costs a bitstream per commit). The final build needed **0 retries**
(synthesis attempt 1, implementation attempt 1). At K=32 the array peak is
K² = **1,024 MACs/cycle**, so the percentile column is relative to 1,024.

NOP program-size sweep at K=32, from
`drivers/chisel_npu_py/tool/fetch_sweep.py`; `cycles` is the session counter
and `burst requests` the `FETCH_STATS` (`0x10`) delta — fetch burst requests,
both prefetch and demand. The tail burst is shortened to 1 beat, so not every
request is 4 beats:

| words | cycles | cycles/instr | burst requests | IPC |
|---:|---:|---:|---:|---:|
| 1,024 | 1,026 | 1.002 | 16 | 0.998 |
| 8,192 | 8,275 | 1.010 | 128 | 0.990 |
| 32,768 | 33,204 | 1.013 | 512 | 0.987 |
| 60,000 | 60,799 | 1.013 | 939 | 0.987 |
| 65,535 | 66,409 | 1.013 | 1,024 | 0.987 |

Reduce-round shape (2 loads + 32 `mma` + `mma.last` + `vse` per round):

| rounds | instructions | cycles | IPC | MACs/cycle | % of 1,024 peak |
|---:|---:|---:|---:|---:|---:|
| 1 | 36 | 242 | 0.149 | 135.4 | 13.2% |
| 4 | 138 | 635 | 0.217 | 206.4 | 20.2% |
| 16 | 546 | 2,027 | 0.269 | 258.7 | 25.3% |
| 64 | 2,178 | 7,595 | 0.287 | 276.1 | 27.0% |
| 256 | 8,706 | 29,867 | 0.291 | 280.9 | 27.4% |

Before this work the previous bitstream took **631 cycles / 51.9 MACs/cyc** on
the 36-instruction round and **106,068 cycles / 79.1 MACs/cyc** on the
256-round shape. The final bitstream: 36 instructions **631 → 242 cycles**
(51.9 → 135.4 MACs/cyc) and 256 rounds **106,068 → 29,867 cycles**
(79.1 → 280.9 MACs/cyc). The NOP slope is flat from 1 k to 65,535 words
(**1.002–1.013 cycles/instruction**, 0.987–0.998 IPC), so the steady state no
longer depends on program size; the ~0.013 cycles/instruction residual above
1.0 is the amortised per-burst fill cost plus the final-line tail.

Run-to-run variance: on the final bitstream the 36-instruction perf test
measured 242 cycles on two back-to-back runs, identical, with `feeds=33` on
both (idempotent). The previous bitstream varied 624–634 cycles on the same
program; the **631** before-figure is representative of that range.

## MACs/cycle: feed scaling and the path to 1K

Measured on the same A+B+P bitstream with
`drivers/chisel_npu_py/tool/fetch_sweep.py`.  The source tree also carries the
later abort-hardening commit (`46259b2`), which is schedule-neutral and is not
in this bitstream; the numbers below are the measured artifact.  At K=32 the
array peak is **K² = 1,024 MACs/cycle**.

### Feed scaling (one capture at the end)

Program: two `vle8` loads + `n` back-to-back `mma` + one `mma.last` + one
`vse32`.  The largest legal `n` is 65,531: 2 + n + 1 + 1 = 65,535 words, the
16-bit-pc program maximum.  A pure feed stream has no per-round capture/store
boundary, so this isolates the array/feed/issue path.

| feeds | instructions | cycles | MACs/cycle | % of 1,024 |
|---:|---:|---:|---:|---:|
| 1 | 5 | 211 | 4.9 | 0.5% |
| 16 | 20 | 226 | 72.5 | 7.1% |
| 32 | 36 | 259 | 126.5 | 12.4% |
| 64 | 68 | 313 | 209.4 | 20.4% |
| 128 | 132 | 405 | 323.6 | 31.6% |
| 256 | 260 | 511 | 513.0 | 50.1% |
| 512 | 516 | 767 | 683.6 | 66.8% |
| 1,024 | 1,028 | 1,279 | 819.8 | 80.1% |
| 2,048 | 2,052 | 2,311 | 907.5 | 88.6% |
| 4,096 | 4,100 | 4,381 | 957.4 | 93.5% |
| 8,192 | 8,196 | 8,559 | 980.1 | 95.7% |
| 16,384 | 16,388 | 16,807 | 998.2 | 97.5% |
| 32,768 | 32,772 | 33,468 | **1,002.6** | 97.9% |
| 65,531 | 65,535 | 66,656 | **1,006.7** | 98.3% |

The curve is the startup/ramp (a single feed is dominated by the load/fetch and
capture latency); from ~4 k feeds the feed rate is flat and **exceeds 1,000
MACs/cycle** at 32,768 feeds.  The array, feed pipeline, tag ring, vd FIFO,
and instruction fetch can therefore sustain the 1K target on a long feed
stream.  The max-length feed run is 1.017 cycles/instruction.

### The round/matmul shape: the DMA is now the wall

| shape | cycles | MACs/cycle | % of peak |
|:--|--:|--:|--:|
| `round-256` (store immediately after each round) | 29,867 | 280.9 | 27.4% |
| `pipe-256` (store delayed 4 rounds, 4-vd ring) | 20,136 | 416.6 | 40.7% |
| `feed-8,192` (one capture) | 8,559 | 980.1 | 95.7% |
| `feed-65,531` (one capture) | 66,656 | 1,006.7 | 98.3% |

The software store pipeline (`PIPE_DEPTH = 4`, a 4-deep vd ring: round r's
store is issued at the start of round r+4, before that round reuses the same
vd) removes the store's RAW wait on the ~81-cycle capture and lifts the shape
from 280.9 to 416.6.  The remaining round time is not the array:

- `pipe-256` issues **256 `vse` stores + 137 instruction-fill requests = 393
  DMA requests**.
- 20,136 cycles / 393 = **51.2 cycles per DMA request** — the DMA engine
  processes one request at a time, so the store's 2-beat write response and the
  fill's 4-beat read cannot overlap.
- In `feed-65,531` there are 1,024 fills + 1 store = 1,025 requests
  (≈52 k cycles at 51.2) hidden behind the 65.5 k feed cycles, so the DMA is
  not visible there; in the round shape it is the critical resource.

### How to reach 1K for the round shape

The ceiling is set by the instruction mix: K useful feeds + `mma.last` +
`vse` per round.

| scheme | useful feeds per issue/feed cycle | MACs/cycle |
|:--|:--|--:|
| single issue, current shape | 32 / 34 | **964** |
| `vse` dual-issued with an MMA | 32 / 33 | **993** |
| `mma.last` consumes no array feed | 32 / 32 | **1,024** |

Steps, in order:

1. **Software store pipeline + vd ring** — implemented in `fetch_sweep.py` and
   measured: round-256 **280.9 → 416.6**.  No RTL.
2. **Pipelined / outstanding DMA** (the load-bearing next RTL change).  Allow
   2–4 outstanding AXI reads/writes, overlap the read and write channels, and
   add a small completion scoreboard so the `dmaQ` can issue the next request
   while the previous response is outstanding.  Hiding the ~51-cycle request
   latency should take `pipe-256` from 20.1 k toward ~9 k cycles:
   **~900–960 MACs/cycle**.  The detailed design is in
   [`docs/designs/05.pipelined-dma.md`](../designs/05.pipelined-dma.md).
3. **Store coalescing** (optional, reduces the request count).  Four adjacent
   128 B `vse` stores can become one 512 B write, cutting 256 stores to 64;
   alone it caps around ~820, combined with (2) it gives margin.
4. **Issue overhead**: remove the one-cycle `vrPending` bubble (allow an MMA
   feed in the cycle the deferred store is enqueued) and/or dual-issue the
   DMA with the MMALU: **964–993**.
5. **Capture-only round boundary** (for exactly 1,024): eliminate the
   `mma.last` zero feed and redesign the `keep=0` reset wave so the capture is
   triggered without an array feed cycle.  This is an ISA/microarchitecture
   change; without it the practical ceiling is **993 (97%)**.

Current status: the feed path is at **1,006.7 (98.3%)**; the round shape is at
**416.6 (40.7%)** and is DMA-request-bound.  The next implementation is
step 2 (the pipelined DMA); steps 3–5 follow.  The exact 1K for a matmul round
requires step 5; the practical 993 requires steps 2–4.
