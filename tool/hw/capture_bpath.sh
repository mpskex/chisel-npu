#!/usr/bin/env bash
set -euo pipefail
REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
source "$REPO_ROOT/.env.sh" >/dev/null 2>&1 || true
LTX="$REPO_ROOT/ip/vivado/xc7k480t/top_npu_engine_with_ila.ltx"
VIVADO_BIN="${VIVADO:-$HOME/Vivado/2025.2/Vivado/bin/vivado}"
LOOP="${1:-16}"

for ((i = 0; i < LOOP; i++)); do
  sed "s|__LTX__|$LTX|" "$REPO_ROOT/tool/hw/arm_cnt.tcl" | "$VIVADO_BIN" -mode tcl -nolog -nojournal >/dev/null 2>&1
  res=$(ssh -i "$SSH_IDENTITY" "$FPGA_HOST" "cd ~/chisel_npu_py && .venv/bin/python -c \"
from chisel_npu_py import ChiselNPU, isa
import numpy as np
npu=ChiselNPU(); k=npu.cfg.K
a=np.arange(1,k+1,dtype=np.int8); b=np.arange(100,100+k,dtype=np.int8)
try:
    r=npu.run([isa.vle8(4,isa.SECT_A,0),isa.vle8(8,isa.SECT_B,0),isa.mma(2,4,8,0),isa.mma_last(0,0,0,0),isa.vse32(2,isa.SECT_OUT,0)],memories={'A':a,'B':b})
    out=r['OUT'][:k].astype(np.int32)
    cols=[c for c in range(k) if np.array_equal(out,a.astype(np.int32)*int(b[c]))]
    print('GOOD' if len(cols)==1 else 'WRONG', out[:4].tolist())
except Exception as e:
    print('ERR', type(e).__name__)
\"")
  sleep 2
  csv="/tmp/bpath_$i.csv"
  sed "s|__LTX__|$LTX|; s|__OUT__|$csv|" "$REPO_ROOT/tool/hw/dump_ila.tcl" | "$VIVADO_BIN" -mode tcl -nolog -nojournal >/dev/null 2>&1
  echo "iter $i: $res"
  echo "$res" | grep -q "WRONG" && { echo "WRONG captured at iter $i: $csv"; exit 0; }
done
echo "No wrong in $LOOP iters"
