# AGENTS.md

Repo-specific guidance for OpenCode. Read `README.md` first for the user-facing overview; this file covers what an agent would otherwise guess wrong.

## Toolchain

- Chisel 6.7.0, Scala 2.13.12, sbt 1.9.7 (`build.sbt`, `project/build.properties`).
- Requires firtool 1.62.1, verilator v5.036, SystemC 3.0.1. All provided by the `fangruil/chisel-dev:{amd64,arm64}` Docker image (`docker/dockerfile`).
- Bare-host `sbt` generally will not work unless firtool is on `PATH` and Chisel 6.7.0 is `publishLocal`'d. Prefer Docker for every command.

## Commands

All `make` targets shell out to `docker run -v $PWD:/workspace fangruil/chisel-dev:<arch> ...`. `ARCH` comes from `uname -m`; `x86_64` is remapped to `amd64` inside `Makefile`.

- `make image` — build the dev image (`make image-amd64` / `image-arm64`).
- `make container` — interactive shell in the image with repo mounted at `/workspace`.
- `make test` — runs `sbt test` in the image.
- `make build` — runs `sbt run`, which elaborates `top.Main` and writes `top.sv` at repo root.
- `make build-sc` — verilator SystemC backend. Note: the target reads `top.v` but `top.Main` emits `top.sv` (`Makefile:29,34` vs `src/main/scala/top/top.scala:18`). Rename or patch before invoking.
- `make docs` — `pip3 install -r docs/requirements.txt && mkdocs serve` on the host (not in Docker).
- `make clean` — removes `target`, `*.v`, `*.anno.json`. Does **not** delete the checked-in `top.sv` (~17 MB).
- `make clean-cache` — wipes `.cache/` (the mounted Coursier cache; see env below).

### Single-test shortcut

- `tool/test-specific-spec.sh <fully.qualified.Spec>` → `sbt "testOnly <Spec>"` in the image.
- `tool/test-all.sh` → same as `make test`.

## Parameter notation (authoritative)

These three symbols are used everywhere in the ISA, VALU, register-file, and backend code.
Confusing them is the primary source of errors in this codebase.

| Symbol | Meaning | Default (test) | Default (top) |
|:---:|:---|:---:|:---:|
| **`N`** (spoken: **N(bits)**) | Base lane width in bits for a VX register. Equals MMALU's `nbits`. Always `N(bits)` in prose to avoid confusion. | 8 | 8 |
| **`L`** | Number of base VX registers. Must be divisible by 4 for VE/VR aliasing. | 32 | 32 |
| **`K`** | SIMD lane count per register. At the backend boundary, `K == MMALU.n` (the array side). | 8 | 64 |

Register-class aliasing over a shared physical byte array (`L × K × N/8` bytes total):

| Class | Count | Lane width | Aliases |
|:---|:---:|:---|:---|
| VX[0..L-1] | 32 | N bits | native |
| VE[0..L/2-1] | 16 | 2N bits | `VE[i] ≡ VX[2i] ∥ VX[2i+1]` |
| VR[0..L/4-1] | 8 | 4N bits | `VR[i] ≡ VX[4i..4i+3]` |

**MMALU `n` vs VALU/backend `K`**: MMALU's `n` is the *systolic array side length*, not a lane count. `NCoreBackend` enforces `K == mmalu.n` at instantiation. Do not conflate them.

## ISA encoding (RISC-V-style 32-bit instruction word)

Three instruction formats (see `src/main/scala/isa/instrFormat.scala` for bit-position constants):

```
R-type  [funct7(7) | rs2(5) | rs1(5) | funct3(3) | rd(5) | opcode(7)]
I-type  [    imm[11:0](12)  | rs1(5) | funct3(3) | rd(5) | opcode(7)]
S-type  [rs3(5)|rnd(2)| rs2(5) | rs1(5) | funct3(3) | rd(5) | opcode(7)]
```

`opcode` (7 bits) selects a *functional family*; `funct3` (3 bits) selects the sub-operation;
`funct7` (7 bits) carries attributes:

