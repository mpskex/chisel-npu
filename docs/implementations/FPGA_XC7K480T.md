# FPGA Verification Platform — xc7k480tffg1156-2

## Overview

This document describes the FPGA verification platform for the Chisel NPU targeting the
Xilinx Kintex-7 `xc7k480tffg1156-2` on the custom `YPCB-00338-1P1` board.  It covers the
hardware architecture, AXI interconnect design, clock-domain strategy, and the full timing
closure history — including the critical DataFeeder refactor and 200 MHz fabric clock that
achieved formal closure (WNS ≥ 0 ns).

All Vivado project files live under `ip/vivado/xc7k480t/`.

---

## Hardware Architecture

```
Host (PCIe Gen2 ×8)
 │
 ▼
XDMA (128-bit AXI @ 250 MHz / userclk2)
 │
 ├─ M_AXI ──► axi_cc_xdma_in (128b, 250→200 MHz)
 │                │
 │                ▼ fabric_aclk (200 MHz)
 │            axi_clkconv_xdma (128b, 200→133 MHz)
 │                │
 │                ▼ c0_ui_clk (133 MHz)
 │            axi_dwidth_xdma (128→512b)
 │                │
 │                ▼
 │            MIG C0  ◄──► DDR3 Bank 11/12/13 (ECC)
 │
 ├─ M_AXI_BYPASS ──► proto_conv_inst (@ 250 MHz)
 │                        │
 │                    axi_cc_byp_in (32b AXI4L, 250→200 MHz)
 │                        │
 │                    npu_ctrl_lite  (start/done/busy @ 200 MHz)
 │
 └─ axi_aclk (250 MHz) ──► clk_wiz_0 (MMCM) ──► fabric_aclk (200 MHz)
                                                       │
                                          ┌────────────┴────────────┐
                                          │                         │
                                       MMALU (K=32)           DMA master (128b)
                                       @ 200 MHz               @ 200 MHz
                                                                     │
                                                              axi_clkconv_npu (200→133 MHz)
                                                                     │
                                                              axi_dwidth_npu (128→512b @ 133 MHz)
                                                                     │
                                                              MIG C1  ◄──► DDR3 Bank 16/17/18 (ECC)
```

### Clock domains

| Domain | Frequency | Source | Clocks fabric |
|---|---|---|---|
| `userclk2` | 250 MHz | XDMA PCIe GTX | XDMA internal, axi_cc slave sides, proto_conv |
| `fabric_aclk` | 200 MHz | MMCM (4×/5 from userclk2) | MMALU, DMA master, ctrl_lite, clkconv slave sides |
| `clk_pll_i` | 133 MHz | MIG C0 PLL | MIG C0 UI, axi_clkconv_xdma master, axi_dwidth_xdma |
| `clk_pll_i_1` | 133 MHz | MIG C1 PLL | MIG C1 UI, axi_clkconv_npu master, axi_dwidth_npu |
| `userclk1` | 500 MHz | XDMA PCIe GTX | PCIe PHY internal only |

### Address map

| Region | Base address | Size |
|---|---|---|
| DDR3 C0 (host DMA) | `0x0000_0000_0000_0000` | 8 GB |
| DDR3 C1 (NPU) | `0x0000_0001_0000_0000` | 8 GB |
| NPU CTRL (BYPASS BAR) | XDMA BAR1 | 4 KB |

### DMA tile addresses (default)

| Buffer | Address |
|---|---|
| Matrix A | `0x1000_0000_0000_0000` |
| Matrix B | `+0x100` |
| Accumulator | `+0x200` |
| Output | `+0x400` |

---

## Key Design Decisions

### K=32 (not K=64)

The device has 298,600 LUTs.  K=64 required ~505K LUTs (1.69× over capacity).  K=32 uses
~163K LUTs (54.7%) — a comfortable fit with room for the AXI bridge IPs.

### Tier-2.5 AXI topology reorder (clkconv-first)

The original topology `axi_dwidth → axi_clkconv → MIG` ran the 128→512-bit width converter
at 250 MHz, where the internal `CMD_QUEUE → mi_register_slice` path was chronically critical
(WNS = −0.564 ns).  Swapping to `axi_clkconv → axi_dwidth → MIG` moves the width converter
to the 133 MHz domain, eliminating that entire path class.

### DataFeeder per-lane `buffer_accum` refactor

