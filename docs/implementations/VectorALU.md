# Vector Arithmetic Logic

[TOC]

## Data types

Implemented items are checked below

- [ ] f32
- [ ] f16
- [ ] bf16
- [ ] f8
- [ ] bf8
- [ ] s8
- [ ] s4pack2
- [ ] s2pack4

VAL will consider identical input/output datatypes and also the implicit data type conversion during operation

## Pseudo instructions

```assembly
.code
main:
    # memory bank usage
    # vector instructions: vinc, vadd, vmul, vlookup, vshl, vshr
    # vector dtype: f32, f16, bf16, s8, i4pack2, i2pack4

    vinc vah
    vadd vb0, vb1, vb2
    vmul vb0, vb1, vb2
    vlookup vb0, vb1, vb2
    vshl vb0, vb1, vb2
    vshr vb0, vb1, vb2
    