# Python Userspace Driver (`chisel_npu_py`)

## Overview

`chisel_npu_py` is the Python userspace driver for the NPU on the FPGA
verification platform. It sits on top of the Xilinx XDMA kernel driver and
speaks to the NPU through the same `ctrl_lite` + DMA-staging protocol as the
C tools (`reg_rw`, `dma_to_device`, `dma_from_device`), but with a strict
**pybind11 boundary**: the C++ module owns every file descriptor, DDR
address, register offset and transfer; Python only moves buffers (numpy
arrays, bytes, bytearray, memoryview).

Source: `drivers/chisel_npu_py/` — installed on the FPGA host at
`~/chisel_npu_py/` with a venv at `~/chisel_npu_py/.venv`.

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

## Why a C++ boundary

Passing raw DDR addresses and `bytes` objects from Python to a kernel
character device is a footgun: nothing ties the address to the buffer, and
nothing validates either. In `chisel_npu_py` the pybind11 module
(`NativeXDMA`) is the **single authority on addresses**:

- DMA uses `pwrite`/`pread` with the file offset as the AXI address — the
  exact semantics of the vendor tools (`cdev_sgdma.c` passes `*pos` into
  `xdma_xfer_submit`). The C++ layer runs the full-transfer loop and checks
  every transfer:
    - `addr % 4 == 0` and `len % 4 == 0` (rejected with `ValueError`),
    - address range inside the 4 GB DDR window (`0x0000_0000..0xFFFF_FFFF`),
    - non-zero length.
- Register access maps one page of `/dev/xdma0_bypass` (`bridge_mmap`) and
  reads/writes 32-bit words at validated offsets — the same mechanism as
  `reg_rw`.
- The MMALU **staging table lives in C++** (`kStaging` in `native.cpp`):
  operands are addressed by *name* (`"A"`, `"B"`, `"ACCUM"`, `"OUT"`) and
  the byte size is checked against the table. Python cannot misaddress
  staging memory; wrong-size buffers are rejected.

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

Lower-level pieces:

```python
from chisel_npu_py import XDMADevice, CtrlLite

dev = XDMADevice()
dev.dma_write(0x4000_0000, a)              # raw DMA; address validated in C++
raw = dev.dma_read(0x4000_0400, 128)       # uint8 ndarray
words = dev.read_i32(0x4000_0400, 32)      # typed int32 read
dev.write_staged("A", a)                   # staged operands (size-checked)
dev.read_staged("OUT", out)

ctrl = CtrlLite(dev)
ctrl.kick()                                # write start=1
ctrl.wait_done(timeout_s=2.0)              # poll done latch
ctrl.is_busy / ctrl.is_done
```

Buffers accepted anywhere: numpy arrays (copied to C-contiguous when
needed), `bytes`, `bytearray`, `memoryview`. Read-back into caller-provided
arrays is zero-copy (`dma_read_into` / `read_staged` with an output buffer);
non-contiguous or read-only output buffers are rejected rather than
silently copied.

Buffer → device mapping (staging table, authoritative in `native.cpp`,
mirrored in `chisel_npu_py/consts.py`):

| Operand | DDR address (MIG C0) | Size | Contents |
|:--------|:---------------------|:-----|:---------|
| A       | `0x4000_0000`        | 32 B  | 32 × int8 |
| B       | `0x4000_0100`        | 32 B  | 32 × int8 |
| ACCUM   | `0x4000_0200`        | 128 B | 32 × int32 |
| OUT     | `0x4000_0400`        | 128 B | 32 × int32 |

`XDMADevice.staging_map()` returns the runtime truth from the native
module; `test_consts.py::test_staging_matches_native_when_built`
cross-checks it against the Python mirror.

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
6. Runs `python -m chisel_npu_py selftest` (node discovery, ctrl_lite idle
   read, 1 KB DDR3 loopback through the pybind boundary).

The wheel produced on the host is
`chisel_npu_py-0.1.0-cp312-cp312-linux_x86_64.whl`; `make py-build` builds
an sdist on the dev host for archiving.

## Testing

| Command | Where | What |
|:--------|:------|:-----|
| `make py-test-unit` | dev host | unit/mock tests (25), no hardware |
| `make py-test-hw` | FPGA host (via SSH) | hardware suite, native pytest |
| `make py-deploy` | FPGA host | install + selftest |

The hardware suite (all PASS on V10 silicon, 14 tests):

- `test_ctrl_lite.py` — BAR access, kick→done, done-latch persistence.
- `test_loopback.py` — DDR3 C0 loopback 1 KB / 1 MB / 64 MB-high-addr, plus
  typed `read_i32` round-trip.
- `test_mmalu_compute.py` — the six MMALU compute tests
  (zero-in/zero-out, ACCUM passthrough, A=0 kills multiplier, multiplier
  alive, and the analytical `OUT[i] = A[i]·B[K-1] + ACCUM[i]` check).

Unit tests inject a pure-Python `FakeNative`
(`tests/fake_native.py`) that mirrors the native module's validation
invariants, so all orchestration logic is testable without the compiled
module or hardware. Hardware tests skip automatically when
`/dev/xdma0_*` is absent.

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
