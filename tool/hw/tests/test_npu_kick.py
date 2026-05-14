"""
test_npu_kick.py — Attempt to trigger the NPU DMA FSM and observe done=1.

⚠ PHASE 2 — XFAIL (deferred)

KNOWN LIMITATION:
  The current RTL routes XDMA M_AXI → MIG C0 (host DMA bank)
  and the NPU DMA master → MIG C1 (NPU data bank).
  There is NO host-driven data path to MIG C1.

  The NPU FSM (start → done) CAN be triggered, but the matrices it reads
  from C1 are uninitialised. Results are non-deterministic and the test
  is expected to timeout or return garbage values.

  To enable this test, one of the following RTL changes is required:
  1. An AXI interconnect to share access to both C0 and C1 from
     both XDMA M_AXI and the NPU DMA master.
  2. A second XDMA M_AXI port routed directly to MIG C1.

The test is kept as xfail to document the gap and provide a starting
point for Phase 2 integration.
"""

import pytest
from .lib import reg_rw


@pytest.mark.hw
@pytest.mark.xfail(
    reason=(
        "Phase 2 deferred: no host-driven data path to MIG C1 (NPU input bank). "
        "See test_npu_kick.py module docstring for required RTL changes."
    ),
    strict=False,
)
def test_npu_start_done(bar: reg_rw.BARDevice) -> None:
    """
    Write start=1, poll done=1 within 1 second.
    XFAIL: NPU input matrices (MIG C1) are uninitialised from host side.
    """
    # Ensure not already running
    assert not bar.is_busy, "NPU FSM unexpectedly busy before test"

    # Trigger
    bar.kick_start()

    # Poll for done with short timeout (uninitialised C1 data, expect timeout)
    done = bar.wait_done(timeout_s=2)
    assert done, (
        "NPU FSM did not assert done=1 within 2 seconds. "
        "This is expected until Phase 2 (host→C1 data path) is implemented."
    )
