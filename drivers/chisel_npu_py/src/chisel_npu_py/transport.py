"""Host transport interface for the NPU driver core.

The platform package implements this protocol (XDMA on Xilinx boards, UIO,
CXL, ...).  The IP driver core never imports a board module.
"""

from __future__ import annotations

from typing import Protocol


class NpuTransport(Protocol):
    def reg_read(self, offset: int) -> int:
        ...

    def reg_write(self, offset: int, value: int) -> None:
        ...

    def mem_read(self, addr: int, nbytes: int) -> bytes:
        ...

    def mem_write(self, addr: int, data: bytes) -> None:
        ...

    def wait_irq(self, timeout_s: float) -> bool:
        ...
