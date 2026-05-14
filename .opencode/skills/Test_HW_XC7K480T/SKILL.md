# Skill: Hardware Testing for xc7k480tffg1156-2 FPGA Platform

## CRITICAL RULES — READ FIRST

**NEVER issue `poweroff`, `shutdown`, `systemctl poweroff`, or any equivalent command
on the FPGA box (`ssh fpga ...`). There is NO remote power-on capability (no IPMI, no
WOL, no remote PDU). Powering the machine off leaves it permanently unreachable until
someone physically presses the power button. Use `sudo /sbin/reboot` for a restart.**

---

## Trigger

Use this skill when the user asks to:
- Flash / program the FPGA bitstream to the BPI flash chip
- Test PCIe link, BAR registers, or DDR3 DMA on the FPGA board
- Run smoke tests or bring-up validation
- Debug XDMA driver issues on the FPGA box
- Modify the XDMA kernel driver or host-side tools

---

## Environment

### Machines

| Role | Alias / Host | Notes |
|---|---|---|
| **Dev host** | (local, this machine) | Vivado 2025.2, FT4232H JTAG USB, hw_server on :3121 |
| **FPGA box** | `ssh fpga` (Ubuntu 24.04, AMD FCH platform) | PCIe target |

### Local dev host

- JTAG cable: FT4232H quad USB (`0403:6011`)
- Local hw_server: `~/Xilinx/2025.2/Vivado/bin/hw_server`, running on `127.0.0.1:3121`
- Local xsdb: `~/Xilinx/2025.2/Vivado/bin/xsdb`
- Bitstream: `ip/vivado/xc7k480t/npu_fpga/npu_fpga.runs/impl_1/top_npu.bit` (18 MB, WNS=+0.003 ns)
- Repo root: `<repo root>` (wherever this repo is checked out)

### FPGA box (`ssh fpga`)

- Host: Ubuntu 24.04.1 LTS, AMD FCH platform
- PCIe device: `01:00.0`, vendor `10ee`, device `7028` — **only present when FPGA configured from flash at boot**
- PCIe bridge: `00:15.0` (AMD SB700 PCIe port 0, Gen1 x1 max per bridge but card is Gen2 x8)
- XDMA driver: pre-built at `~/dma_ip_drivers/XDMA/linux-kernel/xdma/xdma.ko`
- XDMA tools: pre-built at `~/dma_ip_drivers/XDMA/linux-kernel/tools/`
- Load script: `~/dma_ip_drivers/XDMA/linux-kernel/tests/load_driver.sh`
- BPI flash: `mt28gu512aax1e-bpi-x16` (Micron 512 Mb, connected to FPGA config bank)
- `sudo`: passwordless for `/sbin/reboot` (via `/etc/sudoers.d/`)
- `sudo setpci`, `sudo tee /sys/bus/pci/...`: passwordless via sudoers

---

## ⚠ Critical Platform Constraints

### BPI Flash Indirect Programming — BLOCKED

Vivado `program_hw_cfgmem` with `mt28gu512aax1e-bpi-x16` **always fails**:
```
ERROR: [Labtools 27-3347] Flash Programming Unsuccessful: Failure to set flash parameters.
```

**Root cause**: The FT4232H JTAG cable cannot complete the CFI query/handshake that
Vivado's internal flash-programmer kernel requires to identify and configure the MT28GU512.
This failure occurs regardless of:
- CONFIGRATE setting (33, 6, 1 MHz — all fail)
- PERSIST YES/NO in bitstream
- Bitstream used as JTAG intermediary (our bitstream, reference bitstream — both fail)
- Flash part name (mt28gu512aax1e, mt28ew512a, 28f512p30e — all fail)

**Required to program flash**:
- A dedicated parallel NOR flash programmer (Dediprog SF100/600, BPM Microsystems, etc.)
- OR a Xilinx Platform Cable USB II (PCUSB) — not FT4232H
- OR a Digilent HS3 cable (tested to support BPI indirect on 7-series)

