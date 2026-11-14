"""Hardware performance test: instruction-per-cycle counters.

`ChiselNPU.run()` reads the ctrl_lite perf registers latched at done:
  0x40 CYCLES   session cycles
  0x44 MACS     useful K^2 MACs (excluding mma.last)
  0x1C DBG_MMA  accepted MMA feeds (including mma.last)
and returns them in ``result["perf"]``:
  instructions, cycles, macs, mma_feeds, ipc, feeds_per_cycle, macs_per_cycle.

``ipc`` is literal instructions/cycle; ``feeds_per_cycle`` is the repo's
drain-inclusive metric used by CrossMatmulPipelineSpec.
"""

from __future__ import annotations

import numpy as np
import pytest

from chisel_npu_py import ChiselNPU, isa


@pytest.mark.hw
def test_ipc_perf_counters(npu: ChiselNPU):
    cfg = npu.cfg
    k = cfg.K
    a = np.arange(1, k + 1, dtype=np.int8)
    b = np.arange(100, 100 + k, dtype=np.int8)
    # One reduce round: K feeds + mma.last + one vse (the EnginePerfCounterSpec
    # shape, so the expected MAC/feed counts are exact).
    words = [
        isa.vle8(4, isa.SECT_A, 0),
        isa.vle8(8, isa.SECT_B, 0),
    ] + [isa.mma(0, 4, 8, 0) for _ in range(k)] + [
        isa.mma_last(0, 0, 0, 0),
        isa.vse32(0, isa.SECT_OUT, 0),
    ]
    result = npu.run(words, memories={"A": a, "B": b})
    perf = result["perf"]

    assert perf["instructions"] == len(words)
    assert perf["cycles"] > 0, "session cycle counter did not advance"
    assert perf["mma_feeds"] == k + 1, (perf["mma_feeds"], k + 1)
    assert perf["macs"] == k * k * k, (perf["macs"], k * k * k)
    assert 0.0 < perf["ipc"] <= 1.0, perf["ipc"]
    assert 0.0 < perf["feeds_per_cycle"] <= 1.0, perf["feeds_per_cycle"]
    assert perf["macs_per_cycle"] > 0.0

    # A second identical session must report the same per-session feed count.
    # Regression: 0x1C DBG_MMA used to be a free-running counter, so it grew by
    # K+1 on every run and only matched on a freshly programmed FPGA.
    rerun = npu.run(words, memories={"A": a, "B": b})["perf"]
    assert rerun["mma_feeds"] == k + 1, (rerun["mma_feeds"], k + 1)
    assert rerun["macs"] == k * k * k, (rerun["macs"], k * k * k)
    assert rerun["cycles"] > 0

    print(
        f"[hw perf] instr={perf['instructions']} cycles={perf['cycles']} "
        f"ipc={perf['ipc']:.3f} feeds/cyc={perf['feeds_per_cycle']:.3f} "
        f"macs/cyc={perf['macs_per_cycle']:.1f} "
        f"(macs={perf['macs']} feeds={perf['mma_feeds']})"
    )
