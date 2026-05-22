---
name: test-hw-xc7k480t
description: Use when testing, flashing, or debugging the xc7k480tffg1156-2 FPGA board — includes bringup_flash.py, program_flash.sh, serial console, 9 XDMA smoke tests, 6 MMALU compute tests, the u_npu_ila debugger core, and PCIe/DDR3 debugging. Also use for any question about the FPGA hardware bring-up flow or NPU validation on real hardware.
---

# Hardware Testing — xc7k480tffg1156-2 FPGA Platform

## ⚠ CRITICAL: NEVER POWER OFF

**Never run `poweroff`, `shutdown`, `systemctl poweroff`, or equivalent.**
There is no remote power-on (no IPMI, no WOL, no PDU). Use `sudo /sbin/reboot` only.

---

## Platform overview

| Item | Value |
|:-----|:------|
| FPGA | `xc7k480tffg1156-2` (Kintex-7 480T) |
| PCIe | XDMA 4.2, Gen1 ×4 actual (Gen2 ×8 configured, slot-limited) |
| DDR3 | Dual-channel MIG: C0 = 2 GB, C1 = 2 GB — both addressable by host AND NPU via `axi_xbar` (4 GB unified linear map) |
| Flash | `mt28gu512aax1e-bpi-x16` (Micron 512 Mbit BPI) |
| Serial | `/dev/ttyUSB0` default, 115200 8N1, PL2303 USB-UART. After every flash + JTAG cycle the JTAG cable resets and may move the PL2303 device number; check with `udevadm info -q property -n /dev/ttyUSBn | grep ID_MODEL` |
| JTAG | FT4232H quad USB, `hw_server` at `localhost:3121` |
| PCIe address | `01:00.0`, vendor `10ee`, device `7028`, subsystem `0007` |
| PCIe bridge for SBR | `00:15.0` |

Both DDR3 channels are addressable by **both** the host (via XDMA M_AXI) and
the NPU's DMA master through the `axi_xbar`:

| Region | Address (both masters) |
|:-------|:-----------------------|
| MIG C0 | `0x0000_0000 .. 0x7FFF_FFFF` (2 GB) |
| MIG C1 | `0x8000_0000 .. 0xFFFF_FFFF` (2 GB) |

NPU operand staging area (used by `test_mmalu_compute.py`):

| Item | Address (MIG C0) |
|:-----|:-----------------|
| Matrix A (int8[32]) | `0x4000_0000` |
| Matrix B (int8[32]) | `0x4000_0100` |
| ACCUM (int32[32]) | `0x4000_0200` |
| OUT (int32[32]) | `0x4000_0400` |

---

## Standard bring-up (flash + smoke test)

```bash
cd /path/to/chisel-npu

# Production bitstream (built via build_npu.tcl):
python3 tool/hw/bringup_flash.py \
    ip/vivado/xc7k480t/top_npu.bit \
    --max-attempts 6

# OR the debug bitstream with ILA debugger core:
python3 tool/hw/bringup_flash.py \
    ip/vivado/xc7k480t/top_npu_with_ila.bit \
    --max-attempts 6

# Skip re-flashing (reuse what's already in BPI flash):
python3 tool/hw/bringup_flash.py <bit> --skip-flash
```

**What the script does:**

1. Programs bitstream to BPI flash via Vivado `program_hw_cfgmem` (~3 min)
2. JTAG-loads the bitstream into FPGA SRAM (PCIe hard IP becomes live)
3. Warm-reboots the FPGA host via serial console
4. Issues SBR on bridge `00:15.0`, then reboots again
5. Checks for `10ee:7028` in `lspci` (retries up to `--max-attempts` times)
6. Loads XDMA driver; verifies 26 device nodes
7. Runs 9 smoke tests over serial console (no SSH required)

**Expected output (all PASS):**

```
PASS  pcie_device_present   — 01:00.0 Memory controller [10ee:7028]
PASS  pcie_link_speed       — 2.5GT/s Width x4
PASS  pcie_link_width       — x4
PASS  subsystem_id          — Subsystem 0007
PASS  xdma_driver_loaded    — xdma  110592  0
PASS  xdma_devnodes         — 26 nodes
PASS  ddr3_c0_loopback_1kb  — DDR3 C0 1KB write→read match
PASS  ddr3_c0_loopback_1mb  — DDR3 C0 1MB write→read match
PASS  bypass_bar_accessible — reg[0]=0x0
```

If `bringup_flash.py` reports `JTAG SRAM load: FAILED`, the JTAG cable may
have re-enumerated. The recovery is:

