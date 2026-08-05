#!/usr/bin/env bash
# bringup_npu_jtag.sh — Load NPU bitstream via JTAG SRAM + SBR, with reboot retry loop.
#
# Strategy (avoids touching BPI flash, works around AMD FCH cold-boot issue):
#   1. Ensure PCIe link is trained (warm reboot loop until reference design enumerates)
#   2. JTAG-load top_npu.bit into FPGA SRAM (overwrites reference design in SRAM only)
#   3. SBR the PCIe bridge to retrain link with NPU bitstream
#   4. Load XDMA driver and verify /dev/xdma0_*
#   5. Run pytest
#
# The BPI flash always holds reference .bak — so any reboot restores the reference.
# Flash in flash = reference .bak (safe fallback).
#
# Usage:
#   tool/hw/bringup_npu_jtag.sh [max_reboot_attempts]

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
VIVADO="${VIVADO:-$HOME/Xilinx/2025.2/Vivado/bin/vivado}"
FPGA_HOST="${FPGA_HOST:-fpga}"
SSH_IDENTITY="${SSH_IDENTITY:-}"
HW_SERVER="${HW_SERVER:-localhost:3121}"
NPU_BIT="${NPU_BIT:-$REPO_ROOT/ip/vivado/xc7k480t/npu_fpga/npu_fpga.runs/impl_1/top_npu.bit}"
REF_BIT="${REF_BIT:-$REPO_ROOT/ip/vivado/xc7k480t.reference/top_wrapper.bit}"
MAX_ATTEMPTS="${1:-5}"
XDMA_DRV_DIR="~/dma_ip_drivers/XDMA/linux-kernel/xdma"
BRIDGE="00:15.0"

SSH_OPTS="-o ConnectTimeout=5 -o BatchMode=yes -o StrictHostKeyChecking=no"
[ -n "$SSH_IDENTITY" ] && SSH_OPTS="$SSH_OPTS -i $SSH_IDENTITY"

log() { echo "[$(date '+%H:%M:%S')] $*"; }

check_pcie() {
    ssh $SSH_OPTS "$FPGA_HOST" 'sudo lspci -nn 2>/dev/null | grep "10ee:7028"' 2>/dev/null
}

wait_ssh() {
    local timeout=${1:-120}
    local elapsed=0
    while ! ssh $SSH_OPTS "$FPGA_HOST" 'echo UP' &>/dev/null; do
        sleep 5; elapsed=$((elapsed+5))
        [ $elapsed -ge $timeout ] && return 1
        echo -n "."
    done
    echo ""
    return 0
}

reboot_fpga() {
    log "Rebooting $FPGA_HOST..."
    ssh $SSH_OPTS "$FPGA_HOST" 'sudo /sbin/reboot' 2>/dev/null || true
    sleep 75
    log "Waiting for SSH..."
    wait_ssh 120 || { log "ERROR: SSH timeout after reboot"; return 1; }
    log "SSH up."
}

jtag_load() {
    local bit="$1"
    local label="$2"
    log "JTAG-loading $label into FPGA SRAM..."
    local tcl
    tcl=$(mktemp /tmp/jtag_load_XXXXXX.tcl)
    cat > "$tcl" << TCLEOF
open_hw_manager
connect_hw_server -url $HW_SERVER -allow_non_jtag
current_hw_target [lindex [get_hw_targets] 0]
set_property PARAM.FREQUENCY 15000000 [current_hw_target]
open_hw_target
current_hw_device [lindex [get_hw_devices xc7k480t_0] 0]
set_property PROGRAM.FILE {$bit} [current_hw_device]
program_hw_devices [current_hw_device]
refresh_hw_device [current_hw_device]
puts "JTAG_LOAD_OK: $label"
close_hw_target
disconnect_hw_server
close_hw_manager
TCLEOF
    "$VIVADO" -mode batch -source "$tcl" \
        -journal /tmp/jtag_load.jou -log /tmp/jtag_load.log \
        2>&1 | grep -E "JTAG_LOAD_OK|ERROR|27-3164" | grep -v "^#" || true
    rm -f "$tcl"
}

sbr_rescan() {
    log "Performing SBR + rescan on $BRIDGE..."
    ssh -o BatchMode=yes -o StrictHostKeyChecking=no "$FPGA_HOST" "
        sudo setpci -s $BRIDGE BRIDGE_CONTROL=0x0040
        sleep 0.5
        sudo setpci -s $BRIDGE BRIDGE_CONTROL=0x0000
        sleep 5
        echo 1 | sudo tee /sys/bus/pci/devices/0000:$BRIDGE/rescan
    " 2>/dev/null || true
    sleep 3
}

