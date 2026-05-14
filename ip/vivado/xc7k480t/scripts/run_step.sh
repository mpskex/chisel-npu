#!/usr/bin/env bash
# run_step.sh — Build one migration step, flash it, and run smoke tests.
#
# Usage:
#   cd chisel-npu
#   ip/vivado/xc7k480t/scripts/run_step.sh <step>
#   e.g.:  run_step.sh v1_no_mb
#
# Environment:
#   VIVADO      — path to vivado binary (default: ~/Xilinx/2025.2/Vivado/bin/vivado)
#   XDMA_REF_XPR — path to reference XPR (auto-discovered if not set)
#   SERIAL_PORT  — serial device (default: /dev/ttyUSB0)
#   HW_SERVER    — hw_server URL (default: localhost:3121)
#   SKIP_FLASH   — set to 1 to skip flash programming (use existing SRAM load)
#
# Steps: v0_baseline v1_no_mb v2_bypass_ctrl v3_mmcm v4_byp_cdc
#        v5_xdma_cc v6_no_smc v7_dma_master v8_npu_stub v9_npu_full

set -euo pipefail

STEP="${1:-}"
if [[ -z "$STEP" ]]; then
    echo "Usage: $0 <step_name>"
    echo "  e.g: $0 v6_no_smc"
    exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
MIGRATE_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../.." && pwd)"

VIVADO="${VIVADO:-$HOME/Xilinx/2025.2/Vivado/bin/vivado}"
TCL="${SCRIPT_DIR}/build_${STEP}.tcl"
LOG_BASE="${SCRIPT_DIR}/build_${STEP}"
BIT="${MIGRATE_DIR}/top_${STEP}.bit"
RESULTS="${MIGRATE_DIR}/RESULTS.md"

# ── Step 1: Build ─────────────────────────────────────────────────────────────
echo "=== [run_step] Building $STEP ==="
if [[ ! -f "$TCL" ]]; then
    echo "ERROR: TCL script not found: $TCL"
    exit 1
fi

"$VIVADO" -mode batch \
    -source "$TCL" \
    -journal "${LOG_BASE}.jou" \
    -log     "${LOG_BASE}.log"

if [[ ! -f "$BIT" ]]; then
    echo "ERROR: Expected bitstream not produced: $BIT"
    exit 1
fi

echo "=== [run_step] Build complete: $BIT ==="

# ── Step 2: Flash ─────────────────────────────────────────────────────────────
if [[ "${SKIP_FLASH:-0}" == "1" ]]; then
    echo "=== [run_step] SKIP_FLASH=1 — skipping flash programming ==="
else
    echo "=== [run_step] Flashing $BIT ==="
    "$REPO_ROOT/tool/hw/program_flash.sh" "$BIT"
fi

# ── Step 3: Bring-up + smoke tests ───────────────────────────────────────────
echo "=== [run_step] Running bringup_flash.py --skip-flash for $STEP ==="
RESULT_LINE="| $STEP | $(date +%Y-%m-%d) |"
if python3 "$REPO_ROOT/tool/hw/bringup_flash.py" \
       --skip-flash \
       "$BIT" \
       2>&1 | tee "/tmp/bringup_${STEP}.log"; then
    RESULT_LINE="$RESULT_LINE PASS |"
    echo "=== [run_step] $STEP: PASS ==="
else
    RESULT_LINE="$RESULT_LINE FAIL |"
    echo "=== [run_step] $STEP: FAIL ==="
fi

# ── Step 4: Append to RESULTS.md ─────────────────────────────────────────────
echo "$RESULT_LINE" >> "$RESULTS"
echo "=== [run_step] Result recorded in $RESULTS ==="
