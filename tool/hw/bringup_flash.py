#!/usr/bin/env python3
"""
bringup_flash.py — Flash a bitstream and verify XDMA hardware via serial console.

Flow:
  1. Flash the bitstream to BPI flash (program_flash.sh)
  2. JTAG-load the same bitstream into FPGA SRAM (so PCIe hard block is live now)
  3. Reboot (FPGA SRAM retains design → PCIe trained at BIOS POST)
  4. SBR (Secondary Bus Reset) on the PCIe bridge
  5. Reboot again
  6. Check if PCIe device enumerates
  7. Loop back to step 4 until step 6 succeeds (max --max-attempts times)
  8. Load XDMA driver
  9. Run hardware smoke tests over serial (no SSH required)

All hardware interaction is over /dev/ttyUSB0 serial console (115200 baud).

Usage:
    python3 tool/hw/bringup_flash.py <bitstream.bit> [--max-attempts N]
    python3 tool/hw/bringup_flash.py --no-flash   # skip flash, just bring up + test
"""

import argparse
import os
import re
import subprocess
import sys
import time
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO_ROOT))
from tool.hw.serial_console import SerialConsole  # noqa: E402

BRIDGE        = "00:15.0"
XDMA_VID_DID  = "10ee:7028"
XDMA_DRV_DIR  = "~/dma_ip_drivers/XDMA/linux-kernel/xdma"
XDMA_TOOLS    = "~/dma_ip_drivers/XDMA/linux-kernel/tools"
PROGRAM_FLASH = REPO_ROOT / "tool/hw/program_flash.sh"
# hw_server address — override with HW_SERVER env var if needed
HW_SERVER     = os.environ.get("HW_SERVER", "localhost:3121")

PASS = "\033[32mPASS\033[0m"
FAIL = "\033[31mFAIL\033[0m"
SKIP = "\033[33mSKIP\033[0m"


# ── helpers ───────────────────────────────────────────────────────────────────

def clean(buf: bytes) -> str:
    """Strip ANSI/VT100 escapes and CR."""
    return re.sub(r'\x1b\[[^a-zA-Z]*[a-zA-Z]|\x1b\[\?[0-9]+[hl]|\r', '',
                  buf.decode(errors='replace'))


def log(msg: str) -> None:
    print(f"[bringup] {msg}", flush=True)


def serial_run(sc: SerialConsole, cmd: str, timeout: int = 20) -> str:
    """Run a command over serial and return clean stdout (no echo, no prompt)."""
    sentinel = f"__END_{int(time.monotonic()*1000)%99999:05d}__"
    sc._write(f"{cmd}; echo {sentinel}\r\n".encode())
    buf = sc._read_until([re.compile(sentinel.encode())], timeout=timeout)
    text = clean(buf)
    lines = []
    skip_first = True
    for line in text.splitlines():
        s = line.strip()
        if skip_first and (cmd.split()[0] in s or s == ""):
            skip_first = False
            continue
        if sentinel in s or re.search(r'@[\w.-]+:.*[\$#]\s*$', s):
            continue
        lines.append(line)
    return "\n".join(lines).strip()


def suppress_kernel_log(sc: SerialConsole) -> None:
    serial_run(sc, "sudo sysctl -w kernel.printk='0 4 1 7' >/dev/null 2>&1", timeout=5)


# ── PCIe / hardware steps ─────────────────────────────────────────────────────

def flash_bitstream(bit: Path) -> None:
    log(f"Flashing {bit.name} ({bit.stat().st_size // 1048576} MB) to BPI flash...")
    result = subprocess.run([str(PROGRAM_FLASH), str(bit)])
    if result.returncode != 0:
        raise RuntimeError(f"program_flash.sh failed (rc={result.returncode})")
    log("Flash: SUCCESS")


