#!/usr/bin/env python3
"""
bringup_ssh.py — SSH-based FPGA bring-up for xc7k480t NPU board.

Flow:
  1. (optional) Flash bitstream to BPI flash via local JTAG (Vivado)
  2. (optional) JTAG-load bitstream into FPGA SRAM (Vivado)
  3. Wait for SSH reachable on the FPGA host
  4. PCIe link training loop (SBR + reboot + check)
  5. Load XDMA driver
  6. Run pytest hardware tests via SSH

The flash/JTAG steps run on the local dev host (expects FT4232H cable +
Vivado).  The reboot/check/driver/test steps run over SSH to the FPGA host.

Usage:
    python3 tool/hw/bringup_ssh.py --host <fpga-host> <bitstream.bit>
    python3 tool/hw/bringup_ssh.py --host <fpga-host> <bitstream.bit> --skip-flash
    python3 tool/hw/bringup_ssh.py --host <fpga-host> <bitstream.bit> --skip-jtag
    python3 tool/hw/bringup_ssh.py --host <fpga-host> <bitstream.bit> \\
        --identity ~/.ssh/id_fpga_local
"""

import argparse
import os
import subprocess
import sys
import time
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[1]

# Defaults
_VIVADO     = os.environ.get("VIVADO", str(Path.home() / "Vivado/2025.2/bin/vivado"))
_HW_SERVER  = os.environ.get("HW_SERVER", "localhost:3121")
_IDENTITY   = os.environ.get("SSH_IDENTITY", str(Path.home() / ".ssh/id_fpga_local"))
_BRIDGE     = "00:15.0"
_XDMA_VID   = "10ee:7028"
_MAX_ATTEMPTS = 6

PASS = "\033[32mPASS\033[0m"
FAIL = "\033[31mFAIL\033[0m"
INFO = "\033[36mINFO\033[0m"


def log(msg: str) -> None:
    print(f"[bringup] {msg}", flush=True)


# ── Local JTAG / flash helpers (run on dev host) ──────────────────────────────

def flash_bpi(bit: Path) -> None:
    """Program bitstream to BPI flash via Vivado."""
    log(f"Flashing {bit.name} ({bit.stat().st_size // 1048576} MB) to BPI flash...")
    _run_vivado_tcl(_tcl_flash_bpi(bit), "flash_bpi")


def jtag_load_sram(bit: Path) -> None:
    """Load bitstream into FPGA SRAM via JTAG so PCIe hard block is live."""
    log(f"JTAG-loading {bit.name} into FPGA SRAM...")
    _run_vivado_tcl(_tcl_jtag_sram(bit), "jtag_sram")


def _vivado() -> str:
    v = os.environ.get("VIVADO", _VIVADO)
    if not os.path.isfile(v):
        raise RuntimeError(f"Vivado not found at {v}. Set VIVADO env var or install.")
    return v


def _run_vivado_tcl(tcl_body: str, label: str) -> None:
    tcl_file = Path(f"/tmp/bringup_{label}.tcl")
    tcl_file.write_text(tcl_body)
    result = subprocess.run(
        [_vivado(), "-mode", "batch", "-source", str(tcl_file),
         "-nolog", "-nojournal"],
        capture_output=True, text=True, timeout=600,
    )
    ok = f"{label.upper()}_OK" in result.stdout
    if not ok:
        log(f"  stdout: {result.stdout[-500:]}")
        log(f"  stderr: {result.stderr[-500:]}")
        raise RuntimeError(f"{label} failed")
    log(f"{label}: SUCCESS")


def _tcl_jtag_sram(bit: Path) -> str:
    return f"""
open_hw_manager
connect_hw_server -url {_HW_SERVER} -allow_non_jtag
current_hw_target [lindex [get_hw_targets] 0]
set_property PARAM.FREQUENCY 15000000 [current_hw_target]
open_hw_target
current_hw_device [lindex [get_hw_devices xc7k480t_0] 0]
set_property PROGRAM.FILE {{{bit}}} [current_hw_device]
program_hw_devices [current_hw_device]
refresh_hw_device [current_hw_device]
puts "JTAG_SRAM_OK"
close_hw_target
disconnect_hw_server
close_hw_manager
"""


