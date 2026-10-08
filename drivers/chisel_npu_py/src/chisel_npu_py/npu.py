"""ChiselNPU — the whole driver: run(instructions, memories).

Stages the named-section memories, stages the instruction words into CODE,
starts the engine, waits for done, and returns the full memories dict read
back (including OUT).  All XDMA/ctrl handling is internal to `run()`.
"""

from __future__ import annotations

import time
from typing import Mapping, Optional, Union

import numpy as np

from . import isa
from .backend import Buffer, XDMADevice
from .config import (
    CTRL,
    CTRL_DONE_BIT,
    CTRL_START_BIT,
    ERR_INFO,
    FETCH_STATS,
    PROG_LEN,
    STATUS,
    NPUConfig,
    default_config,
)
from .errors import NPUError, NPUTimeoutError, NPUProgramError
from .isa import Program


def _words_bytes(words) -> bytes:
    return Program(words).to_bytes()


def _readback(buf: Buffer):
    """Read a section back with the same type as the input buffer."""
    if isinstance(buf, np.ndarray):
        out = np.empty_like(buf)
        return out
    nbytes = _buffer_nbytes(buf)
    return bytearray(nbytes)


def _buffer_nbytes(buf: Buffer) -> int:
    if isinstance(buf, np.ndarray):
        return int(buf.nbytes)
    return len(buf)


class ChiselNPU:
    def __init__(self, cfg: Optional[NPUConfig] = None, timeout_s: float = 10.0,
                 _dev: Optional[XDMADevice] = None):
        self.cfg = cfg if cfg is not None else default_config()
        self.cfg.validate()
        self.dev = _dev if _dev is not None else XDMADevice(cfg=self.cfg)
        self.timeout_s = timeout_s

    # ── the whole driver ────────────────────────────────────────────────────

    def run(self, instructions, memories: Optional[Mapping[str, Buffer]] = None,
            timeout_s: Optional[float] = None) -> dict:
        """Stage *memories* (section name → buffer), run *instructions* (word
        list), and return every section read back — including OUT.

        Raises :class:`NPUProgramError` on an illegal instruction (carrying
        pc + err_info), :class:`NPUTimeoutError` if the engine hangs.
        """
        words = list(instructions)
        if not words:
            raise NPUError("run: empty instruction sequence")
        cfg = self.cfg
        dev = self.dev
        memories = dict(memories or {})

        # stage the named sections (native validates windows/alignment)
        for name, buf in memories.items():
            dev.write_staged(name, buf)
        dev.write_staged("CODE", _words_bytes(words))

        # Zero-clear the OUT window before the kick.  The engine only writes
        # the produced columns; a read-back of the untouched tail would hit
        # never-written DDR, whose reads are unreliable on this silicon
        # (c2h transfer stalls).  Clearing first makes every OUT byte a
        # "written" byte so the full-window read-back is safe, and also
        # prevents stale data from masking a mismatch.
        dev.write_staged("OUT", np.zeros(
            cfg.sections["OUT"].window // 4, dtype=np.int32))

        # fire: PROG_LEN → start (edge-triggered: write 1 then 0)
        dev.ctrl_write_reg(cfg.ctrl[PROG_LEN], len(words))
        dev.ctrl_write_reg(cfg.ctrl[CTRL], 1 << CTRL_START_BIT)
        dev.ctrl_write_reg(cfg.ctrl[CTRL], 0)

        t = timeout_s if timeout_s is not None else self.timeout_s
        if not self._wait_done(t):
            raise NPUTimeoutError(
                f"engine did not assert done within {t} s (stuck fetch/DMA?)")

        status = dev.ctrl_read_reg(cfg.ctrl[STATUS])
        if (status >> 31) & 1:
            pc = status & 0xFFFF
            err = dev.ctrl_read_reg(cfg.ctrl[ERR_INFO])
            raise NPUProgramError(
                f"illegal instruction at pc={pc} (word=0x{err:08x})",
                pc=pc, err_info=err)
        if (status & 0xFFFF) != len(words) - 1:
            raise NPUError(
                f"engine finished at pc={status & 0xFFFF}, expected {len(words) - 1}")

        # read everything back (same type as the inputs) + OUT
        result: dict = {}
        for name, buf in memories.items():
            result[name] = self._read_section(name, buf)
        result["OUT"] = self._read_section(
            "OUT", np.empty(cfg.sections["OUT"].window // 4, dtype=np.int32))
        return result

    # ── internals ───────────────────────────────────────────────────────────

    def _wait_done(self, timeout_s: float) -> bool:
        cfg = self.cfg
        deadline = time.monotonic() + timeout_s
        while time.monotonic() < deadline:
            if (self.dev.ctrl_read_reg(cfg.ctrl[CTRL]) >> CTRL_DONE_BIT) & 1:
                return True
            time.sleep(0.005)
        return False

    def _read_section(self, name: str, buf: Buffer) -> Buffer:
        if isinstance(buf, np.ndarray):
            out = np.empty_like(buf)
            self.dev.read_staged(name, out)
            return out
        nbytes = len(buf)
        out = bytearray(nbytes)
        self.dev.read_staged(name, out)
        return bytes(out)
