"""ChiselNPU — high-level NPU facade.

Orchestrates a full MMALU compute cycle on top of XDMADevice + CtrlLite:

    stage A/B/ACCUM (native module knows the DDR addresses)
        → kick (ctrl_lite start)
        → wait for done
        → read OUT back

The Python side only ever moves buffers; every address lives in the
native module.
"""

from __future__ import annotations

from typing import Optional

import numpy as np

from .backend import XDMADevice
from .ctrl import CtrlLite
from .errors import NPUError, NPUTimeoutError


class ChiselNPU:
    def __init__(
        self,
        dev: Optional[XDMADevice] = None,
        ctrl: Optional[CtrlLite] = None,
        timeout_s: float = 2.0,
    ):
        self.dev = dev if dev is not None else XDMADevice()
        self.ctrl = ctrl if ctrl is not None else CtrlLite(self.dev)
        self.timeout_s = timeout_s
        out_slot = self.dev.staging_map()["OUT"]
        self._out_nbytes = int(out_slot[1])

    # ── step helpers ────────────────────────────────────────────────────────

    def stage_operands(self, A, B, ACCUM) -> None:
        """Write A (int8[K]), B (int8[K]) and ACCUM (int32[K]) to DDR.

        Sizes are validated by the native module against the staging table.
        """
        self.dev.write_staged("A", A)
        self.dev.write_staged("B", B)
        self.dev.write_staged("ACCUM", ACCUM)

    def kick_and_wait(self, timeout_s: Optional[float] = None) -> None:
        """Kick the NPU FSM and block until done (raises on timeout)."""
        t = timeout_s if timeout_s is not None else self.timeout_s
        if self.ctrl.is_busy:
            raise NPUError("NPU FSM unexpectedly busy before kick")
        self.ctrl.kick()
        if not self.ctrl.wait_done(timeout_s=t):
            raise NPUTimeoutError(
                f"NPU did not assert done within {t} s "
                "(DMA master or MMALU stuck?)"
            )

    def collect_out(self, OUT: Optional[np.ndarray] = None) -> np.ndarray:
        """Read the OUT operand from DDR. Fills *OUT* if given, else allocates int32[K]."""
        if OUT is None:
            OUT = np.empty(self._out_nbytes // 4, dtype=np.int32)
        self.dev.read_staged("OUT", OUT)
        return OUT

    # ── whole cycle ─────────────────────────────────────────────────────────

    def mmalu(
        self,
        A,
        B,
        ACCUM,
        OUT: Optional[np.ndarray] = None,
        timeout_s: Optional[float] = None,
    ) -> np.ndarray:
        """One MMALU cycle: stage → kick → wait done → read OUT.

        *A*, *B*: int8[K] buffers; *ACCUM*: int32[K] buffer (any buffer-like
        accepted). Returns the int32[K] result (into *OUT* if provided).
        """
        self.stage_operands(A, B, ACCUM)
        self.kick_and_wait(timeout_s)
        return self.collect_out(OUT)
