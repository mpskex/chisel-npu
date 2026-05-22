"""
test_mmalu_compute.py — End-to-end MMALU compute validation.

Requires the V10 bitstream (axi_xbar + npu_subsys topology) where:
  - Host XDMA can read/write the full 4 GB DDR3 address space.
  - The NPU's DMA master reads A/B/ACCUM from MIG C0 at 0x4000_0000 and writes
    OUT back to 0x4000_0400.
  - MMALU is actually instantiated (inside the npu_subsys module wrapper).

History (resolved during V10 bring-up):
  An ILA capture of (state, beat_cnt, m_axi_rvalid, m_axi_rready, m_axi_rlast,
  rdata_pipe) confirmed that the AXI READ path is correct — each of the 8
  beats from MIG lands in acc_buf[beat_cnt*4 + 0..3] for beat_cnt 0..7. The
  bug was in the WRITE phase (S_WR_W) which carried stale `m_axi_wdata` on
  the first beat and never sent out_buf[28..31]. Fix: pre-load wdata to
  out_buf[0..3] at the S_WR_AW → S_WR_W transition, and advance wdata to
  out_buf[(beat_cnt+1)*4 + 0..3] inside S_WR_W's IF branch. With that fix
  every test below passes on real silicon.

Hardware-verified result on V10 (positive WNS variant):
   Test 1 (zero in zero out)              PASS
   Test 2 (ACCUM passthrough, any base)   PASS  -- OUT == ACCUM
   Test 3 (A=0 kills multiplier)          PASS  -- OUT == ACCUM
   Test 4 (multiplier alive A=10 B=7)     PASS  -- OUT == 70 in all lanes
   Test 5 (formula OUT=A[i]*B[31]+ACCUM)  PASS  -- analytical match
"""

import struct
import pytest
import numpy as np

from .lib import reg_rw
from .lib.xdma import XDMADevice


# ── Memory map (must match npu_dma_master.v DEFAULT_BASE_* values) ──────────

NPU_BASE     = 0x4000_0000
A_OFFSET     = NPU_BASE + 0x000      # 32 B int8
B_OFFSET     = NPU_BASE + 0x100      # 32 B int8
ACCUM_OFFSET = NPU_BASE + 0x200      # 128 B int32
OUT_OFFSET   = NPU_BASE + 0x400      # 128 B int32

K = 32                                # MMALU systolic array side
A_BYTES      = K * 1                  # 32 B int8
B_BYTES      = K * 1                  # 32 B int8
ACCUM_BYTES  = K * 4                  # 128 B int32
OUT_BYTES    = K * 4                  # 128 B int32


# ── Helpers ─────────────────────────────────────────────────────────────────

def _load_operands(xdma_dev: XDMADevice,
                   A: np.ndarray, B: np.ndarray, ACCUM: np.ndarray) -> None:
    """Stage operands A (int8[K]), B (int8[K]), ACCUM (int32[K]) into MIG C0."""
    assert A.dtype == np.int8 and A.shape == (K,)
    assert B.dtype == np.int8 and B.shape == (K,)
    assert ACCUM.dtype == np.int32 and ACCUM.shape == (K,)
    xdma_dev.h2c(channel=0, ddr_offset=A_OFFSET,     data=A.tobytes())
    xdma_dev.h2c(channel=0, ddr_offset=B_OFFSET,     data=B.tobytes())
    xdma_dev.h2c(channel=0, ddr_offset=ACCUM_OFFSET, data=ACCUM.tobytes())
    # Clear OUT with a sentinel to confirm the NPU actually wrote it back.
    xdma_dev.h2c(channel=0, ddr_offset=OUT_OFFSET,
                 data=b"\xCC" * OUT_BYTES)


def _kick_and_collect(xdma_dev: XDMADevice,
                      bar: reg_rw.BARDevice) -> np.ndarray:
    """Pulse start, wait for done, return OUT as int32[K]."""
    assert not bar.is_busy, "NPU FSM unexpectedly busy before kick"
    bar.kick_start()
    assert bar.wait_done(timeout_s=2), \
        "NPU FSM did not assert done within 2 s"
    out_bytes = xdma_dev.c2h(channel=0, ddr_offset=OUT_OFFSET,
                              length=OUT_BYTES)
    assert len(out_bytes) == OUT_BYTES
    OUT = np.frombuffer(out_bytes, dtype=np.int32).copy()
    # Sentinel check: any OUT word still 0xCCCCCCCC means c2h read fired
    # before write-back completed, or the NPU never wrote at all.
    sentinel = np.int32(0xCCCCCCCC)
    assert not np.any(OUT == sentinel), (
        f"OUT[{np.where(OUT == sentinel)[0].tolist()}] still 0xCCCCCCCC — "
        "DMA write-back did not happen for those lanes."
    )
    return OUT


# ── Tests ───────────────────────────────────────────────────────────────────

@pytest.mark.hw
def test_mmalu_done_smoke(xdma_dev: XDMADevice,
                           bar: reg_rw.BARDevice) -> None:
    """
    Smoke test: kick the FSM and observe done=1 within 1 s.

    Does not validate OUT contents; this passes even with the V10 off-by-one
    read bug because that bug is silent at the protocol level (the FSM still
    transitions to S_DONE).
    """
    assert not bar.is_busy, "NPU FSM unexpectedly busy before kick"
    bar.kick_start()
    assert bar.wait_done(timeout_s=1), (
        "NPU FSM did not assert done=1 within 1 s. "
        "Check that V10 bitstream is loaded and MMALU is wired."
    )


