"""Hardware tests: streamed mma sessions on the engine via ChiselNPU.run().

The streamed model: each mma feed accumulates the running product in the PEs
and its clct captures ONE output column; the chained vse stores it; mma.last
ends the session.  Zero NOP padding — the dispatch chains the dataflow.  The
captured columns are located by search (the phase offset is issue-timing
dependent) and verified against the running-sum numpy reference.
"""

from __future__ import annotations

import numpy as np
import pytest

from chisel_npu_py import ChiselNPU, NPUProgramError, isa


def _s8(v: int) -> int:
    v &= 0xFF
    return v - 256 if v >= 128 else v


@pytest.mark.hw
def test_one_shot_session(npu: ChiselNPU):
    cfg = npu.cfg
    k = cfg.K
    a = np.arange(1, k + 1, dtype=np.int8)
    b = np.arange(100, 100 + k, dtype=np.int8)
    words = [
        isa.vle8(4, isa.SECT_A, 0),
        isa.vle8(8, isa.SECT_B, 0),
        isa.mma(2, 4, 8, 0),
        isa.mma_last(0, 0, 0, 0),
        isa.vse32(2, isa.SECT_OUT, 0),
    ]
    result = npu.run(words, memories={"A": a, "B": b})
    got = result["OUT"][:k].astype(np.int32)
    # one feed: vd[i] = a[i]·b[col] — locate the column
    cols = [c for c in range(k) if np.array_equal(got, a.astype(np.int32) * int(b[c]))]
    assert len(cols) == 1, f"one-shot capture not a single product column"
    print(f"[hw] one-shot capture column: {cols[0]}")


@pytest.mark.hw
def test_session_captures_columns(npu: ChiselNPU):
    cfg = npu.cfg
    k = cfg.K
    m = 3
    aVals = np.array([[(mm * 7 + j + 1) & 0xFF for j in range(k)]
                      for mm in range(m)], dtype=np.uint8)
    bVals = np.array([[(100 + mm * 5 + i) & 0xFF for i in range(k)]
                      for mm in range(m)], dtype=np.uint8)

    # streamed form: reused operands (VX[4]/VX[8]) + vd=0 (VR[0], disjoint)
    # + per-feed vse; each capture = a column of the running partial sum
    words = []
    for mm in range(m):
        words.append(isa.vle8(4, isa.SECT_A, mm * k))
        words.append(isa.vle8(8, isa.SECT_B, mm * k))
        words.append(isa.mma(0, 4, 8, 0))
        words.append(isa.vse32(0, isa.SECT_OUT, mm * 4 * k))
    words.append(isa.mma_last(0, 0, 0, 0))

    result = npu.run(words, memories={"A": aVals, "B": bVals})
    a8 = np.vectorize(_s8)(aVals).astype(np.int32)
    b8 = np.vectorize(_s8)(bVals).astype(np.int32)

    cols = []
    for mm in range(m):
        got = result["OUT"][mm * k:(mm + 1) * k].astype(np.int32)
        pe = np.zeros((k, k), dtype=np.int32)
        for t in range(mm + 1):
            pe += a8[t][:, None] * b8[t][None, :]
        found = [c for c in range(k) if np.array_equal(got, pe[:, c])]
        assert len(found) == 1, f"capture {mm} is not a running-sum column"
        cols.append(found[0])
    print(f"[hw] session capture columns: {cols}")


@pytest.mark.hw
def test_illegal_instruction_halts(npu: ChiselNPU):
    bad = 0x03 | (0 << 7) | (2 << 12) | (1 << 15) | (2 << 20)  # mma.reset
    with pytest.raises(NPUProgramError) as exc:
        npu.run([isa.vle8(0, isa.SECT_A, 0), bad, isa.vse8(0, isa.SECT_OUT, 0)])
    assert exc.value.pc == 1
    assert exc.value.err_info == bad


@pytest.mark.hw
def test_status_after_program(npu: ChiselNPU):
    result = npu.run([isa.NOP] * 8)
    assert len(result["OUT"]) == npu.cfg.sections["OUT"].window // 4
