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
        host,
        command,
    ]
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
        result = subprocess.run(
            ["ssh", "-o", "BatchMode=yes", "-o", f"ConnectTimeout={timeout}",
             "-o", "StrictHostKeyChecking=no", host, "true"],
            capture_output=True, timeout=timeout + 2,
        )
        return result.returncode == 0
    except Exception:
        return False


def copy_file(host: str, local_path: str, remote_path: str) -> None:
    """scp a local file to *host*:*remote_path*."""
    subprocess.run(
        ["scp", "-o", "BatchMode=yes", "-o", "StrictHostKeyChecking=no",
         local_path, f"{host}:{remote_path}"],
        check=True,
    )
