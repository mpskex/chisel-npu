# FPGA Verification Platform — xc7k480tffg1156-2

## Overview

This document describes the FPGA verification platform for the Chisel NPU targeting the
Xilinx Kintex-7 `xc7k480tffg1156-2` on a custom board. It covers the hardware
architecture, AXI interconnect design, clock-domain strategy, timing closure history,
the V0–V10 bring-up bisect ladder, and the full hardware verification flow including
flash programming, serial-console smoke tests, MMALU compute tests, and the ILA-based
read/write-path debug methodology.

All Vivado project files live under `ip/vivado/xc7k480t/`.

## Stack status (V10)

V10 is the current production topology. It supersedes V9 by:

- Instantiating the MMALU end-to-end via a single `npu_subsys` BD cell (V9 had the
  MMALU netlist in the build but never wired it into the BD).
- Adding a 2S:2M `axi_interconnect` named `axi_xbar` so BOTH the host XDMA and the
  NPU's DMA master can reach BOTH MIG channels through a unified 4 GB address space.
- Fixing the `S_WR_W` off-by-one in `npu_dma_master.v` that was discovered with an
  ILA capture during V10 bring-up.

Verified live on `xc7k480tffg1156-2` silicon:

| Category | Status |
|:---------|:-------|
| 9/9 baseline smoke tests (PCIe, DDR3 C0 loopback 1KB/1MB, ctrl_lite) | PASS |
| MMALU FSM kick → done (~315 ns) | PASS |
| MMALU compute (`test_mmalu_compute.py`, 6 tests) | PASS |
| Analytical formula `OUT[i] = A[i] · B[K-1] + ACCUM[i]` on every lane | PASS |

## Hardware architecture (V10)

```
Host (PCIe Gen2 ×8 configured, Gen1 ×4 in this slot)
 │
 ▼
XDMA 4.2 (128-bit AXI @ axi_aclk = 125 MHz)
 │
 ├─ M_AXI ──► axi_cc_xdma_in (128b, 125→200 MHz CDC)
 │              │
 │              ▼ fabric_aclk (200 MHz, clk_wiz_fabric MMCM)
 │           axi_clkconv_xdma (128b, 200→133 MHz CDC)
 │              │
 │              ▼ c0_ui_clk (133 MHz)
 │           axi_dwidth_xdma (128→512b)
 │              │
 │              ▼ 512-bit @ c0_ui_clk
 │           axi_xbar.S00 ───────────────┐
 │                                       │
 │                                       │ (V10: unified 4 GB address map)
 │                                       │   0x0000_0000 .. 0x7FFF_FFFF → C0
 │                                       │   0x8000_0000 .. 0xFFFF_FFFF → C1
 │                                       │
 │                            ┌──────────┴──────────┐
 │                            │                     │
 │                            ▼ M00 (c0_ui_clk)     ▼ M01 (c1_ui_clk, async-FIFO CDC)
 │                          MIG C0                  MIG C1
 │                          ◄──► DDR3 C0            ◄──► DDR3 C1
 │                                       ▲
 │                                       │ 512-bit @ c0_ui_clk
 ├─ M_AXI_BYPASS ──► axi_clkconv_byp ───► byp_dw ──► byp_pc (AXI4 → AXI4-Lite)
 │                    (125→200 MHz)      (128→32)        │
 │                                                       ▼
 │                                              npu_subsys/s_axil_*   ──► ctrl_lite
 │                                                                          │
 └─ axi_aclk (125 MHz) ──► clk_wiz_fabric MMCM ──► fabric_aclk (200 MHz)   │
                                                          │                  │
                          ┌───────────────────────────────┴──────────────────┘
                          │
                          ▼ fabric_aclk = 200 MHz
   ┌────────────────────────────────────────────────────────────────────────────┐
   │ npu_subsys (single BD module cell — V10)                                  │
   │   ┌────────────┐    ┌────────────────┐    ┌──────────────────────────┐    │
   │   │ ctrl_lite  │◄──►│ npu_dma_master │◄──►│ MMALU (K=32, top.sv)     │    │
   │   │ (BAR2+0x0) │    │ (AXI4 K=32)    │    │ N=8 bits, 32-bit accum   │    │
   │   └────────────┘    └─────────┬──────┘    └──────────────────────────┘    │
   │                               │ m_axi (128b, 64-bit addr @ 200 MHz)        │
   └───────────────────────────────┼────────────────────────────────────────────┘
                                   │
                                   ▼
                          axi_clkconv_npu (128b, 200→133 MHz)
                                   │
                                   ▼ c0_ui_clk (V10: same as XDMA path)
                          axi_dwidth_npu (128→512b)
                                   │
                                   ▼ 512-bit @ c0_ui_clk
                          axi_xbar.S01  (sees the SAME 4 GB map as S00)
```

