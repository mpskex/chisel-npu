# Chisel OpenNPU

This is an open-source neural processing unit implementation in Chisel3.

Specifically, this NPU is targeted at to be integerated to a low-power and edge-oriented SoC systems. So all design choices are facing those demands.

For overall chip design, you may find [the FullChipDesign website](https://www.fullchipdesign.com/) pretty helpful there.

## Designs
- [Instructions](designs/01.isa.md)
- [Memory](designs/02.memory.md)
- [Buses](designs/03.bus.md)

## Implementation Details

- [Processing Element (PE)](components/ProcessingElement.md)