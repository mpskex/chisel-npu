"""Hardware tests: staged DDR3 round-trip through the Python driver.

Mirrors the data-integrity intent of the old raw-address loopback tests,
but strictly through the staged-operand API: write a known pattern to an
operand, read it back, compare byte-for-byte.  No DDR addresses appear on
the Python side — the native module owns them.

The staged operands live in MIG C0, so an exact round-trip also proves
the host→DDR3→host path (XDMA DMA + MIG) end-to-end.
"""

from __future__ import annotations

import numpy as np
import pytest

from chisel_npu_py import XDMADevice, consts

OPERAND_BYTES = {
    "A": consts.K,          # int8[K]
    "B": consts.K,          # int8[K]
    "ACCUM": 4 * consts.K,  # int32[K]
    "OUT": 4 * consts.K,    # int32[K]
}


def make_pattern(size: int, seed: int = 0xA5) -> np.ndarray:
    """Deterministic uint8 test pattern of *size* bytes (numpy buffer)."""
    idx = np.arange(size, dtype=np.uint64)
    return ((seed + idx * 0x6B + (idx >> 8) * 0x37) & 0xFF).astype(np.uint8)


def roundtrip(dev: XDMADevice, operand: str, seed: int) -> None:
    size = OPERAND_BYTES[operand]
    assert dev.operand_size(operand) == size
    data = make_pattern(size, seed=seed)
    dev.write_staged(operand, data)
    got = np.empty(size, dtype=np.uint8)
    dev.read_staged(operand, got)
    mismatch = np.nonzero(got != data)[0]
    assert len(mismatch) == 0, (
        f"staged round-trip MISMATCH for operand '{operand}' ({size} B); "
        f"first differing index {mismatch[0] if len(mismatch) else None}"
    )


@pytest.mark.hw
def test_staged_roundtrip_all_operands(xdma_dev: XDMADevice):
    """Every operand round-trips byte-exact (each is a DDR3 region in MIG C0)."""
    for i, operand in enumerate(consts.OPERANDS):
        roundtrip(xdma_dev, operand, seed=0x5A + i)


@pytest.mark.hw
def test_staged_roundtrip_int32_view(xdma_dev: XDMADevice):
    """Typed read-back: OUT round-trips as int32 words (zero-copy path)."""
    words = (np.arange(consts.K, dtype=np.int32) * 0x7F) - 0x1000
    xdma_dev.write_staged("ACCUM", words)
    got = np.empty(consts.K, dtype=np.int32)
    xdma_dev.read_staged("ACCUM", got)
    np.testing.assert_array_equal(got, words)


@pytest.mark.hw
def test_staged_roundtrip_overwrite(xdma_dev: XDMADevice):
    """Writing an operand twice leaves the second pattern (write applies)."""
    first = make_pattern(OPERAND_BYTES["OUT"], seed=0x11)
    second = make_pattern(OPERAND_BYTES["OUT"], seed=0x22)
    xdma_dev.write_staged("OUT", first)
    xdma_dev.write_staged("OUT", second)
    got = np.empty(OPERAND_BYTES["OUT"], dtype=np.uint8)
    xdma_dev.read_staged("OUT", got)
    np.testing.assert_array_equal(got, second)