**Bitstream config constraints added** (`npu_top.xdc`) in preparation for flash programming:
```
BITSTREAM.CONFIG.CONFIGRATE    = 33
BITSTREAM.CONFIG.BPI_SYNC_MODE = DISABLE
CONFIG_MODE                    = BPI16
BITSTREAM.GENERAL.COMPRESS     = TRUE
CFGBVS                         = VCCO
CONFIG_VOLTAGE                 = 3.3
BITSTREAM.CONFIG.PERSIST       = YES
```

### PCIe Enumeration — Requires Flash Boot

The AMD FCH PCIe controller on the FPGA box enumerates PCIe during BIOS POST.
Bus number assignment depends on which devices are physically present at scan time:
- **Flash-booted FPGA present**: device appears at `01:00.0` (BIOS assigns bus 01 to PCIe slot)
- **FPGA blank at boot**: `01` is assigned to conventional PCI bridge `00:14.4`; FPGA slot is `02`

**Post-JTAG SBR is insufficient**: After JTAG programming mid-session, the PCIe hard IP
does NOT complete link training (Link Status register = 0x0000 even after 30+ seconds).
The AMD FCH controller has already given up. Secondary Bus Reset (`00:15.0`) and
full PCIe rescan do not recover the link.

**Only reliable path for PCIe**: Program BPI flash → power cycle (cold boot) → FPGA
auto-configures during POST → PCIe link trains → host enumerates at `01:00.0`.

---

## Correct Bring-Up Flow (when BPI flash is programmed)

```bash
# On dev host, from repo root:
# 1. Program BPI flash via hardware programmer (external tool, one-time per bitstream)
#    See "Flash Programming" section below.

# 2. Cold power cycle the FPGA box (flash auto-configures FPGA during POST)
ssh fpga "sudo reboot"
# Wait ~90 s for POST + DDR3 calibration + OS boot

# 3. Verify PCIe device appears
ssh fpga "lspci -d 10ee:7028"
# Expected: 01:00.0 Memory controller: Xilinx Corporation Device 7028

# 4. Load XDMA driver
ssh fpga "cd ~/dma_ip_drivers/XDMA/linux-kernel/tests && sudo ./load_driver.sh 0"
ssh fpga "ls /dev/xdma0_*"   # should show ~16 device nodes

# 5. Run smoke tests
python -m pytest tool/hw/tests/ -v -m "not slow"
```

---

## Flash Programming Procedures

### Option A: External Hardware Programmer (recommended)

Connect a parallel NOR flash programmer to the `mt28gu512aax1e-bpi-x16` chip via
the board's programming header (if available) or directly to the IC pins.

1. Generate MCS file on dev host:
   ```bash
   cd <repo-root>
   cat > /tmp/gen_mcs.tcl << 'EOF'
   write_cfgmem -format mcs -interface BPIx16 -size 512 \
       -loadbit "up 0x00000000 ip/vivado/xc7k480t/npu_fpga/npu_fpga.runs/impl_1/top_npu.bit" \
       -force -file /tmp/top_npu_flash.mcs
   EOF
   ~/Xilinx/2025.2/Vivado/bin/vivado -mode batch -source /tmp/gen_mcs.tcl
   ```
2. Transfer `/tmp/top_npu_flash.mcs` to the programmer and write to flash.

### Option B: Vivado HW Manager (requires Xilinx Platform Cable USB II or Digilent HS3)

```bash
# From Vivado TCL console (batch mode):
open_hw_manager
connect_hw_server -url localhost:3121
open_hw_target
set dev [lindex [get_hw_devices] 0]; current_hw_device $dev
set_property PROGRAM.FILE {top_npu.bit} $dev; program_hw_devices $dev
create_hw_cfgmem -hw_device $dev \
    -mem_dev [lindex [get_cfgmem_parts {mt28gu512aax1e-bpi-x16}] 0]
set cfgmem [get_property PROGRAM.HW_CFGMEM $dev]
set_property PROGRAM.FILES  {/tmp/top_npu_flash.mcs} $cfgmem
set_property PROGRAM.ERASE  1 $cfgmem
set_property PROGRAM.VERIFY 1 $cfgmem
program_hw_cfgmem -hw_cfgmem $cfgmem
```

