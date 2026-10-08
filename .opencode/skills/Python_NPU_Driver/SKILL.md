---
name: python-npu-driver
description: Use when working with the chisel_npu_py Python userspace driver — the pybind11 XDMA driver for the NPU (drivers/chisel_npu_py/). Includes the NativeXDMA C++ boundary that owns ALL DDR addresses/register offsets, the address-free Python API (ChiselNPU.run(instructions, memories), XDMADevice staged sections, NPUConfig), named sections A/B/ACCUM/OUT/CODE, pybind11 build/deploy to the FPGA host (make py-deploy/py-test-hw/py-test-unit), the selftest, unit tests with FakeNative, and troubleshooting (numpy<2 CPU baseline, pybind11 3.x API changes, udev permissions). Also use for any question about running Python against the NPU over /dev/xdma0_* device nodes.
---

# Python NPU Driver — `chisel_npu_py`

The Python userspace driver for the Chisel NPU over the Xilinx XDMA kernel
driver. It is **instruction-programmed**: stage named-section memories, run a
sequence of ISA words, read everything back. Source lives in
`drivers/chisel_npu_py/`; installed on the FPGA host at `~/chisel_npu_py/`
(venv at `~/chisel_npu_py/.venv`). Current version: `0.2.1`.

The companion skill `npu-xdma-driver-integration` covers the kernel driver,
the C tools, and the ctrl_lite protocol at the device level; this skill
covers the Python package that wraps them.

## Architecture

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

Modules: `config.py` (`NPUConfig`, `Section`, `default_config()`), `isa.py`
(assembler + `Program`), `backend.py` (`XDMADevice`), `npu.py`
(`ChiselNPU`), `errors.py`. There is no `consts.py` and no `ctrl.py`.

**Rule of the house**: the pybind11 module (`NativeXDMA`, in
`src/chisel_npu_py/native_src/native.cpp`) is the ONLY place that handles
fds, DMA transfers and the ctrl register mmap. The section bases/windows and
ctrl offsets come from the Python `NPUConfig` passed at construction; Python
only moves buffers (numpy arrays, bytes, bytearray, memoryview) and names
sections. **No DDR address or register offset ever crosses the boundary.**

## Quick usage

```python
import numpy as np
from chisel_npu_py import ChiselNPU, isa

npu = ChiselNPU()                       # NPUConfig default (K=16 silicon map)
result = npu.run(
    instructions=[
        isa.vle8(0, isa.SECT_A, 0),
        isa.vle8(1, isa.SECT_B, 0),
        isa.mma_last(2, 0, 1, 0),
        *[isa.NOP] * 40,
        isa.vse32(2, isa.SECT_OUT, 0),
    ],
    memories={"A": a_bytes, "B": b_bytes},
)
# result = {"A": <readback>, "B": <readback>, "OUT": int32[...]}
```

`run()` raises `NPUProgramError(pc, err_info)` on an illegal instruction and
`NPUTimeoutError` if the engine hangs.

Low-level pieces (still no addresses):

```python
from chisel_npu_py import XDMADevice, config

dev = XDMADevice(cfg=config.default_config())
dev.write_staged("A", a)                   # stage a section by name
out = np.empty(32, dtype=np.int32)
dev.read_staged("OUT", out)                # read a section into a buffer
dev.section_size("CODE")                   # 262144

cfg = dev.cfg
dev.ctrl_write_reg(cfg.ctrl["CTRL"], 0)    # offsets come from NPUConfig
dev.ctrl_read_reg(cfg.ctrl["STATUS"])
```

## Staged sections (sizes only; bases live in NPUConfig)

| Section | Base | Window | Contents |
|:--------|:-----|:-------|:---------|
| A | `0x4000_0000` | 4 KiB | int8 vectors |
| B | `0x4000_0400` | 4 KiB | int8 vectors |
| ACCUM | `0x4000_0800` | 128 B | int32[K] |
| OUT | `0x4000_0880` | 4 KiB | int32 outputs |
| CODE | `0x4000_4000` | 256 KiB | program words |

The map is owned by the Python `NPUConfig` (`config.py`, `default_config()`)
and serialized to the native module at construction. `dev.section_size(name)`
returns the authoritative byte window from the native module.

## Native module invariants (all enforced in C++)

- Staged sections are name- AND byte-size-checked — unknown names and
  wrong-size buffers raise `ValueError`.
- Transfers are validated internally (zero-length, 4-byte alignment of
  offset and length, section-window bounds, the 4 GB DDR window) before
  touching the device.