```bash
tool/hw/program_bitstream.sh <bit>                       # JTAG-load only
SERIAL_PORT=/dev/ttyUSB4 python3 tool/hw/bringup_flash.py <bit> --no-flash
#                       ^^^^^^^^^^^^^ — re-check with udevadm
```

---

## Individual tools

### Flash programming only

```bash
tool/hw/program_flash.sh ip/vivado/xc7k480t/top_npu.bit
```

Uses the Vivado BPI helper bitstream (`bpi_xc7k480t_pullnone.bit`) to route
CCLK to the flash bus, then programs `mt28gu512aax1e-bpi-x16` via
`program_hw_cfgmem`. Takes ~3 minutes.

### JTAG SRAM load only

```bash
tool/hw/program_bitstream.sh ip/vivado/xc7k480t/top_npu.bit
```

Loads directly into FPGA SRAM via JTAG. PCIe will train on the next warm
reboot (SRAM content survives warm reboot). Does NOT write to flash.

### Serial console

```bash
python3 tool/hw/serial_console.py "sudo lspci -nn | grep 10ee"   # one-shot
python3 tool/hw/serial_console.py                                # interactive
```

Environment overrides: `SERIAL_PORT` (default `/dev/ttyUSB0`), `SERIAL_USER`
(default current user). Assumes no-password login.

### Reboot + SBR loop (no re-flash)

```bash
tool/hw/reboot_and_load.sh
```

JTAG-loads current bitstream → reboots → SBR loop until PCIe enumerates.
Used for recovery when flash already has the right bitstream.

---

## MMALU compute tests (the V10 deliverable)

```bash
# Local pytest (requires SSH to the FPGA host configured as 'fpga'):
python3 -m pytest tool/hw/tests/test_mmalu_compute.py -v -m hw
```

Six tests, all PASS on the current V10 stack:

| Test | Setup | Pass criterion |
|:-----|:------|:---------------|
| `test_mmalu_done_smoke` | (any) | FSM kick → `done` within 1 s |
| `test_mmalu_zero_in_zero_out` | A=0, B=0, ACCUM=0 | OUT = all 0 |
| `test_mmalu_accum_passthrough` | A=0, B=0, random ACCUM | OUT == ACCUM (exact) |
| `test_mmalu_zero_a_kills_multiplier` | A=0, B≠0, random ACCUM | OUT == ACCUM |
| `test_mmalu_multiplier_alive` | A=10, B=7, ACCUM=0 | every OUT lane = 70 |
| `test_mmalu_outer_b_last` | random A, B, ACCUM | OUT[i] = A[i]·B[K-1] + ACCUM[i] |

The host writes A/B/ACCUM via `/dev/xdma0_h2c_0` to MIG C0 at
`0x4000_0000`, pulses `start=1` on ctrl_lite BAR2+0x0, polls `done`, then
reads OUT back via `/dev/xdma0_c2h_0`.

---

## Running pytest smoke tests

```bash
# All hardware tests:
pytest tool/hw/tests/ -v -m hw

# Specific tests:
pytest tool/hw/tests/test_pcie_link.py -v
pytest tool/hw/tests/test_ddr3_c0_loopback.py -v
pytest tool/hw/tests/test_npu_kick.py -v
pytest tool/hw/tests/test_mmalu_compute.py -v

# Exclude slow bandwidth tests:
pytest tool/hw/tests/ -v -m "hw and not slow"
```

Tests communicate with the FPGA host via XDMA device nodes (`/dev/xdma0_*`).
`conftest.py` fixtures: `xdma_dev` (XDMADevice), `bar` (BAR device for
ctrl_lite).

### Current test status (V10 bitstream)

| Test | Status |
|:-----|:-------|
| `test_pcie_link.py` | ✅ 5/5 PASS |
| `test_ddr3_c0_loopback.py` | ✅ 1KB + 1MB PASS |
| `test_bar_ctrl_lite.py` | ✅ PASS |
| `test_ddr3_c0_bandwidth.py` | ✅ PASS |
| `test_npu_kick.py` | ✅ PASS — FSM kick → done |
| `test_mmalu_compute.py` | ✅ 6/6 PASS |

---

## ILA-based hardware debug

If a regression in the `npu_dma_master` FSM or its AXI handshakes appears,
build the debug bitstream and capture cycle-accurate waveforms:

```bash
# Build with ILA:
~/Xilinx/2025.2/Vivado/bin/vivado -mode batch \
    -source ip/vivado/xc7k480t/scripts/build_npu_with_ila.tcl

# Flash:
python3 tool/hw/bringup_flash.py \
    ip/vivado/xc7k480t/top_npu_with_ila.bit
```

