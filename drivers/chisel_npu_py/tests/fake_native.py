"""FakeNative — pure-Python stand-in for the pybind11 `_native` module.

It is the test double of the C++ side, so it keeps DDR addresses INTERNAL
(just like native.cpp); its public interface is the same data-only surface:
write_staged/read_staged/operand_size/ctrl_read/ctrl_write.

Validation invariants mirror native.cpp (alignment, length multiples,
size-checked operands) so Python-side logic is unit-tested against the
same contract.

Register behaviour is scriptable:
  * by default, the ctrl_lite 'done' latch asserts after two ctrl_read
    calls following a kick (start bit set) — mirrors the hardware latch;
  * set `scripted_timeout = True` to keep 'busy' asserted forever
    (wait_done must then time out).
"""

from __future__ import annotations

import numpy as np

from chisel_npu_py import consts

# Internal staging table (addresses never cross the fake's public API) —
# mirrors native.cpp's kStaging.
_STAGING: dict[str, tuple[int, int]] = {
    "A":     (0x4000_0000, 32),    # int8[K]
    "B":     (0x4000_0100, 32),    # int8[K]
    "ACCUM": (0x4000_0200, 128),   # int32[K]
    "OUT":   (0x4000_0400, 128),   # int32[K]
}

_CTRL_REG = 0x00
_DDR_BASE = 0x0000_0000
_DDR_SIZE = 0x1_0000_0000

_SENTINEL = b"\x00" * 4096


class FakeNative:
    def __init__(self, prefix: str = "/dev/xdma0", h2c_ch: int = 0, c2h_ch: int = 0):
        self.prefix = prefix
        self.h2c_ch = h2c_ch
        self.c2h_ch = c2h_ch
        self.regs: dict[int, int] = {}
        self.mem: dict[int, bytes] = {}
        self.scripted_timeout = False
        self._reads_after_kick: int | None = None

    # ── validation (mirrors native.cpp) ─────────────────────────────────────

    @staticmethod
    def _validate(addr: int, nbytes: int) -> None:
        if nbytes == 0:
            raise ValueError("zero-length transfer")
        if addr % 4:
            raise ValueError("address must be 4-byte aligned")
        if nbytes % 4:
            raise ValueError("length must be a multiple of 4 bytes")
        if addr < _DDR_BASE or addr + nbytes > _DDR_BASE + _DDR_SIZE:
            raise ValueError("address range outside the 4 GB DDR window "
                             "(0x00000000..0xFFFFFFFF)")

    @staticmethod
    def _raw_bytes(data) -> tuple[bytes, int]:
        arr = np.ascontiguousarray(data)
        return arr.tobytes(), int(arr.nbytes)

    # ── staged operands (data-only surface, like the real native module) ───

    def _staged_slot(self, operand: str) -> tuple[int, int]:
        if operand not in _STAGING:
            raise ValueError(
                f"unknown staging operand '{operand}' (expected one of: A, B, ACCUM, OUT)"
            )
        return _STAGING[operand]

    def operand_size(self, operand: str) -> int:
        return self._staged_slot(operand)[1]

    def write_staged(self, operand: str, data) -> int:
        base, size = self._staged_slot(operand)
        raw, nbytes = self._raw_bytes(data)
        if nbytes != size:
            raise ValueError(
                f"operand '{operand}' must be exactly {size} bytes, got {nbytes}"
            )
        self.mem[base] = raw
        return nbytes

    def read_staged(self, operand: str, out) -> int:
        base, size = self._staged_slot(operand)
        if not isinstance(out, np.ndarray):
            raise TypeError("out must be a numpy array")
        if out.nbytes != size:
            raise ValueError(
                f"operand '{operand}' must be exactly {size} bytes, got {out.nbytes}"
            )
        raw = self.mem.get(base, b"\x00" * size)
        out[...] = np.frombuffer(raw, dtype=out.dtype).reshape(out.shape)
        return size

    # ── ctrl_lite control word (scripted) ───────────────────────────────────

    def ctrl_read(self) -> int:
        if self._reads_after_kick is not None:
            self._reads_after_kick += 1
            if self.scripted_timeout:
                return self.regs.get(_CTRL_REG, 0) | (1 << consts.CTRL_BUSY_BIT)
            if self._reads_after_kick >= 2:
                self.regs[_CTRL_REG] = self.regs.get(_CTRL_REG, 0) | (
                    1 << consts.CTRL_DONE_BIT
                )
        return self.regs.get(_CTRL_REG, 0)

    def ctrl_write(self, value: int) -> None:
        self.regs[_CTRL_REG] = int(value)
        if value & (1 << consts.CTRL_START_BIT):
            self._reads_after_kick = 0
