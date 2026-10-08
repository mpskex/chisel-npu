# chisel-npu-py

Python userspace driver for the **instruction-programmed** Chisel NPU over
the Xilinx XDMA kernel driver, for the xc7k480t FPGA card.

The whole driver is one call: stage named-section memories, run a sequence
of ISA instruction words, read everything back.

## Usage

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

`run()` stages each memory into its named section (A/B/ACCUM/OUT/CODE,
window-checked natively), stages the words into CODE, fires the engine
(PROG_LEN → start → done), raises `NPUProgramError(pc, err_info)` on an
illegal instruction, and returns the full memories dict read back plus OUT
(as flat int32 of the OUT window; numpy inputs come back with the same
dtype/shape).

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

## Sections (default K=16 config)

| Section | Base | Window | Contents |
|:--------|:-----|:-------|:---------|
| A | `0x4000_0000` | 4 KiB | int8 vectors |
| B | `0x4000_0400` | 4 KiB | int8 vectors |
| ACCUM | `0x4000_0800` | 128 B | int32[K] |
| OUT | `0x4000_0880` | 4 KiB | int32 outputs |
| CODE | `0x4000_4000` | 256 KiB | program words |

## Design

- **`config.NPUConfig`** — the single source of truth: section bases/windows,
  ctrl register offsets, the fixed nop wait. `default_config()` = the
  silicon-verified K=16 bitstream.
- **`isa`** — the Python assembler: `vle8/16/32`, `vse8/16/32`, `mma`,
  `mma_last`, `nop` (pure word encodings, mirroring the Scala assembler).
- **`chisel_npu_py._native`** — pybind11 C++ boundary: the only place with
  XDMA fds and DMA transfers; validates every transfer against the config.

## Tests

```bash
make py-test-unit   # FakeNative (software engine model), no hardware
make py-deploy      # rsync + rebuild the extension on the FPGA host
make py-test-hw     # sessions on silicon
```
