"""Constants shared between the Python layer and the native extension.

The authoritative address tables live in native.cpp; the values below
mirror them so Python-side code and unit tests can reason about the memory
map without importing the compiled module.  At runtime,
`XDMADevice.staging_map()` returns the truth from the native module.
"""

from __future__ import annotations

# MMALU systolic array side (K=32 on the FPGA bitstream)
K = 32

# Unified host-visible DDR window (MIG C0 + C1 through axi_xbar)
DDR_BASE = 0x0000_0000
DDR_SIZE = 0x1_0000_0000  # 4 GB

# ctrl_lite register (BAR2 bypass, offset 0x00)
CTRL_REG = 0x00
CTRL_START_BIT = 0  # W: write 1 to start the NPU DMA+MMA cycle (self-clears)
CTRL_DONE_BIT = 1   # RO: latched 1 when the DMA master finishes
CTRL_BUSY_BIT = 2   # RO: 1 while the DMA master FSM is active

# MMALU operand staging table in MIG C0 (mirror of native.cpp kStaging)
STAGING: dict[str, tuple[int, int]] = {
    "A":     (0x4000_0000, 32),    # int8[K]
    "B":     (0x4000_0100, 32),    # int8[K]
    "ACCUM": (0x4000_0200, 128),   # int32[K]
    "OUT":   (0x4000_0400, 128),   # int32[K]
}

STAGING_BASE = 0x4000_0000  # NPU operand region in MIG C0
