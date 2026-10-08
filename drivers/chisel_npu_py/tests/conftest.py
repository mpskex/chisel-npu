"""Shared pytest fixtures for the chisel_npu_py test suite.

Hardware tests (`-m hw`) run natively on the FPGA host and are skipped when
the XDMA device nodes are absent.  Unit tests use FakeNative (a software
model of the engine) and need no hardware.
"""

from __future__ import annotations

import pytest

from chisel_npu_py import ChiselNPU, XDMADevice, config


def _xdma_nodes_present() -> bool:
    return any(n.startswith("/dev/xdma0_") for n in XDMADevice.list_nodes())


@pytest.fixture(scope="session")
def npu() -> ChiselNPU:
    """Session-scoped ChiselNPU; skips the session when no device nodes exist."""
    if not _xdma_nodes_present():
        pytest.skip(
            "XDMA device nodes (/dev/xdma0_*) not present — run on the FPGA "
            "host with the xdma kernel driver loaded"
        )
    npu = ChiselNPU(cfg=config.default_config())
    npu.dev.assert_nodes_present()
    return npu
