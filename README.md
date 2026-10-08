# Chisel workbench for Open NPU design

[![Documentation Status](https://readthedocs.org/projects/chisel-opennpu/badge/?version=latest)](https://chisel-opennpu.readthedocs.io/en/latest/?badge=latest)

Docs: https://chisel-opennpu.readthedocs.io

This is a chisel workbench designed for someone who like docker containers and vscode dev container plugin.

## Highlights

- **RISC-V-style 32-bit ISA** with 13 opcode families (LD/ST, MMA, VALU_*),
  R/I/S formats, a single authoritative decode map (`isa/InstrTable.scala`)
  driving the decoder (`isa/instrDecoder.scala`), a Scala assembler
  (`isa/NpuAssembler.scala`) and a table-driven disassembler
  (`isa/NpuDisassembler.scala`).
- **K×K systolic MMALU** that natively supports **?×K streaming reduction**
  — one continuous `ctrl.keep = true` feed accumulates over arbitrary M ≥ K
  cycles, with cumulative K×K partial sums emitted at every K-cycle boundary.
  See [docs/implementations/SystolicArray.md — M×K Streaming Reduction](https://chisel-opennpu.readthedocs.io/en/latest/implementations/SystolicArray/#mk-streaming-reduction)
  and the verifying spec
  [`src/test/scala/alu/mma/MMALUStreamReduceSpec.scala`](src/test/scala/alu/mma/MMALUStreamReduceSpec.scala).
- **K-lane VALU** with FP32 / BF16 / BF8 conversions, fused multiply-add,
  programmable two-bank LUT (`vlut` / `vsetlut`), and horizontal reductions.
- **Multi-width register file** with VX (K×N), VE (K×2N), VR (K×4N) views
  sharing the same physical storage — INT8 inputs, INT32/FP32 accumulators in
  one bank.
- **End-to-end post-MMA quantization pipeline** verified bit-accurately against
  a Scala `java.lang.Float` reference (`NCoreBackendQuantSpec`,
  `NCoreBackendGemmSoftmaxSpec`).
- **Streamed program engine** (`engine/NpuProgramEngineFrontend`, K=16, N=8,
  dispatch window `W`): a fetched-instruction frontend + issue window with a
  mask-based scoreboard, unit queues, MMA capture/chaining and a 2-stage MMALU
  feed pipeline. This is the FPGA top (`src/main/scala/top/top.scala`, `W=4`).
- **FPGA reference platform**: Kintex-7 `xc7k480tffg1156-2` with PCIe Gen2×8 +
  dual DDR3 + the K=16 streamed program engine at 200 MHz fabric. See
  [docs/implementations/FPGA_XC7K480T.md](docs/implementations/FPGA_XC7K480T.md).
- **FPGA bring-up on Virtex UltraScale+**: `xcvu9p-flgb2104-2-e` (Alivu9p) with
  PCIe Gen3×8 XDMA + 4× DDR4 MIG + the K=16 program engine (W=4), timing-closed
  at 200 MHz, built with local Vivado and programmed remotely via `hw_server`.
  The `chisel_npu_py` HW suite passes 7/7. See
  [docs/implementations/FPGA_VU9P.md](docs/implementations/FPGA_VU9P.md) and
  [`ip/vivado/xcvu9p/README.md`](ip/vivado/xcvu9p/README.md).

## Usage

```bash
# Build docker image for chisel dev:
make image
# Create & Run the image as a container
make container
# Test chisel design
make test
# Build verilog design from chisel
make build
# Build systemc
make build-sc
# Build docs, visit http://localhost:8000 to see the documentation
make docs
```

Then you can use [vscode dev container plugin](https://marketplace.visualstudio.com/items?itemName=ms-vscode-remote.remote-containers) to connect this container. Happy coding (for chip)

## Project Structure
```
├── build.sbt           // project top level build
├── docker
│   └── dockerfile      // build env docker file
├── docs                // documentation
├── ip                  // IP integration with different EDAs
│   └── xilinx          // xilinx vivado
├── Makefile            // top level make file
├── mkdocs.yml          // readthedocs yaml
├── project             // scala project settings
├── README.md
├── src                 // chisel source
│   ├── main            // chisel design
│   └── test            // chisel tests
└── top.sv              // generated top system verilog
```

## Reference

1. [Chisel Matmul](https://github.com/kazutomo/Chisel-MatMul)
2. [Patmos VLIW processor](https://github.com/t-crest/patmos/tree/master/hardware)

## Useful Links

1. [Chisel project template](https://github.com/freechipsproject/chisel-template/tree/main#chisel-project-template)
2. [Chisel Bootcamp](https://mybinder.org/v2/gh/freechipsproject/chisel-bootcamp/master)
3. [ChiselTest](https://github.com/ucb-bar/chiseltest)
4. [Chisel Cheatsheet](https://github.com/freechipsproject/chisel-cheatsheet/releases/latest/download/chisel_cheatsheet.pdf)
5. [Chisel API Docs](https://javadoc.io/doc/org.chipsalliance/chisel_2.13/5.0.0/index.html)