### Clock domains

| Domain | Frequency | Source | Used by |
|:-------|----------:|:-------|:--------|
| `axi_aclk` (`userclk2`) | 125 MHz | XDMA PCIe GTX | XDMA internal, `axi_cc_xdma_in/S_AXI`, `axi_clkconv_byp/S_AXI` |
| `fabric_aclk` | 200 MHz | `clk_wiz_fabric` MMCM (×8/5 from 125 MHz) | MMALU, DMA master, ctrl_lite, byp_pc/dw, NPU AXI converters' slave sides |
| `c0_ui_clk` | 133 MHz | MIG C0 PLL | MIG C0 UI, **axi_xbar (V10) and its S00/S01/M00 ports**, axi_dwidth_xdma, axi_dwidth_npu (V10) |
| `c1_ui_clk` | 133 MHz | MIG C1 PLL | MIG C1 UI, axi_xbar.M01 (V10: cross-clock to C1 via internal async FIFO) |
| `userclk1` | 250 MHz | XDMA PCIe GTX | PCIe PHY internal only — waived via `set_false_path` |

### Address map (V10)

Both the host's `xdma_0/M_AXI` and the NPU's `npu_subsys/m_axi` see an
identical 4 GB linear address space:

| Region | Base | Range |
|:-------|:-----|:------|
| MIG C0 | `0x0000_0000` | 2 GB |
| MIG C1 | `0x8000_0000` | 2 GB |

The NPU's `npu_dma_master.v` uses these defaults inside MIG C0
(at +1 GB, well clear of any host PCIe DMA staging that typically sits near 0x0):

| Region | Address | Size |
|:-------|:--------|:-----|
| Matrix A | `0x0_4000_0000` | 32 B (int8 × K=32) |
| Matrix B | `0x0_4000_0100` | 32 B (int8 × K=32) |
| ACCUM | `0x0_4000_0200` | 128 B (int32 × K=32) |
| OUT | `0x0_4000_0400` | 128 B (int32 × K=32) |

ctrl_lite still answers at the same BAR2+0x0 32-bit register (start / done / busy).

## Key Design Decisions

### K=32 (not K=64)

The device has 298,600 LUTs. K=64 required ~505K LUTs (1.69× over capacity). K=32 uses
~163K LUTs (54.7%) — a comfortable fit with room for the AXI bridge IPs.

### Tier-2.5 AXI topology reorder (clkconv-first)

The original topology `axi_dwidth → axi_clkconv → MIG` ran the 128→512-bit width converter
at 250 MHz, where the internal `CMD_QUEUE → mi_register_slice` path was chronically critical
(WNS = −0.564 ns). Swapping to `axi_clkconv → axi_dwidth → MIG` moves the width converter
to the 133 MHz domain, eliminating that entire path class.

### DataFeeder per-lane `buffer_accum` refactor

The original `DataFeeder` used `Pipe(Vec(n, SInt(accum_nbits.W)), 2n-1)` — a single Chisel
`Pipe` over an n-element vector. Chisel generates one shared valid register (`_v_reg`) that
drives all n×(2n-1) downstream CE pins. With K=32 this is a single FF with fanout=1025
spanning the full die (~190 CLB columns), producing 7.9 ns of net delay against an 8 ns
2-cycle MCP budget.