```
funct7 [1:0] = width   (0=VX, 1=VE, 2=VR)
funct7 [3:2] = round   (0=RNE, 1=RTZ, 2=floor, 3=ceil)
funct7 [4]   = saturate
funct7 [6:5] = dtype   (0=INT, 1=FP, 2=BF)
```

CVT family (`opcode=0x14`) repurposes `funct7[2:0]` as the source format code and `funct7[3]`
as saturate. See `src/main/scala/isa/instrFormat.scala` for the full `CvtFunct7` layout.

### Opcode family assignments (file: `src/main/scala/isa/instSetArch.scala`)

| Family | Opcode | funct3 subops |
|:---|:---:|:---|
| NOP | 0x00 | — |
| LD / ST | 0x01/0x02 | funct3 = transfer width |
| MMA | 0x03 | 0=mma, 1=mma.last, 2=mma.reset |
| VALU_ARITH | 0x10 | add/sub/mul/neg/abs/max/min/rsub |
| VALU_LOGIC | 0x11 | sll/srl/sra/rol/xor/not/or/and |
| VALU_REDUCE | 0x12 | sum/rmax/rmin/rand/ror/rxor |
| VALU_LUT | 0x13 | exp/recip/tanh/erf |
| VALU_CVT | 0x14 | funct3=dst fmt; funct7[2:0]=src fmt |
| VALU_BCAST | 0x15 | 0=reg, 1=imm |
| VALU_FP | 0x16 | fadd/fsub/fmul/fneg/fabs/fmax/fmin |
| VALU_FP_FMA | 0x17 | fma/fms/nfma/nfms (S-format) |
| VALU_MOV | 0x18 | mov/movi/movh |

### Assembler and decoder (files: `src/main/scala/isa/`)

- **`NpuAssembler.scala`** — Scala-side assembler. `encR / encI / encS` primitives; named helpers like `vadd(rd, rs1, rs2, width, sat)`, `vfma(...)`, `vcvt_s8_f32(...)`. All helpers return `Int` (unsigned 32-bit bit pattern); use `.toLong & 0xFFFFFFFFL` before `.U` to avoid negative-literal Chisel errors.
- **`InstrDecoder.scala`** — combinational decoder module: `UInt(32.W)` → `DecodedMicroOp`. Asserts `io.illegal` for reserved opcodes, invalid funct3, reserved width `3`, or CVT `src==dst`. `NCoreBackend` calls this first.
- **`instrFormat.scala`** — bit-position constants, `VecWidth`, `VecRound`, `VecDtypeCls`, `FmtCode` enums.

### Critical encoding gotchas

- The `VecWidth` ChiselEnum field inside `NCoreVALUBundle` is renamed to **`regCls`** (was `width`) to avoid a Chisel plugin naming conflict with `chisel3.Width`. Anywhere you see `.regCls`, that is the VX/VE/VR register-class selector.
- `opcode` in `_OpCode` is 7-bit. `OpFamily` enum values go up to 0x18 = 24, which requires 5 bits (Chisel auto-infers minimum width). When feeding `opBits` (7-bit) into `OpFamily.safe(...)`, truncate first: `opBits(4, 0)`.
- `NpuAssembler` encodes instruction words as Scala `Int`. Values with bit 31 set are negative in Scala. Always poke as `(instr.toLong & 0xFFFFFFFFL).U` in tests.
- CVT: `vcvt_s8_f32` means INT8→FP32 (wide output to VR). `vcvt_f32_s8` means FP32→INT8 (narrow output to VX). The naming convention is `vcvt_<dst>_<src>`.

## Source layout (`src/main/scala/`)

