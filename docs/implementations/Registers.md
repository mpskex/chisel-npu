# Register Files

[TOC]

The NPU register file is implemented as a **multi-width aliased block** (`MultiWidthRegisterBlock`)
that presents three views over a single physical byte array:
VX (base), VE (paired), and VR (quad).
This design lets GEMM, vector arithmetic, and FP32 post-processing share the same storage
without data copying.

---

## Notation

| Symbol | Meaning | Default (test) | Default (top) |
|:---:|:---|:---:|:---:|
| `N` (N(bits)) | Base lane width in bits | 8 | 8 |
| `L` | Number of VX registers (must be divisible by 4) | 32 | 32 |
| `K` | SIMD lane count per register | 8 | 64 |

Physical storage = `L × K × (N/8)` bytes = **256 B** at test defaults / **2 KiB** at top.

---

## Register Classes

Three views share the same physical bytes:

| Class | Count | Lane width | Per-reg bits | Address bits | Alias |
|:---|:---:|:---:|:---:|:---:|:---|
| **VX**[0..L-1] | 32 | N bits | K × N | 5 | native row |
| **VE**[0..L/2-1] | 16 | 2N bits | K × 2N | 4 | VE[i] = VX[2i] ∥ VX[2i+1] |
| **VR**[0..L/4-1] | 8 | 4N bits | K × 4N | 3 | VR[i] = VX[4i..4i+3] |

### Physical byte layout (N=8, K=8, L=32)

```
Physical bytes: 32 rows × 8 lanes × 1 byte = 256 B

Row  0  : VX[0]  lane[0..7]   ─┐
Row  1  : VX[1]  lane[0..7]   ─┤ VE[0]  lane[0..7] ─┐
Row  2  : VX[2]  lane[0..7]   ─┤                      ┤ VR[0]  lane[0..7]
Row  3  : VX[3]  lane[0..7]   ─┤ VE[1]  lane[0..7] ─┘
          ...                   ┘
Row  4  : VX[4]  lane[0..7]   ─┐
Row  5  : VX[5]  lane[0..7]   ─┤ VE[2]  lane[0..7] ─┐
Row  6  : VX[6]  lane[0..7]   ─┤                      ┤ VR[1]  lane[0..7]
Row  7  : VX[7]  lane[0..7]   ─┤ VE[3]  lane[0..7] ─┘
          ...

Row 28  : VX[28] lane[0..7]   ─┐
Row 29  : VX[29] lane[0..7]   ─┤ VE[14] lane[0..7] ─┐
Row 30  : VX[30] lane[0..7]   ─┤                      ┤ VR[7]  lane[0..7]
Row 31  : VX[31] lane[0..7]   ─┤ VE[15] lane[0..7] ─┘
```

Lane packing within a VE or VR word (little-endian):

```
VE[i] lane j = { VX[2i+1][lane j][N-1:0], VX[2i][lane j][N-1:0] }
                  ──── hi N bits ────────   ──── lo N bits ────────

VR[i] lane j = { VX[4i+3][lane j], VX[4i+2][lane j],
                  VX[4i+1][lane j], VX[4i+0][lane j] }
                  ─── bits [4N-1:3N] ───  ─── [3N-1:2N] ───
                  ─── [2N-1:N] ─────────  ─── [N-1:0] ───
```

### Aliasing consequences

- Writing **VR[i]** atomically updates VX[4i], VX[4i+1], VX[4i+2], VX[4i+3] and thus VE[2i], VE[2i+1].
- Writing **VE[i]** atomically updates VX[2i] and VX[2i+1].
- Reading **VX[j]** after a **VR write** to VR[j/4] returns the byte that was written.
- **Conflict resolution**: last writer wins per physical row. Software is responsible for avoiding write-after-write conflicts within the same cycle.

---

## MultiWidthRegisterBlock

**Source**: `src/main/scala/sram/multiWidthRegister.scala`  
**Package**: `sram.mwreg`

### Parameters

| Parameter | Type | Default | Description |
|:---|:---:|:---:|:---|
| `L` | Int | 32 | Number of VX rows (must be divisible by 4) |
| `K` | Int | 8 | SIMD lane count |
| `N` | Int | 8 | Base lane width in bits (N(bits)) |
| `vx_rd` | Int | 4 | Number of VX async read ports |
| `vx_wr` | Int | 2 | Number of VX write ports |
| `ve_rd` | Int | 2 | Number of VE async read ports |
| `ve_wr` | Int | 1 | Number of VE write ports |
| `vr_rd` | Int | 2 | Number of VR async read ports |
| `vr_wr` | Int | 2 | Number of VR write ports |

### I/O ports

All reads are **asynchronous** (combinational read, registered in the caller).
All writes are **synchronous** (registered on clock edge).