**Fix**: replace with n individual `Pipe(SInt(accum_nbits.W), 2n-1)` instances. Chisel
deduplicates them into one module `Pipe63_SInt32` instantiated 32 times. Each instance has
its own private `_v_reg` chain; maximum fanout per valid signal drops to 2. This eliminated
96.3% of failing endpoints (22,608 → 1,299) and improved WNS by 66% (−0.782 → −0.265 ns).

```scala
// Before — 1025-fanout valid across all 32 lanes:
val buffer_accum = Module(new Pipe(Vec(n, SInt(accum_nbits.W)), 2 * n - 1))

// After — 32 independent per-lane pipes, fanout ≤ 2 per valid:
val buffer_accum = (0 until n map(x => Module(new Pipe(SInt(accum_nbits.W), 2 * n - 1))))
```

### 200 MHz fabric clock for formal timing closure

After the DataFeeder fix, 1,299 failing endpoints remained at −0.265 ns WNS at 250 MHz.
The cure: insert an MMCM (`clk_wiz_fabric`) that derives a 200 MHz `fabric_aclk` from
XDMA's 125 MHz `axi_aclk`, and bridge the two domains with `axi_cc_xdma_in` (XDMA M_AXI
path) and `axi_clkconv_byp` (BYPASS / ctrl-lite path). The 5 ns period gives all fabric
paths +1 ns headroom; the 2-cycle MMALU MCP becomes 10 ns.

### V10: unified 4 GB address map and proper MMALU wiring

V9 left two structural gaps that V10 closes:

1. **MMALU never wired** — V9 added `top.sv` to the synthesis fileset but the BD did
   not instantiate the `MMALU` module. The `dma_master` BD cell's `io_in_a_*`,
   `io_out_*`, `io_clct`, and `io_ctrl_*` pins were promoted to top-level wrapper
   ports by `make_wrapper`, and then optimised away during impl. Cosmetically the
   smoke tests passed, but `io_clct=0` permanently meant `S_WAIT_CLCT` never returned.
2. **C0/C1 partitioned** — XDMA only addressed MIG C0; the NPU DMA master only
   addressed MIG C1. There was no host-driven path to the bank the NPU read from.

V10 replaces the separate `ctrl_lite` + `dma_master` BD cells with a single
`npu_subsys` cell whose Verilog wrapper internally instantiates
`npu_ctrl_lite` + `npu_dma_master` + `MMALU`. It then inserts a 2S:2M
`axi_interconnect` (`axi_xbar`) on the read/write path to MIG so that both
masters see the same 4 GB DDR3 mapping. `axi_xbar` runs `S00`/`S01`/`M00` on
`c0_ui_clk` and exposes `M01` on `c1_ui_clk` with internal async-FIFO CDC.

### V10 bug fix: S_WR_W off-by-one (discovered via ILA)

After V10 was wired up, the first `test_mmalu_compute.py` runs showed a
consistent one-beat shift in OUT: `OUT[0..3]` carried stale data and
`ACCUM[28..31]` never appeared. Theory and a `rdrop` workaround (drop the first
`rpipe_valid` pulse in the FSM) did not fix it.

The fix came from an ILA capture (see "ILA debug methodology" below). The
capture proved the **read path was already correct** — beats 0..7 land in
`acc_buf[0..3]`, `[4..7]`, …, `[28..31]` exactly as designed. The bug was in
the **write phase** (`S_WR_W`):

The original `S_WR_W` only updated `m_axi_wdata` AFTER each accepted handshake
inside its IF branch. The very first beat therefore carried stale `wdata` from
the previous burst, and the loop's `beat_cnt*4` indexing meant beat 7 (with
`wlast=1`) shipped `out_buf[24..27]` while `out_buf[28..31]` was never sent.

The two-line fix:

1. Pre-load `m_axi_wdata` to `{out_buf[3], out_buf[2], out_buf[1], out_buf[0]}`
   at the `S_WR_AW → S_WR_W` transition.
2. In `S_WR_W`'s IF branch, advance the next `m_axi_wdata` to
   `{out_buf[(beat_cnt+1)*4 + 3..0]}` (one beat ahead of the current beat_cnt).

With these two changes, beat N carries `out_buf[N*4 + 0..3]` end-to-end and
beat 7 sends `out_buf[28..31]` with `wlast=1`. All 5 MMALU compute tests pass.

