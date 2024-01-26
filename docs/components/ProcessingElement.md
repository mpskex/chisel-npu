# Processing Element

```ascii_art
        CBUS    TOP
           \     |
            \    |
             \___v___
             | Proc  |
   LEFT ---->| Elem  |-----> RIGHT
              ¯¯¯|¯¯¯\
                 |    \
                 v     \
               BOTTOM   OUT (to TLB mapped memory)
```

Processing Element is the fundamental element in systolic array. This is a basic implementation of a 2D PE for 2D systolic array.


-----------

## General Idea

> **NOTE**: below is not implemented

We are expecting all data pipeline to be systolic array, which means the all of `control`, `data`, `address` to be in side the systolic array. To be more specific, no matter how processing dimension for `data` is (2D or more), the dimension of systolic array for `control` and `address` will be always 1-dimensional. So we can simplify the hardware design by reusing components and building native pipeline for the whole systolic array.

We should also design a configurable design. For some simple implementation for NPU, we will mask other operations other than `MUL`. So that the PE will only do multiplication with systolic array.

-----------

## Control Input

> **NOTE**: below is not implemented

Our NPU should support multiple functionality including `MUL`, `ADD` and other ops. As we are combining [VLIW](https://en.wikipedia.org/wiki/Very_long_instruction_word) with systolic array, we need to expand our control bit length to support independent control to those detachable ALU design.

For each 1-way ALU pass, the control will look like this:

wavedrom (
    {reg: [
        {"bits": 2, "name": "DLEN", "attr": ["8 bits", "16 bits", "32 bits", "Reserved"]},
        {"bits": 2, "name": "DTYPE", "attr": ["INT", "UINT", "FLOAT", "BFLOAT"]},
        {"bits": 4, "name": "OP", "attr": ["MUL", "ADD", "AND", "OR", "XOR", "NOT" ]},
        {"bits": 3, "name": "Reserved", type:1},
        {"bits": 1, "name": "ACCUM"},
    ],
    config: {"hspace": "width", "bits": 12}}
)

And we have 4 1-way ALU inside a single PE, which mean it could be 4x 1-way ALUs, 2x 2-way ALUs, 2x 1-way ALUs + 1x 2-way ALU or 1x 4-way ALU. We will have a flexible design regarding to this design.

wavedrom (
    {reg: [
        {"bits": 12, "name": "CTRL#0"},
        {"bits": 12, "name": "CTRL#1"},
        {"bits": 12, "name": "CTRL#2"},
        {"bits": 12, "name": "CTRL#3"},
    ],
    config: {"hspace": "width", "bits": 48}}
)

This would cause some issue on the scheduling. When dispatching 2-way input with 1-way inputs, the control will have to mask the adjacent control signal. To align with the little endian standard we will always listen from the last (lower bit positions). For example, if we dispatch CTRL#0 for 1-way 8bit OP, CTRL#1&2 for 2-way 16bit OP, and CTRL#3 for 1-way 8bit OP, then this is what we will get:

wavedrom (
    {reg: [
        {"bits": 12, "name": "CTRL#0 for 8bit OP", "type": 3},
        {"bits": 12, "name": "CTRL#1 for 16 bit OP", "type": 2},
        {"bits": 12, "name": "CTRL#2 doesn't matter", "type": 1},
        {"bits": 12, "name": "CTRL#3 for 8bit OP", "type": 4},
    ],
    config: {"hspace": "width", "bits": 48}}
)

In this case, the instruction for CTRL#2 group will be ignored and only executing regarding to the signal from CTRL#1 group.

### On Tiling

Tiling on NPU should be controlled by the software. For example, when a really big matrix multiplication with multiple dimensions, the compiler should be responsible to arrange the dispatch schedule in advance. We will NOT consider multi-tasking for now (unlike modern GPUs) and exposing this to the firmware will be more flexible to work with.

Also, to improve the efficiency we use `ACCUM` signal to chop off the accumulated results from the register. It has two purposes:
1. When `ACCUM` is high, the register inside the PE block will accumulate the result (MatMul / MatBin Ops).
2. When `ACCUM` is low, the register will not accumulate the result (clear with new input) 

Well the tricky part of this is: the MMU should handle the write back regarding to a negative pulse edge on the last clock cycle. We need to do a RTL simulation to see if it is dangerous / insufficient timing gap to our design.

### On Computation Capability

We define a byte flag for PE computation capability of bit length supported.

wavedrom (
    {reg: [
        {"bits": 4, "name": "BitLen Cap", "type": 2},
        {"bits": 4, "name": "Dtype Cap", "type": 3},
    ],
    config: {"hspace": "width", "bits": 8}}
)


|Cap Byte|DataType Cap|BitLen Cap|8-bit int/uint|16-bit int/uint|16-bit float|16-bit bfloat|32-bit int/uint|32 bit float|
|:----|:----|:----|:----:|:----:|:----:|:----:|:----:|:----:|
|0x00|0x0|0x0|O|X|X|X|X|X|
|0x01|0x0|0x1|O|O|X|X|X|X|
|0x02|0x0|0x2|O|O|X|X|O|X|    
|0x11|0x1|0x1|O|O|O|X|X|X|   
|0x12|0x1|0x2|O|O|O|X|O|O|
|0x22|0x2|0x2|O|O|X|O|O|O|
|0x32|0x3|0x2|O|O|O|O|O|O|

This value will be written to NVRAM / other storages. It will represent the actual configuration of our integrated NPU.

-----------

## Data Input

> **NOTE**: below is not implemented

We borrow the basic idea from [VLIW](https://en.wikipedia.org/wiki/Very_long_instruction_word) architecture and try to combine it with systolic arrays. Thus, we will have different layouts for different data length setup. This is designated for dynamic scheduling and improved FP32/16 performance.

When setting `DLEN` to `0`, then the 32-bit input will be treated as below:

wavedrom (
    {reg: [
        {"bits": 8, "name": "8 Bit IN #0"},
        {"bits": 8, "name": "8 Bit IN #1"},
        {"bits": 8, "name": "8 Bit IN #2"},
        {"bits": 8, "name": "8 Bit IN #3"}
    ],
    config: {"hspace": "width", "bits": 32}}
)

When setting `DLEN` to `1`, then the 32-bit input will be treated as below:

wavedrom (
    {reg: [
        {"bits": 16, "name": "16 Bit IN #0"},
        {"bits": 16, "name": "16 Bit IN #1"}
    ],
    config: {"hspace": "width", "bits": 32}}
)

When setting `DLEN` to `2`, then the 32-bit input will be treated as a whole 32-bit input.

The design will not focus on the actual ALU design. We can definitely fuse those ALU with some advanced technologies. This will be an out-of-scope to this prototype NPU design. We will be using the defualt ALU design from the FPGA vender.

-----------

## Timing

PE component will only accumulate the result if the `ACCUM` is high. This is efficient for pipelining multiple GEMM in stream.

wavedrom (
    { signal: [
      { name: "clk", wave:"P......", period: 4 },
      { name: "top_in", wave: "x====xx", data:["top_1", "top_2", "top_3", "top_4"], period: 4},
      { name: "left_in", wave: "x====xx", data:["left_1", "left_2", "left_3", "left_4"], period: 4},
      { name: "accu", wave: "1...01.", period: 4},
      { name: "right_out", wave: "xx====x", data:["top_1", "top_2", "top_3", "top_4"], period: 4},
      { name: "bottom_out", wave: "xx====x", data:["left_1", "left_2", "left_3", "left_4"], period: 4},
      { name: "out", wave: "xx====x", data:["prod1=top_1*left_1", "prod_1 + top_2 * left_2", "prod_3=top_3 * left_3", "prod_3 + top_4 * left_4"], period: 4},
      ] }
)
