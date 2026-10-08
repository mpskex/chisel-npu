---
name: chisel-npu-design
description: Use when working with the Chisel NPU source code — ISA encoding, VALU, MMALU, NCoreBackend, register file, assembler (NpuAssembler), or Scala tests. Also use when regenerating top.sv, running sbt test, or understanding the ISA parameter notation (N-bits, L, K).
---

# Chisel NPU Design

## Toolchain

- **Chisel 6.7.0**, Scala 2.13.12, sbt 1.9.7
- **firtool 1.62.1**, Verilator v5.036, SystemC 3.0.1
- All tools provided by `fangruil/chisel-dev:{amd64,arm64}` Docker image

```bash
make build      # sbt run → top.sv at repo root (~1.07 MB, top module NpuProgramEngineFrontend)
make test       # sbt test (all specs)
make container  # interactive shell in Docker
tool/test-specific-spec.sh <fully.qualified.Spec>  # run one spec
```

**Bare-host `sbt` does not work** unless firtool is on PATH and Chisel 6.7.0
is published locally. Always use Docker or `make` targets.

---

## Parameter notation (authoritative)

| Symbol | Meaning | Default (test) | Default (top) |
|:------:|:--------|:--------------:|:-------------:|
| **N** | Base lane width in bits (= MMALU `nbits`). Always written N(bits). | 8 | 8 |
| **L** | Number of base VX registers. Must be divisible by 4. | 32 | 16 |
| **K** | SIMD lane count per register (= MMALU systolic array side). | 8 | 16 |
| **W** | Dispatch-window depth (`NpuProgramEngineFrontend`). | 16 | 4 |

The FPGA top instantiates `NpuProgramEngineFrontend(K=16, N=8, W=4)` and sets
`L = K` (so L=16). W=8 did not close timing at 200 MHz on xcvu9p.

### Register class aliasing (L × K × N/8 bytes total)

| Class | Count | Lane width | Aliases |
|:------|:-----:|:-----------|:--------|
| VX[0..L-1] | 32 | N bits | native |
| VE[0..L/2-1] | 16 | 2N bits | `VE[i] ≡ VX[2i] ∥ VX[2i+1]` |
| VR[0..L/4-1] | 8 | 4N bits | `VR[i] ≡ VX[4i..4i+3]` |

---

## ISA encoding (32-bit instruction word)

Three formats:
```
R-type  [funct7(7) | rs2(5) | rs1(5) | funct3(3) | rd(5) | opcode(7)]
I-type  [    imm[11:0](12)  | rs1(5) | funct3(3) | rd(5) | opcode(7)]
S-type  [rs3(5)|rnd(2)| rs2(5) | rs1(5) | funct3(3) | rd(5) | opcode(7)]
```

**funct7 attribute fields:**
```
funct7[1:0] = width   (0=VX, 1=VE, 2=VR)
funct7[3:2] = round   (0=RNE, 1=RTZ, 2=floor, 3=ceil)
funct7[4]   = saturate
funct7[6:5] = dtype   (0=INT, 1=FP, 2=BF)
```

### Opcode family assignments

| Family | Opcode | funct3 subops |
|:-------|:------:|:--------------|
| NOP | 0x00 | — |
| LD / ST | 0x07/0x27 | funct3 = transfer width (0=VX, 1=VE, 2=VR) |
| MMA | 0x03 | 0=mma, 1=mma.last, 2=mma.reset |
| VALU_ARITH | 0x10 | add/sub/mul/neg/abs/max/min/rsub |
| VALU_LOGIC | 0x11 | sll/srl/sra/rol/xor/not/or/and |
| VALU_REDUCE | 0x12 | sum/rmax/rmin/rand/ror/rxor |
| VALU_LUT | 0x13 | vlut.A/B (0/1), vsetlut.A/B (4/5) |
| VALU_CVT | 0x14 | funct3=dst fmt; funct7[2:0]=src fmt |
| VALU_BCAST | 0x15 | 0=reg, 1=imm |
| VALU_FP | 0x16 | fadd/fsub/fmul/fneg/fabs/fmax/fmin |
| VALU_FP_FMA | 0x17 | fma/fms/nfma/nfms (S-format) |
| VALU_MOV | 0x18 | mov/movi/movh |

---

## Source layout

