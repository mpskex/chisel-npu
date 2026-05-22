# Chisel workbench for Open NPU design

[![Documentation Status](https://readthedocs.org/projects/chisel-opennpu/badge/?version=latest)](https://chisel-opennpu.readthedocs.io/en/latest/?badge=latest)

Docs: https://chisel-opennpu.readthedocs.io

This is a chisel workbench designed for someone who like docker containers and vscode dev container plugin.

## Highlights

- **RISC-V-style 32-bit ISA** with 13 opcode families (LD/ST, MMA, VALU_*),
  R/I/S formats, full decoder (`isa/instrDecoder.scala`) and Scala assembler
  (`isa/NpuAssembler.scala`).
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
- **FPGA reference platform**: Kintex-7 `xc7k480tffg1156-2` with PCIe Gen2×8 +
  dual DDR3 + K=32 MMALU at 200 MHz fabric / 250 MHz NPU. See
  [docs/implementations/FPGA_XC7K480T.md](docs/implementations/FPGA_XC7K480T.md).

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
