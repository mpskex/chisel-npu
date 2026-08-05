"""Unit tests for CtrlLite bit protocol and wait_done logic (FakeNative)."""

from __future__ import annotations

import numpy as np
import pytest

from chisel_npu_py import CtrlLite, XDMADevice, consts

from .fake_native import FakeNative


@pytest.fixture
def fake_dev() -> XDMADevice:
    return XDMADevice(native=FakeNative())


def test_idle_read_is_zero(fake_dev: XDMADevice):
    ctrl = CtrlLite(fake_dev)
    assert ctrl.read() == 0x0


def test_bit_parsing_idle(fake_dev: XDMADevice):
    ctrl = CtrlLite(fake_dev)
    assert not ctrl.is_done
    assert not ctrl.is_busy


def test_bit_parsing_done_latch(fake_dev: XDMADevice):
    fake_dev.native.regs[consts.CTRL_REG] = 1 << consts.CTRL_DONE_BIT
    ctrl = CtrlLite(fake_dev)
    assert ctrl.is_done
    assert not ctrl.is_busy


def test_bit_parsing_busy(fake_dev: XDMADevice):
    fake_dev.native.regs[consts.CTRL_REG] = 1 << consts.CTRL_BUSY_BIT
    ctrl = CtrlLite(fake_dev)
    assert not ctrl.is_done
    assert ctrl.is_busy


def test_bit_parsing_both(fake_dev: XDMADevice):
    fake_dev.native.regs[consts.CTRL_REG] = (1 << consts.CTRL_DONE_BIT) | (
        1 << consts.CTRL_BUSY_BIT
    )
    ctrl = CtrlLite(fake_dev)
    assert ctrl.is_done and ctrl.is_busy


def test_kick_writes_start_bit(fake_dev: XDMADevice):
    ctrl = CtrlLite(fake_dev)
    ctrl.kick()
    assert fake_dev.native.regs[consts.CTRL_REG] & (1 << consts.CTRL_START_BIT)


def test_wait_done_returns_true_after_kick(fake_dev: XDMADevice):
    ctrl = CtrlLite(fake_dev)
    ctrl.kick()
    assert ctrl.wait_done(timeout_s=0.5) is True


def test_wait_done_times_out_when_busy_forever(fake_dev: XDMADevice):
    fake_dev.native.scripted_timeout = True
    ctrl = CtrlLite(fake_dev)
    ctrl.kick()
    import time

    t0 = time.monotonic()
    assert ctrl.wait_done(timeout_s=0.1) is False
    assert time.monotonic() - t0 < 1.0


def test_done_reported_without_prior_kick_if_latched(fake_dev: XDMADevice):
    """A latched done bit (e.g. from a previous run) is observed directly."""
    fake_dev.native.regs[consts.CTRL_REG] = 1 << consts.CTRL_DONE_BIT
    ctrl = CtrlLite(fake_dev)
    assert ctrl.wait_done(timeout_s=0.1) is True


def test_register_value_roundtrip(fake_dev: XDMADevice):
    ctrl = CtrlLite(fake_dev)
    ctrl.write(0x2A)
    assert ctrl.read() == 0x2A
