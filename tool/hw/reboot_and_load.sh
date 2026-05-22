#!/usr/bin/env bash
# reboot_and_load.sh — Program FPGA via JTAG, force PCIe re-enumeration via SBR,
#                       then load the XDMA driver.
#
# Background:
#   BPI flash indirect programming does NOT work with the FT4232H cable on this
#   board (Vivado "Failure to set flash parameters"). JTAG programs FPGA SRAM only.
#
#   After JTAG programming, PCIe won't auto-appear because the AMD FCH root complex
#   already gave up link training. A Secondary Bus Reset (SBR) on bridge 00:15.0
#   forces the link to re-train without a full reboot.
#
# Usage:
#   tool/hw/reboot_and_load.sh [fpga-ssh-alias] [path/to/top_npu.bit]

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
FPGA_HOST="${1:-fpga}"
BITSTREAM="${2:-$REPO_ROOT/ip/vivado/xc7k480t/npu_fpga/npu_fpga.runs/impl_1/top_npu.bit}"
XSDB="${XSDB:-$HOME/Xilinx/2025.2/Vivado/bin/xsdb}"
LOAD_SCRIPT="~/dma_ip_drivers/XDMA/linux-kernel/tests/load_driver.sh"

PCIe_BRIDGE="00:15.0"          # AMD FCH PCIe bridge to FPGA slot
SBR_WAIT=5                      # seconds for PCIe link training after SBR
MIG_WAIT=10                     # extra seconds for DDR3 MIG calibration

# Validate
if [[ ! -f "$BITSTREAM" ]]; then
    echo "ERROR: Bitstream not found: $BITSTREAM" >&2; exit 1
fi
if ! ssh -o ConnectTimeout=5 -o BatchMode=yes "$FPGA_HOST" true 2>/dev/null; then
    echo "ERROR: Cannot reach $FPGA_HOST" >&2; exit 1
fi

# ── Step 1: JTAG-program the FPGA ─────────────────────────────────────────
echo "INFO: Step 1/3 — JTAG-programming FPGA..."
"$XSDB" << TCLEOF
    connect -url tcp:127.0.0.1:3121
    targets -set -filter {name =~ "*xc7k480t*"}
    fpga -file {$BITSTREAM}
    puts "INFO: Programmed."
    disconnect
TCLEOF

# ── Step 2: Force PCIe re-enumeration via Secondary Bus Reset ─────────────
echo "INFO: Step 2/3 — PCIe Secondary Bus Reset on bridge $PCIe_BRIDGE..."
ssh -o BatchMode=yes "$FPGA_HOST" "
    # Assert Secondary Bus Reset (bit 6 of Bridge Control register)
    sudo setpci -s $PCIe_BRIDGE BRIDGE_CONTROL=0x0040
    sleep 0.5
    # Deassert — link re-training begins
    sudo setpci -s $PCIe_BRIDGE BRIDGE_CONTROL=0x0000
    echo 'INFO: SBR deasserted, waiting ${SBR_WAIT}s for PCIe link + ${MIG_WAIT}s for MIG...'
    sleep $((SBR_WAIT + MIG_WAIT))
    echo 1 | sudo tee /sys/bus/pci/devices/0000:$(echo $PCIe_BRIDGE | tr ':' ':')/rescan
    sleep 2
"

# Verify PCIe device appeared
DEV=$(ssh -o BatchMode=yes "$FPGA_HOST" "lspci -d 10ee:7028 2>/dev/null" || true)
if [[ -z "$DEV" ]]; then
    echo "ERROR: Xilinx PCIe device did not appear after SBR." >&2
    echo "       Check: ssh $FPGA_HOST 'lspci'" >&2
    exit 1
fi
echo "INFO: PCIe device found: $DEV"

# ── Step 3: Load XDMA driver ───────────────────────────────────────────────
echo "INFO: Step 3/3 — Loading XDMA driver..."
ssh -o BatchMode=yes "$FPGA_HOST" \
    "cd ~/dma_ip_drivers/XDMA/linux-kernel/tests && sudo ./load_driver.sh 0"

# Verify
NODES=$(ssh -o BatchMode=yes "$FPGA_HOST" "ls /dev/xdma0_* 2>/dev/null | wc -l" || echo 0)
if [[ "$NODES" -lt 4 ]]; then
    echo "ERROR: Expected ≥4 /dev/xdma0_* nodes, got $NODES" >&2
    echo "       Run: ssh $FPGA_HOST 'sudo journalctl -k | grep xdma | tail -20'" >&2
    exit 1
fi

echo ""
echo "INFO: FPGA box ready."
echo "  PCIe  : $(ssh -o BatchMode=yes "$FPGA_HOST" "lspci -d 10ee:7028" 2>/dev/null)"
echo "  Nodes : $NODES /dev/xdma0_* devices"
echo "  Driver: $(ssh -o BatchMode=yes "$FPGA_HOST" "lsmod | grep ^xdma" 2>/dev/null)"
