"""``python -m chisel_npu_py`` — device self-test (run on the FPGA host).

Verifies, in order:
  1. the compiled native extension is importable,
  2. the XDMA device nodes open,
  3. the ctrl_lite register is readable (idle),
  4. a 1 KB DDR3 loopback through the native module round-trips.

Exit code 0 = all good.
"""

from __future__ import annotations

import sys


def selftest(loopback_addr: int = 0x0, loopback_size: int = 1024) -> int:
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
    print(f"  staging table      : {consts.STAGING}")

    try:
        dev = XDMADevice()
    except XDMAError as exc:
        print(f"FAIL: cannot open device: {exc}")
        return 3
    print(f"  device nodes       : {len(XDMADevice.list_nodes())} present")
    print(f"  native staging map : {dev.staging_map()}")

    ctrl = CtrlLite(dev)
    idle = ctrl.read()
    print(f"  ctrl_lite read     : 0x{idle:08X} (expect 0x0 or 0x2)")
    if idle == 0xFFFF_FFFF:
        print("FAIL: ctrl_lite read returned all-ones (PCIe/BAR error)")
        return 4

    rng = np.random.default_rng(0x5151)
    data = rng.integers(0, 256, size=loopback_size, dtype=np.uint8)
    try:
        dev.dma_write(loopback_addr, data)
        readback = dev.dma_read(loopback_addr, loopback_size)
    except Exception as exc:
        print(f"FAIL: loopback transfer failed: {exc}")
        return 5
    if not np.array_equal(readback, data):
        print("FAIL: 1 KB DDR3 loopback data mismatch")
        return 6
    print(f"  DDR3 loopback      : {loopback_size} bytes @ 0x{loopback_addr:X} OK")

    print("PASS")
    return 0


def main() -> int:
    return selftest()


if __name__ == "__main__":
    sys.exit(main())
