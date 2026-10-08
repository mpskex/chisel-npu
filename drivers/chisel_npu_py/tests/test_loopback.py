"""Hardware tests: section round-trips through ChiselNPU.run().

A copy program (vle8 → vse8) moves bytes from one section to another on the
engine, proving the host→DDR3→engine→DDR3→host path end-to-end through the
public run() API.
"""

from __future__ import annotations

import numpy as np
import pytest

from chisel_npu_py import ChiselNPU, config, isa


def make_pattern(size: int, seed: int = 0xA5) -> np.ndarray:
    idx = np.arange(size, dtype=np.uint64)
    return ((seed + idx * 0x6B + (idx >> 8) * 0x37) & 0xFF).astype(np.uint8)


@pytest.mark.hw
def test_copy_program_roundtrip(npu: ChiselNPU):
    cfg = npu.cfg
    k = cfg.K
    data = make_pattern(k, seed=0xA5)
    result = npu.run(
        instructions=[
            isa.vle8(0, isa.SECT_A, 0),
            isa.vse8(0, isa.SECT_OUT, 0),
        ],
        memories={"A": data},
    )
    np.testing.assert_array_equal(result["A"], data)
    np.testing.assert_array_equal(result["OUT"][: (k + 3) // 4].view(np.uint8)[:k], data)


@pytest.mark.hw
def test_vr_roundtrip_through_run(npu: ChiselNPU):
    """vle32 → vse32 through the engine's VR file."""
    cfg = npu.cfg
    k = cfg.K
    data = np.arange(k, dtype=np.int32) * 0x101 + 3
    result = npu.run(
        instructions=[
            isa.vle32(1, isa.SECT_ACCUM, 0),
            isa.vse32(1, isa.SECT_OUT, 0),
        ],
        memories={"ACCUM": data},
    )
    np.testing.assert_array_equal(result["ACCUM"], data)
    np.testing.assert_array_equal(result["OUT"][:k], data)


@pytest.mark.hw
def test_transfer_validation(npu: ChiselNPU):
    with pytest.raises(ValueError):
        npu.run([isa.NOP], memories={"A": np.zeros(3, dtype=np.uint8)})  # bad size
