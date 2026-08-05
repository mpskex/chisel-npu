---
name: npu-xdma-driver-integration
description: Use when integrating the NPU with the host via PCIe XDMA and the Linux xdma driver — includes building/loading xdma.ko, /dev/xdma0_* device nodes, reg_rw/dma_to_device/dma_from_device tools, ctrl_lite BAR protocol, host→DDR3 operand staging, the NPU kick/done/busy handshake, DMA engine behavior, and host-side integration troubleshooting. Also use for any question about the NPU software/hardware interface or driver bring-up.
---

# NPU ↔ XDMA Driver Integration — xc7k480t PCIe Card

This skill covers the **software/hardware interface** between the host and the NPU:
the Xilinx XDMA Linux driver, the PCIe BAR, the ctrl_lite control register, the
DMA staging protocol, and how the NPU's internal DMA master consumes host data.

The companion skills cover building the bitstream (`build-fpga-xc7k480t`) and
testing/flashing the board (`test-hw-xc7k480t`).

---

## 1. Architecture at a glance

```
Host (x86) ── PCIe Gen1 x4 ──► XDMA 4.2 (axi_aclk 125 MHz)
                                 │
  BAR0 (user)      ──► AXI-Lite user space (unused on NPU bitstream)
  BAR2 (bypass)    ──► axi_clkconv_byp (125→200) ─► byp_dw (128→32)
                          ─► byp_pc ─► ctrl_lite (200 MHz, single CTRL reg)
  M_AXI (DMA)      ──► axi_cc_xdma_in ─► axi_clkconv_xdma ─► axi_dwidth_xdma (128→512)
                          ─► axi_xbar.S00 ─► M00 ─► MIG C0 (DDR3 2 GB)
                                                          ▲
  npu_subsys.m_axi ─► axi_clkconv_npu (200→133) ─► axi_dwidth_npu (128→512)
                          ─► axi_xbar.S01 ─► M00/M01 ─► MIG C0/C1
```

Key point: **both the host (via XDMA M_AXI) and the NPU DMA master share the
same 4 GB DDR3 address space** through `axi_xbar`:
`0x0000_0000..0x7FFF_FFFF` → MIG C0, `0x8000_0000..0xFFFF_FFFF` → MIG C1.

---

## 2. Linux xdma driver

### 2.1 Build (required after every kernel update)

The FPGA host (`10.16.0.31`) auto-updates its kernel; `xdma.ko` then fails with
`Invalid module format` / `disagrees about version of symbol module_layout`.

```bash
ssh -i ~/.ssh/id_fpga_local 10.16.0.31 '
  cd ~/dma_ip_drivers/XDMA/linux-kernel/xdma && \
  make -s && \
  sudo rmmod xdma 2>/dev/null; sudo insmod xdma.ko
'
```

Requires kernel headers: `ls /lib/modules/$(uname -r)/build`.

### 2.2 Load / unload

```bash
sudo insmod xdma.ko          # creates /dev/xdma0_* nodes
sudo rmmod xdma              # safe only when no transfers are in flight
```

The kernel may udev-rename nodes; verify with `ls /dev/xdma0_*`.

### 2.3 Device nodes (26 on this setup)

| Node | Purpose |
|:-----|:--------|
| `/dev/xdma0_user` | BAR0 AXI-Lite user space (may not exist on NPU bitstream) |
| `/dev/xdma0_bypass` | BAR2 AXI-Lite bypass → **ctrl_lite** (NPU control) |
| `/dev/xdma0_h2c_0..N` | host→card DMA channels (write DDR3) |
| `/dev/xdma0_c2h_0..N` | card→host DMA channels (read DDR3) |
| `/dev/xdma0_events_*` | interrupts / events |

### 2.4 Userspace tools (`~/dma_ip_drivers/XDMA/linux-kernel/tools/`)