def jtag_load_sram(bit: Path) -> None:
    """Load bitstream into FPGA SRAM via JTAG so PCIe hard block is live immediately."""
    log(f"JTAG-loading {bit.name} into FPGA SRAM...")
    tcl = (
        f"open_hw_manager\n"
        f"connect_hw_server -url {HW_SERVER} -allow_non_jtag\n"
        f"# Use first available target (auto-discovers cable)\n"
        f"current_hw_target [lindex [get_hw_targets] 0]\n"
        f"set_property PARAM.FREQUENCY 15000000 [current_hw_target]\n"
        f"open_hw_target\n"
        f"current_hw_device [lindex [get_hw_devices xc7k480t_0] 0]\n"
        f"set_property PROGRAM.FILE {{{bit}}} [current_hw_device]\n"
        f"program_hw_devices [current_hw_device]\n"
        f"refresh_hw_device [current_hw_device]\n"
        f"puts \"JTAG_SRAM_OK\"\n"
        f"close_hw_target\n"
        f"disconnect_hw_server\n"
        f"close_hw_manager\n"
    )
    tcl_file = Path("/tmp/jtag_load_sram.tcl")
    tcl_file.write_text(tcl)
    vivado = Path.home() / "Xilinx/2025.2/Vivado/bin/vivado"
    result = subprocess.run(
        [str(vivado), "-mode", "batch",
         "-source", str(tcl_file), "-nolog", "-nojournal"],
        capture_output=True, text=True,
    )
    ok = "JTAG_SRAM_OK" in result.stdout
    log(f"JTAG SRAM load: {'SUCCESS' if ok else 'FAILED'}")
    if not ok:
        log(f"  stdout: {result.stdout[-200:]}")
        raise RuntimeError("JTAG SRAM load failed")


def check_pcie(sc: SerialConsole) -> bool:
    """Return True if the XDMA PCIe device is enumerated."""
    out = serial_run(sc, f"sudo lspci -nn 2>/dev/null | grep {XDMA_VID_DID}", timeout=15)
    found = XDMA_VID_DID in out
    if found:
        for line in out.splitlines():
            if XDMA_VID_DID in line:
                log(f"PCIe FOUND: {line.strip()}")
                break
    return found


def do_sbr(sc: SerialConsole) -> None:
    """Issue Secondary Bus Reset on the PCIe bridge (does NOT rescan)."""
    log(f"SBR on bridge {BRIDGE}...")
    serial_run(sc,
        f"sudo setpci -s {BRIDGE} BRIDGE_CONTROL=0x0040 && "
        f"sleep 0.5 && "
        f"sudo setpci -s {BRIDGE} BRIDGE_CONTROL=0x0000",
        timeout=10)
    log("SBR done.")


def load_xdma(sc: SerialConsole) -> int:
    """Load xdma.ko and return number of /dev/xdma0_* nodes created."""
    serial_run(sc,
        f"cd {XDMA_DRV_DIR} && sudo rmmod xdma 2>/dev/null; sudo insmod xdma.ko",
        timeout=20)
    time.sleep(1)
    out = serial_run(sc, "ls /dev/xdma0_* 2>/dev/null | wc -l", timeout=10)
    nums = [l.strip() for l in out.splitlines() if l.strip().isdigit()]
    return int(nums[0]) if nums else 0


# ── serial smoke tests ────────────────────────────────────────────────────────

