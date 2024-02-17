# Chisel OpenNPU

This is an open-source neural processing unit implementation in Chisel3.

Specifically, this NPU is targeted at to be integerated to a low-power and edge-oriented SoC systems. So all design choices are facing those demands.

You can check the source code on [GitHub](https://github.com/mpskex/chisel-npu).

For overall chip design, you may find [the FullChipDesign website](https://www.fullchipdesign.com/) pretty helpful there.

## ISA Designs
- [Instructions](designs/01.isa.md)
- [Memory](designs/02.memory.md)
- [Buses](designs/03.bus.md)

## Implementation Details

- [Neural Core (NCore)](implementations/NeuralCore.md)
  - [Processing Element (PE)](implementations/ProcessingElement.md)
  - [Systolic Array (SA)](implementations/SystolicArray.md)