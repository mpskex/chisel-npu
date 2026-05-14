# FPGA Verification Platform — xc7k480tffg1156-2

## Overview

This document describes the FPGA verification platform for the Chisel NPU targeting the
Xilinx Kintex-7 `xc7k480tffg1156-2` on a custom board.  It covers the hardware
architecture, AXI interconnect design, clock-domain strategy, timing closure history,
and the full hardware bring-up flow including flash programming and serial-console testing.

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
 │            MIG C0  ◄──► DDR3 C0 (ECC)
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
                                                               MIG C1  ◄──► DDR3 C1 (ECC)
```

### Clock domains

| Domain | Frequency | Source | Clocks |
|---|---|---|---|
| `userclk2` | 250 MHz | XDMA PCIe GTX | XDMA internal, axi_cc slave sides, proto_conv |
| `fabric_aclk` | 200 MHz | MMCM (4×/5 from userclk2) | MMALU, DMA master, ctrl_lite, clkconv slave sides |
| `clk_pll_i` | 133 MHz | MIG C0 PLL | MIG C0 UI, axi_clkconv_xdma master, axi_dwidth_xdma |
| `clk_pll_i_1` | 133 MHz | MIG C1 PLL | MIG C1 UI, axi_clkconv_npu master, axi_dwidth_npu |
| `userclk1` | 500 MHz | XDMA PCIe GTX | PCIe PHY internal only — waived via `set_false_path` |

### Address map

| Region | Base address | Size |
|---|---|---|
| DDR3 C0 (host DMA) | `0x0000_0000_0000_0000` | 8 GB |
| DDR3 C1 (NPU) | `0x0000_0001_0000_0000` | 8 GB |
| NPU CTRL (BYPASS BAR) | XDMA BAR1 | 4 KB |

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
| K=32 first | −0.100 | — | 218 | Scale to K=32 |
| K=32 broad MCP | −0.564 | −4,020 | 22,445 | All intra-MMALU 2-cycle MCP |
| Tier-2.5 reorder | −0.782 | −5,338 | 22,608 | clkconv-first, dwidth at 133 MHz |
| DataFeeder per-lane | −0.265 | −95.8 | 1,299 | `Pipe(Vec)` → n×`Pipe(SInt)`: 96% reduction |
| calib_sync MCP extended | −0.781 | −2,499 | 13,553 | MCP covers `dma_master_inst/*` too |
| **200 MHz fabric clock** | **+0.003** | **0** | **0** | clk_wiz_0 + axi_cc IPs + 3-group clock_groups |

---

## Build Instructions

### Prerequisites

```
Vivado 2025.2  (batch mode)
firtool 1.62.1  (in Docker image fangruil/chisel-dev:{amd64,arm64})
top.sv          (generated by `make build` — K=32 MMALU)
```

### Step 1 — Generate top.sv (K=32)

```bash
# K=32 is set in src/main/scala/top/top.scala
make build   # runs `sbt run` inside Docker, writes top.sv
```

### Step 2 — Create project + synthesise

```bash
~/Xilinx/2025.2/Vivado/bin/vivado -mode batch \
    -source ip/vivado/xc7k480t/create_project.tcl \
    -log    ip/vivado/xc7k480t/create_project.log
```

Creates the Vivado project, generates all IPs, runs synthesis (~15 min).

### Step 3 — Implement + write bitstream

```bash
~/Xilinx/2025.2/Vivado/bin/vivado -mode batch \
    -source ip/vivado/xc7k480t/build_npu.tcl \
    -log    ip/vivado/xc7k480t/build_npu.log
```

Implementation takes ~45-90 min.  Output:
`ip/vivado/xc7k480t/npu_fpga/npu_fpga.runs/impl_1/top_npu.bit` (~18 MB)

---

## Final Build Metrics (NPU bitstream)

```
Device    : xc7k480tffg1156-2 (Kintex-7, speed grade -2)
Top module: top_npu
K         : 32

Timing (post-route + phys_opt, slow corner):
  WNS setup : +0.089 ns  ✅
  WHS hold  : +0.031 ns  ✅
  Failing   :  0 endpoints ✅

Resource utilization (post-impl):
  Slice LUTs : ~163K / 298K  (54.7%)
  Slice FFs  : ~196K / 597K  (32.8%)
  RAMB36     :    30 / 955   ( 3.1%)
  DSPs       :     0 / 1920  ( 0.0%)
```

---

## Hardware Bring-Up

### Requirements

| Component | Purpose |
|---|---|
| JTAG cable (FT4232H or compatible) | Flash programming + SRAM loading |
| Vivado `hw_server` on `localhost:3121` | Required by `program_flash.sh` |
| Serial console adapter on `/dev/ttyUSB0` | Out-of-band board control (no SSH dependency) |
| XDMA driver source (`dma_ip_drivers/`) | Built and installed on the FPGA host |

### On-board flash chip

The board uses a **Micron MT28GU512AAX1E BPI x16** flash (512 Mbit, 3.3 V, Mfg ID `0x89`).
The flash is accessed via Vivado's indirect BPI programming flow:

1. Load `bpi_xc7k480t_pullnone.bit` (from Vivado's `cfgmem/bitfile.zip`) into FPGA SRAM
   via JTAG — this routes `CCLK` to the BPI bus via `STARTUPE2`.
2. Use `program_hw_cfgmem` with part `mt28gu512aax1e-bpi-x16`.

This is automated by `tool/hw/program_flash.sh`.

### Cold-boot PCIe training — root cause and workaround

The AMD FCH on this host attempts PCIe link training during POST.  Cold-boot training
is nondeterministic: the FPGA must assert `DONE` before the FCH times out.

**Root cause (fixed):** `BITSTREAM.CONFIG.PERSIST=YES` sets `COR0[22]` (the DRIVEDONE
bit), delaying the `DONE` pin assertion by one startup cycle.  This pushes `DONE` past the
FCH training window.  Solution: **do not set `PERSIST=YES`** in bitstream constraints.

**Reliable bring-up sequence** (implemented in `tool/hw/bringup_flash.py`):

```
1. Flash BPI flash with the bitstream  (program_flash.sh)
2. JTAG-load the same bitstream into FPGA SRAM
   → PCIe hard block is now live before the host reboots
3. Warm-reboot the host
   → FPGA SRAM retains design; BIOS sees PCIe at POST
4. If PCIe not enumerated: SBR the bridge, warm-reboot, check again
5. Repeat step 4 up to N times (typically succeeds within 1–2 iterations)
6. Load XDMA driver, run smoke tests
```

### Bring-up command

```bash
# Flash + JTAG SRAM load + SBR loop + smoke tests (all via serial console)
python3 tool/hw/bringup_flash.py <path/to/bitstream.bit> --max-attempts 6
```

Options:
- `--no-flash` — skip flashing (board already has the desired bitstream)
- `--max-attempts N` — max SBR→reboot→check iterations (default: 6)

Environment variables:
- `HW_SERVER` — Vivado hw_server URL (default: `localhost:3121`)
- `SERIAL_PORT` — serial console device (default: `/dev/ttyUSB0`)
- `SERIAL_USER` — login username on the FPGA host (default: current OS user)

### Reference design bring-up

The reference design (`ip/vivado/xc7k480t.reference/`) is a headless TCL clone of the
vendor XDMA+MIG+MicroBlaze IPI block design.  It serves as the known-good baseline for
PCIe cold-boot verification.

```bash
# Build reference design bitstream
export XDMA_REF_XPR=/path/to/XC7K480T_XDMA_Test/XC7K480T_XDMA_Test.xpr

~/Xilinx/2025.2/Vivado/bin/vivado -mode batch \
    -source ip/vivado/xc7k480t.reference/scripts/create_project.tcl
~/Xilinx/2025.2/Vivado/bin/vivado -mode batch \
    -source ip/vivado/xc7k480t.reference/scripts/build.tcl

# Flash + verify (9 smoke tests via serial)
python3 tool/hw/bringup_flash.py \
    ip/vivado/xc7k480t.reference/top_wrapper.bit
```

Reference design metrics: WNS = +0.032 ns, COR0 = `0x02003fe5` (PERSIST=0).

### Smoke tests (serial console, no SSH required)

`bringup_flash.py` runs 9 hardware smoke tests over the serial console after PCIe
enumeration succeeds:

| Test | What it checks |
|---|---|
| `pcie_device_present` | `lspci` shows `10ee:7028` Memory controller |
| `pcie_link_speed` | `LnkSta` Speed 2.5 GT/s |
| `pcie_link_width` | `LnkSta` Width x2 or better |
| `subsystem_id` | Subsystem ID matches board (0007) |
| `xdma_driver_loaded` | `xdma.ko` module present |
| `xdma_devnodes` | `/dev/xdma0_*` device nodes created |
| `ddr3_c0_loopback_1kb` | H2C→DDR3→C2H 1 KB write/read match |
| `ddr3_c0_loopback_1mb` | H2C→DDR3→C2H 1 MB write/read match |
| `bypass_bar_accessible` | BYPASS BAR register read ≠ `0xffffffff` |

Expected result with reference design: **9 passed, 0 failed, 0 skipped**.

---

## Constraints Summary (`npu_top.xdc`)

### Clock groups

```xdc
set_clock_groups -asynchronous \
    -group [get_clocks {userclk2 userclk1}] \
    -group [get_clocks -include_generated_clocks {clk_out1_clk_wiz_0_1}] \
    -group [get_clocks {clk_pll_i clk_pll_i_1}]

# userclk1 (500 MHz) is internal to the PCIe hard block — waived as false path
# since the board links at Gen1 and this path is not in user logic.
set_false_path -setup -from [get_clocks userclk1] -to [get_clocks userclk1]
```

Three mutually-exclusive groups prevent Vivado from analysing cross-domain paths that are
handled by AXI CDC FIFOs or 2-FF synchronizers.

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

```xdc
set_property CONFIG_MODE                       BPI16   [current_design]
set_property BITSTREAM.CONFIG.CONFIGRATE       33      [current_design]
set_property CFGBVS                            VCCO    [current_design]
set_property CONFIG_VOLTAGE                    3.3     [current_design]
# PERSIST=YES intentionally omitted — it sets COR0[22] which delays DONE
# and causes PCIe cold-boot training failures on the AMD FCH host.
```

---

## Known Limitations

- **K=32 only**: K=64 exceeds device capacity (~1.69× LUT over-fill).
- **200 MHz fabric**: effective DMA bandwidth to MIG is ~25.6 Gbps (200 MHz × 128 bits).
  Compute throughput is K=32 × 200 MHz × 8b = 51.2 GOPS (INT8 MAC).
- **DataFeeder latency unchanged**: per-lane refactor is functionally equivalent;
  systolic array latency remains 3n−2 = 94 cycles for K=32.
- **PCIe cold-boot nondeterministic**: AMD FCH link-training window varies per boot.
  The `bringup_flash.py` SBR loop handles this reliably (typically 1–2 iterations).
- **Link width x2**: the PCIe root port on the test host is limited to Gen1 x2,
  constraining DMA bandwidth to ~0.5 GB/s rather than the design maximum.
