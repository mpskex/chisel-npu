"""Hardware tests: DDR3 C0 read/write loopback through the Python driver.

Mirrors tool/hw/tests/test_ddr3_c0_loopback.py, but drives the device via
the chisel_npu_py driver (numpy buffers through the pybind11 boundary).
"""

from __future__ import annotations

import numpy as np
import pytest

from chisel_npu_py import XDMADevice


def make_pattern(size: int, seed: int = 0xA5) -> np.ndarray:
    """Deterministic uint8 test pattern of *size* bytes (numpy buffer)."""
    idx = np.arange(size, dtype=np.uint64)
    return ((seed + idx * 0x6B + (idx >> 8) * 0x37) & 0xFF).astype(np.uint8)


def loopback(dev: XDMADevice, offset: int, size: int, seed: int) -> None:
    data = make_pattern(size, seed=seed)
    dev.dma_write(offset, data)
    readback = dev.dma_read(offset, size)
    assert len(readback) == size
    mismatch = np.nonzero(readback != data)[0]
    assert len(mismatch) == 0, (
        f"DDR3 C0 loopback MISMATCH at offset 0x{offset:X}, {size} bytes; "
        f"first differing index {mismatch[0] if len(mismatch) else None}"
    )


@pytest.mark.hw
def test_ddr3_c0_loopback_1kb(xdma_dev: XDMADevice):
    loopback(xdma_dev, 0x0, 1024, seed=0x5A)


@pytest.mark.hw
def test_ddr3_c0_loopback_1mb(xdma_dev: XDMADevice):
    loopback(xdma_dev, 0x0, 1024 * 1024, seed=0x3C)


@pytest.mark.hw
def test_ddr3_c0_loopback_high_addr(xdma_dev: XDMADevice):
    """64 KB loopback at offset 64 MB — checks address routing in the xbar."""
    loopback(xdma_dev, 64 * 1024 * 1024, 64 * 1024, seed=0x9E)


@pytest.mark.hw
def test_ddr3_c0_read_into_numpy_array(xdma_dev: XDMADevice):
    """Read-back into a caller-provided int32 array (typed, zero-copy path)."""
    words = np.arange(64, dtype=np.int32)
    xdma_dev.write_i32(0x0, words)
    got = xdma_dev.read_i32(0x0, 64)
    np.testing.assert_array_equal(got, words)