The original `DataFeeder` used `Pipe(Vec(n, SInt(accum_nbits.W)), 2n-1)` — a single Chisel
`Pipe` over an n-element vector.  Chisel generates one shared valid register (`_v_reg`) that
drives all n×(2n-1) downstream CE pins.  With K=32 this is a single FF with fanout=1025
spanning the full die (~190 CLB columns), producing 7.9 ns of net delay against an 8 ns
2-cycle MCP budget.

**Fix**: replace with n individual `Pipe(SInt(accum_nbits.W), 2n-1)` instances.  Chisel
deduplicates them into one module `Pipe63_SInt32` instantiated 32 times.  Each instance has
its own private `_v_reg` chain; maximum fanout per valid signal drops to 2 (drives only the
next pipeline stage and its own data register).  This eliminated 96.3% of failing endpoints
(22,608 → 1,299) and improved WNS by 66% (−0.782 → −0.265 ns).

```scala
// Before — 1025-fanout valid across all 32 lanes:
val buffer_accum = Module(new Pipe(Vec(n, SInt(accum_nbits.W)), 2 * n - 1))

// After — 32 independent per-lane pipes, fanout ≤ 2 per valid:
val buffer_accum = (0 until n map(x => Module(new Pipe(SInt(accum_nbits.W), 2 * n - 1))))
```

### 200 MHz fabric clock for formal timing closure

After the DataFeeder fix, 1,299 failing endpoints remained at −0.265 ns WNS.  Diagnosis
showed three distinct path classes:

| Class | Count | Root cause |
|---|---|---|
| `xdma_inst` → `dma_master_inst` | 870 | Placement spread; 250 MHz budget too tight |
| `calib_sync2_reg` → fabric registers | 238 | High-fanout reset net, 3.5–4.1 ns route |
| `mmalu_inst/sarray` MMPE carry chain | 195 | 32-bit accumulator at 250 MHz |

**Fix**: insert an MMCM (`clk_wiz_0`) to derive a 200 MHz `fabric_aclk` from XDMA's
250 MHz `axi_aclk`, and bridge the two domains with `axi_cc_xdma_in` (XDMA M_AXI path) and
`axi_cc_byp_in` (BYPASS / ctrl-lite path).

Effect: the 5 ns period gives all fabric paths +1 ns headroom; the 2-cycle MMALU MCP budget
becomes 10 ns (previously 8 ns).  With the three-group `set_clock_groups` constraint the
placer correctly isolates each domain and produces WNS = **+0.003 ns** on the first attempt.

---

## Timing Closure History

| Run | WNS (ns) | TNS (ns) | Failing EPs | Key change |
|---|---|---|---|---|
| K=16 initial | +0.005 | 0 | 0 | Baseline — K=16 closed |
| K=32 first | −0.100 | — | 218 | Scale to K=32; spiral-search at ExtraTimingOpt |
| K=32 broad MCP | −0.564 | −4,020 | 22,445 | All intra-MMALU 2-cycle MCP |
| Tier-2.5 reorder | −0.782 | −5,338 | 22,608 | clkconv-first, dwidth at 133 MHz — stochastic regression |
| DataFeeder per-lane | −0.265 | −95.8 | 1,299 | `Pipe(Vec)` → n×`Pipe(SInt)`: 96% reduction |
| calib_sync MCP extended | −0.781 | −2,499 | 13,553 | MCP covers `dma_master_inst/*` too |
| **200 MHz fabric clock** | **+0.003** | **0** | **0** | clk_wiz_0 + axi_cc IPs; 3-group clock_groups |

---

## Constraints Summary (`npu_top.xdc`)

### Clock groups

```xdc
set_clock_groups -asynchronous \
    -group [get_clocks {userclk2 userclk1}] \
    -group [get_clocks -include_generated_clocks {clk_out1_clk_wiz_0_1}] \
    -group [get_clocks {clk_pll_i clk_pll_i_1}]
```

Three mutually-exclusive groups prevent Vivado from analysing cross-domain paths that are
handled by AXI CDC FIFOs or 2-FF synchronizers.  The `clk_out1_clk_wiz_0_1` name is the
Vivado-internal identifier for the MMCM `clk_wiz_0` output at 200 MHz.

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

### calib_sync MCP

`c0/c1_calib_sync2_reg` are 2-FF synchronizers in the `fabric_aclk` domain that re-synchronize
DDR3 calibration-complete signals from `clk_pll_i`.  These registers drive a high-fanout reset
net into both `mmalu_inst/*` and `dma_master_inst/*`.  A 2-cycle MCP (10 ns budget at 200 MHz)
is safe because `calib_complete` is a one-shot signal that stays asserted for the full DDR3
session.

