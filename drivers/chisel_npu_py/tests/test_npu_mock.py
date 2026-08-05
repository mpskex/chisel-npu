"""Unit tests for the ChiselNPU orchestration and validation (FakeNative)."""

from __future__ import annotations

import numpy as np
import pytest

from chisel_npu_py import ChiselNPU, NPUError, NPUTimeoutError, XDMADevice, consts

from .fake_native import FakeNative


@pytest.fixture
def npu() -> ChiselNPU:
    return ChiselNPU(dev=XDMADevice(native=FakeNative()))


def _operands():
    A = np.full(consts.K, 10, dtype=np.int8)
    B = np.full(consts.K, 7, dtype=np.int8)
    ACCUM = np.zeros(consts.K, dtype=np.int32)
    return A, B, ACCUM


def test_mmalu_full_flow(npu: ChiselNPU):
    A, B, ACCUM = _operands()
    out = npu.mmalu(A, B, ACCUM, timeout_s=0.5)
    assert isinstance(out, np.ndarray)
    assert out.dtype == np.int32 and out.shape == (consts.K,)
    np.testing.assert_array_equal(out, np.zeros(consts.K, dtype=np.int32))


def test_mmalu_returns_expected_data(npu: ChiselNPU):
    """Pre-seeded OUT region is readable back (data path round-trip)."""
    A, B, ACCUM = _operands()
    expected = np.arange(consts.K, dtype=np.int32) * 3 + 1
    npu.dev.write_staged("OUT", expected)  # staged through the same API
    out = npu.mmalu(A, B, ACCUM, timeout_s=0.5)
    np.testing.assert_array_equal(out, expected)


def test_mmalu_fills_caller_out_buffer(npu: ChiselNPU):
    A, B, ACCUM = _operands()
    out = np.empty(consts.K, dtype=np.int32)
    out[:] = -999
    ret = npu.mmalu(A, B, ACCUM, OUT=out, timeout_s=0.5)
    assert ret is out
    np.testing.assert_array_equal(out, np.zeros(consts.K, dtype=np.int32))


def test_operand_sizes_exposed(npu: ChiselNPU):
    assert npu.dev.operand_size("A") == consts.K
    assert npu.dev.operand_size("B") == consts.K
    assert npu.dev.operand_size("ACCUM") == 4 * consts.K
    assert npu.dev.operand_size("OUT") == 4 * consts.K


def test_staged_roundtrip_per_operand(npu: ChiselNPU):
    """Each operand round-trips byte-exact through the staged API."""
    A = np.arange(consts.K, dtype=np.int8)
    B = np.full(consts.K, 7, dtype=np.int8)
    ACCUM = np.arange(consts.K, dtype=np.int32) * 100
    for name, data in (("A", A), ("B", B), ("ACCUM", ACCUM)):
        npu.dev.write_staged(name, data)
        got = np.empty_like(data)
        npu.dev.read_staged(name, got)
        np.testing.assert_array_equal(got, data)


def test_wrong_size_operand_rejected(npu: ChiselNPU):
    A, B, _ = _operands()
    bad = np.full(consts.K - 1, 1, dtype=np.int8)  # 31 bytes
    with pytest.raises(ValueError, match="must be exactly 32 bytes"):
        npu.mmalu(bad, B, np.zeros(consts.K, dtype=np.int32))


def test_unknown_operand_rejected(npu: ChiselNPU):
    with pytest.raises(ValueError, match="unknown staging operand"):
        npu.dev.write_staged("C", np.zeros(32, dtype=np.int8))


def test_busy_before_kick_raises(npu: ChiselNPU):
    npu.ctrl.write(1 << consts.CTRL_BUSY_BIT)
    A, B, ACCUM = _operands()
    with pytest.raises(NPUError, match="busy"):
        npu.mmalu(A, B, ACCUM, timeout_s=0.5)


def test_done_timeout_raises(npu: ChiselNPU):
    npu.dev.native.scripted_timeout = True
    A, B, ACCUM = _operands()
    with pytest.raises(NPUTimeoutError, match="did not assert done"):
        npu.mmalu(A, B, ACCUM, timeout_s=0.1)


def test_bytes_buffers_accepted(npu: ChiselNPU):
    """Raw byte buffers work for staged operands (sizes are byte-checked)."""
    A = bytes([10] * consts.K)
    B = bytearray([7] * consts.K)
    ACCUM = np.zeros(consts.K, dtype=np.int32)
    out = npu.mmalu(A, B, ACCUM, timeout_s=0.5)
    np.testing.assert_array_equal(out, np.zeros(consts.K, dtype=np.int32))


def test_mmalu_accepts_wrong_dtype_same_size(npu: ChiselNPU):
    """Size enforcement is byte-based; e.g. uint8[32] is accepted as int8[32]."""
    A = np.full(consts.K, 10, dtype=np.uint8)
    B = np.full(consts.K, 7, dtype=np.uint8)
    ACCUM = np.zeros(consts.K, dtype=np.int32)
    npu.mmalu(A, B, ACCUM, timeout_s=0.5)