## Timing Closure History

| Run | WNS (ns) | TNS (ns) | Failing EPs | Key change |
|:----|---------:|---------:|------------:|:-----------|
| K=16 initial | +0.005 | 0 | 0 | Baseline — K=16 closed |
| K=32 first | −0.100 | — | 218 | Scale to K=32 |
| K=32 broad MCP | −0.564 | −4,020 | 22,445 | All intra-MMALU 2-cycle MCP |
| Tier-2.5 reorder | −0.782 | −5,338 | 22,608 | clkconv-first, dwidth at 133 MHz |
| DataFeeder per-lane | −0.265 | −95.8 | 1,299 | `Pipe(Vec)` → n×`Pipe(SInt)`: 96% reduction |
| 200 MHz fabric (V9) | +0.020 | 0 | 0 | `clk_wiz_fabric` + axi_cc IPs + 3-group `set_clock_groups` |
| V10 npu xbar | +0.027 to −0.133 | varies | 0–small | Adds `axi_xbar` 2S:2M + `npu_subsys`; fix `S_WR_W` off-by-one; failing path is inside MMALU PE, not on the V10 data plane |

The post-V10 WNS varies slightly across rebuilds (placement-dependent). The
failing path, when present, is always inside an MMALU PE carry chain — not
in the new `axi_xbar` or `npu_subsys` logic — so it does not affect the DMA
or control plane.

## Build Instructions

### Prerequisites

```
Vivado 2025.2  (batch mode)
firtool 1.62.1  (in Docker image fangruil/chisel-dev:{amd64,arm64})
top.sv          (Chisel-generated MMALU netlist; K=32, repo root)
hw_server       (Vivado, listening on localhost:3121)
```

### Step 1 — Generate top.sv (K=32)

```bash
make build   # runs `sbt run` inside Docker, writes top.sv at repo root
```

### Step 2 — Build the bitstream

The build flow ships two top-level scripts. Both produce the same NPU
topology; the only difference is whether an ILA debugger core is wired in:

| Script | Output | Use when |
|:-------|:-------|:---------|
| `build_npu.tcl` | `top_npu.bit` | Production / runtime — no ILA, slightly smaller bitstream |
| `build_npu_with_ila.tcl` | `top_npu_with_ila.bit` + `.ltx` | Hardware debug — adds the `u_npu_ila` core wired to every `(* mark_debug *)` signal in the design |

Either script auto-bootstraps `proj/` on first run (~25 min cold cache),
then runs synth\_1 + impl\_1 + write\_bitstream (~50 min). Re-runs reuse
the existing project (~30 min).

```bash
cd /path/to/chisel-npu

# Production build (no ILA):
~/Xilinx/2025.2/Vivado/bin/vivado -mode batch \
    -source  ip/vivado/xc7k480t/scripts/build_npu.tcl \
    -journal ip/vivado/xc7k480t/scripts/build_npu.jou \
    -log     ip/vivado/xc7k480t/scripts/build_npu.log

# Or, debug build with the ILA core wired in:
~/Xilinx/2025.2/Vivado/bin/vivado -mode batch \
    -source  ip/vivado/xc7k480t/scripts/build_npu_with_ila.tcl \
    -journal ip/vivado/xc7k480t/scripts/build_npu_with_ila.jou \
    -log     ip/vivado/xc7k480t/scripts/build_npu_with_ila.log
```

Outputs:

```
ip/vivado/xc7k480t/top_npu.bit            ~18 MB   (production)
ip/vivado/xc7k480t/top_npu_with_ila.bit   ~18 MB   (debug)
ip/vivado/xc7k480t/top_npu_with_ila.ltx   ~80 KB   (probes file for HW Manager)
```

### Step 3 — Flash + verify

```bash
# Flash BPI flash + JTAG SRAM load + SBR loop + 9 smoke tests (serial-only).
python3 tool/hw/bringup_flash.py \
    ip/vivado/xc7k480t/top_npu.bit \
    --max-attempts 6
```

Expected: **9 passed, 0 failed, 0 skipped** (see `tool/hw/tests/README.md`).

