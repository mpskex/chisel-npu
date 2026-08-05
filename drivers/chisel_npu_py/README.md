# chisel-npu-py

Python userspace driver for the Chisel NPU over the Xilinx XDMA kernel
driver, for the xc7k480t FPGA card.

## Design

The driver is split in two halves with a strict boundary:

- **`chisel_npu_py._native`** — a pybind11 C++ module that is the *only*
  place where file descriptors, DDR addresses, register offsets and DMA
  transfers exist. It opens `/dev/xdma0_h2c_0`, `/dev/xdma0_c2h_0` and
  `/dev/xdma0_bypass`, validates every transfer (4-byte alignment, length
  multiples of 4, address inside the 4 GB DDR window), and owns the MMALU
  operand staging table:

  | Operand | DDR address (MIG C0) | Size |
  |:--------|:---------------------|:-----|
  | A       | `0x4000_0000`        | 32 B int8  |
  | B       | `0x4000_0100`        | 32 B int8  |
  | ACCUM   | `0x4000_0200`        | 128 B int32 |
  | OUT     | `0x4000_0400`        | 128 B int32 |

  `write_staged("A", buf)` checks the operand *name and exact byte size*
  — Python can never misaddress staging memory.

- **the Python layer** — typed API that only moves buffers (numpy arrays,
  bytes, bytearray, memoryview). The staging addresses never appear in
  Python code paths (`XDMADevice.staging_map()` exposes them read-only
  for introspection/tests).

## Usage

```python
import numpy as np
from chisel_npu_py import ChiselNPU

npu = ChiselNPU()                          # opens the XDMA device nodes
a   = np.full(32, 10, dtype=np.int8)
b   = np.full(32, 7,  dtype=np.int8)
acc = np.zeros(32, dtype=np.int32)
out = npu.mmalu(a, b, acc)                 # stage → kick → wait done → read OUT
```

Low-level pieces are also exposed:

```python
from chisel_npu_py import XDMADevice, CtrlLite

dev  = XDMADevice()
dev.dma_write(0x4000_0000, a)              # raw DMA (address validated in C++)
data = dev.dma_read(0x4000_0400, 128)      # uint8 ndarray
vals = dev.read_i32(0x4000_0400, 32)

ctrl = CtrlLite(dev)
ctrl.kick(); ctrl.wait_done(timeout_s=2.0) # done/busy bit protocol
```

Safety invariants enforced by the native module:

- zero-length, misaligned (`addr % 4`), non-multiple-of-4 lengths and
  out-of-window addresses are rejected with `ValueError`;
- staged operands are size-checked against the table;
- non-contiguous inputs are copied to contiguous buffers automatically;
- all errors surface as `XDMAError`/`NPUError`/`NPUTimeoutError` subclasses.

## Installation on the FPGA host

```bash
# from the repo root, after sourcing .env.sh:
make py-deploy
```

`deploy.sh` rsyncs the tree to `~/chisel_npu_py`, installs `python3-dev`
if missing, creates `.venv`, runs `pip install .` (compiling the pybind11
module in the target venv), installs the udev rule
(`SUBSYSTEM=="xdma", MODE="0666"`) so the device nodes are user-rw, and
runs a self-test:

```bash
~/chisel_npu_py/.venv/bin/python -m chisel_npu_py selftest
```

Device nodes must be present (`/dev/xdma0_*` from `xdma.ko`) — run
`make test-hw`'s bring-up or load the driver first.

## Testing

```bash
make py-test-unit    # mock/unit tests, no hardware (dev host)
make py-test-hw      # runs the hw suite natively on the FPGA host via SSH
```

| Test file | Kind | What it covers |
|:----------|:-----|:---------------|
| `test_consts.py` | unit | staging table / register map sanity |
| `test_ctrl_mock.py` | unit | done/busy bit parsing, `wait_done` (FakeNative) |
| `test_npu_mock.py` | unit | mmalu orchestration, size rejection, timeouts |
| `test_ctrl_lite.py` | hw | BAR access, kick→done, done latch |
| `test_loopback.py` | hw | DDR3 C0 loopback via numpy buffers |
| `test_mmalu_compute.py` | hw | the 6 MMALU compute tests |

Hardware tests are skipped automatically when no `/dev/xdma0_*` nodes
exist. `tests/fake_native.py` provides a scriptable stand-in for the
native module so all Python-side logic is unit-testable off-board.

## Distribution

- `pyproject.toml` — setuptools + PEP 517 (`pybind11>=2.12` build dep),
  numpy runtime dependency; sdist via `make py-build`.
- The extension is **built on the FPGA host** inside the target venv
  (the interpreter ABI must match — don't cross-build wheels).
- The staging table in `src/chisel_npu_py/consts.py` mirrors
  `native_src/native.cpp` and is cross-checked at runtime by
  `test_consts.py::test_staging_matches_native_when_built`.

## License

GPL-2.0 (matches the repo; the xdma kernel driver is GPL).
