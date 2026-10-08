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
                          ─► byp_pc ─► npu_engine_ctrl_lite (multi-register map)
  M_AXI (DMA)      ──► axi_cc_xdma_in ─► axi_clkconv_xdma ─► axi_dwidth_xdma (128→512)
                          ─► axi_xbar.S00 ─► M00 ─► MIG C0 (DDR3 2 GB)
                                                          ▲
  npu_engine_subsys.m_axi ─► axi_clkconv_npu (200→133) ─► axi_dwidth_npu (128→512)
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

## 3. Engine ctrl register map (BAR2)

The current engine (`npu_engine_ctrl_lite.v`, reached through
`npu_engine_subsys`) exposes a **multi-register** AXI4-Lite map. Register
offsets are authoritative in
`drivers/chisel_npu_py/src/chisel_npu_py/config.py`:

| Offset | Register | R/W | Meaning |
|:-------|:---------|:----|:--------|
| `0x00` | CTRL | W/RO | bit0 `start` (W, edge, self-clears) · bit1 `done` (RO) · bit2 `busy` (RO) |
| `0x04` | FRAMES | W | config (reserved) |
| `0x08` | STATUS | RO | illegal[31] \| frames_done[30:16] \| pc[15:0] |
| `0x0C` | ERR_INFO | RO | faulting instruction word |
| `0x10` | FETCH_STATS | RO | prefetches[31:16] \| misses[15:0] |
| `0x14` | PROG_LEN | W | instruction count (words) |
| `0x18` | DBG | RO | winState[1:0] \| clct captured[15:0] |
| `0x1C` | DBG_MMA | RO | mma instructions accepted |

CTRL read values: `0x0` idle, `0x2` done, `0x4` busy, `0x6` busy+done.

```bash
# Start a program and poll done (0x2):
sudo ~/dma_ip_drivers/XDMA/linux-kernel/tools/reg_rw /dev/xdma0_bypass 0x0 w 0x1
sudo ~/dma_ip_drivers/XDMA/linux-kernel/tools/reg_rw /dev/xdma0_bypass 0x0 w
# Read engine status/pc or the accepted mma count:
sudo ~/dma_ip_drivers/XDMA/linux-kernel/tools/reg_rw /dev/xdma0_bypass 0x8 w
sudo ~/dma_ip_drivers/XDMA/linux-kernel/tools/reg_rw /dev/xdma0_bypass 0x1C w
```

Note: `start` is edge-sensitive; writes with bit0=0 are ignored. The legacy V10
`npu_ctrl_lite.v` had only the single CTRL register.

---

## 4. NPU staging protocol (host side)

The engine's `NpuDmaEngine` moves vectors between the staging sections in MIG
C0 and the VX/VE/VR register file; the program itself is staged in a `CODE`
section and executed by the frontend. Sections (K=16, from
`drivers/chisel_npu_py/src/chisel_npu_py/config.py`):

| Section | Address | Window | Content |
|:--------|:--------|:-------|:--------|
| A | `0x4000_0000` | 4 KiB | int8 operand vectors |
| B | `0x4000_0400` | 4 KiB | int8 operand vectors |
| ACCUM | `0x4000_0800` | 128 B | int32[K] |
| OUT | `0x4000_0880` | 4 KiB | int32 outputs |
| CODE | `0x4000_4000` | 256 KiB | program words |

Host flow (register-level; the `chisel_npu_py` driver does this for you):

```bash
# 1. Stage operands + program via XDMA DMA (addresses are byte addresses):
sudo tools/dma_to_device -d /dev/xdma0_h2c_0 -f a.bin      -s <bytes> -a 0x40000000
sudo tools/dma_to_device -d /dev/xdma0_h2c_0 -f b.bin      -s <bytes> -a 0x40000400
sudo tools/dma_to_device -d /dev/xdma0_h2c_0 -f acc.bin    -s 128     -a 0x40000800
sudo tools/dma_to_device -d /dev/xdma0_h2c_0 -f code.bin   -s <bytes> -a 0x40004000

# 2. Set PROG_LEN (instruction count words) then start:
sudo tools/reg_rw /dev/xdma0_bypass 0x14 w <words>
sudo tools/reg_rw /dev/xdma0_bypass 0x0  w 0x1

# 3. Poll done:
sudo tools/reg_rw /dev/xdma0_bypass 0x0 w    # expect 0x2

# 4. Read OUT:
sudo tools/dma_from_device -d /dev/xdma0_c2h_0 -f out.bin -s <bytes> -a 0x40000880
```

The engine fetches 16 B program lines through its shared DMA, caches them in an
8-set × 2-way LRU cache, and streams `mma` / `mma.last` sessions with per-`mma`
column capture. `NpuDmaEngine` carries the legacy DMA hardening: `rready` held
across all read phases, and a read-timeout retry that re-issues the AR.

---

## 5. Engine DMA (`NpuDmaEngine`) (for debugging)

The engine's single AXI4 master is `NpuDmaEngine`
(`src/main/scala/dma/npuDmaEngine.scala`), instantiated K=16 at the engine top.
It is request-driven (128-bit AXI, 16-byte beats):

- **L2R** (L3 → RF): burst-read 2/4/8 beats for VX/VE/VR (32/64/128 B), stage
  into a beat buffer, then one wide RF write per vector.
- **R2L** (RF → L3): combinational RF read, beat pack, AXI write (with the
  `wdata`-preload / `wvalid`-with-`awvalid` fix).
- **ACCUM** (`dir=2`): L3 → MMA accumulator buffer.
- **Fetch** (`dir=3`): one-beat read into the instruction line buffer.

Hardened behavior carried from the legacy `npu_dma_master.v`:
- `m_axi_rready` is held asserted from the first AR across all read phases.
- Each read has a **read-timeout retry** (`readTimeout = 16384` cycles):
  if no beat arrives, it re-issues the AR — self-healing the intermittent
  dropped read in the `axi_dwidth_npu` (128→512) / xbar S01 path.

If `busy` stays 1 with no `done`, capture with the engine ILA (build
`build_npu_engine_with_ila.tcl`; see the test-hw skill) or read the engine
`dbg_*` status via the `DBG` (`0x18`) / `DBG_MMA` (`0x1C`) registers. The
legacy V10 `npu_dma_master.v` FSM (`S_READ_A_AR … S_WR_W … S_DONE`) is the
superseded K=32 path.

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

# Legacy V10 hardware suite against the live board:
python3 -m pytest tool/hw/tests/ -v -m hw --fpga-host "$FPGA_HOST" --skip-program

# Current engine session tests (driver-level, over XDMA):
make py-test-hw
# or, on the FPGA host: python3 tool/hw/engine_smoke4.py
```

Key test files / helpers:
- `drivers/chisel_npu_py/tests/test_program_engine.py` — **current engine** session tests (one-shot, multi-column, illegal-halt, status).
- `tool/hw/engine_smoke4.py` — register-level K=16 MAC / chained GEMM on silicon.
- `test_npu_kick.py` — *legacy V10* start→done handshake (the canary: fails if BAR/ctrl_lite/DMA path is broken).
- `test_mmalu_compute.py` — *legacy V10* one-shot operand staging, kick, read-back, math check.
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
