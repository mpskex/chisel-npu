#!/usr/bin/env python3
"""session_diag.py — silicon diagnosis for the mma session-capture alignment.

Runs on the FPGA host against the engine bitstream via the deployed driver
(ChiselNPU.run — the same path the pytest hw suite uses).  Each probe stages
ramp A/B (and optionally ACCUM C) plus a session program, reads back OUT and
the engine debug counters (ctrl 0x18 = clct captured, 0x1C = mma accepted),
and classifies every capture against:

    * an exact product column         g[i] == a[i]·b[c]          (CLEAN)
    * a column + per-lane constant    g[i] == a[i]·b[c] + r[i]   (residue/C)
    * a windowed column sum           g[i] == a[i]·(Σ b[c1..c2]) (lane-mixed)
    * a row / transpose               g[i] == a[c]·b[i]          (transpose)
    * anything else                   raw dump

Probes:
    p1   one-shot session (no C) x N back-to-back runs  — phase + stability
    p2   two nop-separated sessions in ONE program      — cross-session residue
    p3   one-shot session with C from ACCUM             — C/use_accum phase
    p4   3-mma session (the xfailed hw test)            — per-capture columns
"""

from __future__ import annotations

import sys

import numpy as np

from chisel_npu_py import ChiselNPU, isa

K = 16
NOP_WAIT = 2 * K + 8


def s8(v: int) -> int:
    v &= 0xFF
    return v - 256 if v >= 128 else v


# ---------------------------------------------------------------------------
# capture classification
# ---------------------------------------------------------------------------

def classify(g: np.ndarray, a: np.ndarray, b: np.ndarray) -> str:
    """Classify one K-lane capture against a one-feed product a[i]·b[c]."""
    k = K
    pe = a.astype(np.int64)[:, None] * b.astype(np.int64)[None, :]
    g = g.astype(np.int64)

    for c in range(k):
        if np.array_equal(g, pe[:, c]):
            return f"CLEAN column {c} (a[i]·b[{c}])"

    # column + per-lane constant (residue / C added at the wrong phase)
    best = None
    for c in range(k):
        r = g - pe[:, c]
        if np.all(r == r[0]):
            best = c
            break
    if best is not None:
        r = g - pe[:, best]
        return (f"SHIFTED column {best} + const {int(r[0])} "
                f"(per-lane-constant residue)")

    # windowed column sums
    for c1 in range(k):
        for c2 in range(c1, k):
            s = int(b[c1:c2 + 1].astype(np.int64).sum())
            if np.array_equal(g, a.astype(np.int64) * s):
                return f"WINDOW-SUM column b[{c1}..{c2}] sum={s} (lane-mixed)"

    # row / transpose
    for r in range(k):
        if np.array_equal(g, pe[r, :]):
            return f"TRANSPOSE row {r} (a[{r}]·b[i])"

    # ratios (when a[i] != 0)
    mask = a != 0
    ratios = g[mask] / a[mask].astype(np.int64)
    ratio_txt = ""
    if len(ratios) and np.all(ratios == ratios[0]):
        ratio_txt = f"; ratios CONSTANT = {ratios[0]}"

    return (f"OTHER (no product column): g={g.tolist()}" + ratio_txt
            + f"; g/a ratios={ratios.tolist()}")


def columns_of_session(got: np.ndarray, pe: np.ndarray, label: str) -> list:
    """Locate the capture column of every stored column (search-based)."""
    cols = []
    for mm in range(len(pe)):
        g = got[mm * K:(mm + 1) * K].astype(np.int64)
        found = [c for c in range(K)
                 if np.array_equal(g, pe[mm][:, c])]
        cols.append(found[0] if len(found) == 1
                    else f"AMBIG/None:{found}" if len(found) else "NONE")
        print(f"    {label} mma{mm}: {cols[-1]}")
    return cols


# ---------------------------------------------------------------------------
# probes
# ---------------------------------------------------------------------------

