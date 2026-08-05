---
name: python-npu-driver
description: Use when working with the chisel_npu_py Python userspace driver — the pybind11 XDMA driver for the NPU (drivers/chisel_npu_py/). Includes the NativeXDMA C++ boundary that owns ALL DDR addresses/register offsets, the address-free numpy-first Python API (ChiselNPU.mmalu, XDMADevice staged operands, CtrlLite), named MMALU operands A/B/ACCUM/OUT, pybind11 build/deploy to the FPGA host (make py-deploy/py-test-hw/py-test-unit), the selftest, unit tests with FakeNative, and troubleshooting (numpy<2 CPU baseline, pybind11 3.x API changes, udev permissions). Also use for any question about running Python against the NPU over /dev/xdma0_* device nodes.
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
   ├── XDMADevice  — write_staged/read_staged/operand_size/ctrl_read/write
   └── _native.so  ── pybind11 C++ module ── owns: fds, DDR addresses,
                                          staging table, alignment, transfers
                              │
                     /dev/xdma0_* (xdma.ko kernel driver)
```

**Rule of the house**: the pybind11 module (`NativeXDMA`, in
`src/chisel_npu_py/native_src/native.cpp`) is the ONLY place that handles
fds, DDR addresses, register offsets and transfers. Python only moves
buffers (numpy arrays, bytes, bytearray, memoryview) and names staged
operands. **No DDR address or register offset ever crosses the boundary.**

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

Low-level pieces (still no addresses):

```python
from chisel_npu_py import XDMADevice, CtrlLite

dev = XDMADevice()
dev.write_staged("A", a)                   # stage an operand by name
out = np.empty(32, dtype=np.int32)
dev.read_staged("OUT", out)                # read an operand into a buffer
dev.operand_size("ACCUM")                  # 128

ctrl = CtrlLite(dev)
ctrl.kick(); ctrl.wait_done(timeout_s=2.0) # done latch poll
```

## Staged MMALU operands (sizes only; addresses live in C++)

| Operand | Size | Contents |
|:--------|:-----|:---------|
| A       | 32 B  | 32 × int8 |
| B       | 32 B  | 32 × int8 |
| ACCUM   | 128 B | 32 × int32 |
| OUT     | 128 B | 32 × int32 |

The table is owned by `kStaging` in `native.cpp`; `consts.py` deliberately
carries no addresses. `dev.operand_size(name)` returns the authoritative
size from the native module.

## Native module invariants (all enforced in C++)

- Staged operands are name- AND byte-size-checked — unknown names and
  wrong-size buffers raise `ValueError`.
- Transfers are validated internally (alignment, length multiples,
  address window) before touching the device.
- DMA = `pwrite`/`pread` with file offset = AXI address (same semantics as
  `cdev_sgdma.c` `xdma_xfer_submit(..., *pos, ...)`), full-transfer loop.
- ctrl_lite = 1-page `mmap` of `/dev/xdma0_bypass` (same as `reg_rw`),
  exposed as a single address-free control word (`ctrl_read`/`ctrl_write`).
- Inputs copied to C-contiguous when needed; read-back buffers must be
  C-contiguous AND writable — no silent copies (`py::value_error`).
- Errors: `XDMAError` (device/native), `NPUError` (protocol),
  `NPUTimeoutError` (wait_done timeout).

## Commands

```bash
source .env.sh                             # FPGA_HOST, SSH_IDENTITY, ...
make py-build                              # sdist (dev host)
make py-deploy                             # rsync + venv + build + udev + selftest
make py-test-unit                          # 26 mock tests, no hardware (dev host)
make py-test-hw                            # 14 hw tests natively on the FPGA host
```

Deploy (`drivers/chisel_npu_py/tool/deploy.sh`) is idempotent: rsync →
`python3-dev` (apt, if `Python.h` missing) → venv (+`python3-venv` fallback)
→ `pip install pytest 'numpy<2'` → `pip install .` (PEP 517 pulls pybind11;
compiles the extension **on the host in the target venv**) → udev rule
`SUBSYSTEM=="xdma", MODE="0666"` + chmod live nodes → selftest.

## Testing

- `tests/fake_native.py` — pure-Python stand-in for `_native` that mirrors
  its validation invariants; addresses are internal to it, exactly like
  the real C++ side. `XDMADevice(native=FakeNative())` injects it. This is
  how unit tests run without hardware or the compiled module.
- HW tests skip automatically when `/dev/xdma0_*` is absent (see
  `tests/conftest.py`).
- `python -m chisel_npu_py selftest` — node discovery, ctrl_lite read,
  staged OUT round-trip; exit 0 = pass.
- Current status: 26 unit + 14 hw tests, all PASS on V10 silicon.

## Troubleshooting

| Symptom | Likely cause | Fix |
|:--------|:-------------|:----|
| `Illegal instruction (core dumped)` on import/selftest | numpy 2.x wheel on the G-T56N CPU (no SSE4.2) | `pip install 'numpy<2'`; the package pins `numpy>=1.24,<2` — don't upgrade |
| `cannot open '/dev/xdma0_*': No such file or directory` | xdma.ko not loaded | `sudo insmod ~/dma_ip_drivers/XDMA/linux-kernel/xdma/xdma.ko` |
| `cannot open ... Operation not permitted` | udev rule not applied | Re-run `make py-deploy`; check `/etc/udev/rules.d/99-xdma.rules`, perms should be `crw-rw-rw-` |
| `native extension ... is not built` | `_native.so` missing (e.g. after rsync wipe) | `~/chisel_npu_py/.venv/bin/pip install ~/chisel_npu_py` |
| pybind11 compile errors: `buffer.h: No such file` / no `is_c_contiguous` | pybind11 ≥ 3 API changes | Use `pybind11/numpy.h`, `py::array::ensure(obj, py::array::c_style)`, `arr.flags() & py::array::c_style` |
| `unknown staging operand 'X'` | operand not in A/B/ACCUM/OUT | Check the spelling; the table is in `native.cpp` `kStaging` |
| ctrl_lite read = `0x2` at selftest | done latch from a previous kick (persists until next start) | Expected; not an error |

## Gotchas

- **Never cross-build the wheel**: interpreter ABI must match the venv
  python (cp312 on the FPGA host). Build with `pip install .` there.
- `np.frombuffer(...)`-style views are read-only — for in-place read-back
  use `np.empty(...)` buffers with `read_staged`.
- Operand size checks are byte-based, not dtype-based: `uint8[32]` is
  accepted where `int8[32]` is expected.
- `make py-deploy` recompiles the extension on every run; the `build/` and
  `dist/` outputs are gitignored.
- Do NOT add addresses back to `consts.py` or the Python API — the staging
  table and register offsets belong exclusively to `native_src/native.cpp`.
