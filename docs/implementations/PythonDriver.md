# Python Userspace Driver (`chisel_npu_py`)

## Overview

`chisel_npu_py` is the Python userspace driver for the NPU on the FPGA
verification platform. It sits on top of the Xilinx XDMA kernel driver and
speaks to the NPU through the same `ctrl_lite` + DMA-staging protocol as the
C tools (`reg_rw`, `dma_to_device`, `dma_from_device`), with a strict
**pybind11 boundary**: the C++ module owns every file descriptor, DDR
address, register offset and transfer; Python only moves buffers (numpy
arrays, bytes, bytearray, memoryview) and names staged operands. **No DDR
address or register offset ever appears on the Python side.**

Source: `drivers/chisel_npu_py/` — installed on the FPGA host at
`~/chisel_npu_py/` with a venv at `~/chisel_npu_py/.venv`.

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

## The C++ boundary

The pybind11 module (`NativeXDMA`, in
`src/chisel_npu_py/native_src/native.cpp`) is the **single authority on
addresses**:

- DMA uses `pwrite`/`pread` with the file offset as the AXI address — the
  exact semantics of the vendor tools (`cdev_sgdma.c` passes `*pos` into
  `xdma_xfer_submit`). The C++ layer runs the full-transfer loop and
  validates every transfer internally (alignment, lengths, address window).
- Register access maps one page of `/dev/xdma0_bypass` (`bridge_mmap`) and
  reads/writes the ctrl_lite control word at a fixed offset.
- The MMALU **staging table lives in C++** (`kStaging`): Python addresses
  operands by *name* (`"A"`, `"B"`, `"ACCUM"`, `"OUT"`) and the C++ checks
  the byte size against the table. Wrong names or wrong-size buffers are
  rejected — Python cannot misaddress staging memory.

The module's public surface is fully address-free: `write_staged(operand,
data)`, `read_staged(operand, out)`, `operand_size(operand)`,
`ctrl_read()`, `ctrl_write(value)`.

## API

```python
import numpy as np
from chisel_npu_py import ChiselNPU

npu = ChiselNPU()                          # opens /dev/xdma0_h2c_0, _c2h_0, _bypass
a   = np.full(32, 10, dtype=np.int8)
b   = np.full(32, 7,  dtype=np.int8)
acc = np.zeros(32, dtype=np.int32)
out = npu.mmalu(a, b, acc)                 # int32[32] result (stage→kick→wait→read)
```

Lower-level pieces (still no addresses):

```python
from chisel_npu_py import XDMADevice, CtrlLite

dev = XDMADevice()
dev.write_staged("A", a)                   # stage an operand by name
out = np.empty(32, dtype=np.int32)
dev.read_staged("OUT", out)                # read an operand into a buffer
dev.operand_size("ACCUM")                  # 128 (bytes)

ctrl = CtrlLite(dev)
ctrl.kick()                                # write start=1
ctrl.wait_done(timeout_s=2.0)              # poll done latch
ctrl.is_busy / ctrl.is_done
```

Buffers accepted anywhere: numpy arrays (copied to C-contiguous when
needed), `bytes`, `bytearray`, `memoryview`. Read-back into caller-provided
arrays is zero-copy (`read_staged` with an output buffer); non-contiguous
or read-only output buffers are rejected rather than silently copied.

Staged MMALU operands (sizes are data-side facts; addresses stay in C++):

| Operand | Size | Contents |
|:--------|:-----|:---------|
| A       | 32 B  | 32 × int8 |
| B       | 32 B  | 32 × int8 |
| ACCUM   | 128 B | 32 × int32 |
| OUT     | 128 B | 32 × int32 |

## Deployment on the FPGA host

`make py-deploy` (from the repo root, after `source .env.sh`) runs
`drivers/chisel_npu_py/tool/deploy.sh`:

1. `rsync` the package + tests to `fpga:~/chisel_npu_py/`.
2. Installs `python3-dev` if missing (required for the pybind11 build).
3. Creates the venv (installs `python3-venv` if missing) and
   `pip install pytest 'numpy<2'`.
4. `pip install .` — PEP 517 pulls pybind11 and compiles
   `chisel_npu_py._native` **in the target venv** (the interpreter ABI must
   match the running Python; never cross-build wheels for a different
   interpreter version).
5. Installs the udev rule `SUBSYSTEM=="xdma", MODE="0666"` and chmods the
   live nodes, so driver and tests run **without sudo**.
6. Runs `python -m chisel_npu_py selftest` (node discovery, ctrl_lite
   control word, staged OUT round-trip through the pybind boundary).

The wheel produced on the host is
`chisel_npu_py-0.1.0-cp312-cp312-linux_x86_64.whl`; `make py-build` builds
an sdist on the dev host for archiving.

## Testing

| Command | Where | What |
|:--------|:------|:-----|
| `make py-test-unit` | dev host | unit/mock tests (26), no hardware |
| `make py-test-hw` | FPGA host (via SSH) | hardware suite, native pytest |
| `make py-deploy` | FPGA host | install + selftest |

The hardware suite (all PASS on V10 silicon, 23 tests):

- `test_ctrl_lite.py` — control word access, kick→done, done-latch
  persistence.
- `test_loopback.py` — staged round-trips of every operand (DDR3 data
  integrity through the pybind boundary) plus typed int32 read-back.
- `test_mmalu_compute.py` — the six MMALU compute tests
  (zero-in/zero-out, ACCUM passthrough, A=0 kills multiplier, multiplier
  alive, and the analytical `OUT[i] = A[i]·B[K-1] + ACCUM[i]` check) plus
  a formal **bit-exactness suite**: 6 random trials over the full int8
  range (ACCUM ±10^6), full-scale products (16384 / 16129 on every lane),
  mixed-sign operands, all-negative B, and ACCUM near ±2^30 — each
  verified at both the int32 value level and the raw uint32 bit-pattern
  level, with the OUT region sentinel-cleared to prove real write-back.

Unit tests inject a pure-Python `FakeNative`
(`tests/fake_native.py`) that mirrors the native module's validation
invariants (addresses internal to it, exactly like the C++ side), so all
orchestration logic is testable without the compiled module or hardware.
Hardware tests skip automatically when `/dev/xdma0_*` is absent.

## Gotchas

- **CPU baseline (SIGILL)**: the FPGA host runs an AMD G-T56N which lacks
  SSE4.2; numpy 2.x wheels (x86-64-v2 baseline) die with `Illegal
  instruction`. The package pins `numpy>=1.24,<2`. Do not "upgrade" it.
- **pybind11 ≥ 3 API**: `pybind11/buffer.h` was renamed `buffer_info.h` and
  `array::is_c_contiguous()` was removed — use
  `py::array::ensure(obj, py::array::c_style)` (copies non-contiguous
  input) and check `arr.flags() & py::array::c_style` for output buffers.
- The extension must be rebuilt after any change to `native.cpp`
  (`make py-deploy` re-runs `pip install .`).
- Device nodes are root-owned until the udev rule is applied; the deploy
  script fixes this once.
- `consts.py` deliberately carries no addresses; if you need to know where
  an operand lives, the staging table is in `native_src/native.cpp`.
