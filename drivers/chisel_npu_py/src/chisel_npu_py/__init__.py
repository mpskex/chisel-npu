"""chisel_npu_py — Python userspace driver for the Chisel NPU over XDMA.

The pybind11 module `chisel_npu_py._native` (built on the FPGA host via
`pip install .`) owns all file descriptors, DDR addresses, register
offsets and DMA transfers.  This Python layer provides the typed API:

    from chisel_npu_py import ChiselNPU
    import numpy as np

    npu = ChiselNPU()
    a   = np.full(32, 10, dtype=np.int8)
    b   = np.full(32, 7,  dtype=np.int8)
    acc = np.zeros(32, dtype=np.int32)
    out = npu.mmalu(a, b, acc)          # int32[32] result

Run `python -m chisel_npu_py selftest` on the FPGA host for a device
smoke check.
"""

from . import consts
from .backend import Buffer, XDMADevice
from .ctrl import CtrlLite
from .errors import NPUError, NPUTimeoutError, XDMAError
from .npu import ChiselNPU

__version__ = "0.1.0"

__all__ = [
    "Buffer",
    "ChiselNPU",
    "CtrlLite",
    "NPUError",
    "NPUTimeoutError",
    "XDMAError",
    "XDMADevice",
    "consts",
    "__version__",
]