```
src/main/scala/
  alu/mma/          MMALU top, sa/ (SystolicArray2D, DataFeeder, Collector)
  alu/pe/           BasePE trait, MMPE
  alu/vec/          VALU (multi-width K-lane), Qfmt LUT tables, FP helpers
  backend/          NCoreBackend (InstrDecoder + RegisterFile + MMALU + VALU)
  isa/              instrFormat.scala, instSetArch.scala, InstrTable.scala
                    (authoritative decode map), NpuAssembler.scala,
                    NpuDisassembler.scala, instrDecoder.scala, dataType.scala,
                    micro_op/
  engine/           npuFrontend.scala (NpuProgramEngineFrontend: fetch/ctrl_lite),
                    npuProgramEngine.scala (dispatch window, scoreboard, DMA/MMA issue)
  dma/              npuDmaEngine.scala (engine AXI4 master)
  sram/             register.scala (legacy), multiWidthRegister.scala,
                    spm.scala (scratch-pad), sreg.scala (SpecialRegFile)
  utils/gates.scala
  top/top.scala     Top-level: elaborates NpuProgramEngineFrontend(K=16, N=8, W=4)

src/test/scala/
  utils/            testUtil helpers
  *Spec.scala       Unit tests per module
```

---

## Key gotchas

- **`regCls` not `width`** — the `NCoreVALUBundle` field is renamed `regCls`
  (was `width`) to avoid Chisel plugin naming conflict with `chisel3.Width`.

- **`opcode` is 7 bits, `OpFamily` is 6 bits** — `OpFamily` values run
  0x00..0x27, so it auto-infers 6 bits and cannot represent opcodes with bit 6
  set. The decoder compares the **full 7-bit** `opBits` and derives validity
  from membership in `OpFamily.all` (`familyOK`). Never truncate the opcode
  before decode: truncating would alias 0x40..0x7F onto valid families
  (e.g. 0x40 → NOP).

- **Negative literals** — `NpuAssembler` encodes instructions as Scala `Int`.
  Values with bit 31 set are negative in Scala. Always poke as
  `(instr.toLong & 0xFFFFFFFFL).U` in tests.

- **`expect` on ChiselEnum** — `poke(op)` works but `expect(op)` does NOT in
  `EphemeralSimulator`. Use:
  ```scala
  dut.io.field.asInstanceOf[chisel3.UInt].peek().litValue == SomeEnum.val.litValue
  ```

- **`RegisterBlock.io.w_addr`** — is `Vec(rd_banks, ...)` (should be
  `wr_banks` — pre-existing bug). All `rd_banks` entries must be driven to
  avoid firtool "uninitialized sink" errors.

- **CVT naming** — `vcvt_s8_f32` = INT8→FP32 (wide). `vcvt_f32_s8` =
  FP32→INT8 (narrow). Convention: `vcvt_<dst>_<src>`.

- **MMALU `n` vs VALU/backend `K`** — MMALU's `n` is the systolic array
  side length. `NCoreBackend` enforces `K == mmalu.n` at instantiation.

---

## Assembler (NpuAssembler)

```scala
import isa.NpuAssembler._

val instr: Int = vadd(rd=0, rs1=1, rs2=2, width=VecWidth.VX, sat=false)
// Poke safely:
dut.io.instr.poke((instr.toLong & 0xFFFFFFFFL).U)
```

Key helpers: `vadd`, `vsub`, `vmul`, `vfma`, `vfms`, `vcvt_s8_f32`,
`vcvt_f32_s8`, `vbcast_reg`, `vbcast_imm`, `mma`, `mma_last`, `mma_reset`,
`ld`, `st`.

---

## Running tests

```bash
# All tests (inside Docker):
make test

# Single spec:
tool/test-specific-spec.sh isa.InstrDecoderSpec
tool/test-specific-spec.sh alu.vec.VALUArithSpec
tool/test-specific-spec.sh backend.NCoreBackendQuantSpec

# Available specs:
# VALUArithSpec, VALULogicSpec, VALUMinMaxSpec, VALUReduceSpec
# VALUProgrammableLutSpec, VALUActivationSpec, VALUCastSpec
# VALUFP32Spec, VALUCvtSpec
# InstrDecoderSpec, InstrTableSpec, NpuDisassemblerSpec
# MultiWidthRegisterSpec, NCoreBackendQuantSpec, NCoreBackendGemmSoftmaxSpec
# engine: NpuProgramEngineFrontendSpec, NpuProgramEngineTrajSpec,
#         StreamedNSessionSpec, StreamedChainedSpec, StreamedVX0Spec,
#         CaptureDeterminismSpec, CaptureTraceSpec, LateStoreCaptureSpec
# dma: NpuDmaEngineSpec
```

---

## Regenerating top.sv

```bash
make build
# Runs: docker run fangruil/chisel-dev:amd64 sbt run
# Output: top.sv at repo root (~1.07 MB, top module NpuProgramEngineFrontend,
#         submodules NpuProgramEngine, NpuDmaEngine, InstrDecoder, MMALU)
```
