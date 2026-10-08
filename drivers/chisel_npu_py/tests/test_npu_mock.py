"""Unit tests for ChiselNPU.run (FakeNative embeds the engine session model).

The session model (mirroring the Chisel engine):
    per mma feed:  PE[i][j] += A[vs1][i]·B[vs2][j]
    per mma capture: vd[i] = PE[i][(k+COL_OFF) mod K] + C[i]
"""

from __future__ import annotations

import numpy as np
import pytest

from chisel_npu_py import ChiselNPU, NPUProgramError, XDMADevice, config, isa

from .fake_native import FakeNative


@pytest.fixture
def npu() -> ChiselNPU:
    cfg = config.default_config()
    return ChiselNPU(cfg=cfg, _dev=XDMADevice(cfg=cfg, native=FakeNative(cfg=cfg)))


def _session_words(k: int, m: int, a_vx0: int = 4, b_vx0: int = 8,
                   vd_vr0: int = 1):
    """A session of m consecutive mma + mma.last (all operands pre-loaded)."""
    words = [isa.mma(vd_vr0 + mm, a_vx0 + mm, b_vx0 + mm, 0) for mm in range(m)]
    words.append(isa.mma_last(0, 0, 0, 0))
    return words


def _load_words(k: int, m: int, a_vx0: int = 4, b_vx0: int = 8):
    words = []
    for mm in range(m):
        words.append(isa.vle8(a_vx0 + mm, isa.SECT_A, mm * k))
        words.append(isa.vle8(b_vx0 + mm, isa.SECT_B, mm * k))
    return words


def test_run_returns_memories_readback_including_out(npu: ChiselNPU):
    cfg = npu.cfg
    k = cfg.K
    a = np.random.randint(0, 256, size=k, dtype=np.uint8)
    result = npu.run([isa.vle8(0, isa.SECT_A, 0), isa.vse8(0, isa.SECT_OUT, 0)],
                     memories={"A": a})
    np.testing.assert_array_equal(result["A"], a)
    np.testing.assert_array_equal(result["OUT"][: (k + 3) // 4].view(np.uint8)[:k], a)
    assert result["OUT"].dtype == np.int32
    assert result["OUT"].shape == (cfg.sections["OUT"].window // 4,)


def test_run_one_shot_mac(npu: ChiselNPU):
    cfg = npu.cfg
    k = cfg.K
    a = np.arange(1, k + 1, dtype=np.int8)
    b = np.arange(100, 100 + k, dtype=np.int8)
    words = [
        isa.vle8(4, isa.SECT_A, 0),
        isa.vle8(8, isa.SECT_B, 0),
        isa.mma(2, 4, 8, 0),
        isa.mma_last(0, 0, 0, 0),
    ] + [isa.NOP] * (2 * k + 8) + [
        isa.vse32(2, isa.SECT_OUT, 0),
    ]
    result = npu.run(words, memories={"A": a, "B": b})
    col = FakeNative.COL_OFF
    want = np.zeros(k, dtype=np.int32)
    for i in range(k):
        want[i] = int(a[i]) * int(b[col % k])
    np.testing.assert_array_equal(result["OUT"][:k], want)


def test_run_session_captures_columns(npu: ChiselNPU):
    cfg = npu.cfg
    k = cfg.K
    m = 3
    aVals = np.array([[(mm * 7 + j + 1) & 0xFF for j in range(k)]
                      for mm in range(m)], dtype=np.uint8)
    bVals = np.array([[(100 + mm * 5 + i) & 0xFF for i in range(k)]
                      for mm in range(m)], dtype=np.uint8)

    def s8(v: int) -> int:
        v &= 0xFF
        return v - 256 if v >= 128 else v

    words = _load_words(k, m) + _session_words(k, m) + \
        [isa.NOP] * (2 * k + 8)
    for mm in range(m):
        words.append(isa.vse32(1 + mm, isa.SECT_OUT, mm * 4 * k))

    result = npu.run(words, memories={"A": aVals, "B": bVals})
    # PE(i,j) = Σ_m a_m[i]·b_m[j]; capture k = column (k+COL_OFF)
    for mm in range(m):
        col = (mm + FakeNative.COL_OFF) % k
        for i in range(k):
            want = sum(s8(int(aVals[t][i])) * s8(int(bVals[t][col])) for t in range(m))
            got = int(result["OUT"][mm * k + i])
            assert got == want, f"mma{mm} lane{i}: got {got} want {want}"


def test_run_illegal_instruction_raises(npu: ChiselNPU):
    bad = 0x03 | (0 << 7) | (2 << 12) | (1 << 15) | (2 << 20)  # mma.reset
    with pytest.raises(NPUProgramError) as exc:
        npu.run([isa.vle8(0, isa.SECT_A, 0), bad, isa.vse8(0, isa.SECT_OUT, 0)])
    assert exc.value.pc == 1
    assert exc.value.err_info == bad


def test_run_times_out(npu: ChiselNPU):
    npu.dev.native.scripted_timeout = True
    with pytest.raises(Exception) as exc:
        npu.run([isa.NOP], timeout_s=0.1)
    assert "did not assert done" in str(exc.value)


def test_run_rejects_empty(npu: ChiselNPU):
    with pytest.raises(Exception):
        npu.run([])


def test_run_rejects_bad_section(npu: ChiselNPU):
    with pytest.raises(ValueError):
        npu.run([isa.NOP], memories={"NOPE": b"\x00" * 16})
