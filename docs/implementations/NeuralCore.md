# Neural Core

Neural Core should be a super-scalar processor with super pipeline. Due to the streaming characteristics of systolic array, which is our major components in our core, we should design the processor to do something while the systolic array is busy to improve the throughput. 

Tranditional Instruction Per Cycle (IPC) cannot be very expressive on Neural Processing Unit designs. Systolic array will need a series of micro opeartion to finish the matrix multiplication. During those long series of micro operations, the core can still do something else, like vector operations and loading data.

<div style="text-align: center">
<img src="../../images/neural_core.png" width=80%/>
</div>

Similar to normal super-scalar processors, we also adopt those multi dispatch design with an out-of-order dispatch and out-of-order execution over a super-pipelining framework.