#!/usr/bin/env python3
"""Instruction-fetch size sweep: program length and reduce-round scaling.

Two shapes are measured on the FPGA host:

  nop-<n>     ``n`` back-to-back NOPs, i.e. a fetch-only program.  The
              cycles/instruction slope shows whether instruction supply is
              size independent (a flat slope) or degrades with program length.
  round-<r>   two vector loads followed by ``r`` reduce rounds of K MMAs +
              mma.last + one vector store.  The MACs/cycle column shows how
              much of the array throughput survives with a long dependency
              chain and a per-round store.

For every case the ``FETCH_STATS`` ctrl register (0x10) is sampled before and
after the run; its high half counts prefetch fills and its low half counts
demand misses.  The counters are cumulative since FPGA configuration (never
cleared per session), so ``fills`` is the sum of both deltas modulo 65536.

Usage (on the FPGA host, after ``make py-deploy``):

    cd ~/chisel_npu_py && .venv/bin/python tool/fetch_sweep.py
"""

from __future__ import annotations

from contextlib import contextmanager

import numpy as np

from chisel_npu_py import ChiselNPU, config, isa

# Fetch-only program lengths.  65535 is the largest legal program (the pc is
# 16 bits); it fills all 4096 instruction lines of the CODE window.
NOP_SIZES = (1024, 8192, 32768, 60000, 65535)

# Reduce-round counts; 2 + r*(K+2) instructions each.
ROUND_COUNTS = (1, 4, 16, 64, 256)

# CODE window in bytes / words (the engine's fetch address space).  The
# engine's program counter is 16 bits, so the last instruction sits at
# pc=65535 and 65535 words is the largest legal program.
CODE_BYTES = config.default_config().sections["CODE"].window
CODE_WORDS = CODE_BYTES // 4
MAX_WORDS = CODE_WORDS - 1


# ── fetch statistics ────────────────────────────────────────────────────────

def read_fetch_stats(npu: ChiselNPU) -> tuple[int, int]:
    """(prefetch fills, demand misses) from the FETCH_STATS ctrl register."""
    v = npu.dev.ctrl_read_reg(npu.cfg.ctrl["FETCH_STATS"])
    return (v >> 16) & 0xFFFF, v & 0xFFFF


def fills_since(before: tuple[int, int], after: tuple[int, int]) -> int:
    """Prefetch + miss delta, modulo 65536 (the counters are 16 bits each)."""
    prefetch = (after[0] - before[0]) & 0xFFFF
    miss = (after[1] - before[1]) & 0xFFFF
    return (prefetch + miss) & 0xFFFF


@contextmanager
def _full_code_window(npu: ChiselNPU):
    """Allow a max-length program to occupy the whole CODE window.

    ``ChiselNPU.run`` stages the program plus one 64-byte guard beat past the
    final instruction line (the fetch may read one beat beyond the last
    useful line).  At 65535 words the rounded image is exactly the 256 KiB
    window and that guard beat falls outside it, so the native staging check
    rejects the write.  The final instruction line is already complete (the
    64-byte rounding zero-fills it), and the prefetch of the line past the end
    is suppressed by the engine (``fillNext < lastLine``), so dropping the
    all-zero guard tail is safe.  Only engages when the overrun is exactly
    that trailing guard beat; anything else still fails loudly.
    """
    orig_write_staged = npu.dev.write_staged

    def write_staged(name, data, offset=0):
        if name == "CODE" and offset == 0 and len(data) > CODE_BYTES:
            tail = data[CODE_BYTES:]
            if len(tail) > 64 or any(tail):
                raise ValueError(
                    f"CODE image {len(data)} B overruns the {CODE_BYTES} B "
                    "window by more than the guard beat")
            data = data[:CODE_BYTES]
        return orig_write_staged(name, data, offset)

    npu.dev.write_staged = write_staged
    try:
        yield npu
    finally:
        npu.dev.write_staged = orig_write_staged


# ── the two program shapes ──────────────────────────────────────────────────

def run_nops(npu: ChiselNPU, n: int) -> dict:
    """Run ``n`` NOPs and return the perf dict plus the fetch-fill delta."""
    if not 0 < n <= MAX_WORDS:
        raise ValueError(
            f"{n} words exceeds the {MAX_WORDS}-word program limit (16-bit pc)")
    before = read_fetch_stats(npu)
    with _full_code_window(npu):
        perf = dict(npu.run([isa.NOP] * n)["perf"])
    perf["fills"] = fills_since(before, read_fetch_stats(npu))
    return perf


def run_rounds(npu: ChiselNPU, rounds: int) -> dict:
    """Two loads + ``rounds`` reduce rounds (K MMAs + mma.last + one store)."""
    k = npu.cfg.K
    # All-ones operands: each round's accumulator stays within int32 and the
    # MACs/cycle column stays comparable across round counts.
    a = np.ones(k, dtype=np.int8)
    b = np.ones(k, dtype=np.int8)
    words = [isa.vle8(4, isa.SECT_A, 0), isa.vle8(8, isa.SECT_B, 0)]
    for _ in range(rounds):
        words += [isa.mma(0, 4, 8, 0) for _ in range(k)]
        words += [isa.mma_last(0, 0, 0, 0), isa.vse32(0, isa.SECT_OUT, 0)]
    if len(words) > CODE_WORDS:
        raise ValueError(
            f"{rounds} rounds = {len(words)} words exceeds the CODE window")
    before = read_fetch_stats(npu)
    perf = dict(npu.run(words, memories={"A": a, "B": b})["perf"])
    perf["fills"] = fills_since(before, read_fetch_stats(npu))
    return perf


# ── report ──────────────────────────────────────────────────────────────────

HEADER = f"{'case':<12}{'instr':>8}{'cycles':>12}{'cyc/instr':>11}" \
         f"{'fills':>8}{'ipc':>9}{'macs/cyc':>10}"


def print_row(case: str, perf: dict) -> None:
    n = perf["instructions"]
    cycles = perf["cycles"]
    cyc_per_instr = (cycles / n) if n else 0.0
    print(
        f"{case:<12}{n:>8}{cycles:>12}{cyc_per_instr:>11.3f}"
        f"{perf['fills']:>8}{perf['ipc']:>9.3f}{perf['macs_per_cycle']:>10.1f}",
        flush=True,
    )


def main() -> int:
    npu = ChiselNPU(cfg=config.default_config())
    print(f"K={npu.cfg.K} N={npu.cfg.N} "
          f"CODE={CODE_BYTES} B ({CODE_WORDS} words, max program "
          f"{MAX_WORDS} words)")
    print(HEADER, flush=True)
    for n in NOP_SIZES:
        print_row(f"nop-{n}", run_nops(npu, n))
    for rounds in ROUND_COUNTS:
        print_row(f"round-{rounds}", run_rounds(npu, rounds))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
