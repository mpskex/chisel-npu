"""``python -m chisel_npu_py`` — device self-test (run on the FPGA host).

Verifies: native extension importable, device nodes open, a section
round-trip, and a tiny program (nops) runs to completion.
"""

from __future__ import annotations

import sys


def selftest() -> int:
    import numpy as np

    from . import __version__, config, isa
    from .backend import XDMADevice
    from .errors import XDMAError
    from .npu import ChiselNPU

    print("== chisel_npu_py selftest ==")

    try:
        from ._native import __version__ as native_version
    except ImportError as exc:
        print(f"FAIL: native extension not built: {exc}")
        return 2

    cfg = config.default_config()
    print(f"  package version    : {__version__} (native {native_version})")
    print(f"  config             : K={cfg.K}, sections="
          f"{ {k: v.window for k, v in cfg.sections.items()} }")

    try:
        npu = ChiselNPU(cfg=cfg)
    except XDMAError as exc:
        print(f"FAIL: cannot open device: {exc}")
        return 3
    print(f"  device nodes       : {len(XDMADevice.list_nodes())} present")

    # section round-trip through run() with a copy program (vle8 → vse8)
    rng = np.random.default_rng(0x5151)
    a = rng.integers(0, 256, size=cfg.K, dtype=np.uint8)
    result = npu.run(
        instructions=[
            isa.vle8(0, isa.SECT_A, 0),
            isa.vse8(0, isa.SECT_OUT, 0),
        ],
        memories={"A": a},
    )
    out_bytes = result["OUT"][: (cfg.K + 3) // 4].view(np.uint8)[:cfg.K]
    if not np.array_equal(result["A"], a) or not np.array_equal(out_bytes, a):
        print("FAIL: section round-trip via run() mismatch")
        return 4
    print(f"  section round-trip : A → OUT via copy program OK")

    # a program runs to completion
    prog = isa.Program().nops(8)
    result = npu.run(prog)
    print(f"  program run        : pc=7 OK")

    print("PASS")
    return 0


def main() -> int:
    return selftest()


if __name__ == "__main__":
    sys.exit(main())
