"""
test_bar_ctrl_lite.py — Verify ctrl_lite BAR register access.

The ctrl_lite BAR is accessible via /dev/xdma0_user (NPU bitstream, preferred)
or /dev/xdma0_bypass (reference XDMA test design).  The `bar` fixture in
conftest.py probes both candidates and skips the test session if neither is
accessible — which is expected when the reference design (no BYPASS BAR) is in
flash.

Tests here verify basic BAR accessibility (not-all-ones) which confirms:
  1. PCIe BAR is mapped by the kernel
  2. XDMA device is functioning
  3. AXI fabric is alive
"""

import pytest
from .lib.reg_rw import BARDevice

# 0xFFFFFFFF is the PCIe completion-error sentinel (unmapped BAR)
_FFFF = 0xFFFF_FFFF


@pytest.mark.hw
def test_bypass_bar_readable(bar: BARDevice) -> None:
    """ctrl_lite BAR offset 0x0 must be readable without PCIe error."""
    val = bar.read(0x0)
    assert val != _FFFF, (
        f"BAR offset 0x0 returned 0x{val:08X} (PCIe completion error). "
        "Check that the FPGA is configured and the AXI fabric is running."
    )


@pytest.mark.hw
def test_bypass_bar_device_exists(bar: BARDevice) -> None:
    """The ctrl_lite BAR device node must exist (fixture already verified this)."""
    # If we reach here the bar fixture succeeded — device is confirmed present.
    assert bar.device is not None, "BARDevice has no device path"


@pytest.mark.hw
def test_ctrl_reg_not_all_ones(bar: BARDevice) -> None:
    """BAR read at offset 0x0 must not return 0xFFFFFFFF (PCIe error sentinel).
    With NPU bitstream: ctrl_lite start/done/busy register.
    With reference XDMA bitstream: MicroBlaze peripheral space."""
    val = bar.read(0x0)
    assert val != _FFFF, (
        f"BAR read returned 0x{val:08X} (all-ones = PCIe timeout or unmapped BAR). "
        "Check that the FPGA AXI fabric is running."
    )
