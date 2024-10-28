# Systolic Array

[TOC]

Systolic arrays are often the fundamental execution unit in modern NPU implementations. They are efficient in matrix multiplication, which is widely adopted in neural networks. However, it is not flexible to execute high performance vector or scalar operations. In general, a systolic array will have latency of $3N-2$ (from the first element of input matrices to the final element of the output matrix). To maximize the gain from a systolic array architecture, most implementation will have around $64\times 64$ grid of [PEs](ProcessingElement.md). Larger size of the grid will suffer more from the memory wall. Hence hardware engineers need to battle with this multi-constrained problem and try to find a sweet point for the design.

Improving IPC(Instruction per Cycle) of systolic arrays is quite straight forward. We will handle the pre-fetch and pipeline the stages for sure. We also need to design an appropriate control for the systolic array to make it exeuting instructions without bubbles in the array.

## What is bubbles in a systolic array?

We need to reshape the input matrix into a stair shaped vectors like below. And there will be some areas (marked in gray) where does not have any useful data in it. This is what we call bubbles here.

<div style="text-align: center">
<img src="../../images/sa_tick_1.png" width=80%/>
</div>

Those bubbles will drastically harm the performance if not handled properly. Some of you may notice that we can actually tile the previous input matrix with it. Yes that is correct, but we need to figure out the timing first.

## Timing of systolic array: with an $4\times 4$ array example

We will discuss the timing with an $4\times 4$ systolic array. The latency from an $4\times 4$ systolic array is $3\times 4 - 2 = 10$. We have already demonstrated the system status at the first tick [in the previous section](#what-is-bubbles-in-a-systolic-array). And here is the system status at the 4-th tick:

<div style="text-align: center">
<img src="../../images/sa_tick_4.png" width=60%/>
</div>


At the end of the 4-th cycle, the first element of the systolic array has completed its computation. From this cycle, we can gradually write back the result to scratch pad memory (SPM / L1-D) or L2 memory. 

<div style="text-align: center">
<img src="../../images/sa_tick_5.png" width=60%/>
</div>


The next cycle (the 5-th cycle), the first element will be idle. In another word, this element can accept data for the next input and compute. 

<div style="text-align: center">
<img src="../../images/sa_tick_7.png" width=60%/>
</div>


The systolic **will not receiver any input for the current matrix multiplication** from this cycle. 

<div style="text-align: center">
<img src="../../images/sa_tick_10.png" width=80%/>
</div>


This is the what the final cycle will look like.

## General Timing for $N\times N$ array

<div style="text-align: center">
<img src="../../images/sa_ticks_seq.png" width=80%/>
</div>

## Summary

Systolic array can support pipelining, but unlike traditional DSP architectures, a systolic pipeline is more gradual and continuous. So the control unit should be designed carefully to make use of this advantage.