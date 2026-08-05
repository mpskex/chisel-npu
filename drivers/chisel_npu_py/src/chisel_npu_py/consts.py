"""Constants for the chisel_npu_py driver.

Addresses deliberately do NOT appear here: the native pybind11 module
(`chisel_npu_py._native`) is the single authority on DDR addresses and
register offsets.  Python only deals with operand names, buffer sizes and
protocol bit positions.
"""

from __future__ import annotations

# MMALU systolic array side (K=32 on the FPGA bitstream)
K = 32

# Named MMALU staging operands (byte sizes; addresses live in native.cpp)
OPERANDS = ("A", "B", "ACCUM", "OUT")

# ctrl_lite register bit positions (native module reads/writes the register)
CTRL_START_BIT = 0  # W: write 1 to start the NPU DMA+MMA cycle (self-clears)
CTRL_DONE_BIT = 1   # RO: latched 1 when the DMA master finishes
CTRL_BUSY_BIT = 2   # RO: 1 while the DMA master FSM is active