def run_tests(sc: SerialConsole) -> dict:
    """
    Execute hardware smoke tests over serial.
    Returns dict of {test_name: (result_str, detail)}.
    result_str is "PASS", "FAIL", or "SKIP".
    """
    results = {}

    def record(name: str, passed: bool, detail: str = "", skip: bool = False) -> None:
        if skip:
            results[name] = ("SKIP", detail)
            print(f"  {SKIP} {name}" + (f" — {detail}" if detail else ""))
        elif passed:
            results[name] = ("PASS", detail)
            print(f"  {PASS} {name}" + (f" — {detail}" if detail else ""))
        else:
            results[name] = ("FAIL", detail)
            print(f"  {FAIL} {name}" + (f" — {detail}" if detail else ""))

    print("")
    print("=== Hardware smoke tests (serial) ===")

    # 1. PCIe device present
    out = serial_run(sc, f"sudo lspci -nn 2>/dev/null | grep {XDMA_VID_DID}")
    present = XDMA_VID_DID in out
    record("pcie_device_present", present,
           out.strip() if present else "not found")

    if not present:
        for name in ["pcie_link_speed", "pcie_link_width", "subsystem_id",
                     "xdma_driver_loaded", "xdma_devnodes",
                     "ddr3_c0_loopback_1kb", "ddr3_c0_loopback_1mb",
                     "bypass_bar_accessible"]:
            record(name, False, skip=True, detail="PCIe not present")
        return results

    # 2. PCIe link speed
    out = serial_run(sc, "sudo lspci -vvv -s 01:00.0 2>/dev/null | grep LnkSta:")
    speed_ok = "2.5GT/s" in out
    record("pcie_link_speed", speed_ok, out.strip())

    # 3. PCIe link width
    width_ok = "Width x" in out and "unknown" not in out.lower()
    m = re.search(r'Width (x\d+)', out)
    record("pcie_link_width", width_ok, m.group(1) if m else out.strip())

    # 4. Subsystem ID — check for known-good value (0007 for this board)
    out2 = serial_run(sc, "sudo lspci -vvv -s 01:00.0 2>/dev/null | grep Subsystem:")
    sub_ok = "0007" in out2 or "10ee" in out2.lower()
    record("subsystem_id", sub_ok, out2.strip())

    # 5. XDMA driver loaded
    out3 = serial_run(sc, "lsmod 2>/dev/null | grep xdma")
    drv_ok = "xdma" in out3
    if not drv_ok:
        # try loading it
        serial_run(sc,
            f"cd {XDMA_DRV_DIR} && sudo rmmod xdma 2>/dev/null; sudo insmod xdma.ko",
            timeout=20)
        time.sleep(1)
        out3 = serial_run(sc, "lsmod 2>/dev/null | grep xdma")
        drv_ok = "xdma" in out3
    record("xdma_driver_loaded", drv_ok, out3.strip())

    # 6. Device nodes
    out4 = serial_run(sc, "ls /dev/xdma0_* 2>/dev/null | wc -l")
    nums = [l.strip() for l in out4.splitlines() if l.strip().isdigit()]
    node_count = int(nums[0]) if nums else 0
    record("xdma_devnodes", node_count > 0, f"{node_count} nodes")

    def ddr3_loopback(size_bytes: int, label: str, timeout: int = 60) -> bool:
        """Write random data to DDR3 C0, read it back, compare. Returns True on match."""
        f_w, f_r = "/tmp/lb_w.bin", "/tmp/lb_r.bin"
        cmd = (
            f"dd if=/dev/urandom bs={size_bytes} count=1 2>/dev/null of={f_w} && "
            f"sudo {XDMA_TOOLS}/dma_to_device   -d /dev/xdma0_h2c_0 "
            f"  -f {f_w} -s {size_bytes} -a 0 >/dev/null 2>&1 && "
            f"sudo {XDMA_TOOLS}/dma_from_device -d /dev/xdma0_c2h_0 "
            f"  -f {f_r} -s {size_bytes} -a 0 >/dev/null 2>&1 && "
            f"cmp -s {f_w} {f_r} && echo LOOPBACK_OK || echo LOOPBACK_FAIL"
        )
        out = serial_run(sc, cmd, timeout=timeout)
        return "LOOPBACK_OK" in out

    # 7. DDR3 C0 loopback 1 KB
    if drv_ok and node_count > 0:
        ok = ddr3_loopback(1024, "1KB", timeout=30)
        record("ddr3_c0_loopback_1kb", ok,
               "DDR3 C0 1KB write→read match" if ok else "data mismatch")
    else:
        record("ddr3_c0_loopback_1kb", False, skip=True, detail="driver not loaded")

    # 8. DDR3 C0 loopback 1 MB
    if drv_ok and node_count > 0:
        ok = ddr3_loopback(1048576, "1MB", timeout=60)
        record("ddr3_c0_loopback_1mb", ok,
               "DDR3 C0 1MB write→read match" if ok else "data mismatch")
    else:
        record("ddr3_c0_loopback_1mb", False, skip=True, detail="driver not loaded")

    # 9. BYPASS BAR accessible (ctrl_lite or MicroBlaze space)
    bypass_node = serial_run(sc, "test -c /dev/xdma0_bypass && echo EXISTS")
    if "EXISTS" in bypass_node:
        out7 = serial_run(sc,
            f"sudo {XDMA_TOOLS}/reg_rw /dev/xdma0_bypass 0x0 w 2>/dev/null | "
            f"grep -oE '0x[0-9a-fA-F]+'",
            timeout=10)
        val = out7.strip()
        bar_ok = bool(val) and val != "0xffffffff"
        record("bypass_bar_accessible", bar_ok,
               f"reg[0]={val}" if val else "no response")
    else:
        record("bypass_bar_accessible", False, skip=True,
               detail="no /dev/xdma0_bypass (no BYPASS BAR in this design)")

    return results