- `alu/mma/` — `MMALU` top, `sa/` systolic array + DataFeeder/Collector, `cu/ControlUnit`.
- `alu/pe/` — `BasePE` trait and `MMPE`.
- `alu/vec/` — `VALU` (multi-width K-lane), `Qfmt` LUT tables (shared with tests), `fp.scala` (FP32/BF16/BF8 Tier-2 helpers, `FpRef` Scala reference for tests).
- `backend/SimpleBackend.scala` — `NCoreBackend`: InstrDecoder + MultiWidthRegisterBlock + MMALU + VALU.
- `isa/` — `instrFormat.scala`, `instSetArch.scala`, `NpuAssembler.scala`, `instrDecoder.scala`, `dataType.scala`, `micro_op/` (VALUMicroCode, MMALUMicroCode, memMicroCode).
- `sram/register.scala` — legacy `RegisterBlock` (still used by old tests).
- `sram/multiWidthRegister.scala` — `MultiWidthRegisterBlock` (VX/VE/VR aliased RF, used by NCoreBackend).
- `sram/spm.scala` — `SPM(K, N, SPM_ROWS)`: scratch-pad memory, K-wide read/write, 1-cycle read latency.
- `sram/sreg.scala` — `SpecialRegFile`: `.sreg` — tile_h/tile_w counters + `ConvParams` for future ld.tile/PAG.
- `utils/gates.scala`, `top/top.scala`.
- `ip/vivado/` — packaged Vivado project. Not part of sbt build.

## Testing

- Uses `chisel3.simulator.EphemeralSimulator` (native Chisel 6). **Not** `chiseltest`.
- Shared helpers in package `testUtil` (`src/test/scala/utils/`). Import `testUtil._`.
- ChiselEnum fields: **`poke(op)` works** but **`expect(op)` does NOT** in EphemeralSimulator. Use `dut.io.field.asInstanceOf[chisel3.UInt].peek().litValue == SomeEnum.val.litValue` to compare enum outputs.
- All new test specs use `isa.NpuAssembler` to build instruction words instead of poking bundle fields directly — more realistic and tests the decoder path.
- `VALUArithSpec`, `VALULogicSpec`, `VALUMinMaxSpec`, `VALUReduceSpec`, `VALULutSpec`, `VALUActivationSpec` — VALU unit tests (poke `ctrl` bundle directly; `K=8`).
- `VALUCastSpec` — vbcast (broadcast) tests.
- `VALUFP32Spec`, `VALUCvtSpec` — FP32 arithmetic and conversion tests.
- `InstrDecoderSpec` — 32-bit decode correctness (via NpuAssembler).
- `MultiWidthRegisterSpec` — VX/VE/VR aliasing.
- `NCoreBackendQuantSpec` — end-to-end: MMA→vcvt→vfma→vcvt quantization pipeline.

## CI

`.github/workflows/actions.yml`: on push/PR to `main` or `releases/**`, runs `sbt run` then `sbt test` inside `fangruil/chisel-dev:amd64`. Reproduce failures locally with `make container` + `sbt run` / `sbt test`.

## Gotchas

- `build.sbt` contains unfilled chisel-template placeholders: `name := "%NAME%"` and `organization := "%ORGANIZATION%"`. sbt accepts them; leave alone unless intentionally renaming the project.
- `.cache/` at repo root is the Coursier cache (Dockerfile sets `COURSIER_CACHE=/workspace/.cache/coursier/v1`). Do not `rm -rf` it casually; use `make clean-cache`.
- Dockerfile env: `CHISEL_FIRTOOL_PATH=/usr/local/bin`, `SYSTEMC_INCLUDE=/opt/systemc/include`, `SYSTEMC_LIBDIR=/opt/systemc/lib`.
- `scalacOptions` include `-Xcheckinit` and `-Ymacro-annotations`; the `chisel-plugin` compiler plugin is required.
- `target/`, `test_run_dir/`, and `top.sv` are build artifacts — do not commit edits to them as source changes.
- `RegisterBlock.io.w_addr` is `Vec(rd_banks, ...)` (should be `wr_banks` — pre-existing bug). All `rd_banks` entries must be driven to avoid firtool "uninitialized sink" errors.
- `MultiWidthRegisterBlock.io.ext_r_addr` must be driven from the backend even when the external read port is not used; default it to 0.
- VecOp enum values go up to 0x45 = 69, requiring 7-bit width. The enum is declared with `.U(7.W)` values. If you add new entries, ensure the max value still fits in 7 bits.
- The ISA uses RISC-V-style R/I/S encoding: opcode selects a functional *family*; `funct3` selects the sub-op; `funct7` carries attributes. See `docs/designs/01.isa.md` for the full field layout.
