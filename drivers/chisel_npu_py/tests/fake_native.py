"""FakeNative — pure-Python stand-in for the pybind11 `_native` module.

Implements the same public interface and the same validation invariants
(alignment, length multiples, DDR window, staging size checks) so that the
Python-side logic (XDMADevice / CtrlLite / ChiselNPU) can be unit-tested
without the compiled extension or hardware.

Register behaviour is scriptable:
  * by default, the ctrl_lite 'done' latch asserts after two register reads
    following a kick (start bit set) — mirrors the hardware done latch;
  * set `scripted_timeout = True` to keep 'busy' asserted forever (wait_done
    must then time out).
"""

from __future__ import annotations

import numpy as np

from chisel_npu_py import consts

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
        if addr < consts.DDR_BASE or addr + nbytes > consts.DDR_BASE + consts.DDR_SIZE:
            raise ValueError("address range outside the 4 GB DDR window "
                             "(0x00000000..0xFFFFFFFF)")

    def _raw_bytes(self, data) -> tuple[bytes, int]:
        arr = np.ascontiguousarray(data)
        return arr.tobytes(), int(arr.nbytes)

    # ── DMA ─────────────────────────────────────────────────────────────────

    def dma_write(self, data, addr: int) -> int:
        raw, nbytes = self._raw_bytes(data)
        self._validate(int(addr), nbytes)
        self.mem[int(addr)] = raw
        return nbytes

    def dma_read_raw(self, addr: int, nbytes: int) -> bytes:
        self._validate(int(addr), int(nbytes))
        return self.mem.get(int(addr), _SENTINEL)[: int(nbytes)]

    def dma_read_into(self, out, addr: int) -> int:
        addr = int(addr)
        if isinstance(out, np.ndarray):
            raw = self.mem.get(addr, b"\x00" * out.nbytes)[: out.nbytes]
            out[...] = np.frombuffer(raw, dtype=out.dtype).reshape(out.shape)
            return out.nbytes
        raw = self.mem.get(addr, b"\x00" * len(out))[: len(out)]
        out[:] = raw
        return len(out)

    # ── staged operands ─────────────────────────────────────────────────────

    def staging_map(self) -> dict[str, tuple[int, int]]:
        return {name: (base, size) for name, (base, size) in consts.STAGING.items()}

    def _staged_slot(self, operand: str) -> tuple[int, int]:
        if operand not in consts.STAGING:
            raise ValueError(
                f"unknown staging operand '{operand}' (expected one of: A, B, ACCUM, OUT)"
            )
        return consts.STAGING[operand]

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
        if isinstance(out, np.ndarray):
            if out.nbytes != size:
                raise ValueError(
                    f"operand '{operand}' must be exactly {size} bytes, got {out.nbytes}"
                )
            return self.dma_read_into(out, base)
        raise TypeError("out must be a numpy array")

    # ── registers (scripted ctrl_lite) ──────────────────────────────────────

    def reg_write(self, offset: int, value: int) -> None:
        offset, value = int(offset), int(value)
        self.regs[offset] = value
        if offset == consts.CTRL_REG and (value & (1 << consts.CTRL_START_BIT)):
            self._reads_after_kick = 0

    def reg_read(self, offset: int) -> int:
        offset = int(offset)
        if offset == consts.CTRL_REG and self._reads_after_kick is not None:
            self._reads_after_kick += 1
            if self.scripted_timeout:
                return self.regs.get(offset, 0) | (1 << consts.CTRL_BUSY_BIT)
            if self._reads_after_kick >= 2:
                self.regs[offset] = self.regs.get(offset, 0) | (1 << consts.CTRL_DONE_BIT)
        return self.regs.get(offset, 0)