# ── main ──────────────────────────────────────────────────────────────────────

def main() -> int:
    ap = argparse.ArgumentParser(
        description="Flash + bring up XDMA on FPGA board via serial console")
    ap.add_argument("bitstream", nargs="?", type=Path,
                    help="Path to .bit file to flash")
    ap.add_argument("--no-flash", action="store_true",
                    help="Skip flash step (board already has the desired bitstream)")
    ap.add_argument("--max-attempts", type=int, default=6,
                    help="Max SBR→reboot→check iterations (default: 6)")
    args = ap.parse_args()

    if not args.no_flash:
        if args.bitstream is None:
            ap.error("bitstream argument required unless --no-flash is given")
        bit = Path(args.bitstream)
        if not bit.exists():
            ap.error(f"Bitstream not found: {bit}")

    # ── Step 1: Flash BPI flash ───────────────────────────────────────────────
    if not args.no_flash:
        flash_bitstream(bit)

        # ── Step 2: JTAG-load SRAM ────────────────────────────────────────
        # Loading SRAM after flash means the PCIe hard block is already
        # initialised when the host reboots — the AMD FCH sees it at POST.
        log("Step 2: JTAG-loading SRAM (PCIe hard block comes alive now)...")
        jtag_load_sram(bit)
    else:
        log("Skipping flash + JTAG SRAM load (--no-flash).")

    with SerialConsole() as sc:
        sc.login()
        suppress_kernel_log(sc)

        # ── Step 3: Initial reboot (SRAM retained → PCIe trained at POST) ─
        log("Step 3: Reboot (FPGA SRAM retains design through warm reboot)...")
        sc.reboot(wait=180)
        suppress_kernel_log(sc)

        if check_pcie(sc):
            log("PCIe trained on first reboot.")
        else:
            # ── Steps 4-7: SBR → reboot → check loop ──────────────────────
            trained = False
            for attempt in range(1, args.max_attempts + 1):
                log(f"Step 4 (attempt {attempt}/{args.max_attempts}): SBR...")
                do_sbr(sc)

                log("Step 5: Reboot...")
                sc.reboot(wait=180)
                suppress_kernel_log(sc)

                log("Step 6: Check PCIe...")
                if check_pcie(sc):
                    trained = True
                    log(f"PCIe trained after {attempt} SBR+reboot iteration(s).")
                    break
                log("PCIe not yet trained. Retrying...")

            if not trained:
                log(f"ERROR: PCIe did not train after {args.max_attempts} attempts.")
                log("Board may need a physical power cycle.")
                return 1

        # ── Step 7: Load XDMA driver ───────────────────────────────────────
        log("Loading XDMA driver...")
        nodes = load_xdma(sc)
        log(f"XDMA driver loaded: {nodes} device nodes.")

        # ── Step 8: Smoke tests over serial ───────────────────────────────
        results = run_tests(sc)

    # ── Summary ───────────────────────────────────────────────────────────────
    print("")
    print("=== Results ===")
    passed = sum(1 for s, _ in results.values() if s == "PASS")
    failed = sum(1 for s, _ in results.values() if s == "FAIL")
    skipped = sum(1 for s, _ in results.values() if s == "SKIP")
    print(f"{passed} passed, {failed} failed, {skipped} skipped")

    return 0 if failed == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