# ── Validate inputs ──────────────────────────────────────────────────────────
[ -f "$NPU_BIT" ] || { log "ERROR: NPU bitstream not found: $NPU_BIT"; exit 1; }
[ -x "$VIVADO"  ] || { log "ERROR: Vivado not found: $VIVADO"; exit 1; }
log "NPU bit : $NPU_BIT ($(du -sh "$NPU_BIT" | cut -f1))"
log "Ref bit : $REF_BIT"
log "Max reboot attempts: $MAX_ATTEMPTS"
echo ""

# ══════════════════════════════════════════════════════════════════════════════
# Phase 1: Ensure reference design PCIe link is alive
# ══════════════════════════════════════════════════════════════════════════════
log "=== Phase 1: Establish reference design PCIe link ==="

TRAINED=0
for attempt in $(seq 1 $MAX_ATTEMPTS); do
    log "Attempt $attempt/$MAX_ATTEMPTS: checking PCIe..."
    PCIE=$(check_pcie)
    if [ -n "$PCIE" ]; then
        log "PCIe enumerated: $PCIE"
        TRAINED=1
        break
    fi
    log "PCIe not found. Trying SBR..."
    sbr_rescan
    PCIE=$(check_pcie)
    if [ -n "$PCIE" ]; then
        log "PCIe enumerated after SBR: $PCIE"
        TRAINED=1
        break
    fi
    log "SBR did not help. Warm rebooting (attempt $attempt)..."
    reboot_fpga || { log "ERROR: Reboot failed"; exit 1; }
done

if [ $TRAINED -eq 0 ]; then
    log "ERROR: Could not establish PCIe link after $MAX_ATTEMPTS attempts."
    log "Check BPI flash content and physical PCIe connection."
    exit 1
fi

# ══════════════════════════════════════════════════════════════════════════════
# Phase 2: JTAG-load NPU bitstream, SBR to re-enumerate
# ══════════════════════════════════════════════════════════════════════════════
log ""
log "=== Phase 2: JTAG-load NPU bitstream + SBR ==="

# Unload XDMA driver cleanly before JTAG reprogram
log "Unloading XDMA driver..."
ssh -o BatchMode=yes -o StrictHostKeyChecking=no "$FPGA_HOST" \
    'sudo rmmod xdma 2>/dev/null; echo "driver unloaded"' 2>/dev/null || true
sleep 1

# JTAG-load NPU bit into FPGA SRAM
jtag_load "$NPU_BIT" "top_npu.bit"
sleep 2

# Warm-reboot the host — DO NOT use SBR/rescan (causes kernel freeze).
# During warm reboot, the FPGA SRAM retains the NPU bitstream (power stays on).
# The BIOS then sees the NPU PCIe endpoint during POST and enumerates it.
log "Warm-rebooting host (FPGA SRAM retains NPU bit through warm reboot)..."
ssh -o BatchMode=yes -o StrictHostKeyChecking=no "$FPGA_HOST" \
    'sudo /sbin/reboot' 2>/dev/null || true
sleep 75
log "Waiting for SSH..."
wait_ssh 120 || { log "ERROR: SSH timeout after NPU reboot"; exit 1; }

# Check if NPU bit trained PCIe at boot
NPU_PCIE=$(check_pcie)
if [ -z "$NPU_PCIE" ]; then
    log "WARNING: PCIe not enumerated with NPU bitstream after reboot."
    log "NPU bitstream PCIe startup timing may not meet AMD FCH window."
    log "BPI flash still has reference .bak — baseline is preserved."
    exit 2
fi
log "NPU PCIe enumerated: $NPU_PCIE"

# ══════════════════════════════════════════════════════════════════════════════
# Phase 3: Load XDMA driver + verify
# ══════════════════════════════════════════════════════════════════════════════
log ""
log "=== Phase 3: Load XDMA driver ==="

ssh -o BatchMode=yes -o StrictHostKeyChecking=no "$FPGA_HOST" "
    cd $XDMA_DRV_DIR
    sudo rmmod xdma 2>/dev/null; sudo insmod xdma.ko && echo 'XDMA driver loaded'
    sleep 1
    ls /dev/xdma0_* | wc -l
    sudo lspci -vvv -s 01:00.0 2>/dev/null | grep -E 'Subsystem|LnkSta:|LnkCap:'
" 2>/dev/null

# ══════════════════════════════════════════════════════════════════════════════
# Phase 4: Run pytest
# ══════════════════════════════════════════════════════════════════════════════
log ""
log "=== Phase 4: Run pytest ==="
cd "$REPO_ROOT"
pytest tool/hw/tests/ -m hw -v 2>&1 | tee /tmp/opencode/npu_jtag_pytest.log

log ""
log "=== DONE ==="
log "NPU bitstream functional test complete."
log "NOTE: BPI flash still holds reference .bak — any reboot restores reference."
log "To make NPU persistent in flash: tool/hw/program_flash.sh $NPU_BIT"