def probe1(npu: ChiselNPU, n_runs: int = 20) -> None:
    """One-shot session, no C, back-to-back runs — phase stability."""
    print(f"=== p1: one-shot session (no C) x {n_runs} runs ===")
    a = np.arange(1, K + 1, dtype=np.int8)
    b = np.arange(100, 100 + K, dtype=np.int8)
    words = [
        isa.vle8(4, isa.SECT_A, 0),
        isa.vle8(8, isa.SECT_B, 0),
        isa.mma(2, 4, 8, 0),
        isa.mma_last(0, 0, 0, 0),
    ] + [isa.NOP] * NOP_WAIT + [
        isa.vse32(2, isa.SECT_OUT, 0),
    ]
    out_cols = []
    for run in range(n_runs):
        res = npu.run(words, memories={"A": a, "B": b})
        g = res["OUT"][:K]
        c = classify(g, a, b)
        out_cols.append(c)
        print(f"  run {run:2d}: {c}")
    clean = [c for c in out_cols if c.startswith("CLEAN")]
    print(f"  summary: {len(clean)}/{n_runs} clean; "
          f"distinct cols={sorted({c.split()[2] for c in clean})}")
    print(f"  dbg: clct={npu.dev.ctrl_read_reg(0x18) & 0xFFFF} "
          f"mma_acc={npu.dev.ctrl_read_reg(0x1C) & 0xFFFF}")


def probe2(npu: ChiselNPU, n_runs: int = 5) -> None:
    """Two nop-separated sessions in ONE program — cross-session residue."""
    print(f"=== p2: two sessions in one program x {n_runs} runs ===")
    a1 = np.arange(1, K + 1, dtype=np.int8)
    b1 = np.arange(100, 100 + K, dtype=np.int8)
    a2 = np.arange(33, 33 + K, dtype=np.int8)
    b2 = np.arange(200, 200 + K, dtype=np.int8)
    A = np.concatenate([a1, a2])
    B = np.concatenate([b1, b2])
    words = [
        isa.vle8(4, isa.SECT_A, 0), isa.vle8(8, isa.SECT_B, 0),
        isa.mma(1, 4, 8, 0), isa.mma_last(0, 0, 0, 0),
    ] + [isa.NOP] * NOP_WAIT + [
        isa.vse32(1, isa.SECT_OUT, 0),
        isa.vle8(5, isa.SECT_A, 16), isa.vle8(9, isa.SECT_B, 16),
        isa.mma(2, 5, 9, 0), isa.mma_last(0, 0, 0, 0),
    ] + [isa.NOP] * NOP_WAIT + [
        isa.vse32(2, isa.SECT_OUT, 64),
    ]
    for run in range(n_runs):
        res = npu.run(words, memories={"A": A, "B": B})
        g1 = res["OUT"][:K]
        g2 = res["OUT"][K:2 * K]
        c1 = classify(g1, a1, b1)
        c2 = classify(g2, a2, b2)
        print(f"  run {run:2d}: sess1 {c1}")
        print(f"      : sess2 {c2}")


def probe3(npu: ChiselNPU, n_runs: int = 5) -> None:
    """One-shot session with C from ACCUM — C/use_accum phase."""
    print(f"=== p3: one-shot session with C x {n_runs} runs ===")
    a = np.arange(1, K + 1, dtype=np.int8)
    b = np.arange(100, 100 + K, dtype=np.int8)
    c = (7 + np.arange(K)).astype(np.int32)
    words = [
        isa.vle8(4, isa.SECT_A, 0),
        isa.vle8(8, isa.SECT_B, 0),
        isa.vle32(3, isa.SECT_ACCUM, 0),
        isa.mma(2, 4, 8, 3),
        isa.mma_last(0, 0, 0, 0),
    ] + [isa.NOP] * NOP_WAIT + [
        isa.vse32(2, isa.SECT_OUT, 0),
    ]
    for run in range(n_runs):
        res = npu.run(words, memories={"A": a, "B": b, "ACCUM": c})
        g = res["OUT"][:K].astype(np.int64)
        base = classify(g - c, a, b)          # with C subtracted
        print(f"  run {run:2d}: (g - C) {base}")
        pe = a.astype(np.int64)[:, None] * b.astype(np.int64)[None, :]
        for delta in range(K):
            cshift = np.roll(c, delta)
            for col in range(K):
                if np.array_equal(g, pe[:, col] + cshift):
                    print(f"    -> matches column {col} + C rolled by {delta}")
                    break
            else:
                continue
            break
        else:
            print(f"    -> no (column + rolled-C) match; g={g.tolist()}")


