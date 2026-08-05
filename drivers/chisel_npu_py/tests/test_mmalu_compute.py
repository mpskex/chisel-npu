"""Hardware tests: end-to-end MMALU compute via the chisel_npu_py driver.

Port of tool/hw/tests/test_mmalu_compute.py onto the new driver API.  The
six tests exercise the full cycle through the pybind11 boundary:
stage A/B/ACCUM (native owns the DDR addresses) → kick → wait done → read
OUT back into a numpy buffer.

Memory map (MIG C0, see chisel_npu_py.consts.STAGING):
    A     @ 0x4000_0000  32 B int8
    B     @ 0x4000_0100  32 B int8
    ACCUM @ 0x4000_0200 128 B int32
    OUT   @ 0x4000_0400 128 B int32
"""

from __future__ import annotations

import numpy as np
import pytest

from chisel_npu_py import ChiselNPU, CtrlLite, XDMADevice, consts

K = consts.K


def _load_operands(npu: ChiselNPU, A: np.ndarray, B: np.ndarray,
                   ACCUM: np.ndarray) -> None:
    """Stage A/B/ACCUM and clear OUT with a sentinel to prove write-back."""
    assert A.dtype == np.int8 and A.shape == (K,)
    assert B.dtype == np.int8 and B.shape == (K,)
    assert ACCUM.dtype == np.int32 and ACCUM.shape == (K,)
    npu.stage_operands(A, B, ACCUM)
    # 0xCCCCCCCC as int32 = -858993460 (numpy rejects the unsigned literal)
    npu.dev.write_staged("OUT", np.full(K, -858993460, dtype=np.int32))


def _kick_and_collect(npu: ChiselNPU, ctrl: CtrlLite) -> np.ndarray:
    """Kick, wait for done, return OUT as int32[K] with sentinel check."""
    assert not ctrl.is_busy, "NPU FSM unexpectedly busy before kick"
    ctrl.kick()
    assert ctrl.wait_done(timeout_s=2), "NPU FSM did not assert done within 2 s"
    OUT = np.empty(K, dtype=np.int32)
    npu.dev.read_staged("OUT", OUT)
    sentinel = np.int32(-858993460)
    assert not np.any(OUT == sentinel), (
        f"OUT[{np.where(OUT == sentinel)[0].tolist()}] still 0xCCCCCCCC — "
        "DMA write-back did not happen for those lanes."
    )
    return OUT


@pytest.mark.hw
def test_mmalu_done_smoke(npu: ChiselNPU, ctrl: CtrlLite):
    """Smoke: kick the FSM and observe done=1 within 1 s."""
    assert not ctrl.is_busy, "NPU FSM unexpectedly busy before kick"
    ctrl.kick()
    assert ctrl.wait_done(timeout_s=1), (
        "NPU FSM did not assert done=1 within 1 s. "
        "Check that the V10 bitstream is loaded and MMALU is wired."
    )


@pytest.mark.hw
def test_mmalu_zero_in_zero_out(npu: ChiselNPU, ctrl: CtrlLite):
    """A=0, B=0, ACCUM=0 → OUT must be all zero."""
    A = np.zeros(K, dtype=np.int8)
    B = np.zeros(K, dtype=np.int8)
    ACCUM = np.zeros(K, dtype=np.int32)
    _load_operands(npu, A, B, ACCUM)
    OUT = _kick_and_collect(npu, ctrl)
    np.testing.assert_array_equal(
        OUT, np.zeros(K, dtype=np.int32), err_msg=f"Expected zeros, got {OUT.tolist()}"
    )


@pytest.mark.hw
def test_mmalu_accum_passthrough(npu: ChiselNPU, ctrl: CtrlLite):
    """A=0, B=0, ACCUM=variable → OUT == ACCUM (ACCUM pipeline check)."""
    A = np.zeros(K, dtype=np.int8)
    B = np.zeros(K, dtype=np.int8)
    rng = np.random.default_rng(0xC0FFEE)
    ACCUM = rng.integers(-10_000, 10_000, size=K, dtype=np.int32)
    _load_operands(npu, A, B, ACCUM)
    OUT = _kick_and_collect(npu, ctrl)
    np.testing.assert_array_equal(
        OUT, ACCUM,
        err_msg=f"ACCUM passthrough failed.\n  ACCUM = {ACCUM.tolist()}\n  OUT   = {OUT.tolist()}",
    )


@pytest.mark.hw
def test_mmalu_zero_a_kills_multiplier(npu: ChiselNPU, ctrl: CtrlLite):
    """A=0, B≠0, ACCUM=variable → OUT == ACCUM (isolates multiplier-A path)."""
    A = np.zeros(K, dtype=np.int8)
    B = np.arange(K, dtype=np.int8)
    rng = np.random.default_rng(0xBEEF)
    ACCUM = rng.integers(-5_000, 5_000, size=K, dtype=np.int32)
    _load_operands(npu, A, B, ACCUM)
    OUT = _kick_and_collect(npu, ctrl)
    np.testing.assert_array_equal(
        OUT, ACCUM, err_msg=f"A=0 should yield OUT=ACCUM but got OUT-ACCUM = {(OUT - ACCUM).tolist()}"
    )


@pytest.mark.hw
def test_mmalu_multiplier_alive(npu: ChiselNPU, ctrl: CtrlLite):
    """A=10, B=7, ACCUM=0 → OUT must contain at least one non-zero entry."""
    A = np.full(K, 10, dtype=np.int8)
    B = np.full(K, 7, dtype=np.int8)
    ACCUM = np.zeros(K, dtype=np.int32)
    _load_operands(npu, A, B, ACCUM)
    OUT = _kick_and_collect(npu, ctrl)
    assert np.any(OUT != 0), (
        "MMALU produced all-zero output for A=10, B=7, ACCUM=0 — "
        "multiplier or systolic array data path appears dead."
    )


@pytest.mark.hw
def test_mmalu_outer_b_last(npu: ChiselNPU, ctrl: CtrlLite):
    """Strong formula check: OUT[i] = A[i] * B[K-1] + ACCUM[i]."""
    rng = np.random.default_rng(0xDEADBEEF)
    A = rng.integers(-32, 32, size=K, dtype=np.int8)
    B = rng.integers(-32, 32, size=K, dtype=np.int8)
    ACCUM = rng.integers(-1_000, 1_000, size=K, dtype=np.int32)
    _load_operands(npu, A, B, ACCUM)
    OUT = _kick_and_collect(npu, ctrl)
    EXPECTED = (A.astype(np.int32) * np.int32(B[K - 1])) + ACCUM
    np.testing.assert_array_equal(
        OUT, EXPECTED,
        err_msg=(
            "OUT does not match A[i]*B[K-1]+ACCUM[i].\n"
            f"  A     = {A.tolist()}\n"
            f"  B[31] = {int(B[K - 1])}\n"
            f"  ACCUM = {ACCUM.tolist()}\n"
            f"  OUT   = {OUT.tolist()}\n"
            f"  EXP   = {EXPECTED.tolist()}"
        ),
    )
