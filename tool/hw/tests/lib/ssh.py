"""
ssh.py — lightweight SSH runner using subprocess + system ssh.
No paramiko/fabric dependency required.
"""

from __future__ import annotations
import subprocess
import shlex
from typing import Optional


class SSHError(RuntimeError):
    def __init__(self, cmd: str, returncode: int, stdout: str, stderr: str):
        self.cmd = cmd
        self.returncode = returncode
        self.stdout = stdout
        self.stderr = stderr
        super().__init__(
            f"SSH command failed (rc={returncode}):\n  cmd: {cmd}\n  stderr: {stderr.strip()}"
        )


def _identity_opt() -> list[str]:
    """Return ['-i', path] if SSH_IDENTITY env var is set."""
    import os
    path = os.environ.get("SSH_IDENTITY", "")
    if path and os.path.isfile(path):
        return ["-i", path]
    return []


def run(
    host: str,
    command: str,
    *,
    check: bool = True,
    timeout: int = 60,
    env: Optional[dict] = None,
) -> subprocess.CompletedProcess:
    """Run a command on *host* via ssh, return CompletedProcess."""
    ssh_cmd = [
        "ssh",
        "-o", "BatchMode=yes",
        "-o", "ConnectTimeout=10",
        "-o", "StrictHostKeyChecking=no",
    ] + _identity_opt() + [host, command]
    result = subprocess.run(
        ssh_cmd,
        capture_output=True,
        text=True,
        timeout=timeout,
    )
    if check and result.returncode != 0:
        raise SSHError(command, result.returncode, result.stdout, result.stderr)
    return result


def check_output(host: str, command: str, timeout: int = 30) -> str:
    """Run *command* on *host* and return stdout stripped."""
    return run(host, command, timeout=timeout).stdout.strip()


def is_reachable(host: str, timeout: int = 5) -> bool:
    """Return True if SSH to *host* succeeds within *timeout* seconds."""
    try:
        cmd = ["ssh", "-o", "BatchMode=yes", "-o", f"ConnectTimeout={timeout}",
               "-o", "StrictHostKeyChecking=no"] + _identity_opt() + [host, "true"]
        result = subprocess.run(cmd, capture_output=True, timeout=timeout + 2)
        return result.returncode == 0
    except Exception:
        return False


def copy_file(host: str, local_path: str, remote_path: str) -> None:
    """scp a local file to *host*:*remote_path*."""
    subprocess.run(
        ["scp", "-o", "BatchMode=yes", "-o", "StrictHostKeyChecking=no"]
        + _identity_opt() + [local_path, f"{host}:{remote_path}"],
        check=True,
    )


# ── SshConsole — orchestration-level SSH wrapper ──────────────────────────────

_BRIDGE       = "00:15.0"
_XDMA_VID_DID = "10ee:7028"
_XDMA_DRV_DIR = "~/dma_ip_drivers/XDMA/linux-kernel/xdma"


class SshConsole:
    """SSH-based console for FPGA bring-up operations.

    Provides the same logical interface as SerialConsole (run, reboot,
    pcie_present) but over SSH to a remote host.
    """

    def __init__(self, host: str, identity: str | None = None, user: str | None = None):
        self.host = host
        self.identity = identity
        self.user = user
        self._base_opts = ["-o", "BatchMode=yes",
                           "-o", "ConnectTimeout=10",
                           "-o", "StrictHostKeyChecking=no"]
        if identity:
            self._base_opts += ["-i", identity]

    def _target(self) -> str:
        return f"{self.user}@{self.host}" if self.user else self.host

    def run(self, command: str, timeout: int = 60, check: bool = True) -> str:
        """Run *command* on the FPGA host, return stdout."""
        ssh_cmd = ["ssh"] + self._base_opts + [self._target(), command]
        result = subprocess.run(ssh_cmd, capture_output=True, text=True,
                                timeout=timeout)
        if check and result.returncode != 0:
            raise RuntimeError(
                f"SSH command failed (rc={result.returncode}):\n"
                f"  host: {self.host}\n"
                f"  cmd: {command}\n"
                f"  stderr: {result.stderr.strip()}"
            )
        return result.stdout.strip()

    def run_bg(self, command: str) -> None:
        """Fire-and-forget command (e.g. reboot).  No wait, no output capture."""
        ssh_cmd = ["ssh"] + self._base_opts + [self._target(), command]
        try:
            subprocess.Popen(ssh_cmd, stdout=subprocess.DEVNULL,
                             stderr=subprocess.DEVNULL)
        except Exception:
            pass

    def wait_ssh(self, timeout: int = 120) -> bool:
        """Block until SSH is reachable (or *timeout* seconds elapse)."""
        import time
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            try:
                self.run("true", timeout=8, check=True)
                return True
            except Exception:
                time.sleep(5)
        return False

    def pcie_present(self) -> bool:
        """Return True if the XDMA PCIe device (10ee:7028) is enumerated."""
        try:
            out = self.run(f"lspci -d {_XDMA_VID_DID} 2>/dev/null", timeout=10,
                           check=False)
            return _XDMA_VID_DID in out
        except Exception:
            return False

    def sbr(self) -> None:
        """Issue Secondary Bus Reset on the PCIe bridge."""
        self.run(
            f"sudo setpci -s {_BRIDGE} BRIDGE_CONTROL=0x0040 && "
            f"sleep 0.5 && "
            f"sudo setpci -s {_BRIDGE} BRIDGE_CONTROL=0x0000",
            timeout=15, check=False)

    def reboot(self, wait: int = 180) -> bool:
        """Reboot the FPGA host via SSH.  Returns True when SSH comes back."""
        import time
        self.run_bg("sudo /sbin/reboot")
        time.sleep(10)
        for _ in range(5):
            try:
                self.run("true", timeout=5, check=False)
            except Exception:
                pass
            time.sleep(2)
        return self.wait_ssh(timeout=wait)

    def load_xdma(self) -> int:
        """Remove stale xdma module, insmod fresh, return device node count."""
        import time
        self.run(f"cd {_XDMA_DRV_DIR} && sudo rmmod xdma 2>/dev/null; "
                 f"sudo insmod xdma.ko", timeout=30, check=False)
        time.sleep(2)
        out = self.run("ls /dev/xdma0_* 2>/dev/null | wc -l", timeout=10,
                       check=False)
        try:
            return int(out.strip())
        except (ValueError, TypeError):
            return 0
