"""chisel_npu_py — Python userspace driver for the Chisel NPU over XDMA.

The whole driver is `ChiselNPU.run(instructions, memories)`:

    from chisel_npu_py import ChiselNPU, isa

    npu = ChiselNPU()
    result = npu.run(
        instructions=[isa.vle8(0, isa.SECT_A, 0),
                      isa.vle8(1, isa.SECT_B, 0),
                      isa.mma_last(2, 0, 1, 0),
                      *[isa.NOP] * 40,
                      isa.vse32(2, isa.SECT_OUT, 0)],
        memories={"A": a_bytes, "B": b_bytes},
    )
    # result = {"A": ..., "B": ..., "OUT": int32[...]}

All addresses and register offsets live in the single NPUConfig (default:
the silicon-verified K=16 bitstream); the XDMA staging and ctrl handling are
internal to run().
"""

from . import config, isa
from .backend import Buffer, XDMADevice
from .config import NPUConfig, Section, default_config
from .errors import NPUError, NPUProgramError, NPUTimeoutError, XDMAError
from .isa import Program
from .npu import ChiselNPU

__version__ = "0.2.1"

__all__ = [
    "Buffer",
    "ChiselNPU",
    "NPUConfig",
    "NPUError",
    "NPUProgramError",
    "NPUTimeoutError",
    "Program",
    "Section",
    "XDMADevice",
    "XDMAError",
    "config",
    "default_config",
    "isa",
    "__version__",
]