| Tool | Usage |
|:-----|:------|
| `reg_rw` | read/write a BAR register: `reg_rw /dev/xdma0_bypass 0x0 w` (read), `reg_rw /dev/xdma0_bypass 0x0 w 0x1` (write) |
| `dma_to_device` | host→FPGA: `dma_to_device -d /dev/xdma0_h2c_0 -f file.bin -s <bytes> -a <addr>` |
| `dma_from_device` | FPGA→host: `dma_from_device -d /dev/xdma0_c2h_0 -f file.bin -s <bytes> -a <addr>` |
| `performance` | bandwidth measurement (see §6 — known to under-measure) |

### 2.5 reg_rw output format — IMPORTANT

`reg_rw` prints the address AND the value:

```
Read 32-bit value at address 0x0 (0x7bfc50a5f000): 0x00000002
```

When parsing programmatically, take the **LAST** `0x` token (the value).
The first `0x` token is the address — a previous parser bug that took the
first token silently returned `0` for every register read and made every
"wait for done" test fail. (Fixed in `tool/hw/tests/lib/xdma.py::reg_read`.)

---

## 3. ctrl_lite control register (BAR2 + 0x0)

The host controls the NPU through a single 32-bit register (`npu_ctrl_lite.v`):

| Bit | Field | R/W | Meaning |
|:----|:------|:----|:--------|
| 0 | `start` | W | write 1 → 1-cycle start pulse to the NPU DMA master (self-clears) |
| 1 | `done_latch` | RO | latched 1 when the DMA master finishes; cleared on next start |
| 2 | `busy` | RO | level; 1 while the DMA master FSM is active |

Read values: `0x0` idle, `0x2` done, `0x4` busy, `0x6` busy+done.

```bash
# Kick the NPU and poll (0x2 = done):
sudo ~/dma_ip_drivers/XDMA/linux-kernel/tools/reg_rw /dev/xdma0_bypass 0x0 w 0x1
sudo ~/dma_ip_drivers/XDMA/linux-kernel/tools/reg_rw /dev/xdma0_bypass 0x0 w
```

Note: `start` is edge-sensitive; writes with bit0=0 are ignored. `done_latch`
persists until the next `start`.

---

## 4. NPU operand staging protocol (host side)

The NPU's internal DMA master (`npu_dma_master.v`, K=32) reads its operands
from fixed DDR3 addresses in MIG C0 and writes the result back:

| Region | Address | Size | Content |
|:-------|:--------|:-----|:--------|
| Matrix A | `0x4000_0000` | 32 B | 32 × int8 |
| Matrix B | `0x4000_0100` | 32 B | 32 × int8 |
| ACCUM | `0x4000_0200` | 128 B | 32 × int32 (initial accumulator) |
| OUT | `0x4000_0400` | 128 B | 32 × int32 (result, written back) |

Host flow:

```bash
# 1. Stage operands via XDMA DMA:
sudo tools/dma_to_device -d /dev/xdma0_h2c_0 -f a.bin    -s 32   -a 0x40000000
sudo tools/dma_to_device -d /dev/xdma0_h2c_0 -f b.bin    -s 32   -a 0x40000100
sudo tools/dma_to_device -d /dev/xdma0_h2c_0 -f acc.bin  -s 128  -a 0x40000200

# 2. Kick:
sudo tools/reg_rw /dev/xdma0_bypass 0x0 w 0x1

# 3. Poll done (≤ ~11 µs at 200 MHz):
sudo tools/reg_rw /dev/xdma0_bypass 0x0 w    # expect 0x2

# 4. Read OUT:
sudo tools/dma_from_device -d /dev/xdma0_c2h_0 -f out.bin -s 128 -a 0x40000400
```

The NPU DMA master internally performs: read A (2×128-bit) → read B (2×128-bit)
→ read ACCUM (8×128-bit) → kick MMALU → wait `clct` (64 cycles) → write OUT
(8×128-bit) → assert done. Total ~2200 cycles.