def _tcl_flash_bpi(bit: Path) -> str:
    helper = Path("/tmp/bpi_xc7k480t_pullnone.bit")
    mcs = Path(f"/tmp/{bit.stem}.mcs")
    # Ensure helper bitstream exists
    bitfile_zip = Path.home() / "Vivado/2025.2/Vivado/data/xicom/cfgmem/bitfile.zip"
    if not helper.exists() and bitfile_zip.exists():
        import zipfile
        with zipfile.ZipFile(bitfile_zip) as z:
            z.extract("bitfile/bpi_xc7k480t_pullnone.bit", "/tmp/")
        (Path("/tmp/bitfile") / "bpi_xc7k480t_pullnone.bit").rename(helper)
    return f"""
puts "=== write_cfgmem ==="
write_cfgmem -format mcs -interface SPIx1 -size 64 \\
    -loadbit "up 0x00000000 {{{bit}}}" -force -file {{{mcs}}}
open_hw_manager
connect_hw_server -url {_HW_SERVER} -allow_non_jtag
current_hw_target [lindex [get_hw_targets] 0]
set_property PARAM.FREQUENCY 15000000 [current_hw_target]
open_hw_target
current_hw_device [lindex [get_hw_devices xc7k480t_0] 0]
puts "=== load BPI helper ==="
set_property PROGRAM.FILE {{{helper}}} [current_hw_device]
program_hw_devices [current_hw_device]
refresh_hw_device [current_hw_device]
puts "=== create_hw_cfgmem ==="
create_hw_cfgmem -hw_device [current_hw_device] \\
    -mem_dev [lindex [get_cfgmem_parts mt28gu512aax1e-bpi-x16] 0]
set CM [get_property PROGRAM.HW_CFGMEM [current_hw_device]]
set_property PROGRAM.BLANK_CHECK  0      $CM
set_property PROGRAM.ERASE        1      $CM
set_property PROGRAM.CFG_PROGRAM  1      $CM
set_property PROGRAM.VERIFY       1      $CM
set_property PROGRAM.FILES        {{{mcs}}} $CM
puts "=== program_hw_cfgmem ==="
program_hw_cfgmem -hw_cfgmem $CM
puts "FLASH_BPI_OK"
close_hw_target
disconnect_hw_server
close_hw_manager
"""


# ── SSH helpers (run on FPGA host) ────────────────────────────────────────────

def _ssh(host: str, identity: str | None) -> list[str]:
    cmd = ["ssh", "-o", "BatchMode=yes", "-o", "ConnectTimeout=10",
           "-o", "StrictHostKeyChecking=no"]
    if identity and os.path.isfile(identity):
        cmd += ["-i", identity]
    cmd.append(host)
    return cmd


def _ssh_run(host: str, command: str, identity: str | None = None,
             timeout: int = 60, check: bool = True) -> str:
    cmd = _ssh(host, identity) + [command]
    result = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
    if check and result.returncode != 0:
        raise RuntimeError(f"SSH[{host}]: {command} failed (rc={result.returncode}):\n"
                           f"  {result.stderr.strip()}")
    return result.stdout.strip()


