#!/usr/bin/env bash
# program_bitstream.sh — Flash top_npu.bit via local JTAG (FT4232H) using xsdb
#
# Usage:
#   tool/hw/program_bitstream.sh [path/to/top_npu.bit]
#
# Defaults to:
#   ip/vivado/xc7k480t/npu_fpga/npu_fpga.runs/impl_1/top_npu.bit
#
# Prerequisites (dev host):
#   - FT4232H JTAG USB cable plugged in (lsusb should show 0403:6011)
#   - hw_server running on 127.0.0.1:3121 (Vivado 2025.2)
#   - ~/Xilinx/2025.2/Vivado/bin/xsdb on PATH or at default location

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
XSDB="${XSDB:-$HOME/Xilinx/2025.2/Vivado/bin/xsdb}"
HW_SERVER="${HW_SERVER:-tcp:127.0.0.1:3121}"
DEVICE_FILTER="${DEVICE_FILTER:-xc7k480t}"

BITSTREAM="${1:-$REPO_ROOT/ip/vivado/xc7k480t/npu_fpga/npu_fpga.runs/impl_1/top_npu.bit}"

# Validate
if [[ ! -f "$BITSTREAM" ]]; then
    echo "ERROR: bitstream not found: $BITSTREAM" >&2
    echo "       Run 'make build' and Vivado impl first." >&2
    exit 1
fi
if [[ ! -x "$XSDB" ]]; then
    echo "ERROR: xsdb not found at $XSDB" >&2
    exit 1
fi

echo "INFO: Programming FPGA"
echo "  Bitstream : $BITSTREAM  ($(du -sh "$BITSTREAM" | cut -f1))"
echo "  hw_server : $HW_SERVER"
echo "  Device    : $DEVICE_FILTER"

"$XSDB" << TCLEOF
    if {[catch {
        connect -url {$HW_SERVER}
        set tgts [targets -filter {name =~ "*${DEVICE_FILTER}*"}]
        if {[llength \$tgts] == 0} {
            puts "ERROR: No target matching '$DEVICE_FILTER' found."
            puts "Available targets:"
            targets
            exit 1
        }
        targets -set -filter {name =~ "*${DEVICE_FILTER}*"}
        puts "INFO: Programming [target] ..."
        fpga -file {$BITSTREAM}
        puts "INFO: Done."
        disconnect
    } err]} {
        puts "ERROR: \$err"
        exit 1
    }
TCLEOF

echo "INFO: Bitstream programmed via JTAG."
echo ""
echo "NOTE: BPI flash indirect programming is NOT supported by this cable/board setup."
echo "      (Vivado 'Failure to set flash parameters' — JTAG cable cannot reach BPI bus)"
echo ""
echo "      JTAG programs FPGA SRAM only. To enumerate PCIe without rebooting:"
echo "        ssh fpga 'sudo setpci -s 00:15.0 BRIDGE_CONTROL=0x0040; sleep 0.5; \\"
echo "                  sudo setpci -s 00:15.0 BRIDGE_CONTROL=0x0000; sleep 4; \\"
echo "                  echo 1 | sudo tee /sys/bus/pci/devices/0000:00:15.0/rescan'"
echo ""
echo "      OR run the full bring-up script which handles this automatically:"
echo "        tool/hw/reboot_and_load.sh"