---

## 5. NPU DMA master FSM (for debugging)

States (`npu_dma_master.v`): `S_IDLE=0, S_READ_A_AR=1, S_READ_A_R=2,
S_READ_B_AR=3, S_READ_B_R=4, S_READ_ACC_AR=5, S_READ_ACC_R=6, S_KICK=7,
S_WAIT_CLCT=8, S_WR_AW=9, S_WR_W=10, S_WR_B=11, S_DONE=12`.

Known-hardened behavior (2026-07-31):
- `m_axi_rready` is held asserted across all read phases.
- Each read-data state has a **read-timeout retry** (16K cycles ≈ 82 µs):
  if no beat arrives, it re-issues the AR. This self-heals the intermittent
  dropped-read in the `axi_dwidth_npu` (128→512) / xbar S01 path.
- All reads use AXI_ID=1, ARLEN=1 (A/B) and ARLEN=7 (ACCUM).

If `busy` stays 1 with no `done`, the FSM is stuck. Check via ILA
(`top_npu_with_ila.bit`, probe `top_i/npu_subsys/inst/u_dma/state[3:0]`):
stuck in `S_READ_B_R` = B read dropped (old bitstream), stuck in `S_WAIT_CLCT`
= MMALU issue.

---

## 6. Known integration issues

1. **Bandwidth `performance` tool under-measures** (0.01–0.02 GB/s on Gen1×4,
   below any sane floor). It reports a tiny window (`clock_cycle_count=627`,
   `data duty cycle=1%`). The DMA itself is fine — byte-exact loopback tests
   pass. Don't trust `performance` for acceptance; use
   `dma_to_device`+`dma_from_device`+`cmp` instead.
2. **Kernel updates invalidate xdma.ko** — always rebuild after `uname -r`
   changes (§2.1).
3. **`/dev/xdma0_user` may be absent** on the NPU bitstream — the BAR discovery
   in `tool/hw/tests/lib/reg_rw.py` falls back to `/dev/xdma0_bypass`.
4. **DDR3 loopback intermittently mismatches** right after some reboots —
   marginal MIG calibration. Retry or reboot; check `c0_init_calib_complete`.
5. **Never power off the FPGA host** — no remote power-on exists. Reboot only.

---

## 7. Test workflow (host side)

```bash
source .env.sh
export PATH="$HOME/miniconda3/bin:$PATH"     # host python has no pip; miniconda has pytest+numpy

# Full hardware suite against the live board:
python3 -m pytest tool/hw/tests/ -v -m hw --fpga-host "$FPGA_HOST" --skip-program

# Just the integration-relevant pieces:
python3 -m pytest tool/hw/tests/test_npu_kick.py \
                    tool/hw/tests/test_mmalu_compute.py -v --fpga-host "$FPGA_HOST" --skip-program
```

Key test files:
- `test_npu_kick.py` — start→done handshake (the canary: fails if BAR/ctrl_lite/DMA path is broken).
- `test_mmalu_compute.py` — stages operands, kicks, reads OUT, checks math.
- `test_ddr3_c0_loopback.py` — XDMA DMA data integrity (proves host→DDR3 path).
- `tool/hw/tests/lib/xdma.py` — the XDMA wrapper (reg_read/reg_write/h2c/c2h).

## 8. Useful environment

```bash
source .env.sh   # exports: VIVADO, VIVADO_JOBS, VIVADO_IMPL_STRATEGY,
                 # FPGA_HOST, SSH_IDENTITY, BITSTREAM, HW_SERVER
```

| Variable | Default |
|:---------|:--------|
| `FPGA_HOST` | (set in `.env.sh`, e.g. `10.16.0.31`) |
| `SSH_IDENTITY` | `~/.ssh/id_fpga_local` |
| `TOOLS_DIR` (in tests) | `~/dma_ip_drivers/XDMA/linux-kernel/tools` on the FPGA host |
