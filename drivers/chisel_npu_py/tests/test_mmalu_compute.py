"""Hardware tests: end-to-end MMALU compute via the chisel_npu_py driver.

Port of tool/hw/tests/test_mmalu_compute.py onto the new driver API.  The
tests exercise the full cycle through the pybind11 boundary: stage
A/B/ACCUM (by name — the native module owns the DDR addresses) → kick →
wait done → read OUT back into a numpy buffer.

The formula suite (`test_mmalu_formula_*`) verifies BIT-EXACTNESS of the
one-shot kick semantics, OUT[i] = A[i]·B[K-1] + ACCUM[i], at both the
int32 value level and the raw uint32 bit-pattern level, over random
trials (full int8 range) and edge cases (full-scale products, mixed
signs, negative B[K-1], ACCUM near ±2^30).

Staged operands (sizes only; addresses live in native.cpp):
    A     int8[K]     K bytes
    B     int8[K]     K bytes
    ACCUM int32[K]    4·K bytes
    OUT   int32[K]    4·K bytes
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


# ── Formal bit-exactness suite (promoted from the bit_exact_check script) ────

def _assert_formula_bit_exact(npu: ChiselNPU, ctrl: CtrlLite,
                              A: np.ndarray, B: np.ndarray,
                              ACCUM: np.ndarray) -> np.ndarray:
    """One MMALU kick; assert OUT == A[i]·B[K-1] + ACCUM[i] bit-exactly.

    The OUT region is sentinel-cleared before the kick, so a mismatch can
    never be masked by stale data — the result is proven to have been
    written back by the NPU.  Checks both the int32 values and the raw
    uint32 bit patterns (equal int32 values ⟺ equal two's-complement bits).
    """
    _load_operands(npu, A, B, ACCUM)   # stages operands + sentinel-cleans OUT
    OUT = _kick_and_collect(npu, ctrl)
    EXPECTED = (A.astype(np.int32) * np.int32(B[K - 1])) + ACCUM
    np.testing.assert_array_equal(
        OUT, EXPECTED,
        err_msg=(
            "OUT[i] = A[i]*B[K-1] + ACCUM[i] value mismatch.\n"
            f"  A     = {A.tolist()}\n"
            f"  B[31] = {int(B[K - 1])}\n"
            f"  ACCUM = {ACCUM.tolist()}\n"
            f"  OUT   = {OUT.tolist()}\n"
            f"  EXP   = {EXPECTED.tolist()}"
        ),
    )
    assert np.array_equal(OUT.view(np.uint32), EXPECTED.view(np.uint32)), (
        "Bit-level (uint32) mismatch despite equal int32 values — "
        "sign/extension or two's-complement bug.\n"
        f"  OUT bits = {OUT.view(np.uint32).tolist()}\n"
        f"  EXP bits = {EXPECTED.view(np.uint32).tolist()}"
    )
    return OUT


@pytest.mark.hw
@pytest.mark.parametrize(
    "seed",
    [0xDEADBEEF, 0xC0FFEE, 0x5EED, 7, 42, 20260731],
    ids=["deadbeef", "c0ffee", "5eed", "seed7", "seed42", "20260731"],
)
def test_mmalu_formula_bit_exact_random(npu: ChiselNPU, ctrl: CtrlLite, seed: int):
    """Full int8 range operands, ACCUM ±10^6, random signed products per seed."""
    rng = np.random.default_rng(seed)
    A = rng.integers(-128, 128, size=K, dtype=np.int8)
    B = rng.integers(-128, 128, size=K, dtype=np.int8)
    ACCUM = rng.integers(-10**6, 10**6, size=K, dtype=np.int32)
    _assert_formula_bit_exact(npu, ctrl, A, B, ACCUM)


@pytest.mark.hw
def test_mmalu_formula_full_scale_products(npu: ChiselNPU, ctrl: CtrlLite):
    """Full-scale products on every lane: -128·-128 = 16384, 127·127 = 16129."""
    A = np.full(K, -128, dtype=np.int8)
    B = np.full(K, -128, dtype=np.int8)
    OUT = _assert_formula_bit_exact(npu, ctrl, A, B, np.zeros(K, dtype=np.int32))
    np.testing.assert_array_equal(OUT, np.full(K, 16384, dtype=np.int32))

    A = np.full(K, 127, dtype=np.int8)
    B = np.full(K, 127, dtype=np.int8)
    OUT = _assert_formula_bit_exact(npu, ctrl, A, B, np.zeros(K, dtype=np.int32))
    np.testing.assert_array_equal(OUT, np.full(K, 16129, dtype=np.int32))


@pytest.mark.hw
def test_mmalu_formula_signed_cross(npu: ChiselNPU, ctrl: CtrlLite):
    """Mixed signs incl. B[K-1]=0 (A·0 = 0) and alternating ± lanes."""
    A = np.array([-128, 127, 1, -1] * (K // 4), dtype=np.int8)
    B = np.array([127, -128, -127, 0] * (K // 4), dtype=np.int8)
    ACCUM = np.arange(K, dtype=np.int32) * 1000 - 500
    _assert_formula_bit_exact(npu, ctrl, A, B, ACCUM)


@pytest.mark.hw
def test_mmalu_formula_negative_b_last(npu: ChiselNPU, ctrl: CtrlLite):
    """All-negative B → B[K-1] < 0: negative products on every lane."""
    rng = np.random.default_rng(7)
    A = rng.integers(-128, 128, size=K, dtype=np.int8)
    B = rng.integers(-128, 0, size=K, dtype=np.int8)
    assert B[K - 1] < 0
    _assert_formula_bit_exact(npu, ctrl, A, B, np.zeros(K, dtype=np.int32))


@pytest.mark.hw
def test_mmalu_formula_large_accum(npu: ChiselNPU, ctrl: CtrlLite):
    """ACCUM near ±2^30: stresses the 32-bit adder without overflow.

    Max |product| is 128·128 = 16384, so |OUT| ≤ 2^30 + 16384 < 2^31 —
    the addition must be exact in 32 bits.
    """
    rng = np.random.default_rng(0xACC0DE)
    A = rng.integers(-128, 128, size=K, dtype=np.int8)
    B = rng.integers(-128, 128, size=K, dtype=np.int8)
    signs = (rng.integers(0, 2, size=K) * 2) - 1              # ±1
    ACCUM = ((signs * (2**30)) + rng.integers(0, 10_000, size=K)).astype(np.int32)
    _assert_formula_bit_exact(npu, ctrl, A, B, ACCUM)
