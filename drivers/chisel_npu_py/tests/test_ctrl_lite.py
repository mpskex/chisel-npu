"""Hardware tests: ctrl_lite BAR register access and NPU kick/done handshake.

Runs natively on the FPGA host (auto-skipped when /dev/xdma0_* is absent).
0xFFFFFFFF is the PCIe completion-error sentinel (unmapped BAR).
"""

from __future__ import annotations

import pytest

from chisel_npu_py import CtrlLite, XDMADevice

_FFFF = 0xFFFF_FFFF


@pytest.mark.hw
def test_bypass_bar_readable(xdma_dev: XDMADevice):
    """ctrl_lite BAR offset 0x0 must be readable without PCIe error."""
    val = xdma_dev.reg_read(0x0)
    assert val != _FFFF, (
        f"BAR offset 0x0 returned 0x{val:08X} (PCIe completion error). "
        "Check that the FPGA is configured and the AXI fabric is running."
    )


@pytest.mark.hw
def test_ctrl_reg_not_all_ones(xdma_dev: XDMADevice):
    """BAR read at offset 0x0 must not return 0xFFFFFFFF (PCIe error sentinel)."""
    val = xdma_dev.reg_read(0x0)
    assert val != _FFFF, (
        f"BAR read returned 0x{val:08X} (all-ones = PCIe timeout or unmapped BAR)"
    )


@pytest.mark.hw
def test_npu_start_done(ctrl: CtrlLite):
    """Kick the FSM and observe done=1 within 1 s (the integration canary)."""
    assert not ctrl.is_busy, "NPU FSM unexpectedly busy before test"
    ctrl.kick()
    assert ctrl.wait_done(timeout_s=1), (
        "NPU FSM did not assert done=1 within 1 s. "
        "Possible causes: MMALU not wired, MIG C0 stuck, or DMA master FSM hung."
    )


@pytest.mark.hw
def test_done_latch_persists_until_next_start(ctrl: CtrlLite):
    """After a kick, the done latch stays set (bit 1) until the next start."""
    if ctrl.is_busy:
        ctrl.wait_done(timeout_s=2)
    ctrl.kick()
    assert ctrl.wait_done(timeout_s=2)
    assert (ctrl.read() >> 1) & 1, "done latch not set after completed cycle"
    assert not ctrl.is_busy, "busy still asserted after done"
