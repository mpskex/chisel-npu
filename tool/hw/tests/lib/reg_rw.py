"""
reg_rw.py — Wrapper around the remote reg_rw C tool for BAR register access.

Provides a clean Python API for reading/writing 32-bit registers in the
XDMA AXI-Lite user BAR or bypass BAR.
"""

from __future__ import annotations
from . import ssh, xdma

# ctrl_lite register map (byte offsets)
REG_CTRL     = 0x00   # [0]=start(W1S), [1]=done(R), [2]=busy(R)

# Discovery: which BAR device to use for ctrl_lite
# XDMA config: BAR0 → axist_bypass (128-bit) → split to user/bypass devices
# Try /dev/xdma0_user first (AXI-Lite user), then /dev/xdma0_bypass
CANDIDATE_DEVS = [
    "/dev/xdma0_user",
    "/dev/xdma0_bypass",
]
_SENTINEL = 0x0000_0000  # uninitialized/disconnected reads come back as 0


class BARError(RuntimeError):
    pass


class BARDevice:
    """
    Access the ctrl_lite register block through the XDMA bypass/user BAR.
    Automatically discovers which device node works.
    """

    def __init__(self, host: str, device: str | None = None):
        self.host = host
        self._dev = device or self._discover()

    @property
    def device(self) -> str:
        return self._dev

    def _discover(self) -> str:
        """Try candidate devices and return the first that doesn't error."""
        xd = xdma.XDMADevice(self.host)
        for dev in CANDIDATE_DEVS:
            try:
                xd.reg_read(dev, 0x0)
                return dev
            except Exception:
                continue
        raise BARError(
            f"Cannot access ctrl_lite via any of {CANDIDATE_DEVS}. "
            "Check that the XDMA driver is loaded and ctrl_lite is mapped."
        )

    def read(self, offset: int) -> int:
        """Read 32-bit register at *offset*."""
        return xdma.XDMADevice(self.host).reg_read(self._dev, offset)

    def write(self, offset: int, value: int) -> None:
        """Write 32-bit register at *offset*."""
        xdma.XDMADevice(self.host).reg_write(self._dev, offset, value)

    # ── ctrl_lite helpers ───────────────────────────────────────────────────

    def ctrl_read(self) -> int:
        return self.read(REG_CTRL)

    @property
    def is_done(self) -> bool:
        return bool((self.ctrl_read() >> 1) & 1)

    @property
    def is_busy(self) -> bool:
        return bool((self.ctrl_read() >> 2) & 1)

    def kick_start(self) -> None:
        """Assert start=1 to trigger the NPU FSM."""
        self.write(REG_CTRL, 0x1)

    def wait_done(self, timeout_s: int = 10) -> bool:
        """Poll until done=1 or *timeout_s* seconds elapsed. Returns True on success."""
        import time
        deadline = time.monotonic() + timeout_s
        while time.monotonic() < deadline:
            if self.is_done:
                return True
            time.sleep(0.01)
        return False
