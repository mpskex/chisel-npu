"""Unit tests for the constant tables (no hardware required)."""

from __future__ import annotations

import numpy as np
import pytest

from chisel_npu_py import XDMADevice, consts


def test_staging_sizes_are_4byte_multiple():
    for name, (base, size) in consts.STAGING.items():
        assert size % 4 == 0, f"staging operand {name} size {size} not %4==0"


def test_staging_sizes_match_k():
    assert consts.STAGING["A"][1] == consts.K            # int8[K]
    assert consts.STAGING["B"][1] == consts.K
    assert consts.STAGING["ACCUM"][1] == consts.K * 4    # int32[K]
    assert consts.STAGING["OUT"][1] == consts.K * 4


def test_staging_within_ddr_window():
    for name, (base, size) in consts.STAGING.items():
        assert base >= consts.DDR_BASE
        assert base + size <= consts.DDR_BASE + consts.DDR_SIZE


def test_ctrl_bit_positions_are_distinct():
    bits = {consts.CTRL_START_BIT, consts.CTRL_DONE_BIT, consts.CTRL_BUSY_BIT}
    assert bits == {0, 1, 2}


def test_staging_matches_native_when_built():
    """If the native module is available, Python consts must equal C++ truth."""
    try:
        dev = XDMADevice()
    except Exception as exc:  # not built / no device: nothing to compare
        pytest.skip(f"native module unavailable: {exc}")
    native = {k: tuple(int(v) for v in tup) for k, tup in dev.staging_map().items()}
    assert native == consts.STAGING


def test_numpy_types_used_by_mmalu():
    a = np.full(consts.K, 10, dtype=np.int8)
    b = np.full(consts.K, 7, dtype=np.int8)
    acc = np.zeros(consts.K, dtype=np.int32)
    assert a.nbytes == consts.STAGING["A"][1]
    assert b.nbytes == consts.STAGING["B"][1]
    assert acc.nbytes == consts.STAGING["ACCUM"][1]