### Step 4 — MMALU compute tests

`tool/hw/tests/test_mmalu_compute.py` validates the V10 NPU end-to-end. The
host writes A/B/ACCUM via XDMA H2C into MIG C0 at `0x4000_0000`, kicks
ctrl_lite, polls `done`, and reads OUT back via XDMA C2H:

```bash
python3 -m pytest tool/hw/tests/test_mmalu_compute.py -v -m hw
```

Six tests; all PASS on V10 silicon:

| Test | Verifies |
|:-----|:---------|
| `test_mmalu_done_smoke` | FSM kick → done within 1 s |
| `test_mmalu_zero_in_zero_out` | `A=B=ACCUM=0` → `OUT` all zero |
| `test_mmalu_accum_passthrough` | `A=B=0` → `OUT == ACCUM` |
| `test_mmalu_zero_a_kills_multiplier` | `A=0, B≠0` → `OUT == ACCUM` |
| `test_mmalu_multiplier_alive` | `A=10, B=7, ACCUM=0` → `OUT == 70` (in every lane) |
| `test_mmalu_outer_b_last` | `OUT[i] == A[i] · B[K−1] + ACCUM[i]` (analytical formula) |

## Bring-up history (V0..V10, squashed)

The hardware was brought up via a 10-step bisect ladder (V0 → V1 → … → V10).
Each step incrementally modifies the BD topology and produces its own
bitstream so any regression can be bisected. The per-step build scripts and
their `_apply_v*.tcl` BD-delta libraries are no longer in the tree — they
have been squashed into a single `_apply_npu_topology.tcl` that
self-detects the current BD state and applies only the missing steps. The
ladder is preserved here for historical reference and to document what each
internal `_npu_step_*` proc inside `_apply_npu_topology.tcl` accomplishes.

| Step | Internal proc | What it adds | 9/9 smoke | MMALU live |
|:-----|:--------------|:-------------|:---------:|:----------:|
| V0 baseline | (vendor reference) | XDMA + MIG + MicroBlaze + SmartConnect | ✅ | — |
| V1 remove MB | `_npu_step_remove_mb` | Drop MicroBlaze + clk_wiz; replace MIG S\*\_AXI\_CTRL with idle VIP | ✅ | — |
| V2 bypass ctrl | `_npu_step_bypass_ctrl` | BYPASS-BAR path + `ctrl_lite` | ✅ | — |
| V3 fabric MMCM | `_npu_step_mmcm` | `clk_wiz_fabric` (125→200 MHz) + `rst_fabric_200M` | ✅ | — |
| V4 BYPASS CDC | `_npu_step_byp_cdc` | `axi_clkconv_byp` (125→200) + move BYPASS chain to fabric | ✅ | — |
| V5 XDMA CC | `_npu_step_xdma_cc` | `axi_cc_xdma_in` (125→200) on the M_AXI data path | ✅ | — |
| V6 remove SMC | `_npu_step_remove_smc` | Drop `axi_smc`, install flat `axi_clkconv_xdma` + `axi_dwidth_xdma` chain | ✅ | — |
| V7 NPU DMA | `_npu_step_dma_master` | `npu_dma_master` + path to MIG C1 | ✅ | — |
| V8 NPU stub | (no proc) | Synthesis-only test with `mmalu_stub.v` (now removed) | ✅ | — |
| V9 NPU full | (no proc) | Add `top.sv` to fileset (MMALU still not wired in BD) | ✅ | — |
| **V10 NPU xbar** | **`_npu_step_xbar_and_subsys`** | **`npu_subsys` cell + `axi_xbar` 2S:2M + 4 GB unified address map + `S_WR_W` write-phase fix** | ✅ | ✅ (5/5) |

The bootstrap project (`bootstrap_project.tcl` + `xc7k480t.reference/`) ships
a V7-equivalent BD so on a fresh build only `_npu_step_xbar_and_subsys`
actually runs.

## ILA Debug Methodology

`build_npu_with_ila.tcl` produces a bitstream containing the `u_npu_ila`
debugger core wired to every `(* mark_debug = "true" *)` net in the
design. This is what found the V10 `S_WR_W` write-phase off-by-one in the
first place; the same workflow is available for any future regression in
the `npu_dma_master` FSM or its AXI handshakes.

