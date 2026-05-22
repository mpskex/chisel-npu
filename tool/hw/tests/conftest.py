"""
conftest.py — pytest fixtures for xc7k480t FPGA hardware tests.

Usage:
    python -m pytest tool/hw/tests/ -v
    python -m pytest tool/hw/tests/ --fpga-host myboard --bitstream path/to/top.bit

Environment variables (override CLI flags):
    FPGA_HOST        SSH alias for the FPGA box        (default: fpga)
    BITSTREAM        Path to .bit file on dev host     (default: repo impl_1 output)
    SKIP_PROGRAM     If set to '1', skip JTAG programming
    XDMA_TOOLS_DIR   Remote path to compiled xdma tools
"""

from __future__ import annotations
import os
import subprocess
from pathlib import Path

import pytest

from .lib import ssh, xdma, reg_rw

# ── Default paths ────────────────────────────────────────────────────────────

_REPO_ROOT = Path(__file__).resolve().parents[3]
_DEFAULT_BITSTREAM = (
    _REPO_ROOT
    / "ip/vivado/xc7k480t/npu_fpga/npu_fpga.runs/impl_1/top_npu.bit"
)
_DEFAULT_HOST = "fpga"


# ── CLI options ───────────────────────────────────────────────────────────────

def pytest_addoption(parser: pytest.Parser) -> None:
    parser.addoption(
        "--fpga-host",
        default=os.environ.get("FPGA_HOST", _DEFAULT_HOST),
        help="SSH target for the FPGA box (default: fpga)",
    )
    parser.addoption(
        "--bitstream",
        default=os.environ.get("BITSTREAM", str(_DEFAULT_BITSTREAM)),
        help="Local path to the .bit file to program",
    )
    parser.addoption(
        "--skip-program",
        action="store_true",
        default=bool(os.environ.get("SKIP_PROGRAM")),
        help="Skip JTAG programming (assume FPGA already has the right bitstream)",
    )


# ── Session-scoped fixtures ───────────────────────────────────────────────────

@pytest.fixture(scope="session")
def fpga_host(request: pytest.FixtureRequest) -> str:
    return request.config.getoption("--fpga-host")


@pytest.fixture(scope="session")
def bitstream_path(request: pytest.FixtureRequest) -> Path:
    p = Path(request.config.getoption("--bitstream"))
    if not p.exists():
        pytest.skip(f"Bitstream not found: {p}. Build it first with Vivado.")
    return p


@pytest.fixture(scope="session", autouse=True)
def fpga_reachable(fpga_host: str) -> None:
    """Abort entire session if FPGA box is not reachable."""
    if not ssh.is_reachable(fpga_host):
        pytest.exit(
            f"FPGA box '{fpga_host}' is not reachable via SSH. "
            "Check the connection and retry.",
            returncode=3,
        )


@pytest.fixture(scope="session")
def xdma_dev(fpga_host: str) -> xdma.XDMADevice:
    """Session-scoped handle to the XDMADevice on the FPGA box."""
    dev = xdma.XDMADevice(fpga_host)
    dev.assert_nodes_present(minimum=4)
    return dev


@pytest.fixture(scope="session")
def bar(fpga_host: str) -> reg_rw.BARDevice:
    """Session-scoped handle to the ctrl_lite BAR (NPU bitstream only).
    Skips if neither xdma0_user nor xdma0_bypass responds without error."""
    try:
        return reg_rw.BARDevice(fpga_host)
    except reg_rw.BARError as e:
        pytest.skip(f"BAR not accessible (reference design in flash?): {e}")
