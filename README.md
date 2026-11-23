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
- **Streamed program engine** (`engine/NpuProgramEngineFrontend`, vu9p
  baseline K=32, N=8, W=8): a pipelined frontend (fetch engine → instruction
  FIFO → issue, ~1 word/cycle) feeding an issue window with a mask-based
  scoreboard, unit queues, MMA capture/chaining and a 2-stage MMALU feed
  pipeline.
- **Board-agnostic peripheral**: `peripheral/NpuPeripheral` wraps the engine
  in the NPUCTL v1 control hub (start/stop/reset, IRQ, watchdog, trace,
  five programmable segment bases) and translates the core's segmented AXI
  master to a standard 64-bit system AXI master. The IP release is
  `build/NpuPeripheral.sv` plus `build/npu_peripheral.json` (`make release`).
  See [docs/designs/07.npuctl-v1.md](docs/designs/07.npuctl-v1.md).
- **Board-centric split**: the XDMA/PCIe configuration, DDR3/DDR4 controllers
  and their memory models, clocking, ILA and board testbenches live in the
  separate `npu-platform` repository (bootstrapped at
  `~/chisel-npu-platform`). The same `NpuPeripheral` release is pinned there.
- **Board flows**: Kintex-7 `xc7k480tffg1156-2` (PCIe + dual DDR3) and
  Virtex UltraScale+ `xcvu9p-flgb2104-2-e` (XDMA + 4× DDR4 MIG) now live in the
  separate `~/chisel-npu-platform` repository (Vivado projects, constraints,
  bitstreams, board tests). The old in-repo `ip/vivado/**` and `tool/hw/**`
  copies are being removed from this IP repository.

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
# Build the board-facing NpuPeripheral release + manifest
make release
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
├── ip                  // legacy board integration (scheduled to move)
│   └── vivado          // xilinx vivado
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