1. **Tag signals** in `npu_dma_master.v` with `(* mark_debug = "true" *)`:
   `state`, `beat_cnt`, `rpipe_valid`, `rlast_pipe`, `rdata_pipe`, plus
   single-cycle shadow regs `dbg_rvalid` / `dbg_rready` / `dbg_rlast` /
   `dbg_rdata_lo` / `dbg_arvalid` / `dbg_arready` / `dbg_state_d1` /
   `dbg_beat_cnt_d1` for AXI handshake observation.
2. **`build_npu_with_ila.tcl`** sources `_apply_npu_ila.tcl` which, after
   `synth_1` opens, scans for `MARK_DEBUG == 1` nets, creates a `u_npu_ila`
   core on `fabric_aclk`, connects one probe per base signal (e.g.
   `state[3:0]` as one 4-bit probe), and writes the `.ltx` probes file next
   to the bitstream.
3. **Capture** (Vivado HW Manager TCL, headless):

   ```tcl
   open_hw_manager
   connect_hw_server -url localhost:3121
   current_hw_target [get_hw_targets */xilinx_tcf/Xilinx/*]
   open_hw_target
   current_hw_device [get_hw_devices xc7k480t_0]
   refresh_hw_device -update_hw_probes false [current_hw_device]
   set_property PROBES.FILE      ip/vivado/xc7k480t/top_npu_with_ila.ltx [current_hw_device]
   set_property FULL_PROBES.FILE ip/vivado/xc7k480t/top_npu_with_ila.ltx [current_hw_device]
   refresh_hw_device [current_hw_device]

   set ila [lindex [get_hw_ilas -of_objects [current_hw_device]] 0]
   set state_probe [get_hw_probes -of_objects $ila *u_dma/state]
   set_property CONTROL.DATA_DEPTH       4096 $ila
   set_property CONTROL.TRIGGER_POSITION 1024 $ila
   # Trigger on state == S_READ_ACC_R = 4'b0110
   set_property TRIGGER_COMPARE_VALUE eq4'b0110 $state_probe
   run_hw_ila $ila
   ```

   Then kick the NPU from the host via PCIe, wait for the ILA to fire, and
   `upload_hw_ila_data` + `write_hw_ila_data -csv_file …` to dump a CSV that
   Python can parse cycle-by-cycle.

4. **For production** use `build_npu.tcl` instead — same topology, no ILA,
   slightly smaller bitstream and no debug overhead in the synth/impl
   timeline.

The investigation that root-caused the `S_WR_W` bug found that:

- The read path (`rvalid`, `rready`, `rpipe_valid`, `rdata_pipe`, `beat_cnt`)
  is **correct** — beats arrive in order and land at `acc_buf[bc*4 + 0..3]`
  for `bc = 0..7`.
- The off-by-one observed at the host is entirely caused by the write phase
  shipping stale `wdata` on beat 0 and never sending `out_buf[28..31]`.

This kind of diagnosis is non-obvious from black-box observation; shipping
`build_npu_with_ila.tcl` as a first-class build variant makes follow-up
debug straightforward.

## RTL Source Reference

| File | Role |
|:-----|:-----|
| `ip/vivado/xc7k480t/src/npu_ctrl_lite.v` | AXI4-Lite slave at BAR2+0x0. Single 32-bit CTRL register (`start`/`done`/`busy`) |
| `ip/vivado/xc7k480t/src/npu_dma_master.v` | AXI4 master FSM (128b, 64-bit addr). Reads A/B/ACCUM from MIG, kicks MMALU, writes OUT back. Contains the `S_WR_W` write-phase fix and `(* mark_debug *)` tags |
| `ip/vivado/xc7k480t/src/npu_subsys.v` | Module wrapper instantiating ctrl_lite + dma_master + MMALU. **V10 BD references this as a single cell** |
| `ip/vivado/xc7k480t/src/mmalu_stub.v` | Empty MMALU stub for V8 synthesis sanity |
| `top.sv` (repo root) | Chisel-generated `MMALU` module (K=32, N=8, 32-bit accum), 1.7 MB, firtool-1.62.1 |