In Vivado HW Manager TCL (headless), point at the `.ltx` file, set a
trigger on `state == 4'd6` (S_READ_ACC_R) or `4'd10` (S_WR_W), arm the
ILA, kick the NPU via PCIe, upload + write the data to a CSV. The
`u_npu_ila` core has probes for `state`, `beat_cnt`, `rpipe_valid`,
`rlast_pipe`, `rdata_pipe[31:0]` plus AXI handshake shadow registers
(`dbg_rvalid`, `dbg_rready`, `dbg_rlast`, `dbg_rdata_lo`, `dbg_arvalid`,
`dbg_arready`, `dbg_state_d1`, `dbg_beat_cnt_d1`).

See `docs/implementations/FPGA_XC7K480T.md` § "ILA Debug Methodology" for
the worked example that root-caused the V10 `S_WR_W` write-phase bug.

---

## Cold-boot PCIe notes

The AMD FCH host cannot reliably enumerate PCIe on cold boot without
warm-boot + SBR. `bringup_flash.py` handles this automatically (typically
1 SBR attempt suffices).

**Two fixed bugs** that previously caused cold-boot failure:

1. `CONFIG_MODE=BPI16` set `COR0 DRIVEDONE=1` / `MATCH_CYCLE=2` → delayed
   DONE past FCH window. Fix: `CONFIG_MODE=SPIx1`, `CONFIGRATE=3`.
   COR0 = `0x02003fe5`.
2. `mig_sys_rst_n = axi_aresetn` → MIG held in reset until PCIe trains
   (deadlock). Fix: drive from board reset pin.

Both fixes are baked into `build_npu.tcl` / `build_npu_with_ila.tcl`.

---

## ctrl_lite register (BAR2 + 0x0)

| Bit | Field | Direction | Description |
|:----|:------|:----------|:------------|
| 0 | start | W | Write 1 to start NPU DMA+MMA cycle (self-clears) |
| 1 | done | RO | Pulses 1 when DMA write-back completes |
| 2 | busy | RO | Asserted while DMA is active |

Access via `tool/hw/tests/lib/reg_rw.py`:

```bash
python3 -c "
from tool.hw.tests.lib import reg_rw, xdma
dev = xdma.XDMADevice()
bar = reg_rw.BARDevice(dev.bypass_path)
print(hex(bar.read32(0)))  # should be 0x0 at idle
"
```

Or directly on the FPGA host:

```bash
sudo ~/dma_ip_drivers/XDMA/linux-kernel/tools/reg_rw /dev/xdma0_bypass 0x0 w
```

---

## Troubleshooting

| Symptom | Likely cause | Fix |
|:--------|:-------------|:----|
| No `10ee:7028` after 6 SBR attempts | Wrong bitstream in flash, or MIG not calibrating | Re-flash with `program_flash.sh`; check WNS in build log |
| XDMA loads 0 device nodes | PCIe enumerated but AXI hangs (MIG not calibrated) | Check that `top_npu.bit` or `top_npu_with_ila.bit` is the V10 build; verify COR0 = `0x02003fe5` |
| `bringup_flash.py` serial timeout | `/dev/ttyUSB0` not accessible or wrong baud | `ls /dev/ttyUSB*`; check the PL2303 with `udevadm info`; set `SERIAL_PORT` accordingly |
| `JTAG SRAM load failed` immediately after flashing | JTAG cable just reset; PL2303 moved to a new ttyUSB | Run `tool/hw/program_bitstream.sh` manually then resume bringup with `--no-flash` and a corrected `SERIAL_PORT` |
| `program_hw_cfgmem` fails | `hw_server` not running, or JTAG cable disconnected | `ps aux | grep hw_server`; check USB cable |
| DDR3 loopback data mismatch | Timing violation in bitstream | Check WNS in build log; rebuild if WNS < 0 on a data-plane net |
| MMALU OUT is shifted by one beat | V10 `S_WR_W` regression | Confirm `npu_dma_master.v` still has the pre-load in `S_WR_AW` and the `(beat_cnt+1)*4` advance in `S_WR_W`. Reproduce + diagnose with `build_npu_with_ila.tcl` and an ILA capture triggered on `state == 4'd10` |
| `reg_rw` returns 0xFFFFFFFF | BAR not mapped or fabric in reset | Check `dmesg | grep xdma`; verify PCIe at `01:00.0` |
