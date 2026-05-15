---
name: test-hw-xc7k480t
description: Use when testing, flashing, or debugging the xc7k480tffg1156-2 FPGA board — includes bringup_flash.py, program_flash.sh, serial console, XDMA smoke tests, and PCIe/DDR3 debugging. Also use for any question about the FPGA hardware bring-up flow or NPU validation on real hardware.
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
| DDR3 | Dual-channel MIG: C0 = host DMA @ 0x0 [2 GB], C1 = NPU DMA |
| Flash | `mt28gu512aax1e-bpi-x16` (Micron 512 Mbit BPI) |
| Serial | `/dev/ttyUSB0`, 115200 8N1, PL2303 USB-UART |
| JTAG | FT4232H quad USB, `hw_server` at `localhost:3121` |
| PCIe address | `01:00.0`, vendor `10ee`, device `7028`, subsystem `0007` |
| PCIe bridge for SBR | `00:15.0` |

---

## Standard bring-up (flash + test)

The **canonical bring-up script** is `tool/hw/bringup_flash.py`. It handles
everything: flash programming, JTAG SRAM load, SBR loop, driver load, and 9
smoke tests over the serial console — no SSH required.

```bash
cd /path/to/chisel-npu

# Full flow: flash → JTAG load → reboot → SBR loop → smoke tests
python3 tool/hw/bringup_flash.py \
    ip/vivado/xc7k480t/top_v9_npu_full.bit \
    --max-attempts 6

# Skip re-flashing (reuse what's already in BPI flash):
python3 tool/hw/bringup_flash.py \
    ip/vivado/xc7k480t/top_v9_npu_full.bit \
    --skip-flash
```

**What the script does:**
1. Programs bitstream to BPI flash via Vivado `program_hw_cfgmem` (~3 min)
2. JTAG-loads the bitstream into FPGA SRAM (PCIe hard IP becomes live)
3. Warm-reboots the FPGA host via serial console
4. Issues SBR on bridge `00:15.0`, then reboots again
5. Checks for `10ee:7028` in lspci (retries up to `--max-attempts` times)
6. Loads XDMA driver; verifies 26 device nodes
7. Runs 9 smoke tests over serial console

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

---

## Individual tools

### Flash programming only

```bash
tool/hw/program_flash.sh ip/vivado/xc7k480t/top_v9_npu_full.bit
```

Uses the Vivado BPI helper bitstream (`bpi_xc7k480t_pullnone.bit`) to route
CCLK to the flash bus, then programs `mt28gu512aax1e-bpi-x16` via
`program_hw_cfgmem`. Takes ~3 minutes. Requires `hw_server` on `localhost:3121`.

### JTAG SRAM load only

```bash
tool/hw/program_bitstream.sh ip/vivado/xc7k480t/top_v9_npu_full.bit
```

Loads directly into FPGA SRAM via JTAG. PCIe will train on the next warm
reboot (SRAM content survives warm reboot). Does NOT write to flash.

### Serial console

```bash
# Run a single command and return:
python3 tool/hw/serial_console.py "sudo lspci -nn | grep 10ee"

# Interactive shell on FPGA host:
python3 tool/hw/serial_console.py
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

## Running pytest smoke tests

All tests require `pytest.ini` markers (`hw`, `slow`).

```bash
# All hardware smoke tests (requires PCIe active):
pytest tool/hw/tests/ -v -m hw

# Specific tests:
pytest tool/hw/tests/test_pcie_link.py -v
pytest tool/hw/tests/test_ddr3_c0_loopback.py -v
pytest tool/hw/tests/test_npu_kick.py -v   # NPU functional test

# Exclude slow bandwidth tests:
pytest tool/hw/tests/ -v -m "hw and not slow"
```

Tests communicate with the FPGA host via XDMA device nodes (`/dev/xdma0_*`).
`conftest.py` fixtures: `xdma_dev` (XDMADevice), `bar` (BAR device for ctrl_lite).

### Current test status

| Test | Status |
|:-----|:-------|
| `test_pcie_link.py` (5 tests) | ✅ PASS |
| `test_ddr3_c0_loopback.py` (1kb, 1mb) | ✅ PASS |
| `test_bar_ctrl_lite.py` | ✅ PASS (bypass_bar_accessible) |
| `test_ddr3_c0_bandwidth.py` | ✅ PASS |
| `test_npu_kick.py` | ⏳ Requires NPU bitstream wired to DMA master |

---

## Cold-boot PCIe notes

The AMD FCH host cannot reliably enumerate PCIe on cold boot without warm-boot +
SBR. `bringup_flash.py` handles this automatically (typically 1 SBR attempt).

**Two fixed bugs** that previously caused cold-boot failure:
1. `CONFIG_MODE=BPI16` set `COR0 DRIVEDONE=1` / `MATCH_CYCLE=2` → delayed DONE past FCH window. Fix: `CONFIG_MODE=SPIx1`, `CONFIGRATE=3`. COR0 = `0x02003fe5`.
2. `mig_sys_rst_n = axi_aresetn` → MIG held in reset until PCIe trains (deadlock). Fix: drive from board reset pin.

---

## ctrl_lite register (BAR2 + 0x0)

| Bit | Field | Direction | Description |
|:----|:------|:----------|:------------|
| 0 | start | W | Write 1 to start NPU DMA+MMA cycle (self-clears) |
| 1 | done | RO | Pulses 1 when DMA write-back completes |
| 2 | busy | RO | Asserted while DMA is active |

Access via `tool/hw/tests/lib/reg_rw.py` or direct:
```bash
# Verify ctrl_lite responds (should read 0x0 at idle):
python3 -c "
from tool.hw.tests.lib import reg_rw, xdma
dev = xdma.XDMADevice()
bar = reg_rw.BARDevice(dev.bypass_path)
print(hex(bar.read32(0)))
"
```

---

## Troubleshooting

| Symptom | Likely cause | Fix |
|:--------|:-------------|:----|
| No `10ee:7028` after 6 SBR attempts | Wrong bitstream in flash, or MIG not calibrating | Re-flash with `program_flash.sh`; check WNS in build log |
| XDMA loads 0 device nodes | PCIe enumerated but AXI hangs (MIG not calibrated) | Check that `top.sv` is the V9 build; verify COR0 = `0x02003fe5` |
| `bringup_flash.py` serial timeout | `/dev/ttyUSB0` not accessible or wrong baud | `ls /dev/ttyUSB*`; check `SERIAL_PORT` env var |
| `program_hw_cfgmem` fails | `hw_server` not running, or JTAG cable disconnected | `ps aux | grep hw_server`; check USB cable |
| DDR3 loopback data mismatch | Timing violation in bitstream | Check WNS in build log; rebuild if WNS < 0 |
| reg_rw returns 0xFFFFFFFF | BAR not mapped or fabric in reset | Check `dmesg | grep xdma`; verify PCIe at `01:00.0` |
