"""
test_ddr3_c0_loopback.py — DDR3 C0 read/write loopback via XDMA DMA channel 0.

Tests:
  1. Write 1 KB of known pattern to DDR3 C0 offset 0x0, read back, compare.
  2. Write 1 MB of incrementing data, read back, compare.
  3. Write to a high DDR3 offset (64 MB) to check address mapping.

DDR3 C0 is connected to the XDMA M_AXI path (host DMA bank).
Address space: 0x0 → 0x1_FFFF_FFFF (8 GB, MIG 31-bit AXI address).
"""

import struct
import pytest
from .lib.xdma import XDMADevice


def make_pattern(size: int, seed: int = 0xA5) -> bytes:
    """Generate a deterministic test pattern of *size* bytes."""
    out = bytearray(size)
    for i in range(size):
        out[i] = (seed + i * 0x6B + (i >> 8) * 0x37) & 0xFF
    return bytes(out)


@pytest.mark.hw
def test_ddr3_c0_loopback_1kb(xdma_dev: XDMADevice) -> None:
    """1 KB write → read loopback at DDR3 C0 offset 0."""
    size = 1024
    data = make_pattern(size, seed=0x5A)
    xdma_dev.h2c(channel=0, ddr_offset=0x0, data=data)
    readback = xdma_dev.c2h(channel=0, ddr_offset=0x0, length=size)
    assert readback == data, (
        f"DDR3 C0 loopback MISMATCH at offset 0x0, {size} bytes.\n"
        f"First differing byte at index {next(i for i,(a,b) in enumerate(zip(data,readback)) if a!=b)}"
    )


@pytest.mark.hw
def test_ddr3_c0_loopback_1mb(xdma_dev: XDMADevice) -> None:
    """1 MB write → read loopback at DDR3 C0 offset 0."""
    size = 1024 * 1024
    data = make_pattern(size, seed=0x3C)
    xdma_dev.h2c(channel=0, ddr_offset=0x0, data=data)
    readback = xdma_dev.c2h(channel=0, ddr_offset=0x0, length=size)
    assert readback == data, (
        f"DDR3 C0 1 MB loopback MISMATCH at offset 0x0"
    )


@pytest.mark.hw
def test_ddr3_c0_loopback_high_addr(xdma_dev: XDMADevice) -> None:
    """64 KB loopback at DDR3 C0 offset 64 MB (checks address routing)."""
    size = 64 * 1024
    offset = 64 * 1024 * 1024  # 0x400_0000
    data = make_pattern(size, seed=0x9E)
    xdma_dev.h2c(channel=0, ddr_offset=offset, data=data)
    readback = xdma_dev.c2h(channel=0, ddr_offset=offset, length=size)
    assert readback == data, (
        f"DDR3 C0 loopback MISMATCH at offset {hex(offset)}, {size} bytes"
    )
