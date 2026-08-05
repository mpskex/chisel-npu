"""Shared pytest fixtures for the chisel_npu_py test suite.

Hardware tests (`-m hw`) run natively on the FPGA host and are skipped
automatically when the XDMA device nodes are absent (e.g. on a dev host).
Unit/mock tests (the default) need no hardware at all.
"""

from __future__ import annotations

import pytest

from chisel_npu_py import ChiselNPU, CtrlLite, XDMADevice


def _xdma_nodes_present() -> bool:
    return any(n.startswith("/dev/xdma0_") for n in XDMADevice.list_nodes())


@pytest.fixture(scope="session")
def xdma_dev() -> XDMADevice:
    """Session-scoped XDMADevice. Skips the session when no device nodes exist."""
    if not _xdma_nodes_present():
        pytest.skip(
            "XDMA device nodes (/dev/xdma0_*) not present — run on the FPGA "
            "host with the xdma kernel driver loaded"
        )
    dev = XDMADevice()
    dev.assert_nodes_present()
    return dev


@pytest.fixture(scope="session")
def ctrl(xdma_dev: XDMADevice) -> CtrlLite:
    return CtrlLite(xdma_dev)


@pytest.fixture(scope="session")
def npu(xdma_dev: XDMADevice, ctrl: CtrlLite) -> ChiselNPU:
    return ChiselNPU(dev=xdma_dev, ctrl=ctrl)
