#!/usr/bin/env bash
# capture_engine_ila.sh — headless ILA capture of the engine DMA store wedge.
#
# Arms the u_npu_ila core (trigger on DMA state == WAW/6, the store phase),
# then runs ONE NPU program on the FPGA host via chisel_npu_py.  If the run
# wedges (done never asserts) the capture should show the store stuck; if it
# completes, the capture shows a normal 6->7->8->9 store.  Dumps the waveform
# CSV.  Repeats for --loop N captures, stopping early if the run wedges.
#
# Usage: tool/hw/capture_engine_ila.sh <out_base> [--loop N]

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
source "$REPO_ROOT/.env.sh" >/dev/null 2>&1 || true

LTX="${LTX:-$REPO_ROOT/ip/vivado/xc7k480t/top_npu_engine_with_ila.ltx}"
VIVADO_BIN="${VIVADO:-$HOME/Vivado/2025.2/Vivado/bin/vivado}"
OUT_BASE="${1:-/tmp/ila_store}"
LOOP="${2:-6}"

arm_ila() {
    "$VIVADO_BIN" -mode tcl -nolog -nojournal <<TCLEOF
open_hw_manager
connect_hw_server -url 127.0.0.1:3121
current_hw_target [get_hw_targets */xilinx_tcf/Xilinx/*]
open_hw_target
current_hw_device [get_hw_devices xc7k480t_0]
refresh_hw_device -update_hw_probes false [current_hw_device]
set_property PROBES.FILE      {$LTX} [current_hw_device]
set_property FULL_PROBES.FILE {$LTX} [current_hw_device]
refresh_hw_device [current_hw_device]
set ila [lindex [get_hw_ilas -of_objects [current_hw_device]] 0]
set_property CONTROL.DATA_DEPTH       4096 \$ila
set_property CONTROL.TRIGGER_POSITION 1024 \$ila
set st [get_hw_probes -of_objects \$ila -filter {NAME =~ *dma/state}]
if {[llength \$st] == 0} { puts "ERROR: no state probe"; exit 1 }
set_property TRIGGER_COMPARE_VALUE eq4'b0110 [lindex \$st 0]
run_hw_ila \$ila
puts "ILA_ARMED"
close_hw_target
disconnect_hw_server
close_hw_manager
TCLEOF
}

dump_ila() {
    local csv="$1"
    "$VIVADO_BIN" -mode tcl -nolog -nojournal <<TCLEOF
open_hw_manager
connect_hw_server -url 127.0.0.1:3121
current_hw_target [get_hw_targets */xilinx_tcf/Xilinx/*]
open_hw_target
current_hw_device [get_hw_devices xc7k480t_0]
refresh_hw_device -update_hw_probes false [current_hw_device]
set_property PROBES.FILE      {$LTX} [current_hw_device]
set_property FULL_PROBES.FILE {$LTX} [current_hw_device]
refresh_hw_device [current_hw_device]
set ila [lindex [get_hw_ilas -of_objects [current_hw_device]] 0]
wait_on_hw_ila \$ila 10
upload_hw_ila_data \$ila
write_hw_ila_data -force -csv_file {$csv} \$ila
puts "CAPTURE {$csv}"
close_hw_target
disconnect_hw_server
close_hw_manager
TCLEOF
}

for ((i = 0; i < LOOP; i++)); do
    csv="$OUT_BASE.$i.csv"
    echo "=== iteration $i: arming ILA ==="
    arm_ila
    echo "=== iteration $i: running NPU program ==="
    set +e
    result=$(ssh -i "$SSH_IDENTITY" "$FPGA_HOST" "cd ~/chisel_npu_py && .venv/bin/python - <<'PYEOF'
from chisel_npu_py import ChiselNPU, isa
import numpy as np
npu = ChiselNPU()
k = npu.cfg.K
a = np.arange(1, k + 1, dtype=np.int8)
b = np.arange(100, 100 + k, dtype=np.int8)
try:
    npu.run([isa.vle8(4, isa.SECT_A, 0), isa.vle8(8, isa.SECT_B, 0),
             isa.mma(2, 4, 8, 0), isa.mma_last(0,0,0,0),
             isa.vse32(2, isa.SECT_OUT, 0)], memories={'A': a, 'B': b})
    print('RUN_OK')
except Exception as e:
    print('RUN_ERR', type(e).__name__)
PYEOF")
    set -e
    echo "$result"
    sleep 2
    echo "=== iteration $i: dumping capture ==="
    dump_ila "$csv"
    echo "$result" | grep -q RUN_ERR && { echo "WEDGE CAPTURED: $csv"; exit 0; }
done
echo "Done. No wedge in $LOOP iterations."
