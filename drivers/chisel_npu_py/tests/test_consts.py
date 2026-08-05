"""Unit tests for the constant tables (no hardware required)."""

from __future__ import annotations

import numpy as np
import pytest

from chisel_npu_py import XDMADevice, consts


def test_operand_set_is_expected():
    assert consts.OPERANDS == ("A", "B", "ACCUM", "OUT")


def test_operand_sizes_derive_from_k():
    """Buffer sizes are data-side facts: A/B = K int8, ACCUM/OUT = K int32."""
    assert (consts.K, 4 * consts.K) == (32, 128)


def test_ctrl_bit_positions_are_distinct():
    bits = {consts.CTRL_START_BIT, consts.CTRL_DONE_BIT, consts.CTRL_BUSY_BIT}
    assert bits == {0, 1, 2}


def test_no_addresses_in_consts():
    """The Python layer must not carry DDR addresses or BAR offsets."""
    for name, value in vars(consts).items():
        if name.isupper() and isinstance(value, int):
            assert value <= 0xFF, (
                f"{name} = 0x{value:X} looks like a device address — "
                "addresses belong in native.cpp only"
            )


def test_operand_sizes_match_native_when_built():
    """If the native module is available, sizes must match the C++ staging table."""
    try:
        dev = XDMADevice()
    except Exception as exc:  # not built / no device: nothing to compare
        pytest.skip(f"native module unavailable: {exc}")
    expected = {"A": consts.K, "B": consts.K,
                "ACCUM": 4 * consts.K, "OUT": 4 * consts.K}
    for name, size in expected.items():
        assert dev.operand_size(name) == size


def test_numpy_types_used_by_mmalu():
    a = np.full(consts.K, 10, dtype=np.int8)
    b = np.full(consts.K, 7, dtype=np.int8)
    acc = np.zeros(consts.K, dtype=np.int32)
    assert a.nbytes == consts.K
    assert b.nbytes == consts.K
    assert acc.nbytes == 4 * consts.K