---

## Build Instructions

### Prerequisites

```
Vivado 2025.2  (batch mode)   ~/Xilinx/2025.2/Vivado/bin/vivado
firtool 1.62.1                in Docker image fangruil/chisel-dev:{amd64,arm64}
top.sv                        generated by `make build` (K=32 MMALU)
```

### Step 1 — Generate top.sv (K=32)

```bash
# Chisel K=32 is set in src/main/scala/top/top.scala
# SimpleBackend.scala and NpuAssembler.scala have pre-existing compile errors;
# rename them before running sbt.
mv src/main/scala/backend/SimpleBackend.scala{,.bak}
mv src/main/scala/isa/NpuAssembler.scala{,.bak}
docker run --rm -v $PWD:/workspace fangruil/chisel-dev:amd64 sbt "runMain top.Main"
mv src/main/scala/backend/SimpleBackend.scala{.bak,}
mv src/main/scala/isa/NpuAssembler.scala{.bak,}
```

`top.sv` will be ~43,000 lines (K=32 with per-lane DataFeeder pipes).

### Step 2 — Create project + synthesise

```bash
cd ip/vivado/xc7k480t
~/Xilinx/2025.2/Vivado/bin/vivado -mode batch -source create_project.tcl
```

This copies `top.sv`, creates the Vivado project, generates all IPs (including `clk_wiz_0`,
`axi_cc_xdma_in`, `axi_cc_byp_in`), and runs synthesis (~8 min).

### Step 3 — Implement + write bitstream

```bash
cat > /tmp/impl.tcl << 'EOF'
set proj_xpr "ip/vivado/xc7k480t/npu_fpga/npu_fpga.xpr"
set out_dir  "ip/vivado/xc7k480t/npu_fpga/npu_fpga.runs/impl_1"
open_project $proj_xpr
open_run synth_1 -name netlist_1
file mkdir $out_dir
opt_design   -directive Default
place_design -directive Default
phys_opt_design -directive AggressiveExplore
route_design -directive Explore -tns_cleanup
phys_opt_design -directive AggressiveExplore
report_timing_summary -max_paths 20 -input_pins \
    -file "ip/vivado/xc7k480t/npu_fpga/timing_summary.rpt"
write_bitstream -force -bin_file "$out_dir/top_npu.bit"
close_project
EOF
~/Xilinx/2025.2/Vivado/bin/vivado -mode batch -source /tmp/impl.tcl
```

Implementation takes ~1.5 hours.  The final bitstream is written to
`npu_fpga/npu_fpga.runs/impl_1/top_npu.bit`.

---

## Final Build Metrics

```
Device    : xc7k480tffg1156-2 (Kintex-7, speed grade -2)
Top module: top_npu
K         : 32 (systolic array side length)

Timing (post-route + phys_opt, slow corner):
  WNS setup : +0.003 ns  ✅
  WHS hold  : +0.017 ns  ✅
  Failing   :  0 endpoints ✅

Resource utilization (post-impl):
  Slice LUTs : ~163K / 298K  (54.7%)
  Slice FFs  : ~196K / 597K  (32.8%)
  RAMB36     :    30 / 955   ( 3.1%)
  DSPs       :     0 / 1920  ( 0.0%)

Bitstream   : npu_fpga/npu_fpga.runs/impl_1/top_npu.bit  (18 MB)
```

---

## Known Limitations

- **K=32 only**: K=64 exceeds device capacity (~1.69× LUT over-fill).
- **200 MHz fabric**: XDMA M_AXI bus runs at 250 MHz; effective DMA bandwidth to MIG is
  ~25.6 Gbps (200 MHz × 128 bits), down from 32 Gbps at 250 MHz.  Compute throughput is
  K=32 × 200 MHz × 8b = 51.2 GOPS (INT8 MAC).
- **DataFeeder latency unchanged**: the per-lane `Pipe63_SInt32` refactor is functionally
  equivalent to the original; no change to systolic array latency (3n−2 = 94 cycles for K=32).
- **MMPE pipeline not yet added**: the 8-bit MAC carry chain is covered by the 2-cycle MCP
  rather than by a pipeline register.  A proper 2-stage MMPE pipeline (mult_reg + accumulate)
  would allow formal closure at 250 MHz without a frequency drop, but requires test-latency
  adjustments across `DataFeeder`, `DataCollector`, and `ControlUnit`.