| Port | Direction | Width | Description |
|:---|:---:|:---|:---|
| `vx_r_addr(p)` | Input | 5 bits | VX read address for port p |
| `vx_r_data(p)` | Output | K × N bits | VX read data for port p |
| `vx_w_addr(p)` | Input | 5 bits | VX write address for port p |
| `vx_w_data(p)` | Input | K × N bits | VX write data for port p |
| `vx_w_en(p)` | Input | Bool | VX write enable for port p |
| `ve_r_addr(p)` | Input | 4 bits | VE read address |
| `ve_r_data(p)` | Output | K × 2N bits | VE read data |
| `ve_w_addr(p)` | Input | 4 bits | VE write address |
| `ve_w_data(p)` | Input | K × 2N bits | VE write data |
| `ve_w_en(p)` | Input | Bool | VE write enable |
| `vr_r_addr(p)` | Input | 3 bits | VR read address |
| `vr_r_data(p)` | Output | K × 4N bits | VR read data |
| `vr_w_addr(p)` | Input | 3 bits | VR write address |
| `vr_w_data(p)` | Input | K × 4N bits | VR write data |
| `vr_w_en(p)` | Input | Bool | VR write enable |
| `ext_r_addr` | Input | 5 bits | External read (VX width); test-harness use |
| `ext_r_data` | Output | K × N bits | External read data |
| `ext_w_addr` | Input | 5 bits | External write address |
| `ext_w_data` | Input | K × N bits | External write data |
| `ext_w_en` | Input | Bool | External write enable |

### Write priority (per physical row)

When multiple write ports target the same row in the same cycle, priority is:

```
VR (highest) > VE > VX > ext (lowest)
```

The last-priority rule is implemented as overwrite chaining in combinational logic:
each successive priority level simply overwrites the previous assignment for
that row's `wr_data` wire.

!!! warning "ext_r_addr must be driven"
    `ext_r_addr` is an input port that must always be driven from the backend, even when
    the external read port is not in use. Default it to `0.U`. Leaving it undriven causes
    firtool to report an "uninitialized sink" elaboration error.

---

## Backend Port Assignment

`NCoreBackend` instantiates `MultiWidthRegisterBlock` with 4 VX read ports,
2 VX write ports, 2 VE read/1 VE write port, and 2 VR read/2 VR write ports.

### Read ports

| RF port | Index | Connected to | Purpose |
|:---|:---:|:---|:---|
| `vx_r_addr(0)` | 0 | `io.mma_a_addr` | MMALU operand A |
| `vx_r_addr(1)` | 1 | `io.vx_a_addr` | VALU `in_a_vx` |
| `vx_r_addr(2)` | 2 | `io.vx_b_addr` | VALU `in_b_vx` |
| `vx_r_addr(3)` | 3 | `io.mma_b_addr` / `io.ext_rd_addr` | MMALU B or external read |
| `ve_r_addr(0)` | 0 | `io.ve_a_addr` | VALU `in_a_ve` |
| `ve_r_addr(1)` | 1 | `io.ve_b_addr` | VALU `in_b_ve` |
| `vr_r_addr(0)` | 0 | `io.vr_a_addr` | VALU `in_a_vr` (and `out_vr` read-back) |
| `vr_r_addr(1)` | 1 | `io.vr_b_addr` | VALU `in_b_vr` + `in_c_vr` |
| `ext_r_addr` | — | `io.ext_rd_addr` | Test-harness read (VX lanes) |

### Write ports

| RF port | Index | Connected to | Purpose |
|:---|:---:|:---|:---|
| `vx_w_en(0)` | 0 | VALU narrow result | INT8/BF8 output |
| `vx_w_en(1)` | 1 | `io.ext_wr_en` | Test-harness write |
| `ve_w_en(0)` | 0 | VALU VE result | INT16/BF16 output |
| `vr_w_en(0)` | 0 | VALU VR result | INT32/FP32 VALU output |
| `vr_w_en(1)` | 1 | **MMALU accumulator** (direct) | INT32 from systolic array — **no truncation** |

!!! note "MMALU direct VR write"
    The MMALU's `Vec(K, SInt(4N.W))` accumulator output is wired directly into VR write
    port 1. There is **no INT8 truncation** — the full INT32 precision is preserved in VR
    for subsequent `vcvt_f32_s32` and `vfma` instructions.

---

## Legacy RegisterBlock

`src/main/scala/sram/register.scala` contains the original flat `RegisterBlock`
(multi-bank, single width). It is **still used by standalone MMALU and SA tests**
(`MMALUSpec`, `RegisterSpec`, etc.) but is not used by `NCoreBackend`.

!!! warning "RegisterBlock w_addr quirk"
    `RegisterBlock.io.w_addr` is declared as `Vec(rd_banks, ...)` instead of
    `Vec(wr_banks, ...)` — a pre-existing naming inconsistency. When using this module
    in test harnesses, **all `rd_banks` entries of `w_addr` must be explicitly driven**
    (even unused write address slots) to avoid firtool "uninitialized sink" errors.
    See `SimpleBackend.scala` for the workaround pattern.

---

## Implemented vs Planned

| Lane type | VX | VE | VR | Notes |
|:---|:---:|:---:|:---:|:---|
| INT8 (S8) | ✓ | — | — | Primary VALU dtype |
| INT16 (S16) | — | ✓ | — | ARITH, LOGIC ops |
| INT32 (S32) | — | — | ✓ | MMALU accumulator; ARITH, CVT |
| FP32 | — | — | ✓ | Tier-2 IEEE754 subset |
| BF16 | — | ✓ | — | CVT only (truncation/padding) |
| BF8 E4M3 | ✓ | — | — | CVT only |
| BF8 E5M2 | ✓ | — | — | CVT only (funct7[6]=1) |
| UINT8 (U8) | — | — | — | Planned |
| FP16 | — | — | — | Planned |