def _ssh_bg(host: str, command: str, identity: str | None = None) -> None:
    cmd = _ssh(host, identity) + [command]
    try:
        subprocess.Popen(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    except Exception:
        pass


def wait_ssh(host: str, identity: str | None = None, timeout: int = 120) -> bool:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            _ssh_run(host, "true", identity, timeout=8)
            return True
        except Exception:
            time.sleep(5)
    return False


def check_pcie(host: str, identity: str | None = None) -> bool:
    try:
        out = _ssh_run(host, f"lspci -d {_XDMA_VID} 2>/dev/null", identity,
                       timeout=10, check=False)
        return _XDMA_VID in out
    except Exception:
        return False


def do_sbr(host: str, identity: str | None = None) -> None:
    log(f"SBR on bridge {_BRIDGE}...")
    _ssh_run(host,
             f"sudo setpci -s {_BRIDGE} BRIDGE_CONTROL=0x0040 && "
             f"sleep 0.5 && "
             f"sudo setpci -s {_BRIDGE} BRIDGE_CONTROL=0x0000",
             identity, timeout=15, check=False)
    log("SBR done.")


def load_xdma(host: str, identity: str | None = None) -> int:
    log("Loading XDMA driver...")
    _ssh_run(host,
             "cd ~/dma_ip_drivers/XDMA/linux-kernel/xdma && "
             "sudo rmmod xdma 2>/dev/null; sudo insmod xdma.ko",
             identity, timeout=30, check=False)
    time.sleep(2)
    out = _ssh_run(host, "ls /dev/xdma0_* 2>/dev/null | wc -l", identity,
                   timeout=10, check=False)
    try:
        return int(out.strip())
    except (ValueError, TypeError):
        return 0


def reboot_host(host: str, identity: str | None = None, wait: int = 180) -> bool:
    log("Rebooting FPGA host...")
    _ssh_bg(host, "sudo /sbin/reboot", identity)
    time.sleep(10)
    # Drain any lingering SSH
    for _ in range(5):
        try:
            _ssh_run(host, "true", identity, timeout=5, check=False)
        except Exception:
            pass
        time.sleep(2)
    log("Waiting for SSH to come back...")
    ok = wait_ssh(host, identity, timeout=wait)
    if ok:
        log("SSH is back.")
    else:
        log(f"ERROR: SSH not reachable after {wait}s")
    return ok


# ── pytest runner ─────────────────────────────────────────────────────────────

def run_pytest(host: str, bitstream: Path | None = None) -> int:
    """Run FPGA hardware tests via pytest. Returns number of failures."""
    cmd = [
        sys.executable, "-m", "pytest",
        str(REPO_ROOT / "tool/hw/tests/"),
        "-v", "-m", "hw", "--tb=short",
        "--fpga-host", host,
        "--skip-program",
    ]
    if bitstream:
        cmd += ["--bitstream", str(bitstream)]
    env = os.environ.copy()
    env["FPGA_HOST"] = host
    log(f"Running: {' '.join(cmd)}")
    result = subprocess.run(cmd, env=env)
    return result.returncode


# ── main ──────────────────────────────────────────────────────────────────────

def main() -> int:
    ap = argparse.ArgumentParser(
        description="SSH-based FPGA bring-up for xc7k480t NPU board")
    ap.add_argument("bitstream", nargs="?", type=Path,
                    help="Path to .bit file to flash/program")
    ap.add_argument("--host", required=True,
                    help="FPGA host SSH address (e.g. 10.16.0.31 or fpga.local)")
    ap.add_argument("--identity", default=_IDENTITY,
                    help="SSH private key path (default: ~/.ssh/id_fpga_local)")
    ap.add_argument("--skip-flash", action="store_true",
                    help="Skip BPI flash programming")
    ap.add_argument("--skip-jtag", action="store_true",
                    help="Skip JTAG SRAM load")
    ap.add_argument("--skip-pytest", action="store_true",
                    help="Skip pytest (just do bring-up)")
    ap.add_argument("--max-attempts", type=int, default=_MAX_ATTEMPTS,
                    help=f"Max SBR+reboot iterations (default: {_MAX_ATTEMPTS})")
    ap.add_argument("--vivado", default=_VIVADO,
                    help=f"Vivado binary path (default: ~/Vivado/2025.2/bin/vivado)")
    args = ap.parse_args()

    if not args.skip_flash and args.bitstream is None:
        ap.error("bitstream argument required unless --skip-flash is given")
    if args.bitstream and not args.bitstream.exists():
        ap.error(f"Bitstream not found: {args.bitstream}")

    if not args.skip_flash or not args.skip_jtag:
        _VIVADO_GLOBAL = args.vivado
        if not os.path.isfile(args.vivado):
            ap.error(f"Vivado not found at {args.vivado}. Set --vivado or VIVADO env.")

    identity = args.identity if os.path.isfile(args.identity) else None
    host = args.host

    # ── Step 1: Flash BPI (optional) ────────────────────────────────────────
    if not args.skip_flash:
        log("=== Step 1: Flash BPI ===")
        flash_bpi(args.bitstream)

    # ── Step 2: JTAG SRAM load (optional) ───────────────────────────────────
    if not args.skip_jtag:
        log("=== Step 2: JTAG SRAM load ===")
        jtag_load_sram(args.bitstream)
    else:
        log("Skipping JTAG SRAM load.")

    # ── Step 3: Wait for SSH ────────────────────────────────────────────────
    log("=== Step 3: Wait for SSH reachable ===")
    if not wait_ssh(host, identity, timeout=120):
        log(f"ERROR: Cannot reach {host} via SSH after flash/JTAG.")
        log("Check the connection and JTAG programming.")
        return 1
    log(f"SSH to {host}: OK")

    # ── Step 4: PCIe link training ──────────────────────────────────────────
    log("=== Step 4: PCIe link training ===")
    if check_pcie(host, identity):
        log("PCIe already trained.")
    else:
        trained = False
        for attempt in range(1, args.max_attempts + 1):
            log(f"SBR attempt {attempt}/{args.max_attempts}...")
            do_sbr(host, identity)
            if not reboot_host(host, identity, wait=180):
                log("Reboot failed, check host power.")
                return 1
            if check_pcie(host, identity):
                trained = True
                log(f"PCIe trained after {attempt} SBR+reboot iteration(s).")
                break
            log("PCIe not yet trained.")
        if not trained:
            log(f"ERROR: PCIe not trained after {args.max_attempts} attempts.")
            log("Board may need a physical power cycle.")
            return 1

    # ── Step 5: Load XDMA driver ────────────────────────────────────────────
    log("=== Step 5: Load XDMA driver ===")
    nodes = load_xdma(host, identity)
    log(f"XDMA driver loaded: {nodes} device nodes.")

    # ── Step 6: Run pytest ──────────────────────────────────────────────────
    if not args.skip_pytest:
        log("=== Step 6: Run pytest hardware tests ===")
        rc = run_pytest(host, args.bitstream)
        if rc != 0:
            log(f"pytest finished with failures (rc={rc}).")
            return rc
        log("All hardware tests PASSED.")
    else:
        log("Skipping pytest (--skip-pytest).")

    log("=== Bring-up complete ===")
    return 0


if __name__ == "__main__":
    sys.exit(main())
