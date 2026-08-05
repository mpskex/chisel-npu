"""``python -m chisel_npu_py`` — device self-test (run on the FPGA host).

Verifies, in order:
  1. the compiled native extension is importable,
  2. the XDMA device nodes open,
  3. the ctrl_lite control word is readable,
  4. a staged round-trip (write "OUT" → read "OUT") through the pybind11
     boundary is byte-exact.

Exit code 0 = all good.  No DDR addresses appear here — the native module
owns them.
"""

from __future__ import annotations

import sys


def selftest() -> int:
    import numpy as np

    from .errors import XDMAError

    print("== chisel_npu_py selftest ==")

    try:
        from ._native import __version__ as native_version
    except ImportError as exc:
        print(f"FAIL: native extension not built: {exc}")
        print("      Build on the FPGA host:  pip install .")
        return 2

    from . import __version__, consts
    from .backend import XDMADevice
    from .ctrl import CtrlLite

    print(f"  package version    : {__version__} (native {native_version})")
    print(f"  device prefix      : /dev/xdma0")
    print(f"  staged operands    : { {op: None for op in consts.OPERANDS} }")

    try:
        dev = XDMADevice()
    except XDMAError as exc:
        print(f"FAIL: cannot open device: {exc}")
        return 3
    print(f"  device nodes       : {len(XDMADevice.list_nodes())} present")
    sizes = {op: dev.operand_size(op) for op in consts.OPERANDS}
    print(f"  operand sizes (B)  : {sizes}")

    ctrl = CtrlLite(dev)
    idle = ctrl.read()
    print(f"  ctrl_lite read     : 0x{idle:08X} (expect 0x0 or 0x2)")
    if idle == 0xFFFF_FFFF:
        print("FAIL: ctrl_lite read returned all-ones (PCIe/BAR error)")
        return 4

    size = sizes["OUT"]
    rng = np.random.default_rng(0x5151)
    data = rng.integers(0, 256, size=size, dtype=np.uint8)
    try:
        dev.write_staged("OUT", data)
        got = np.empty(size, dtype=np.uint8)
        dev.read_staged("OUT", got)
    except Exception as exc:
        print(f"FAIL: staged round-trip failed: {exc}")
        return 5
    if not np.array_equal(got, data):
        print("FAIL: staged round-trip data mismatch")
        return 6
    print(f"  staged round-trip  : 'OUT' {size} B write→read OK")

    print("PASS")
    return 0


def main() -> int:
    return selftest()


if __name__ == "__main__":
    sys.exit(main())