def probe4(npu: ChiselNPU, n_runs: int = 10) -> None:
    """3-mma session — the xfailed test_session_captures_columns."""
    print(f"=== p4: 3-mma session (xfailed hw test) x {n_runs} runs ===")
    m = 3
    aVals = np.array([[(mm * 7 + j + 1) & 0xFF for j in range(K)]
                      for mm in range(m)], dtype=np.uint8)
    bVals = np.array([[(100 + mm * 5 + i) & 0xFF for i in range(K)]
                      for mm in range(m)], dtype=np.uint8)
    words = []
    for mm in range(m):
        words.append(isa.vle8(4 + mm, isa.SECT_A, mm * K))
        words.append(isa.vle8(8 + mm, isa.SECT_B, mm * K))
    for mm in range(m):
        words.append(isa.mma(1 + mm, 4 + mm, 8 + mm, 0))
    words.append(isa.mma_last(0, 0, 0, 0))
    words += [isa.NOP] * NOP_WAIT
    for mm in range(m):
        words.append(isa.vse32(1 + mm, isa.SECT_OUT, mm * 4 * K))

    pe = np.zeros((m, K, K), dtype=np.int64)
    for t in range(m):
        pe[t] = (aVals[t].astype(np.int64)[:, None]
                 * np.array([s8(v) for v in bVals[t]])[None, :])

    allcols = []
    for run in range(n_runs):
        res = npu.run(words, memories={"A": aVals, "B": bVals})
        got = res["OUT"].astype(np.int64)
        cols = columns_of_session(got, pe, f"run {run:2d}")
        allcols.append(cols)
    flat = [c for cs in allcols for c in cs]
    print(f"  summary: {flat}")


def probe5(npu: ChiselNPU, n_runs: int = 5) -> None:
    """One-shot + dump VX[4] (A) and VX[8] (B) AFTER the capture — checks
    whether the capture to VR[2] (=VX[8..11]) clobbered the B operand."""
    print(f"=== p5: one-shot + post-capture VX dump x {n_runs} runs ===")
    a = np.arange(1, K + 1, dtype=np.int8)
    b = np.arange(100, 100 + K, dtype=np.int8)
    words = [
        isa.vle8(4, isa.SECT_A, 0),
        isa.vle8(8, isa.SECT_B, 0),
        isa.mma(2, 4, 8, 0),
        isa.mma_last(0, 0, 0, 0),
    ] + [isa.NOP] * NOP_WAIT + [
        isa.vse32(2, isa.SECT_OUT, 0),
        isa.vse8(4, isa.SECT_OUT, 64),    # dump A (VX[4]) to OUT+64
        isa.vse8(8, isa.SECT_OUT, 80),    # dump B (VX[8]) to OUT+80
    ]
    for run in range(n_runs):
        res = npu.run(words, memories={"A": a, "B": b})
        out_i32 = res["OUT"].astype(np.int64)
        g = out_i32[:K]
        out_bytes = res["OUT"].view(np.uint8)
        a_dump_s = out_bytes[64:80].astype(np.int32)
        a_dump_s = np.where(a_dump_s >= 128, a_dump_s - 256, a_dump_s)
        b_dump_s = out_bytes[80:96].astype(np.int32)
        b_dump_s = np.where(b_dump_s >= 128, b_dump_s - 256, b_dump_s)
        print(f"  run {run}: capture {classify(g, a, b)}")
        print(f"      A=VX[4] dump: {a_dump_s.tolist()} (expect {a.astype(np.int64).tolist()})")
        print(f"      B=VX[8] dump: {b_dump_s.tolist()} (expect {b.astype(np.int64).tolist()})")


def probe6(npu: ChiselNPU, n_runs: int = 10) -> None:
    """One-shot with vd=6 (VR[6]=VX[24..27], disjoint from A=VX[4]/B=VX[8]) —
    if captures turn clean, the vd/operand aliasing is implicated."""
    print(f"=== p6: one-shot with disjoint vd=6 x {n_runs} runs ===")
    a = np.arange(1, K + 1, dtype=np.int8)
    b = np.arange(100, 100 + K, dtype=np.int8)
    words = [
        isa.vle8(4, isa.SECT_A, 0),
        isa.vle8(8, isa.SECT_B, 0),
        isa.mma(6, 4, 8, 0),
        isa.mma_last(0, 0, 0, 0),
    ] + [isa.NOP] * NOP_WAIT + [
        isa.vse32(6, isa.SECT_OUT, 0),
    ]
    nclean = 0
    for run in range(n_runs):
        res = npu.run(words, memories={"A": a, "B": b})
        g = res["OUT"][:K]
        c = classify(g, a, b)
        nclean += c.startswith("CLEAN")
        print(f"  run {run:2d}: {c}")
    print(f"  summary: {nclean}/{n_runs} clean")


def main() -> None:
    which = sys.argv[1:] or ["1", "2", "3", "4"]
    npu = ChiselNPU()
    for w in which:
        {"1": probe1, "2": probe2, "3": probe3, "4": probe4,
         "5": probe5, "6": probe6}[w](npu)


if __name__ == "__main__":
    main()
