"""FakeNative — pure-Python stand-in for the pybind11 `_native` module.

It is the test double of the C++ side, so it keeps DDR addresses INTERNAL
(just like native.cpp); its public interface is the same data-only surface:
write_staged/read_staged/section_size/ctrl_read_reg/ctrl_write_reg.

Beyond the transport, FakeNative embeds a **software model of the engine**
(the mma session / K-burst model, mirroring the Chisel engine):

    per mma feed:  PE[i][j] += A[vs1][i] · B[vs2][j]   (the session product)
    per mma capture (at its clct): vd[i] = PE[i][col_k] + C[i]
        col_k = (k + COL_OFF) mod K  (COL_OFF = the observed capture phase)
        C = 0 for vs3 = x0, else VR[vs3]

When the CTRL start bit is written it decodes the staged CODE program and
executes it against a fake register file + PE matrix (vle8/vle32 loads,
mma/mma.last, vse32 stores, nops), then latches done + STATUS (pc,
frames_done, illegal, ERR_INFO).

Validation invariants mirror native.cpp (alignment, length multiples,
window checks).  ``scripted_timeout = True`` keeps done deasserted forever.
"""

from __future__ import annotations

import numpy as np

from chisel_npu_py import config as _cfg
from chisel_npu_py.config import NPUConfig, default_config

_OP_NOP = 0x00
_OP_MMA = 0x03
_OP_LD = 0x07
_OP_ST = 0x27

_F3_VX, _F3_VE, _F3_VR = 0, 1, 2