## Build & ILA TCL Reference

| File | Role |
|:-----|:-----|
| `ip/vivado/xc7k480t/scripts/build_npu.tcl` | **Production build (no ILA)**. Boots `proj/` if missing, applies the squashed NPU topology, runs `launch_runs synth_1` + OOC sub-runs, copies IP DCPs, opens synth_1, runs impl_1, writes `top_npu.bit` |
| `ip/vivado/xc7k480t/scripts/build_npu_with_ila.tcl` | **Debug build (with ILA)**. Same as `build_npu.tcl` but inserts the `u_npu_ila` core post-synth and writes the matching `top_npu_with_ila.ltx` probes file |
| `ip/vivado/xc7k480t/scripts/_apply_npu_topology.tcl` | **BD topology library** (squashed V1..V10). Defines internal `_npu_step_*` procs plus the public `apply_npu_topology` entry point. Detects the current BD state and applies only the missing steps |
| `ip/vivado/xc7k480t/scripts/_apply_npu_ila.tcl` | Post-synth ILA insertion (auto-scans `MARK_DEBUG` nets, builds `u_npu_ila` with one probe per base signal) |
| `ip/vivado/xc7k480t/scripts/bootstrap_project.tcl` | First-run project bootstrap from `xc7k480t.reference/` (V7-equivalent BD state) |
| `ip/vivado/xc7k480t/scripts/migrate_lib.tcl` | Shared helpers: `open_ref_project`, `assert_synth_done`, `run_impl_and_write_bit`, `save_bd` |

## Constraints Summary

### MMALU MCP

```xdc
set_multicycle_path 2 -setup \
    -from [get_cells -hierarchical -filter {NAME =~ mmalu_inst/*}] \
    -to   [get_cells -hierarchical -filter {NAME =~ mmalu_inst/*}]
set_multicycle_path 1 -hold \
    -from [get_cells -hierarchical -filter {NAME =~ mmalu_inst/*}] \
    -to   [get_cells -hierarchical -filter {NAME =~ mmalu_inst/*}]
```

At 200 MHz the 2-cycle setup budget is 10 ns — comfortable for the 8-bit MAC CARRY4×8 chain
(~4.3 ns logic) plus routing.

### Bitstream configuration

Applied in `migrate_lib.tcl::run_impl_and_write_bit` before `write_bitstream`:

```tcl
set_property CONFIG_MODE                 SPIx1 [current_design]
set_property BITSTREAM.CONFIG.CONFIGRATE 3     [current_design]
```

Target COR0 = `0x02003fe5` (no `PERSIST=YES` — that bit delays DONE and breaks
AMD FCH cold-boot training).

## Known Limitations

- **K=32 only**: K=64 exceeds device capacity (~1.69× LUT over-fill).
- **200 MHz fabric**: compute throughput is K=32 × 200 MHz × 8b = 51.2 GOPS (INT8 MAC).
- **DataFeeder latency unchanged**: systolic array latency remains 3n−2 = 94 cycles for K=32.
- **PCIe cold-boot nondeterministic**: AMD FCH link-training window varies per boot.
  The `bringup_flash.py` SBR loop handles this reliably (typically 1–2 iterations).
- **Link width x4 at Gen1**: the PCIe slot is limited to Gen1 x4, constraining DMA
  bandwidth to ~0.5 GB/s rather than the design maximum.
- **MMALU "one-shot" kick semantics**: the V10 dma_master pulses `ctrl.busy=1` for
  exactly one cycle and holds inputs constant. Under those semantics, the only
  meaningful output is `OUT[i] = A[i] · B[K-1] + ACCUM[i]` (each PE captures
  exactly one product). To run a full GEMM, the dma_master FSM needs to stream
  K consecutive A/B rows — that is a Phase 2 extension.
- **V10 read path uses one xbar regslice per port**; M01 also carries an async data
  FIFO for the C0→C1 cross-clock. WNS varies by ±0.1 ns across rebuilds; the failing
  path (when present) is always inside an MMALU PE, not on the data plane.
