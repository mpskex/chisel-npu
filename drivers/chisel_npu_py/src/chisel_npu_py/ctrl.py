"""CtrlLite — NPU control register protocol.

The ctrl_lite register lives inside the native module at a fixed BAR
offset; Python only sees the bit protocol:

  bit 0  start  W    write 1 → one-cycle start pulse (self-clears, edge-triggered)
  bit 1  done   RO   latched 1 when the NPU DMA master finishes (cleared on next start)
  bit 2  busy   RO   level; 1 while the NPU DMA master FSM is active
"""

from __future__ import annotations

import time

from . import consts


class CtrlLite:
    def __init__(self, dev):
        self._dev = dev

    def read(self) -> int:
        return self._dev.ctrl_read()

    def write(self, value: int) -> None:
        self._dev.ctrl_write(value)

    def kick(self) -> None:
        """Write start=1 to trigger the NPU DMA+MMA cycle."""
        self.write(1 << consts.CTRL_START_BIT)

    @property
    def is_done(self) -> bool:
        return bool((self.read() >> consts.CTRL_DONE_BIT) & 1)

    @property
    def is_busy(self) -> bool:
        return bool((self.read() >> consts.CTRL_BUSY_BIT) & 1)

    def wait_done(self, timeout_s: float = 10.0) -> bool:
        """Poll until done=1 or *timeout_s* seconds elapse. Returns True on success."""
        deadline = time.monotonic() + timeout_s
        while time.monotonic() < deadline:
            if self.is_done:
                return True
            time.sleep(0.01)
        return False
