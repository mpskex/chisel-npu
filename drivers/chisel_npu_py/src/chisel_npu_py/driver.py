"""Board-agnostic NPUCTL driver core.

`NpuDriverCore` knows only the NPUCTL register map and a transport.  It has
no PCIe/XDMA/BAR/DDR-size knowledge; the platform supplies a transport and
system addresses.  The same core runs on every board and in simulation.
"""

from __future__ import annotations

import time
from dataclasses import dataclass
from typing import Mapping, Optional

from . import protocol as p
from .transport import NpuTransport


class NpuDriverError(RuntimeError):
    pass


class NpuTimeout(NpuDriverError):
    pass


@dataclass(frozen=True)
class NpuErrorInfo:
    code: int
    pc: int
    info: int
    addr: int

    @property
    def name(self) -> str:
        return {
            p.ERROR_NONE: "NONE",
            p.ERROR_ILLEGAL: "ILLEGAL",
            p.ERROR_MEM_FAULT: "MEM_FAULT",
            p.ERROR_WATCHDOG: "WATCHDOG",
            p.ERROR_BUS_ERROR: "BUS_ERROR",
            p.ERROR_BAD_CONFIG: "BAD_CONFIG",
            p.ERROR_ABORTED: "ABORTED",
        }.get(self.code, f"UNKNOWN({self.code})")


@dataclass(frozen=True)
class NpuPerf:
    cycles: int
    macs: int
    fetch_stats: int


