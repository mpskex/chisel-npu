# chisel-npu-py

Python userspace driver for the Chisel NPU over the Xilinx XDMA kernel
driver, for the xc7k480t FPGA card.

## Design

The driver is split in two halves with a strict boundary:

- **`chisel_npu_py._native`** — a pybind11 C++ module that is the *only*
  place where file descriptors, DDR addresses, register offsets and DMA
  transfers exist. It opens `/dev/xdma0_h2c_0`, `/dev/xdma0_c2h_0` and
  `/dev/xdma0_bypass`, owns the MMALU operand staging table, validates
  every transfer (alignment, lengths, address window) and exposes a
  fully **address-free** interface: staged operands by *name* and a
  single ctrl_lite control word.

- **the Python layer** — typed API that only moves buffers (numpy arrays,
  bytes, bytearray, memoryview). **No DDR address or register offset ever
  appears on the Python side.**

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

The staged MMALU operands (named, size-checked by the native module):

| Operand | Size | Contents |
|:--------|:-----|:---------|
| A       | 32 B  | 32 × int8 |
| B       | 32 B  | 32 × int8 |
| ACCUM   | 128 B | 32 × int32 |
| OUT     | 128 B | 32 × int32 |

Lower-level pieces (still no addresses):

```python
from chisel_npu_py import XDMADevice, CtrlLite

dev = XDMADevice()
dev.write_staged("A", a)                   # stage an operand by name
out = np.empty(32, dtype=np.int32)
dev.read_staged("OUT", out)                # read an operand into a buffer
dev.operand_size("ACCUM")                  # 128

ctrl = CtrlLite(dev)
ctrl.kick(); ctrl.wait_done(timeout_s=2.0) # done/busy bit protocol
```

Safety invariants enforced by the native module:

- staged operands are name- and byte-size-checked against its table —
  wrong names or wrong-size buffers raise `ValueError`;
- zero-length, misaligned and out-of-window transfers are rejected
  internally;
- non-contiguous inputs are copied to contiguous buffers automatically;
- read-back buffers must be C-contiguous and writable (no silent copies);
- all errors surface as `XDMAError`/`NPUError`/`NPUTimeoutError`
  subclasses.

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
| `test_consts.py` | unit | operand set, K-derived sizes, no-addresses-in-consts |
| `test_ctrl_mock.py` | unit | done/busy bit parsing, `wait_done` (FakeNative) |
| `test_npu_mock.py` | unit | mmalu orchestration, size rejection, timeouts |
| `test_ctrl_lite.py` | hw | control word access, kick→done, done latch |
| `test_loopback.py` | hw | staged round-trips (DDR3 data integrity) |
| `test_mmalu_compute.py` | hw | the 6 MMALU compute tests |

Hardware tests are skipped automatically when no `/dev/xdma0_*` nodes
exist. `tests/fake_native.py` provides a scriptable stand-in for the
native module (addresses internal to it, like the real C++ side) so all
Python-side logic is unit-testable off-board.

## Distribution

- `pyproject.toml` — setuptools + PEP 517 (`pybind11>=2.12` build dep),
  numpy runtime dependency; sdist via `make py-build`.
- The extension is **built on the FPGA host** inside the target venv
  (the interpreter ABI must match — don't cross-build wheels).
- `consts.py` deliberately contains no addresses — the staging table is
  owned exclusively by `native_src/native.cpp`.

## License

GPL-2.0 (matches the repo; the xdma kernel driver is GPL).
