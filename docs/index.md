# Chisel OpenNPU

[TOC]

An open-source Neural Processing Unit implementation in Chisel 6.
Targets low-power, edge-oriented SoC integration.

Source code: [GitHub](https://github.com/mpskex/chisel-npu)

---

## Notation

The following three symbols appear throughout all documentation, source code, and tests.
Confusing them causes hard-to-debug hardware elaboration errors.

!!! info "Parameter definitions"
    | Symbol | Meaning | Test default | FPGA top (K=16) |
    |:---:|:---|:---:|:---:|
    | **`N`** (**N(bits)**) | Base lane width in bits. Matches MMALU `nbits`. Always spelled `N(bits)` in prose. | 8 | 8 |
    | **`L`** | Number of base VX registers. Must be divisible by 4. | 32 | 16 |
    | **`K`** | SIMD lane count per register. Equals MMALU array-side `n` at the backend boundary. | 8 | 16 |

    `W` (program-engine dispatch-window depth) defaults to 16 and is **4** for the FPGA builds.

    Register classes share the same physical bytes (`L × K × N/8` total):

    | Class | Count | Lane width | Aliases |
    |:---|:---:|:---|:---|
    | VX[0..L-1] | 32 | N bits | native |
    | VE[0..L/2-1] | 16 | 2N bits | VE[i] = VX[2i] ∥ VX[2i+1] |
    | VR[0..L/4-1] | 8 | 4N bits | VR[i] = VX[4i..4i+3] |

    The FPGA top is the streamed **program engine**
    (`engine/NpuProgramEngineFrontend`, `K=16, N=8, W=4`); it sets `L = K`, so its
    register file is 256 B. The `K=8` column is the `NCoreBackend` test default;
    `K=32`/`K=64` appear only in legacy `npu_subsys` docs.

---

## ISA Designs

- [Instructions (ISA)](designs/01.isa.md) — 32-bit RISC-V-style encoding, 13 opcode families, funct7 attribute map, timing reference; decode map `isa/InstrTable.scala`
- [Streamed dispatch (overview)](designs/02.streamed-dispatch.md) — high-level streamed-matmul overview (partly superseded by the issuing doc)
- [Streamed issuing (detail)](designs/04.streamed-issuing.md) — authoritative issuing model of `NpuProgramEngine`
- [Memory (aspirational)](designs/02.memory.md) — early WIP; the implemented memory path is the engine's `NpuDmaEngine` + `NpuSections` map
- [Buses (aspirational)](designs/03.bus.md) — early WIP; not implemented

---

## Implementation Details

- [Neural Core (NCore)](implementations/NeuralCore.md) — `NCoreBackend` (test/legacy backend): InstrDecoder + MultiWidthRF + MMALU + VALU pipeline
    - [Processing Element (PE)](implementations/ProcessingElement.md)
    - [Systolic Array (SA)](implementations/SystolicArray.md)
    - [Vector ALU (VALU)](implementations/VectorALU.md) — K-lane, FP32/BF16/BF8, multi-width arithmetic (used by `NCoreBackend`, not the FPGA top)
    - [Register Files](implementations/Registers.md) — `MultiWidthRegisterBlock`, VX/VE/VR aliasing

- [Program Engine](designs/04.streamed-issuing.md) — `NpuProgramEngineFrontend` (the FPGA top, K=16, W=4): fetch frontend + dispatch window + scoreboard + MMA capture/chaining + 2-stage feed pipeline

- [Quantization Pipeline](implementations/Quantization.md) — worked example: MMA → vcvt → vfma → vcvt INT8 requantization

- [FPGA Verification Platform (xc7k480t)](implementations/FPGA_XC7K480T.md) — PCIe Gen2×8 + dual DDR3 + K=16 program engine at 200 MHz; legacy V10 topology history

- [FPGA Bring-up (xcvu9p)](implementations/FPGA_VU9P.md) — Virtex UltraScale+ (`xcvu9p-flgb2104-2-e`): PCIe Gen3×8 + 4× DDR4, engine timing-closed at 200 MHz, remote `hw_server` programming

- [Silicon Bring-up Log](implementations/SiliconBringup.md) — program-engine determinism fixes found on the xc7k480t

- [Python Driver (chisel_npu_py)](implementations/PythonDriver.md) — instruction-programmed userspace XDMA driver with a pybind11 address-owning C++ boundary and a numpy-first Python API

---

## Tutorials

- [GEMM + Softmax Quantization](tutorials/gemm_softmax_quantization.md) — post-accumulation quantization pipeline for transformer attention activation; demonstrates reduction ops, programmable LUT activation (`vlut`/`vsetlut`), numerical stability, and full end-to-end quantization chain with Scala reference verification

---

## Quick Start

```bash
# Build the dev image
make image

# Enter the dev container
make container

# Run all tests (inside container or via Docker)
make test

# Elaborate top-level design (writes top.sv)
make build
```

See `README.md` for full setup instructions.