class FakeNative:
    # observed capture phase for consecutive feeds (K=16 engine harness)
    COL_OFF = 6

    def __init__(self, prefix: str = "/dev/xdma0", h2c_ch: int = 0,
                 c2h_ch: int = 0, cfg: NPUConfig | None = None):
        self.prefix = prefix
        self.h2c_ch = h2c_ch
        self.c2h_ch = c2h_ch
        self.cfg = cfg if cfg is not None else default_config()
        self.mem: dict[str, bytearray] = {
            name: bytearray(sec.window) for name, sec in self.cfg.sections.items()
        }
        self.regs: dict[int, int] = {}
        self.scripted_timeout = False
        self._reads_after_kick: int | None = None

    # ── validation (mirrors native.cpp) ─────────────────────────────────────

    @staticmethod
    def _validate_chunk(offset: int, nbytes: int, window: int) -> None:
        if nbytes == 0:
            raise ValueError("zero-length transfer")
        if offset % 4:
            raise ValueError("offset must be 4-byte aligned")
        if nbytes % 4:
            raise ValueError("length must be a multiple of 4 bytes")
        if offset > window or nbytes > window - offset:
            raise ValueError("chunk exceeds the section window")

    @staticmethod
    def _raw_bytes(data) -> tuple[bytes, int]:
        arr = np.ascontiguousarray(data)
        return arr.tobytes(), int(arr.nbytes)

    # ── named sections (data-only surface, like the real native module) ─────

    def section_size(self, name: str) -> int:
        return self.cfg.sections[name].window

    def write_staged(self, name: str, data, offset: int = 0) -> int:
        raw, nbytes = self._raw_bytes(data)
        if name not in self.cfg.sections:
            raise ValueError(f"unknown section '{name}'")
        self._validate_chunk(offset, nbytes, self.cfg.sections[name].window)
        self.mem[name][offset:offset + nbytes] = raw
        return nbytes

    def read_staged(self, name: str, out, offset: int = 0) -> int:
        if not isinstance(out, np.ndarray):
            raise TypeError("out must be a numpy array")
        self._validate_chunk(offset, int(out.nbytes),
                             self.cfg.sections[name].window)
        raw = bytes(self.mem[name][offset:offset + out.nbytes])
        out[...] = np.frombuffer(raw, dtype=out.dtype).reshape(out.shape)
        return int(out.nbytes)

    # ── ctrl register map ───────────────────────────────────────────────────

    def _reg(self, name: str) -> int:
        return self.cfg.ctrl[name]

    def ctrl_read_reg(self, offset: int) -> int:
        if offset == self._reg(_cfg.CTRL) and self._reads_after_kick is not None:
            self._reads_after_kick += 1
            if self.scripted_timeout:
                return self.regs.get(offset, 0) | (1 << _cfg.CTRL_BUSY_BIT)
            if self._reads_after_kick >= 2:
                self.regs[offset] = self.regs.get(offset, 0) | (
                    1 << _cfg.CTRL_DONE_BIT
                )
        return self.regs.get(offset, 0)

    def ctrl_write_reg(self, offset: int, value: int) -> None:
        self.regs[offset] = int(value)
        if offset == self._reg(_cfg.CTRL) and value & (1 << _cfg.CTRL_START_BIT):
            self._reads_after_kick = 0
            if not self.scripted_timeout:
                self._execute_program()

    # ── software model of the engine (mma session / K-burst) ────────────────

    def _execute_program(self) -> None:
        cfg = self.cfg
        k = cfg.K
        code = bytes(self.mem["CODE"])
        n_words = len(code) // 4
        prog_len = self.regs.get(self._reg(_cfg.PROG_LEN), n_words)
        words = [
            int.from_bytes(code[4 * i:4 * i + 4], "little")
            for i in range(min(prog_len, n_words))
        ]

        vx = [[0] * k for _ in range(32)]
        vr = [[0] * k for _ in range(8)]
        pe = [[0] * k for _ in range(k)]   # session PE matrix
        pending = []                       # (vd, col, C) captures, applied
                                           # before the first store (the
                                           # hardware captures at the clct,
                                           # after the session's feeds)
        feed_idx = 0
        frames = 0

        def s8(v: int) -> int:
            v &= 0xFF
            return v - 256 if v >= 128 else v

        def apply_captures() -> None:
            for vd, col, c in pending:
                for i in range(k):
                    vr[vd][i] = pe[i][col] + c[i]
            pending.clear()

        for pc, w in enumerate(words):
            op = w & 0x7F
            rd = (w >> 7) & 0x1F
            f3 = (w >> 12) & 0x7
            rs1 = (w >> 15) & 0x1F
            rs2 = (w >> 20) & 0x1F
            rs3 = (w >> 27) & 0x1F
            off = (w >> 20) & 0xFFF
            sect = rs1 & 3
            sec_name = ["A", "B", "ACCUM", "OUT"][sect]

            if op == _OP_LD:
                raw = self.mem[sec_name]
                if f3 == _F3_VX:
                    vx[rd] = [s8(raw[off + i]) for i in range(k)]
                elif f3 == _F3_VR:
                    base = off // 4
                    for i in range(k):
                        vr[rd][i] = int.from_bytes(
                            raw[4 * (base + i):4 * (base + i) + 4], "little",
                            signed=True)
            elif op == _OP_ST and f3 == _F3_VX:
                apply_captures()
                raw = self.mem[sec_name]
                for i in range(k):
                    raw[off + i] = int(vx[rd][i]) & 0xFF
            elif op == _OP_ST and f3 == _F3_VR:
                apply_captures()
                raw = self.mem[sec_name]
                for i in range(k):
                    raw[off + 4 * i:off + 4 * i + 4] = \
                        int(vr[rd][i]).to_bytes(4, "little", signed=True)
            elif op == _OP_MMA:
                if f3 > 1:                       # mma.reset / reserved
                    self._halt(pc, w)
                    return
                # feed: accumulate the term into the session PE matrix
                for i in range(k):
                    for j in range(k):
                        pe[i][j] += vx[rs1][i] * s8(vx[rs2][j])
                # capture (deferred): this mma's column (k + COL_OFF) mod K
                col = (feed_idx + self.COL_OFF) % k
                c = [0] * k if rs3 == 0 else list(vr[rs3])
                pending.append((rd, col, c))
                feed_idx += 1
                if f3 == 1:
                    frames += 1
            elif op != _OP_NOP:
                self._halt(pc, w)
                return

        last = len(words) - 1
        self.regs[self._reg(_cfg.STATUS)] = (
            (frames & 0x7FFF) << 16) | (last & 0xFFFF)
        self.regs[self._reg(_cfg.CTRL)] = (
            self.regs.get(self._reg(_cfg.CTRL), 0) | (1 << _cfg.CTRL_DONE_BIT))

    def _halt(self, pc: int, word: int) -> None:
        ctrl_off = self._reg(_cfg.CTRL)
        self.regs[self._reg(_cfg.STATUS)] = (1 << 31) | (pc & 0xFFFF)
        self.regs[self._reg(_cfg.ERR_INFO)] = word
        self.regs[ctrl_off] = self.regs.get(ctrl_off, 0) | (1 << _cfg.CTRL_DONE_BIT)
