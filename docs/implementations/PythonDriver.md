# Python Userspace Driver (`chisel_npu_py`)

## Overview

`chisel_npu_py` is the **instruction-programmed** Python userspace driver for
the Chisel NPU over the Xilinx XDMA kernel driver, for the xc7k480t FPGA
card. It sits on top of `xdma.ko` and talks to the NPU through the same
`ctrl_lite` + DMA-staging protocol as the C tools (`reg_rw`,
`dma_to_device`, `dma_from_device`), with a strict **pybind11 boundary**.

The whole driver is one call: stage named-section memories, run a sequence of
ISA instruction words, read everything back.

Source: `drivers/chisel_npu_py/` — installed on the FPGA host at
`~/chisel_npu_py/` with a venv at `~/chisel_npu_py/.venv`. Current version is
`0.2.1`.

```
tests (pytest, native on FPGA host)
   │
chisel_npu_py (Python: orchestration, ISA assembly, injectable native)
   ├── ChiselNPU   — run(instructions, memories): stage → kick → wait → read
   ├── XDMADevice  — write_staged/read_staged/section_size/ctrl_read_reg/ctrl_write_reg
   └── _native.so  ── pybind11 C++ module ── owns: fds, DMA transfers,
                                          ctrl mmap, transfer validation
                              │
                     /dev/xdma0_* (xdma.ko kernel driver)
```

## The C++ boundary

The pybind11 module (`NativeXDMA`, in
`src/chisel_npu_py/native_src/native.cpp`) is the only place that handles file
descriptors, DMA transfers and the register mmap. Python never sees a DDR
address or a raw register offset; it moves buffers (numpy arrays, `bytes`,
`bytearray`, `memoryview`) and names sections.

- The **section bases/windows and ctrl register offsets are supplied by the
  Python `NPUConfig` at construction** (`XDMADevice` serializes
  `cfg.sections` / `cfg.ctrl` across the boundary). The config is the single
  source of truth; native validates every transfer against it.
- DMA uses `pwrite`/`pread` with the file offset as the AXI address — the
  exact semantics of the vendor tools (`cdev_sgdma.c` passes `*pos` into
  `xdma_xfer_submit`). The C++ layer runs the full-transfer loop and
  validates every transfer internally: zero-length rejection, 4-byte
  alignment of offset and length, section-window bounds, and the 4 GB DDR
  window (`0x00000000..0xFFFFFFFF`).
- Register access maps one page (4 KiB) of `/dev/xdma0_bypass` and
  reads/writes a `uint32` at a validated, 4-byte-aligned offset.
- Inputs are copied to C-contiguous when needed. Read-back output buffers
  must be C-contiguous **and writable** — no silent copies (a copied
  read-back would discard the data).

The module's public surface is fully address-free: `write_staged(name, data,
offset=0)`, `read_staged(name, out, offset=0)`, `section_size(name)`,
`ctrl_read_reg(offset)`, `ctrl_write_reg(offset, value)`.

## API

```python
import numpy as np
from chisel_npu_py import ChiselNPU, isa

npu = ChiselNPU()                       # NPUConfig default (K=16 silicon map)

result = npu.run(
    instructions=[
        isa.vle8(0, isa.SECT_A, 0),     # A → VX[0]
        isa.vle8(1, isa.SECT_B, 0),     # B → VX[1]
        isa.mma_last(2, 0, 1, 0),       # session end: capture → VR[2]
        *[isa.NOP] * 40,                # clct/drain window
        isa.vse32(2, isa.SECT_OUT, 0),  # store the result column
    ],
    memories={"A": a_bytes, "B": b_bytes},   # section name → buffer
)
# result = {"A": <readback>, "B": <readback>, "OUT": int32[...]}
```

`ChiselNPU.__init__(cfg=None, timeout_s=10.0)`. `run(instructions,
memories=None, timeout_s=None)`:

1. Stages each named memory into its section (A/B/ACCUM/OUT/CODE; the native
   side window-checks every transfer).
2. Stages the instruction words into `CODE` as a little-endian word stream
   (`isa.Program.to_bytes()`).
3. Zero-clears the `OUT` window before the kick (the engine only writes the
   produced columns; clearing makes the full-window read-back safe and stops
   stale data from masking a mismatch).
4. Fires the engine: writes `PROG_LEN`, then `CTRL` start=1 followed by
   start=0 (edge-triggered).
5. Polls the `CTRL` done bit (5 ms period) until the timeout, else raises
   `NPUTimeoutError`.
6. Reads `STATUS`: if the illegal bit (31) is set, raises
   `NPUProgramError(pc, err_info)`; if the pc field does not reach
   `len(words) - 1`, raises `NPUError`.
7. Returns a dict of every input memory read back (same dtype/shape for numpy
   inputs, `bytes` otherwise) plus `OUT` as a flat `int32` array of the whole
   OUT window.

Lower-level pieces (still no addresses):

```python
from chisel_npu_py import XDMADevice, config

