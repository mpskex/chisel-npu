---
name: python-npu-driver
description: Use when working with the chisel_npu_py Python userspace driver — the pybind11 XDMA driver for the NPU (drivers/chisel_npu_py/). Includes the NativeXDMA C++ boundary, numpy-first API (ChiselNPU.mmalu, XDMADevice, CtrlLite), the MMALU staging table, pybind11 build/deploy to the FPGA host (make py-deploy/py-test-hw/py-test-unit), the selftest, unit tests with FakeNative, and troubleshooting (numpy<2 CPU baseline, pybind11 3.x API changes, udev permissions). Also use for any question about running Python against the NPU over /dev/xdma0_* device nodes.
---

# Python NPU Driver — `chisel_npu_py`

The Python userspace driver for the Chisel NPU over the Xilinx XDMA kernel
driver. Source lives in `drivers/chisel_npu_py/`; installed on the FPGA host
at `~/chisel_npu_py/` (venv at `~/chisel_npu_py/.venv`).

The companion skill `npu-xdma-driver-integration` covers the kernel driver,
the C tools, and the ctrl_lite protocol at the device level; this skill
covers the Python package that wraps them.

## Architecture

```
tests (pytest, native on FPGA host)
   │
chisel_npu_py (Python: orchestration, bit protocol, injectable native)
   ├── ChiselNPU   — mmalu(A, B, ACCUM[, OUT]): stage → kick → wait → collect
   ├── CtrlLite    — start/done/busy bit protocol, wait_done
   ├── XDMADevice  — typed numpy/bytes API over the native module
   └── _native.so  ── pybind11 C++ module ── owns: fds, DDR addresses,
                                          staging table, alignment, transfers
                              │
                     /dev/xdma0_* (xdma.ko kernel driver)
```

**Rule of the house**: the pybind11 module (`NativeXDMA`, in
`src/chisel_npu_py/native_src/native.cpp`) is the ONLY place that handles
fds, DDR addresses, register offsets and transfers. Python only passes
buffers (numpy arrays, bytes, bytearray, memoryview) — never an address for
staged operands.

## Quick usage

```python
import numpy as np
from chisel_npu_py import ChiselNPU

npu = ChiselNPU()                          # opens /dev/xdma0_h2c_0, _c2h_0, _bypass
a   = np.full(32, 10, dtype=np.int8)
b   = np.full(32, 7,  dtype=np.int8)
acc = np.zeros(32, dtype=np.int32)
out = npu.mmalu(a, b, acc)                 # int32[32] result
```

Low-level pieces:

```python
from chisel_npu_py import XDMADevice, CtrlLite

dev = XDMADevice()
dev.dma_write(0x4000_0000, a)              # raw DMA; address validated in C++
raw   = dev.dma_read(0x4000_0400, 128)     # uint8 ndarray
words = dev.read_i32(0x4000_0400, 32)
dev.write_staged("A", a)                   # staged operand (size-checked)
dev.read_staged("OUT", out)

ctrl = CtrlLite(dev)
ctrl.kick(); ctrl.wait_done(timeout_s=2.0) # done latch poll
```

## Staging table (authoritative in C++)

| Operand | DDR address (MIG C0) | Size | Contents |
|:--------|:---------------------|:-----|:---------|
| A       | `0x4000_0000`        | 32 B  | 32 × int8 |
| B       | `0x4000_0100`        | 32 B  | 32 × int8 |
| ACCUM   | `0x4000_0200`        | 128 B | 32 × int32 |
| OUT     | `0x4000_0400`        | 128 B | 32 × int32 |

`kStaging` in `native.cpp` is the source of truth; `consts.py` mirrors it.
`XDMADevice.staging_map()` returns the runtime truth. Staged operands are
name- AND byte-size-checked — wrong-size buffers raise `ValueError`.

## Native module invariants (all enforced in C++)

- `addr % 4 == 0`, `len % 4 == 0`, non-zero length, address within the 4 GB
  DDR window (`0x0000_0000..0xFFFF_FFFF`) → else `ValueError`.
