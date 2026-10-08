"""XDMADevice — Python access to the NPU's XDMA interface (data side only).

All address handling happens inside the pybind11 `_native` module
(class `NativeXDMA`) against the NPUConfig supplied at construction; Python
never sees a DDR address or a raw register offset.  This class only moves
buffers:

  * `write_staged("A", buf, offset)` / `read_staged("OUT", out, offset)` —
    named sections; offsets are section-relative and validated natively;
  * `section_size("CODE")` — window sizes, for allocation and checks;
  * `ctrl_read_reg(off)` / `ctrl_write_reg(off, val)` — the ctrl register
    map by *name* is resolved in `chisel_npu_py.ctrl` (Python still passes
    offsets, but they come from the single NPUConfig).
"""

from __future__ import annotations

import glob
from typing import Optional, Union

import numpy as np

from .config import NPUConfig, default_config
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


def _sections_map(cfg: NPUConfig):
    """{name: (base, window)} — the only place the Python→native boundary
    serializes addresses (straight from the single NPUConfig)."""
    return {name: (sec.base, sec.window) for name, sec in cfg.sections.items()}


def _ctrl_map(cfg: NPUConfig):
    return {name: off for name, off in cfg.ctrl.items()}


class XDMADevice:
    """A handle to one XDMA card's device nodes, configured by an NPUConfig."""

    def __init__(
        self,
        cfg: Optional[NPUConfig] = None,
        prefix: str = _DEFAULT_PREFIX,
        h2c_ch: int = 0,
        c2h_ch: int = 0,
        native=None,
    ):
        self.cfg = cfg if cfg is not None else default_config()
        self.prefix = prefix
        if native is None:
            native = _require_native()(
                prefix, h2c_ch, c2h_ch,
                _sections_map(self.cfg), _ctrl_map(self.cfg),
            )
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

    # ── named sections (offsets only; addresses live in the NPUConfig) ──────

    def write_staged(self, name: str, data: Buffer, offset: int = 0) -> int:
        """Write *data* into named section *name* at section offset *offset*."""
        return int(self._native.write_staged(name, data, int(offset)))

    def read_staged(self, name: str, out: Buffer, offset: int = 0) -> int:
        """Read section *name* from *offset* into *out* (size-checked)."""
        return int(self._native.read_staged(name, out, int(offset)))

    def section_size(self, name: str) -> int:
        """Byte window of section *name* (as owned by the NPUConfig)."""
        return int(self._native.section_size(name))

    # ── ctrl register map (offsets from the NPUConfig) ──────────────────────

    def ctrl_read_reg(self, offset: int) -> int:
        return int(self._native.ctrl_read_reg(int(offset)))

    def ctrl_write_reg(self, offset: int, value: int) -> None:
        self._native.ctrl_write_reg(int(offset), int(value))