dev = XDMADevice(cfg=config.default_config())  # opens /dev/xdma0_{h2c,c2h}_0, _bypass
dev.write_staged("A", a)                   # stage a section by name
out = np.empty(32, dtype=np.int32)
dev.read_staged("OUT", out)                # read a section into a buffer
dev.section_size("CODE")                   # 262144 (bytes)
dev.ctrl_write_reg(cfg.ctrl["CTRL"], 0)    # offsets come from NPUConfig
dev.ctrl_read_reg(cfg.ctrl["STATUS"])
```

Other public names: `Buffer`, `Program`, `Section`, `NPUConfig`,
`default_config`, `config`, `isa`, and the exception hierarchy
(`XDMAError` → `NPUError` → `NPUTimeoutError` / `NPUProgramError`).

## Section map (default K=16 config)

The bases/windows live in `config.py` (`default_config()`); the native module
receives them at construction.

| Section | Base | Window | Contents |
|:--------|:-----|:-------|:---------|
| A | `0x4000_0000` | 4 KiB | int8 vectors |
| B | `0x4000_0400` | 4 KiB | int8 vectors |
| ACCUM | `0x4000_0800` | 128 B | int32[K] |
| OUT | `0x4000_0880` | 4 KiB | int32 outputs |
| CODE | `0x4000_4000` | 256 KiB | program words |

`NPUConfig` also carries the ctrl register map (`CTRL`, `STATUS`,
`ERR_INFO`, `PROG_LEN`, `DBG`, `DBG_MMA`, …), `K=16`, `N=8`, and `nop_wait =
2K + 8 = 40`. `NPUConfig.usable(name)` returns the bytes of a section that
may be staged at offset 0 without clobbering the adjacent next section.

## The mma session model

`mma` = one feed tick (`in_a` = a VX column, `in_b` = a VX row); the MMALU
accumulates the session's terms in its PEs. Each mma's clct (2n−1 later)
captures **one output column** of the session product to its own `vd`
(per-mma capture, columns located by the issue-timing phase). `mma.last`
marks the session end. Store the columns with multiple `vse32`:

```python
result = npu.run(
    instructions=[
        isa.vle8(4, isa.SECT_A, 0),  isa.vle8(8, isa.SECT_B, 0),
        isa.vle8(5, isa.SECT_A, 16), isa.vle8(9, isa.SECT_B, 16),
        isa.vle8(6, isa.SECT_A, 32), isa.vle8(10, isa.SECT_B, 32),
        isa.mma(1, 4, 8, 0),    # feed 0 → captures column c0
        isa.mma(2, 5, 9, 0),    # feed 1 → captures column c1
        isa.mma(3, 6, 10, 0),   # feed 2 → captures column c2
        isa.mma_last(0, 0, 0, 0),
        *[isa.NOP] * 40,
        isa.vse32(1, isa.SECT_OUT, 0),
        isa.vse32(2, isa.SECT_OUT, 64),
        isa.vse32(3, isa.SECT_OUT, 128),
    ],
    memories={"A": a_cols, "B": b_cols},
)
```

## Deployment on the FPGA host

`make py-deploy` (from the repo root, after `source .env.sh`) runs
`drivers/chisel_npu_py/tool/deploy.sh`, which is idempotent:

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
6. Runs `python -m chisel_npu_py selftest` (node discovery, a section
   round-trip via `run()`, and an 8-NOP program runs to completion).

`make py-build` builds an sdist on the dev host for archiving; the
user-built wheel carries package version `0.2.1`.

## Testing

| Command | Where | What |
|:--------|:------|:-----|
| `make py-test-unit` | dev host | unit/mock tests (20), no hardware |
| `make py-test-hw` | FPGA host (via SSH) | hardware suite, native pytest |
| `make py-deploy` | FPGA host | install + selftest |

The unit suite (`-m "not hw"`, 20 tests) runs without the compiled module or
hardware by injecting a pure-Python `FakeNative` (`tests/fake_native.py`)
that mirrors the native validation invariants (addresses internal to it,
just like the C++ side) and embeds a software model of the engine (the mma
session / K-burst capture model). It covers `config` validation, `isa`
assembler encodings (cross-checked against the Scala assembler and silicon),
and the `ChiselNPU.run` orchestration (readback, one-shot MAC, session
captures, illegal-instruction, timeout, empty program, bad section).

The hardware suite (`-m hw`, 7 tests) skips automatically when
`/dev/xdma0_*` is absent (see `tests/conftest.py`):

- `tests/test_loopback.py` (3) — section round-trips through `run()`: a
  copy program (vle8 → vse8), a VR round-trip (vle32 → vse32), and transfer
  size validation.
- `tests/test_program_engine.py` (4) — streamed mma sessions on silicon:
  `test_one_shot_session`, `test_session_captures_columns`,
  `test_illegal_instruction_halts`, `test_status_after_program`.

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
- Addresses (section bases/windows and ctrl offsets) live in exactly two
  places: the Python `NPUConfig` (`config.py`) and the native module
  (`native_src/native.cpp`) that consumes it. Do not hardcode addresses
  anywhere else.