- DMA = `pwrite`/`pread` with file offset = AXI address (same semantics as
  `cdev_sgdma.c` `xdma_xfer_submit(..., *pos, ...)`), full-transfer loop.
- ctrl_lite = 1-page `mmap` of `/dev/xdma0_bypass` (same as `reg_rw`),
  exposed as address-free `ctrl_read_reg`/`ctrl_write_reg` (plus
  `ctrl_read`/`ctrl_write` aliases for offset 0).
- Inputs copied to C-contiguous when needed; read-back buffers must be
  C-contiguous AND writable — no silent copies (`py::value_error`).
- Errors: `XDMAError` (device/native), `NPUError` (protocol),
  `NPUTimeoutError` (wait timeout), `NPUProgramError` (illegal instruction,
  carrying `pc` and `err_info`).

## Commands

```bash
source .env.sh                             # FPGA_HOST, SSH_IDENTITY, ...
make py-build                              # sdist (dev host)
make py-deploy                             # rsync + venv + build + udev + selftest
make py-test-unit                          # 20 mock tests, no hardware (dev host)
make py-test-hw                            # 7 hw tests natively on the FPGA host
```

Deploy (`drivers/chisel_npu_py/tool/deploy.sh`) is idempotent: rsync →
`python3-dev` (apt, if `Python.h` missing) → venv (+`python3-venv` fallback)
→ `pip install pytest 'numpy<2'` → `pip install .` (PEP 517 pulls pybind11;
compiles the extension **on the host in the target venv**) → udev rule
`SUBSYSTEM=="xdma", MODE="0666"` + chmod live nodes → selftest.

## Testing

- `tests/fake_native.py` — pure-Python stand-in for `_native` that mirrors
  its validation invariants (addresses are internal to it, exactly like the
  real C++ side) and embeds a software model of the engine (mma session /
  K-burst capture). `XDMADevice(native=FakeNative())` injects it. This is
  how unit tests run without hardware or the compiled module.
- Unit suite (`-m "not hw"`, 20 tests): `test_config.py` (config validation
  + derived quantities), `test_isa.py` (assembler encodings cross-checked
  against the Scala assembler and silicon), `test_npu_mock.py`
  (`ChiselNPU.run` orchestration: readback, one-shot MAC, session captures,
  illegal instruction, timeout, empty program, bad section).
- HW suite (`-m hw`, 7 tests): `test_loopback.py` (3) — section round-trips
  through `run()` (copy program, VR round-trip, transfer validation);
  `test_program_engine.py` (4) — `test_one_shot_session`,
  `test_session_captures_columns`, `test_illegal_instruction_halts`,
  `test_status_after_program`.
- HW tests skip automatically when `/dev/xdma0_*` is absent (see
  `tests/conftest.py`).
- `python -m chisel_npu_py selftest` — node discovery, a section round-trip
  via `run()`, and an 8-NOP program; exit 0 = pass.
- Current status: 20 unit + 7 hw tests, all PASS on V10 silicon.

## Troubleshooting

| Symptom | Likely cause | Fix |
|:--------|:-------------|:----|
| `Illegal instruction (core dumped)` on import/selftest | numpy 2.x wheel on the G-T56N CPU (no SSE4.2) | `pip install 'numpy<2'`; the package pins `numpy>=1.24,<2` — don't upgrade |
| `cannot open '/dev/xdma0_*': No such file or directory` | xdma.ko not loaded | `sudo insmod ~/dma_ip_drivers/XDMA/linux-kernel/xdma/xdma.ko` |
| `cannot open ... Operation not permitted` | udev rule not applied | Re-run `make py-deploy`; check `/etc/udev/rules.d/99-xdma.rules`, perms should be `crw-rw-rw-` |
| `native extension ... is not built` | `_native.so` missing (e.g. after rsync wipe) | `~/chisel_npu_py/.venv/bin/pip install ~/chisel_npu_py` |
| pybind11 compile errors: `buffer.h: No such file` / no `is_c_contiguous` | pybind11 ≥ 3 API changes | Use `pybind11/numpy.h`, `py::array::ensure(obj, py::array::c_style)`, `arr.flags() & py::array::c_style` |
| `unknown section 'X'` | section not in A/B/ACCUM/OUT/CODE | Check the spelling; the map is the `NPUConfig` sections in `config.py` |
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
- Do NOT hardcode addresses outside `config.py` (the `NPUConfig`) and
  `native_src/native.cpp` (which consumes it) — that pair owns every section
  base/window and register offset.