@pytest.mark.hw
def test_mmalu_zero_in_zero_out(xdma_dev: XDMADevice,
                                 bar: reg_rw.BARDevice) -> None:
    """A=0, B=0, ACCUM=0 → OUT must be all zero, regardless of timing."""
    A     = np.zeros(K, dtype=np.int8)
    B     = np.zeros(K, dtype=np.int8)
    ACCUM = np.zeros(K, dtype=np.int32)

    _load_operands(xdma_dev, A, B, ACCUM)
    OUT = _kick_and_collect(xdma_dev, bar)

    np.testing.assert_array_equal(
        OUT, np.zeros(K, dtype=np.int32),
        err_msg=f"Expected all-zero output but got {OUT.tolist()}"
    )


@pytest.mark.hw
def test_mmalu_accum_passthrough(xdma_dev: XDMADevice,
                                  bar: reg_rw.BARDevice) -> None:
    """A=0, B=0, ACCUM=variable → OUT == ACCUM.

    With both operands zero, every PE.res settles to 0 → DataCollector emits
    0 + accum_in[i] = ACCUM[i].  This verifies the ACCUM pipeline through
    DataFeeder (Pipe(2n-1)) and the use_accum=1 path through ControlUnit.
    """
    A     = np.zeros(K, dtype=np.int8)
    B     = np.zeros(K, dtype=np.int8)
    rng   = np.random.default_rng(0xC0FFEE)
    ACCUM = rng.integers(-10_000, 10_000, size=K, dtype=np.int32)

    _load_operands(xdma_dev, A, B, ACCUM)
    OUT = _kick_and_collect(xdma_dev, bar)

    np.testing.assert_array_equal(
        OUT, ACCUM,
        err_msg=("ACCUM passthrough failed.\n"
                 f"  ACCUM = {ACCUM.tolist()}\n"
                 f"  OUT   = {OUT.tolist()}")
    )


@pytest.mark.hw
def test_mmalu_zero_a_kills_multiplier(xdma_dev: XDMADevice,
                                        bar: reg_rw.BARDevice) -> None:
    """A=0, B=arbitrary, ACCUM=arbitrary → OUT == ACCUM.

    Forces a=0 in every PE while b is non-zero. The product a*b = 0 → res=0,
    and the only contribution to OUT is the ACCUM addition. This isolates
    the multiplier-A operand path.
    """
    A     = np.zeros(K, dtype=np.int8)
    B     = np.arange(K, dtype=np.int8)              # 0, 1, ..., 31 (non-zero)
    rng   = np.random.default_rng(0xBEEF)
    ACCUM = rng.integers(-5_000, 5_000, size=K, dtype=np.int32)

    _load_operands(xdma_dev, A, B, ACCUM)
    OUT = _kick_and_collect(xdma_dev, bar)

    np.testing.assert_array_equal(
        OUT, ACCUM,
        err_msg=(f"A=0 should yield OUT=ACCUM but got OUT-ACCUM = "
                 f"{(OUT-ACCUM).tolist()}")
    )


@pytest.mark.hw
def test_mmalu_multiplier_alive(xdma_dev: XDMADevice,
                                 bar: reg_rw.BARDevice) -> None:
    """A=B=non-zero, ACCUM=0 → OUT must contain at least one non-zero entry.

    This is a weak but topology-independent assertion: as long as ANY PE in
    the systolic array produced and propagated a non-zero product, the
    multiplier path is alive.  Stronger formula-based checks live in
    test_mmalu_outer_b_last below.
    """
    A     = np.full(K, 10, dtype=np.int8)
    B     = np.full(K, 7,  dtype=np.int8)
    ACCUM = np.zeros(K, dtype=np.int32)

    _load_operands(xdma_dev, A, B, ACCUM)
    OUT = _kick_and_collect(xdma_dev, bar)

    assert np.any(OUT != 0), (
        "MMALU produced all-zero output for A=10, B=7, ACCUM=0 — "
        "multiplier or systolic array data path appears dead."
    )


@pytest.mark.hw
def test_mmalu_outer_b_last(xdma_dev: XDMADevice,
                             bar: reg_rw.BARDevice) -> None:
    """Strong formula check: OUT[i] = A[i] * B[K-1] + ACCUM[i].

    Verifies the per-PE multiplier, the systolic data feeder + collector, the
    ACCUM addition, and signed-arithmetic correctness all at once.

    Derivation lives at the top of this file.  If this test fails on real
    silicon, run with -s and capture (A, B, ACCUM, OUT) — the actual formula
    can be recovered from the data and compared to MMALUSpec behaviour to
    pinpoint timing differences between simulation and the FPGA.
    """
    rng   = np.random.default_rng(0xDEADBEEF)
    A     = rng.integers(-32, 32, size=K, dtype=np.int8)
    B     = rng.integers(-32, 32, size=K, dtype=np.int8)
    ACCUM = rng.integers(-1_000, 1_000, size=K, dtype=np.int32)

    _load_operands(xdma_dev, A, B, ACCUM)
    OUT = _kick_and_collect(xdma_dev, bar)

    EXPECTED = (A.astype(np.int32) * np.int32(B[K - 1])) + ACCUM
    np.testing.assert_array_equal(
        OUT, EXPECTED,
        err_msg=(
            "OUT does not match A[i]*B[K-1]+ACCUM[i].\n"
            f"  A     = {A.tolist()}\n"
            f"  B[31] = {int(B[K-1])}\n"
            f"  ACCUM = {ACCUM.tolist()}\n"
            f"  OUT   = {OUT.tolist()}\n"
            f"  EXP   = {EXPECTED.tolist()}\n"
            f"  diff  = {(OUT-EXPECTED).tolist()}"
        )
    )
