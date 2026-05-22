"""
test_npu_kick.py — Trigger the NPU DMA FSM and observe done=1.

V10 (post xbar+npu_subsys) actually wires the MMALU and exposes both MIG banks
to both XDMA and the NPU master. The DMA master reads its operands from MIG C0
at 0x4000_0000 (DEFAULT_BASE_A) and writes the result back to 0x4000_0400.

This test is the bare smoke check that the FSM cycle completes:
    start=1 → busy=1 → (62-cycle MMALU drain) → done=1.

We do NOT validate the OUT contents here — that is the job of
test_mmalu_compute.py, which initialises the operand region deterministically.
This test simply asserts the round-trip end-to-end and the done bit comes
back inside a generous timeout.
"""

import pytest
from .lib import reg_rw


@pytest.mark.hw
def test_npu_start_done(bar: reg_rw.BARDevice) -> None:
    """
    Write start=1, poll done=1 within 1 second.

    With V10 wiring, the MMALU is actually instantiated and io_clct pulses
    on cycle 2K-1 (= 63 at K=32) after the busy edge.  At 200 MHz that is
    ~315 ns, so 1 s is wildly conservative — used to absorb XDMA register
    access latency over PCIe.
    """
    # Ensure not already running
    assert not bar.is_busy, "NPU FSM unexpectedly busy before test"

    # Trigger
    bar.kick_start()

    # Poll for done
    done = bar.wait_done(timeout_s=1)
    assert done, (
        "NPU FSM did not assert done=1 within 1 s. "
        "Possible causes: MMALU not wired (pre-V10 bitstream), MIG C0 stuck, "
        "or DMA master FSM hung."
    )
