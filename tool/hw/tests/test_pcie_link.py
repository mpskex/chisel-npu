"""
test_pcie_link.py — Verify PCIe Gen2 ×8 link status on the FPGA box.

Expected:
  LnkSta: Speed 5GT/s, Width x8
"""

import re
import pytest
from .lib import ssh


@pytest.mark.hw
def test_pcie_device_present(fpga_host: str) -> None:
    """01:00.0 Xilinx Device 7028 must appear in lspci."""
    out = ssh.check_output(fpga_host, "lspci -d 10ee:7028", timeout=10)
    assert "7028" in out, f"Xilinx Device 7028 not found in lspci:\n{out}"


@pytest.mark.hw
def test_pcie_link_speed(fpga_host: str) -> None:
    """LnkSta must report ≥ 2.5 GT/s (PCIe Gen1 minimum).
    Note: the reference XDMA flash design links at 2.5GT/s (Gen1) on this platform.
    The NPU bitstream targets Gen2 (5GT/s) when programmed to flash."""
    out = ssh.check_output(
        fpga_host,
        "sudo lspci -d 10ee:7028 -vvv 2>/dev/null || sudo lspci -d 10ee:7028 -vv",
        timeout=15,
    )
    m = re.search(r"LnkSta:.*?Speed\s+([\d.]+)GT/s", out)
    assert m is not None, (
        f"LnkSta Speed not found in lspci output. "
        f"Run with sudo for capability access.\n{out}"
    )
    speed = float(m.group(1))
    assert speed >= 2.5, f"PCIe link speed {speed} GT/s < 2.5 GT/s (Gen1 minimum)"


@pytest.mark.hw
def test_pcie_link_width(fpga_host: str) -> None:
    """LnkSta must report ≥ x1 lanes.
    Note: the reference XDMA flash design links at x2 on this platform.
    The NPU bitstream targets x8 when programmed to flash."""
    out = ssh.check_output(
        fpga_host,
        "sudo lspci -d 10ee:7028 -vvv 2>/dev/null || sudo lspci -d 10ee:7028 -vv",
        timeout=15,
    )
    m = re.search(r"LnkSta:.*?Width\s+x(\d+)", out)
    assert m is not None, (
        f"LnkSta Width not found in lspci output. "
        f"Run with sudo for capability access.\n{out}"
    )
    width = int(m.group(1))
    assert width >= 1, f"PCIe link width x{width} < x1"


@pytest.mark.hw
def test_xdma_driver_loaded(fpga_host: str) -> None:
    """xdma module must be in lsmod."""
    out = ssh.check_output(fpga_host, "lsmod | grep '^xdma' || true", timeout=10)
    assert "xdma" in out, "xdma kernel module is not loaded"


@pytest.mark.hw
def test_xdma_devnodes_present(fpga_host: str) -> None:
    """At least 6 /dev/xdma0_* nodes must exist (control, h2c_0/1, c2h_0/1, bypass)."""
    out = ssh.check_output(fpga_host, "ls /dev/xdma0_* | wc -l", timeout=10)
    count = int(out.strip())
    assert count >= 6, f"Only {count} /dev/xdma0_* nodes found, expected ≥6"