- DMA = `pwrite`/`pread` with file offset = AXI address (same semantics as
  `cdev_sgdma.c` `xdma_xfer_submit(..., *pos, ...)`), full-transfer loop.
- Registers = 1-page `mmap` of `/dev/xdma0_bypass` (same as `reg_rw`).
- Inputs copied to C-contiguous when needed; outputs (read-back) must be
  C-contiguous AND writable — no silent copies (`py::value_error`).
- Errors: `XDMAError` (device/native), `NPUError` (protocol),
  `NPUTimeoutError` (wait_done timeout).

## Commands

```bash
source .env.sh                             # FPGA_HOST, SSH_IDENTITY, ...
make py-build                              # sdist (dev host)
make py-deploy                             # rsync + venv + build + udev + selftest
make py-test-unit                          # 25 mock tests, no hardware (dev host)
make py-test-hw                            # 14 hw tests natively on the FPGA host
```

Deploy (`drivers/chisel_npu_py/tool/deploy.sh`) is idempotent: rsync →
`python3-dev` (apt, if `Python.h` missing) → venv (+`python3-venv` fallback)
→ `pip install pytest 'numpy<2'` → `pip install .` (PEP 517 pulls pybind11;
compiles the extension **on the host in the target venv**) → udev rule
`SUBSYSTEM=="xdma", MODE="0666"` + chmod live nodes → selftest.

## Testing

- `tests/fake_native.py` — pure-Python stand-in for `_native` that mirrors
  its validation invariants; `XDMADevice(native=FakeNative())` injects it.
  This is how unit tests run without hardware or the compiled module.
- HW tests skip automatically when `/dev/xdma0_*` is absent (see
  `tests/conftest.py`).
- `python -m chisel_npu_py selftest` — node discovery, ctrl_lite read,
  1 KB DDR3 loopback; exit 0 = pass.
- Current status: 25 unit + 14 hw tests, all PASS on V10 silicon.

## Troubleshooting

| Symptom | Likely cause | Fix |
|:--------|:-------------|:----|
| `Illegal instruction (core dumped)` on import/selftest | numpy 2.x wheel on the G-T56N CPU (no SSE4.2) | `pip install 'numpy<2'`; the package pins `numpy>=1.24,<2` — don't upgrade |
| `cannot open '/dev/xdma0_*': No such file or directory` | xdma.ko not loaded | `sudo insmod ~/dma_ip_drivers/XDMA/linux-kernel/xdma/xdma.ko` |
| `cannot open ... Operation not permitted` | udev rule not applied | Re-run `make py-deploy`; check `/etc/udev/rules.d/99-xdma.rules`, perms should be `crw-rw-rw-` |
| `native extension ... is not built` | `_native.so` missing (e.g. after rsync wipe) | `~/chisel_npu_py/.venv/bin/pip install ~/chisel_npu_py` |
| pybind11 compile errors: `buffer.h: No such file` / no `is_c_contiguous` | pybind11 ≥ 3 API changes | Use `pybind11/numpy.h`, `py::array::ensure(obj, py::array::c_style)`, `arr.flags() & py::array::c_style` |
| `staging map` mismatch in tests | `consts.py` out of sync with `native.cpp` | Update both; `test_consts.py::test_staging_matches_native_when_built` checks |
| ctrl_lite read = `0x2` at selftest | done latch from a previous kick (persists until next start) | Expected; not an error |

## Gotchas

- **Never cross-build the wheel**: interpreter ABI must match the venv
  python (cp312 on the FPGA host). Build with `pip install .` there.
- `np.frombuffer(dma_read_raw(...))` returns a read-only array — fine for
  comparison, not for `dma_read_into`-style in-place use.
- The staging table is byte-checked, not dtype-checked: `uint8[32]` is
  accepted where `int8[32]` is expected.
- `make py-deploy` recompiles the extension on every run; the `build/` and
  `dist/` outputs are gitignored.
