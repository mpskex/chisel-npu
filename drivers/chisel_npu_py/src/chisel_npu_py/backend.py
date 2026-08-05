"""XDMADevice — high-level Python access to the NPU's XDMA interface.

All address handling, descriptor work and transfers happen inside the
pybind11 `_native` module (class `NativeXDMA`); Python only passes buffers
(numpy arrays, bytes, bytearray, memoryview) and, for raw transfers, the
DDR address — which the native module validates (window + alignment)
before touching the device.
"""

from __future__ import annotations

import glob
from typing import Optional, Union

import numpy as np

from . import consts
from .errors import XDMAError

try:
    from ._native import NativeXDMA as _NativeXDMA
    _NATIVE_IMPORT_ERROR: Optional[ImportError] = None
except ImportError as exc:  # pragma: no cover - depends on deployment
    _NativeXDMA = None
    _NATIVE_IMPORT_ERROR = exc

Buffer = Union[np.ndarray, bytes, bytearray, memoryview]

_DEFAULT_PREFIX = "/dev/xdma0"


def _require_native():
    if _NativeXDMA is None:
        raise XDMAError(
            "native extension 'chisel_npu_py._native' is not built "
            f"({_NATIVE_IMPORT_ERROR}). "
            "Build it on the FPGA host with: pip install . "
            "(compiles the pybind11 module in the target venv)."
        )
    return _NativeXDMA


class XDMADevice:
    """A handle to one XDMA card's device nodes.

    Opens ``<prefix>_h2c_<ch>``, ``<prefix>_c2h_<ch>`` and
    ``<prefix>_bypass`` in the native module and keeps them for the
    lifetime of the object.
    """

    def __init__(
        self,
        prefix: str = _DEFAULT_PREFIX,
        h2c_ch: int = 0,
        c2h_ch: int = 0,
        native=None,
    ):
        self.prefix = prefix
        if native is None:
            native = _require_native()(prefix, h2c_ch, c2h_ch)
        self._native = native

    # ── discovery ───────────────────────────────────────────────────────────

    @staticmethod
    def list_nodes(prefix: str = _DEFAULT_PREFIX) -> list[str]:
        """Return sorted /dev/xdma* node names with the given prefix."""
        return sorted(glob.glob(prefix + "_*"))

    def assert_nodes_present(self, minimum: int = 3) -> None:
        nodes = self.list_nodes(self.prefix)
        if len(nodes) < minimum:
            raise XDMAError(
                f"Expected >= {minimum} {self.prefix}_* device nodes, got "
                f"{len(nodes)}. Is the xdma kernel driver loaded?"
            )

    @property
    def native(self):
        """The underlying native module object (injectable for tests)."""
        return self._native

    def staging_map(self) -> dict[str, tuple[int, int]]:
        """Return the native module's MMALU staging table (name → addr, nbytes)."""
        return dict(self._native.staging_map())

    # ── raw DMA (addresses validated by the native module) ──────────────────

    def dma_write(self, ddr_addr: int, data: Buffer) -> int:
        """Write *data* (numpy array or bytes-like) to *ddr_addr*. Returns bytes written."""
        return int(self._native.dma_write(data, int(ddr_addr)))

    def dma_read_raw(self, ddr_addr: int, nbytes: int) -> bytes:
        """Read *nbytes* raw bytes from *ddr_addr*."""
        return self._native.dma_read_raw(int(ddr_addr), int(nbytes))

    def dma_read_into(self, ddr_addr: int, out: Buffer) -> int:
        """Read into caller-provided buffer *out* (zero-copy for numpy arrays)."""
        return int(self._native.dma_read_into(out, int(ddr_addr)))

    def dma_read(self, ddr_addr: int, nbytes: int) -> np.ndarray:
        """Read raw bytes from *ddr_addr* as a uint8 ndarray."""
        return np.frombuffer(self.dma_read_raw(ddr_addr, nbytes), dtype=np.uint8)

    def read_i32(self, ddr_addr: int, count: int) -> np.ndarray:
        """Read *count* int32 words from *ddr_addr*."""
        out = np.empty(count, dtype=np.int32)
        self.dma_read_into(ddr_addr, out)
        return out

    def write_i32(self, ddr_addr: int, values: np.ndarray) -> int:
        """Write an int32 ndarray to *ddr_addr*."""
        return self.dma_write(ddr_addr, np.ascontiguousarray(values, dtype=np.int32))

    # ── staged MMALU operands (addresses owned by the native module) ────────

    def write_staged(self, operand: str, data: Buffer) -> int:
        """Stage operand *operand* ('A'|'B'|'ACCUM'|'OUT') to DDR.

        The native module checks the name AND the exact byte size; the
        address itself is never visible to Python.
        """
        return int(self._native.write_staged(operand, data))

    def read_staged(self, operand: str, out: Buffer) -> int:
        """Read operand *operand* from DDR into *out* (size-checked)."""
        return int(self._native.read_staged(operand, out))

    # ── ctrl_lite registers ─────────────────────────────────────────────────

    def reg_read(self, offset: int) -> int:
        """Read a 32-bit ctrl_lite register at *offset* (BAR2 bypass)."""
        return int(self._native.reg_read(int(offset)))

    def reg_write(self, offset: int, value: int) -> None:
        """Write a 32-bit ctrl_lite register at *offset* (BAR2 bypass)."""
        self._native.reg_write(int(offset), int(value))
