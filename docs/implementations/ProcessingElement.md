# Processing Element

```ascii_art
        ACCUM   IN_B
           \     |
            \    |
             \___v___
             | Proc  |
   IN_A ---->| Elem  |
              ¯¯¯|¯¯¯
                 |
                 v
                OUT (to TLB mapped memory)
```

Processing Element is the fundamental element in systolic array. This is a basic implementation of a 2D PE for 2D systolic array or DSP grid.

## Timing

PE component will only accumulate the result if the `ACCUM` is high. This is efficient for pipelining multiple GEMM in stream.

wavedrom (
    { signal: [
      { name: "clk", wave:"P......", period: 4 },
      { name: "in_a", wave: "x====xx", data:["a_1", "a_2", "a_3", "a_4"], period: 4},
      { name: "in_b", wave: "x====xx", data:["b_1", "b_2", "b_3", "b_4"], period: 4},
      { name: "accu", wave: "1...01.", period: 4},
      { name: "out", wave: "xx====x", data:["prod1=a_1*b_1", "prod_1 + a_2 * b_2", "prod_3=a_3 * b_3", "prod_3 + a_4 * b_4"], period: 4},
      ] }
)
