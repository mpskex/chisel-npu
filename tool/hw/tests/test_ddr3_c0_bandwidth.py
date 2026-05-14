"""
test_ddr3_c0_bandwidth.py — Measure XDMA DMA bandwidth to DDR3 C0.

Runs the pre-built `performance` tool on the FPGA box.
Expected: ≥ 4 GB/s sustained H2C (limited by PCIe Gen2 ×8 ≈ 4 GB/s theoretical).
"""

import pytest
from .lib.xdma import XDMADevice, XDMAError

# Minimum acceptable bandwidth thresholds.
# Reference XDMA design links at Gen1 x2 (theoretical ~0.5 GB/s).
# NPU bitstream targets Gen2 x8 (~4 GB/s) when programmed to flash.
# Set floor at 0.05 GB/s to catch complete failures.
MIN_H2C_GBPS = 0.05  # GB/s host-to-card
MIN_C2H_GBPS = 0.05  # GB/s card-to-host
TRANSFER_MB   = 128  # MB per measurement


@pytest.mark.hw
@pytest.mark.slow
def test_h2c_bandwidth(xdma_dev: XDMADevice) -> None:
    """H2C (host→FPGA) bandwidth must be ≥ {MIN_H2C_GBPS} GB/s."""
    try:
        bw = xdma_dev.measure_bandwidth_gbps(
            channel=0, transfer_size_mb=TRANSFER_MB, direction="h2c"
        )
    except XDMAError as e:
        pytest.skip(f"performance tool failed: {e}")
    assert bw >= MIN_H2C_GBPS, (
        f"H2C bandwidth {bw:.2f} GB/s < {MIN_H2C_GBPS} GB/s minimum. "
        "Check PCIe link width (should be x8) and MIG C0 calibration."
    )


@pytest.mark.hw
@pytest.mark.slow
def test_c2h_bandwidth(xdma_dev: XDMADevice) -> None:
    """C2H (FPGA→host) bandwidth must be ≥ {MIN_C2H_GBPS} GB/s."""
    try:
        bw = xdma_dev.measure_bandwidth_gbps(
            channel=0, transfer_size_mb=TRANSFER_MB, direction="c2h"
        )
    except XDMAError as e:
        pytest.skip(f"performance tool failed: {e}")
    assert bw >= MIN_C2H_GBPS, (
        f"C2H bandwidth {bw:.2f} GB/s < {MIN_C2H_GBPS} GB/s minimum."
    )
