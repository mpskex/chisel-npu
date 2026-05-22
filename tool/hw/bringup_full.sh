#!/usr/bin/env bash
# bringup_full.sh — One-shot: program bitstream → reboot → load driver → smoke test
#
# Usage:
#   tool/hw/bringup_full.sh [path/to/top_npu.bit] [fpga-ssh-alias]

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

BITSTREAM="${1:-$REPO_ROOT/ip/vivado/xc7k480t/npu_fpga/npu_fpga.runs/impl_1/top_npu.bit}"
FPGA_HOST="${2:-fpga}"

echo "================================================================"
echo "  NPU FPGA Bring-Up: xc7k480tffg1156-2"
echo "  Bitstream : $BITSTREAM"
echo "  Target    : $FPGA_HOST"
echo "================================================================"

echo ""
echo "--- Step 1/3: Program bitstream via JTAG ---"
"$SCRIPT_DIR/program_bitstream.sh" "$BITSTREAM"

echo ""
echo "--- Step 2/3: Reboot FPGA box + load XDMA driver ---"
"$SCRIPT_DIR/reboot_and_load.sh" "$FPGA_HOST"

echo ""
echo "--- Step 3/3: Run smoke tests ---"
cd "$REPO_ROOT"
python -m pytest tool/hw/tests/ \
    --fpga-host "$FPGA_HOST" \
    --bitstream "$BITSTREAM" \
    -v \
    --tb=short \
    -x

echo ""
echo "================================================================"
echo "  Bring-up complete."
echo "================================================================"