class NpuDriverCore:
    def __init__(self, transport: NpuTransport, timeout_s: float = 10.0,
                 poll_interval_s: float = 0.001):
        self.transport = transport
        self.timeout_s = timeout_s
        self.poll_interval_s = poll_interval_s
        self.info: Optional[p.DeviceInfo] = None

    # ---- discovery -------------------------------------------------------

    def open(self) -> p.DeviceInfo:
        ident = self._reg(p.ID)
        if ident != p.ID_MAGIC:
            raise NpuDriverError(
                f"bad NPUCTL ID 0x{ident:08x}, expected 0x{p.ID_MAGIC:08x}")
        version = self._reg(p.VERSION)
        caps = self._reg(p.CAPS)
        config = self._reg(p.CONFIG)
        info = p.make_device_info(version, caps, config)
        if info.protocol_major != 1:
            raise NpuDriverError(
                f"unsupported NPUCTL major {info.protocol_major}")
        if info.K <= 0 or info.N <= 0 or info.L <= 0 or info.W <= 0:
            raise NpuDriverError(f"invalid CONFIG 0x{config:08x}")
        self.info = info
        return info

    # ---- registers -------------------------------------------------------

    def _reg(self, offset: int) -> int:
        return int(self.transport.reg_read(offset)) & 0xFFFFFFFF

    def _wreg(self, offset: int, value: int) -> None:
        self.transport.reg_write(offset, int(value) & 0xFFFFFFFF)

    def read_reg(self, offset: int) -> int:
        return self._reg(offset)

    def write_reg(self, offset: int, value: int) -> None:
        self._wreg(offset, value)

    # ---- segment table ---------------------------------------------------

    def program_segment(self, seg: int, base: int, limit: int = 0) -> None:
        if self.info is None:
            raise NpuDriverError("open() before programming segments")
        if not (0 <= seg < self.info.num_seg):
            raise ValueError(f"segment {seg} outside 0..{self.info.num_seg - 1}")
        if base < 0 or base >= (1 << 64):
            raise ValueError("segment base must fit in 64 bits")
        if base & 0x3F:
            raise ValueError("segment base must be 64-byte aligned")
        if limit < 0 or limit >= (1 << 32):
            raise ValueError("segment limit must fit in 32 bits")
        if limit & 0x3F:
            raise ValueError("segment limit must be a multiple of 64")
        self._wreg(p.seg_base_lo(seg), base & 0xFFFFFFFF)
        self._wreg(p.seg_base_hi(seg), (base >> 32) & 0xFFFFFFFF)
        self._wreg(p.seg_limit(seg), limit)

    def program_segments(self, segments: Mapping[int, tuple[int, int]]) -> None:
        for seg, (base, limit) in segments.items():
            self.program_segment(seg, base, limit)

    # ---- session control -------------------------------------------------

    def start(self, prog_len: int, entry_pc: int = 0, session_id: int = 0,
              watchdog: int = 0, irq_enable: int = 0xF) -> None:
        st = self.status()
        if st & (1 << p.STATUS_BUSY):
            raise NpuDriverError(
                f"NPU is busy (STATUS=0x{st:08x}); wait for DONE/ERROR or stop it")
        if prog_len <= 0:
            raise ValueError("prog_len must be positive")
        if entry_pc < 0 or entry_pc + prog_len > 0xFFFF:
            raise ValueError("entry_pc + prog_len exceeds the 16-bit PC space")
        self._wreg(p.IRQ_ENABLE, irq_enable)
        self._wreg(p.WATCHDOG, watchdog)
        self._wreg(p.SESSION_ID, session_id)
        self._wreg(p.PROG_LEN, prog_len)
        self._wreg(p.ENTRY_PC, entry_pc)
        self._wreg(p.CONTROL, 1 << p.CTRL_START)

    def stop(self) -> None:
        self._wreg(p.CONTROL, 1 << p.CTRL_STOP)

    def soft_reset(self) -> None:
        self._wreg(p.CONTROL, 1 << p.CTRL_SOFT_RESET)

    def status(self) -> int:
        return self._reg(p.STATUS)

    def status_flags(self) -> dict[str, bool]:
        st = self.status()
        state = (st >> 4) & 0xF
        return {
            "busy": bool(st & (1 << p.STATUS_BUSY)),
            "done": bool(st & (1 << p.STATUS_DONE)),
            "error": bool(st & (1 << p.STATUS_ERROR)),
            "aborted": bool(st & (1 << p.STATUS_ABORTED)),
            "idle": state == 0,
            "state": state,
            "irq": bool((st >> 31) & 1),
        }

    def wait_done(self, timeout_s: Optional[float] = None) -> int:
        timeout = self.timeout_s if timeout_s is None else timeout_s
        deadline = time.monotonic() + timeout
        while True:
            st = self.status()
            # Error/abort wins over a stale DONE from a previous session.
            if st & ((1 << p.STATUS_ERROR) | (1 << p.STATUS_ABORTED)):
                return st
            if st & (1 << p.STATUS_DONE):
                return st
            if time.monotonic() >= deadline:
                raise NpuTimeout(
                    f"NPU did not finish within {timeout} s (STATUS=0x{st:08x})")
            wait_irq = getattr(self.transport, "wait_irq", None)
            if wait_irq is not None:
                try:
                    if wait_irq(min(self.poll_interval_s * 50,
                                    max(0.0, deadline - time.monotonic()))):
                        continue
                except Exception:
                    pass
            time.sleep(self.poll_interval_s)

    def error_info(self) -> NpuErrorInfo:
        return NpuErrorInfo(
            code=self._reg(p.ERROR_CODE),
            pc=self._reg(p.ERROR_PC),
            info=self._reg(p.ERROR_INFO),
            addr=self._reg(p.ERROR_ADDR),
        )

    def perf(self) -> NpuPerf:
        return NpuPerf(
            cycles=self._reg(p.CYCLES),
            macs=self._reg(p.MACS),
            fetch_stats=self._reg(p.FETCH_STATS),
        )

    # ---- high-level job ---------------------------------------------------

    def run(self, prog_len: int,
            segments: Mapping[int, tuple[int, int, bytes]],
            entry_pc: int = 0, session_id: int = 0, watchdog: int = 0,
            irq_enable: int = 0xF, readback: bool = True) -> dict[int, bytes]:
        """Stage *segments* (seg -> (system_base, limit, bytes)), run, and read back.

        The platform owns the system bases; this core only programs them and
        moves the bytes.  Raises NpuDriverError on an illegal/mem/bus error.
        """
        for seg, (base, limit, data) in segments.items():
            if data:
                self.mem_write(base, data)
            self.program_segment(seg, base, limit)
        self.start(prog_len, entry_pc, session_id, watchdog, irq_enable)
        st = self.wait_done()
        err = self.error_info()
        if (st & ((1 << p.STATUS_ERROR) | (1 << p.STATUS_ABORTED))) or \
                err.code != p.ERROR_NONE:
            raise NpuDriverError(
                f"NPU job failed: STATUS=0x{st:08x} {err.name} "
                f"pc={err.pc} addr=0x{err.addr:08x}")
        if not readback:
            return {}
        return {
            seg: self.mem_read(base, len(data))
            for seg, (base, _limit, data) in segments.items()
        }

    # ---- data plane ------------------------------------------------------

    def mem_read(self, addr: int, nbytes: int) -> bytes:
        return self.transport.mem_read(addr, nbytes)

    def mem_write(self, addr: int, data: bytes) -> None:
        self.transport.mem_write(addr, data)
