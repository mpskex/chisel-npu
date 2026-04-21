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
    | Symbol | Meaning | Test default | Top (K=64) |
    |:---:|:---|:---:|:---:|
    | **`N`** (**N(bits)**) | Base lane width in bits. Matches MMALU `nbits`. Always spelled `N(bits)` in prose. | 8 | 8 |
    | **`L`** | Number of base VX registers. Must be divisible by 4. | 32 | 32 |
    | **`K`** | SIMD lane count per register. Equals MMALU array-side `n` at the backend boundary. | 8 | 64 |

    Register classes share the same physical bytes (`L × K × N/8` total):

    | Class | Count | Lane width | Aliases |
    |:---|:---:|:---|:---|
    | VX[0..L-1] | 32 | N bits | native |
    | VE[0..L/2-1] | 16 | 2N bits | VE[i] = VX[2i] ∥ VX[2i+1] |
    | VR[0..L/4-1] | 8 | 4N bits | VR[i] = VX[4i..4i+3] |

---

## ISA Designs

- [Instructions (ISA)](designs/01.isa.md) — 32-bit RISC-V-style encoding, 13 opcode families, funct7 attribute map, timing reference
- [Memory](designs/02.memory.md)
- [Buses](designs/03.bus.md)

---

## Implementation Details

- [Neural Core (NCore)](implementations/NeuralCore.md) — `NCoreBackend`: InstrDecoder + MultiWidthRF + MMALU + VALU pipeline
    - [Processing Element (PE)](implementations/ProcessingElement.md)
    - [Systolic Array (SA)](implementations/SystolicArray.md)
    - [Vector ALU (VALU)](implementations/VectorALU.md) — K-lane, FP32/BF16/BF8, multi-width arithmetic
    - [Register Files](implementations/Registers.md) — `MultiWidthRegisterBlock`, VX/VE/VR aliasing

- [Quantization Pipeline](implementations/Quantization.md) — worked example: MMA → vcvt → vfma → vcvt INT8 requantization

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