---

## Current Test Status

| Test | Status | Blocker |
|---|---|---|
| Flash programming | ❌ BLOCKED | FT4232H cannot do BPI indirect programming |
| PCIe enumeration | ❌ BLOCKED | Requires flash-booted FPGA |
| `test_pcie_link.py` | ❌ Untested | Requires PCIe device at `01:00.0` |
| `test_bar_ctrl_lite.py` | ❌ Untested | Requires XDMA dev nodes |
| `test_ddr3_c0_loopback.py` | ❌ Untested | Requires XDMA dev nodes |
| `test_ddr3_c0_bandwidth.py` | ❌ Untested | Requires XDMA dev nodes |
| `test_npu_kick.py` | ❌ XFAIL | Phase 2 (host→C1 path not implemented) |

Tests are ready to run once the BPI flash contains the NPU bitstream.

---

## JTAG Programming (SRAM only — for debug, not PCIe)

```bash
# From repo root on dev host:
tool/hw/program_bitstream.sh
# or: tool/hw/program_bitstream.sh path/to/top_npu.bit
```

JTAG programs FPGA SRAM. After JTAG programming:
- The FPGA **runs the bitstream** (DDR3 calibrates, fabric runs at 200 MHz)
- PCIe is **NOT accessible** (link training won't complete after JTAG mid-session)
- Useful for: verifying the bitstream loads without errors (DONE LED asserts)

---

## ctrl_lite Register Map (once PCIe is available)

Access via `/dev/xdma0_user` (BAR0):

| Offset | Field | Notes |
|---|---|---|
| 0x00 | bit[0] = start (W), bit[1] = done (R), bit[2] = busy (R) | NPU FSM control |

```bash
# Read:
ssh fpga "~/dma_ip_drivers/XDMA/linux-kernel/tools/reg_rw /dev/xdma0_user 0x0 w"
# Write start=1:
ssh fpga "~/dma_ip_drivers/XDMA/linux-kernel/tools/reg_rw /dev/xdma0_user 0x0 w 0x1"
```

---

## Driver Modifications

```bash
# 1. Edit source in driver/linux/
# 2. Sync to remote:
rsync -av --exclude='*.o' --exclude='*.ko' --exclude='*.mod*' \
    driver/linux/ fpga:~/dma_ip_drivers/XDMA/linux-kernel/
# 3. Build on remote:
ssh fpga "make -C ~/dma_ip_drivers/XDMA/linux-kernel/xdma clean all"
ssh fpga "make -C ~/dma_ip_drivers/XDMA/linux-kernel/tools clean all"
# 4. Reload (only works when PCIe is active):
ssh fpga "cd ~/dma_ip_drivers/XDMA/linux-kernel/tests && sudo ./load_driver.sh 0"
```

---

## Failure Modes

| Symptom | Cause | Fix |
|---|---|---|
| `program_hw_cfgmem`: "Failure to set flash parameters" | FT4232H cable cannot do BPI indirect programming | Use hardware programmer or different cable |
| No `10ee:7028` in lspci after boot | FPGA not configured from flash (blank or old design) | Program BPI flash with correct MCS |
| No `10ee:7028` after JTAG program + SBR | PCIe link doesn't train mid-session on AMD FCH | Cannot fix; must flash and cold-boot |
| xdma driver loads but no devices recognized | FPGA not presenting as PCIe device | Same as above |
| `/dev/xdma0_*` missing | Driver load failed | Check `dmesg | grep xdma`; ensure PCIe device is at `01:00.0` |
| `reg_rw` returns `0xFFFFFFFF` | BAR not mapped or fabric in reset | Check DDR3 calib and fabric_aresetn |
| DMA transfer hangs | MIG C0 not responding | Check PCIe completion timeout in dmesg |
