"""NPUConfig — single source of truth for the driver.

Every address, window size, register offset and tiling parameter lives in
one frozen dataclass.  Both the Python layer (program builders, staging,
ctrl protocol) and the native pybind11 module (validated section bases and
register offsets) consume the same object — nothing is hardcoded elsewhere.

``default_config()`` describes the current silicon-verified K=16 bitstream.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Mapping


@dataclass(frozen=True)
class Section:
    """A named DDR staging region owned by the engine.

    ``base`` is the absolute AXI address; ``window`` is the byte range the
    engine can address through its 12-bit section offsets.
    """

    base: int
    window: int


# ctrl_lite register offsets (byte addresses into the bypass BAR window)
CTRL = "CTRL"                # bit0 start (W) / bit1 done (RO) / bit2 busy (RO)
FRAMES = "FRAMES"            # config, reserved
STATUS = "STATUS"            # illegal@31 | frames_done[30:16] | pc[15:0]
ERR_INFO = "ERR_INFO"        # faulting instruction word
FETCH_STATS = "FETCH_STATS"  # prefetches[31:16] | misses[15:0]
PROG_LEN = "PROG_LEN"        # instruction count (words)
DBG = "DBG"                  # winState[1:0] | clct captured[15:0]
DBG_MMA = "DBG_MMA"          # mma instructions accepted

# ctrl_lite CTRL word bit positions
CTRL_START_BIT = 0
CTRL_DONE_BIT = 1
CTRL_BUSY_BIT = 2


@dataclass(frozen=True)
class NPUConfig:
    """Everything the driver needs to program the NPU."""

    K: int = 16                       # systolic array side / lanes per register
    N: int = 8                        # base lane width in bits
    sections: Mapping[str, Section] = field(default_factory=dict)
    ctrl: Mapping[str, int] = field(default_factory=dict)
    nop_wait: int = 40                # fixed nops after mma.last (2K+8)

    # ── section helpers ──────────────────────────────────────────────────────

    def section(self, name: str) -> Section:
        return self.sections[name]

    def usable(self, name: str) -> int:
        """Bytes of *name* that may be staged without clobbering the next
        section, capped at the section's own window.  The engine's section
        bases are adjacent, so a chunk staged at offset 0 must stay below
        the next section's base."""
        window = self.sections[name].window
        names = list(self.sections)
        idx = names.index(name)
        if idx + 1 >= len(names):
            return window
        return min(window, self.sections[names[idx + 1]].base - self.sections[name].base)

    def vector_bytes(self) -> int:
        """Bytes of one K-lane VX vector (K × N/8)."""
        return self.K * self.N // 8

    def col_bytes(self) -> int:
        """Bytes of one K×1 int32 output column."""
        return 4 * self.K

    def validate(self) -> None:
        if self.K <= 0 or self.N <= 0:
            raise ValueError("K and N must be positive")
        for name, sec in self.sections.items():
            if sec.base % 4 or sec.window % 4 or sec.window <= 0:
                raise ValueError(f"section '{name}' has a bad base/window")
        if self.nop_wait < 0:
            raise ValueError("nop_wait must be >= 0")


def default_config() -> NPUConfig:
    """The silicon-verified K=16 program-engine map (see NpuSections in
    npuProgramEngine.scala and the ctrl map in npuFrontend.scala)."""
    k = 16
    return NPUConfig(
        K=k,
        N=8,
        sections={
            "A": Section(0x4000_0000, 0x1000),   # int8, 4 KiB window
            "B": Section(0x4000_0400, 0x1000),   # int8, 4 KiB window
            "ACCUM": Section(0x4000_0800, 0x80), # int32[K], 128 B
            "OUT": Section(0x4000_0880, 0x1000), # int32, 4 KiB window
            "CODE": Section(0x4000_4000, 0x40000),  # program, 64K words
        },
        ctrl={
            CTRL: 0x00,
            FRAMES: 0x04,
            STATUS: 0x08,
            ERR_INFO: 0x0C,
            FETCH_STATS: 0x10,
            PROG_LEN: 0x14,
            DBG: 0x18,
            DBG_MMA: 0x1C,
        },
        nop_wait=2 * k + 8,
    )
