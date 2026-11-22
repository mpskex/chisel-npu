"""Unit tests for the board-agnostic NPUCTL driver core."""

from __future__ import annotations

import pytest

from chisel_npu_py import protocol as p
from chisel_npu_py.driver import NpuDriverCore, NpuDriverError, NpuTimeout


class FakeTransport:
    def __init__(self):
        self.regs = {
            p.ID: p.ID_MAGIC,
            p.VERSION: 0x01000000,
            p.CAPS: (6 << 28) | (8 << 16) | (1 << 12) | (5 << 8) | 0xF,
            p.CONFIG: 32 | (8 << 8) | (32 << 16) | (8 << 24),
            p.STATUS: 0,
        }
        self.mem = bytearray(4096)

    def reg_read(self, offset: int) -> int:
        return self.regs.get(offset, 0)

    def reg_write(self, offset: int, value: int) -> None:
        self.regs[offset] = value & 0xFFFFFFFF

    def mem_read(self, addr: int, nbytes: int) -> bytes:
        return bytes(self.mem[addr:addr + nbytes])

    def mem_write(self, addr: int, data: bytes) -> None:
        self.mem[addr:addr + len(data)] = data

    def wait_irq(self, timeout_s: float) -> bool:
        return False


def test_open_decodes_protocol_and_config():
    drv = NpuDriverCore(FakeTransport())
    info = drv.open()
    assert info.protocol_major == 1
    assert (info.K, info.N, info.L, info.W) == (32, 8, 32, 8)
    assert info.num_seg == 5
    assert info.trace_depth_log2 == 8
    assert info.axi_data_w == 6


def test_bad_id_is_rejected():
    t = FakeTransport()
    t.regs[p.ID] = 0xDEADBEEF
    with pytest.raises(NpuDriverError):
        NpuDriverCore(t).open()


def test_program_segment_writes_base_and_limit():
    t = FakeTransport()
    drv = NpuDriverCore(t)
    drv.open()
    drv.program_segment(p.SEG_B, base=0x1_0000_0000, limit=0x1000)
    assert t.regs[p.seg_base_lo(p.SEG_B)] == 0
    assert t.regs[p.seg_base_hi(p.SEG_B)] == 1
    assert t.regs[p.seg_limit(p.SEG_B)] == 0x1000


def test_misaligned_segment_is_rejected():
    t = FakeTransport()
    drv = NpuDriverCore(t)
    drv.open()
    with pytest.raises(ValueError):
        drv.program_segment(p.SEG_A, base=0x1001)
    with pytest.raises(ValueError):
        drv.program_segment(p.SEG_A, base=0, limit=0x1001)


def test_start_programs_the_session_registers():
    t = FakeTransport()
    drv = NpuDriverCore(t)
    drv.open()
    drv.start(prog_len=5, entry_pc=2, session_id=7, watchdog=9)
    assert t.regs[p.PROG_LEN] == 5
    assert t.regs[p.ENTRY_PC] == 2
    assert t.regs[p.SESSION_ID] == 7
    assert t.regs[p.WATCHDOG] == 9
    assert t.regs[p.IRQ_ENABLE] == 0xF
    assert t.regs[p.CONTROL] == (1 << p.CTRL_START)


def test_wait_done_and_error_decode():
    t = FakeTransport()
    drv = NpuDriverCore(t)
    drv.open()
    t.regs[p.STATUS] = (1 << p.STATUS_DONE) | (2 << 4)
    assert drv.wait_done(timeout_s=0.1) & (1 << p.STATUS_DONE)
    assert drv.status_flags()["done"]

    t.regs[p.STATUS] = (1 << p.STATUS_ERROR) | (3 << 4)
    t.regs[p.ERROR_CODE] = p.ERROR_MEM_FAULT
    t.regs[p.ERROR_PC] = 11
    t.regs[p.ERROR_ADDR] = 0x1234
    err = drv.error_info()
    assert err.name == "MEM_FAULT"
    assert err.pc == 11
    assert err.addr == 0x1234


def test_wait_done_times_out():
    t = FakeTransport()
    drv = NpuDriverCore(t, poll_interval_s=0.001)
    drv.open()
    with pytest.raises(NpuTimeout):
        drv.wait_done(timeout_s=0.01)


def test_mem_read_write_are_proxied():
    t = FakeTransport()
    drv = NpuDriverCore(t)
    drv.open()
    drv.mem_write(0x10, b"abcd")
    assert drv.mem_read(0x10, 4) == b"abcd"
