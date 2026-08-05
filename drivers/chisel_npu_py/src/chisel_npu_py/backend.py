"""XDMADevice — Python access to the NPU's XDMA interface (data side only).

All address handling happens inside the pybind11 `_native` module
(class `NativeXDMA`); Python never sees a DDR address or a register
offset.  This class only moves buffers:

  * `write_staged("A", buf)` / `read_staged("OUT", out)` — MMALU operands
    are addressed by NAME; the native module checks the name and the exact
    byte size against its staging table;
  * `operand_size("ACCUM")` — buffer sizes, for allocating arrays;
  * `ctrl_read()` / `ctrl_write(value)` — the ctrl_lite register is a
    single control word at a fixed offset inside the native module.
"""

from __future__ import annotations

import glob
from typing import Optional, Union

import numpy as np

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

    Opens the h2c/c2h DMA channels and the bypass BAR inside the native
    module and keeps them for the lifetime of the object.
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

    # ── staged MMALU operands (addressed by name; addresses owned natively) ─

    def write_staged(self, operand: str, data: Buffer) -> int:
        """Stage operand *operand* ('A'|'B'|'ACCUM'|'OUT') to the NPU memory.

        The native module checks the name AND the exact byte size; the
        address itself never appears on the Python side.
        """
        return int(self._native.write_staged(operand, data))

    def read_staged(self, operand: str, out: Buffer) -> int:
        """Read operand *operand* back into *out* (size-checked, zero-copy)."""
        return int(self._native.read_staged(operand, out))

    def operand_size(self, operand: str) -> int:
        """Byte size of the staged operand *operand* (as owned by native)."""
        return int(self._native.operand_size(operand))

    # ── ctrl_lite control word (single register, offset owned natively) ─────

    def ctrl_read(self) -> int:
        """Read the ctrl_lite control word (start/done/busy bits)."""
        return int(self._native.ctrl_read())

    def ctrl_write(self, value: int) -> None:
        """Write the ctrl_lite control word."""
        self._native.ctrl_write(int(value))
