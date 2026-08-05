"""
xdma.py — Helpers for interacting with /dev/xdma0_* device nodes on the FPGA box.

All operations are executed remotely via SSH using the xdma userspace tools
(dma_to_device, dma_from_device, reg_rw) pre-built on the FPGA box.
"""

from __future__ import annotations
import os
import struct
import tempfile
from pathlib import Path

from . import ssh

# Default paths on the remote machine
TOOLS_DIR = "~/dma_ip_drivers/XDMA/linux-kernel/tools"
DEV_PREFIX = "/dev/xdma0"

# ctrl_lite register offsets (32-bit words at byte addresses)
CTRL_START_DONE_BUSY = 0x00  # [0]=start(W), [1]=done(R), [2]=busy(R)


class XDMAError(RuntimeError):
    pass


class XDMADevice:
    """Represents /dev/xdma0_* on a remote FPGA host."""

    def __init__(self, host: str):
        self.host = host

    # ── Device node helpers ─────────────────────────────────────────────────

    def list_nodes(self) -> list[str]:
        """Return list of /dev/xdma0_* device node names."""
        out = ssh.check_output(self.host, f"ls {DEV_PREFIX}_* 2>/dev/null || true")
        return [ln.strip() for ln in out.splitlines() if ln.strip()]

    def assert_nodes_present(self, minimum: int = 4) -> None:
        nodes = self.list_nodes()
        if len(nodes) < minimum:
            raise XDMAError(
                f"Expected ≥{minimum} /dev/xdma0_* nodes, got {len(nodes)}. "
                f"Is the xdma driver loaded?"
            )

    # ── BAR / control register access ──────────────────────────────────────

    def reg_read(self, device: str, offset: int) -> int:
        """
        Read a 32-bit register at *offset* from *device* (e.g. '/dev/xdma0_bypass').
        Returns the integer value.
        """
        cmd = f"sudo {TOOLS_DIR}/reg_rw {device} {hex(offset)} w"
        out = ssh.check_output(self.host, cmd, timeout=10)
        # reg_rw output: "Read 32-bit value at address 0x0 (0x7f...): 0xDEADBEEF"
        # Parse the LAST 0x token (the value), NOT the first (the address).
        import re as _re
        matches = _re.findall(r"0x[0-9a-fA-F]+", out)
        if matches:
            return int(matches[-1], 16)
        raise XDMAError(f"Cannot parse reg_rw output: {out!r}")

    def reg_write(self, device: str, offset: int, value: int) -> None:
        """Write a 32-bit register at *offset* in *device*."""
        cmd = f"sudo {TOOLS_DIR}/reg_rw {device} {hex(offset)} w {hex(value)}"
        ssh.run(self.host, cmd, timeout=10)

    # ── DMA transfers ───────────────────────────────────────────────────────

    def h2c(
        self, channel: int, ddr_offset: int, data: bytes, *, h2c_dev: str | None = None
    ) -> None:
        """
        Transfer *data* from host to FPGA DDR3 at *ddr_offset*.
        Uses dma_to_device on the remote side via a temp file.
        """
        dev = h2c_dev or f"{DEV_PREFIX}_h2c_{channel}"
        # Write data to a temp file on the remote
        remote_tmp = f"/tmp/xdma_h2c_{os.getpid()}.bin"
        with tempfile.NamedTemporaryFile(delete=False, suffix=".bin") as f:
            local_tmp = f.name
            f.write(data)
        try:
            ssh.copy_file(self.host, local_tmp, remote_tmp)
            cmd = (
                f"sudo {TOOLS_DIR}/dma_to_device"
                f" -d {dev}"
                f" -f {remote_tmp}"
                f" -s {len(data)}"
                f" -a {hex(ddr_offset)}"
            )
            ssh.run(self.host, cmd, timeout=60)
        finally:
            os.unlink(local_tmp)
            ssh.run(self.host, f"rm -f {remote_tmp}", check=False, timeout=5)

    def c2h(
        self, channel: int, ddr_offset: int, length: int, *, c2h_dev: str | None = None
    ) -> bytes:
        """
        Transfer *length* bytes from FPGA DDR3 at *ddr_offset* to host.
        Returns the raw bytes.
        """
        dev = c2h_dev or f"{DEV_PREFIX}_c2h_{channel}"
        remote_tmp = f"/tmp/xdma_c2h_{os.getpid()}.bin"
        try:
            cmd = (
                f"sudo {TOOLS_DIR}/dma_from_device"
                f" -d {dev}"
                f" -f {remote_tmp}"
                f" -s {length}"
                f" -a {hex(ddr_offset)}"
            )
            ssh.run(self.host, cmd, timeout=60)
            # Read back via base64 to avoid binary SSH transfer issues
            b64 = ssh.check_output(
                self.host, f"sudo base64 {remote_tmp}", timeout=30
            )
            import base64
            return base64.b64decode(b64)
        finally:
            ssh.run(self.host, f"rm -f {remote_tmp}", check=False, timeout=5)

    def loopback_check(
        self,
        channel: int = 0,
        ddr_offset: int = 0x0,
        size_bytes: int = 1024 * 1024,
        pattern: bytes | None = None,
    ) -> bool:
        """
        Write *size_bytes* of *pattern* to DDR3, read back, compare.
        Returns True if identical.
        """
        if pattern is None:
            # Default: repeating 0..255
            pattern = bytes(range(256)) * (size_bytes // 256 + 1)
        data = pattern[:size_bytes]
        self.h2c(channel, ddr_offset, data)
        readback = self.c2h(channel, ddr_offset, size_bytes)
        return data == readback

    # ── Performance measurement ─────────────────────────────────────────────

    def measure_bandwidth_gbps(
        self,
        channel: int = 0,
        transfer_size_mb: int = 128,
        direction: str = "h2c",
    ) -> float:
        """
        Run the XDMA performance tool and return measured bandwidth in GB/s.
        *direction*: 'h2c' or 'c2h'.
        """
        dev = f"{DEV_PREFIX}_{direction}_{channel}"
        size = transfer_size_mb * 1024 * 1024
        cmd = (
            f"sudo {TOOLS_DIR}/performance"
            f" -d {dev}"
            f" -s {size}"
            f" -c 1"
        )
        out = ssh.check_output(self.host, cmd, timeout=120)
        # performance tool format: "data rate ***** bytes length = N, rate = X.XXXXXX"
        # where rate is in GB/s
        import re as _re
        # Try "rate = X.XXX" format (XDMA v2020.2 tools)
        m = _re.search(r"rate\s*=\s*([\d.]+)", out)
        if m:
            return float(m.group(1))
        # Fallback: "X.XX GB/s" or "X.XX MB/s" format
        for line in out.splitlines():
            if "GB/s" in line or "Gb/s" in line or "MB/s" in line:
                parts = line.split()
                for i, p in enumerate(parts):
                    try:
                        val = float(p)
                        unit = parts[i + 1] if i + 1 < len(parts) else ""
                        if "GB/s" in unit:
                            return val
                        if "Gb/s" in unit:
                            return val / 8.0
                        if "MB/s" in unit:
                            return val / 1024.0
                    except (ValueError, IndexError):
                        continue
        raise XDMAError(f"Cannot parse performance output:\n{out}")
