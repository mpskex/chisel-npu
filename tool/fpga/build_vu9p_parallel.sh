#!/usr/bin/env bash
# Build the xcvu9p NPU bitstream with the parallel ML-on implementation flow.
#
#   1. Chisel -> top.sv            (make build, unless --skip-chisel)
#   2. synth + opt_design          (project run in the 22.04 Vivado container)
#   3. parallel place/route        (tool/fpga/parallel_impl.py)
#
# Usage:
#   tool/fpga/build_vu9p_parallel.sh [options]
#
# Options:
#   --skip-chisel              reuse the existing top.sv
#   --skip-synth               reuse the existing hw_platform_opt.dcp
#   --jobs N                   concurrent candidates (default 3)
#   --threads N                general.maxThreads per candidate (default 8)
#   --synth-jobs N             parallel OOC IP synthesis jobs (default 4)
#   --ml on|off|hybrid         ML model mode (default on)
#   --place-directives LIST    comma-separated place directives
#   --route-directives LIST    comma-separated route directives
#   --ultrathreads             opt-in place_design -ultrathreads (experimental;
#                              crashes in HAPLFSimPLFlow::collectControlSetInfo)
#   --no-ultrathreads          default; plain 8-thread placement
#   --resume                   reuse successful candidate checkpoints
#   --dry-run                  print the plan; do not run Vivado
#
# Environment:
#   VIVADO_ROOT   Vivado install (default $HOME/Vivado/2025.2)
#   VIVADO_IMAGE  runtime image (default vivado-runtime:22.04)
set -euo pipefail

REPO_ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
cd "$REPO_ROOT"

VIVADO_ROOT=${VIVADO_ROOT:-$HOME/Vivado/2025.2}
VIVADO_IMAGE=${VIVADO_IMAGE:-vivado-runtime:22.04}
BUILD_DIR="$REPO_ROOT/build/parallel_build"
OPT_DCP="$REPO_ROOT/ip/vivado/xcvu9p/prj/prj.runs/impl_1/hw_platform_opt.dcp"

SKIP_CHISEL=0
SKIP_SYNTH=0
JOBS=3
THREADS=8
SYNTH_JOBS=4
ML=on
PLACE_DIRECTIVES="ExtraTimingOpt,SSI_SpreadLogic_high,AltSpreadLogic_high"
ROUTE_DIRECTIVES="Default"
ULTRA=0
RESUME=0
DRY_RUN=0

while [ $# -gt 0 ]; do
    case "$1" in
        --skip-chisel) SKIP_CHISEL=1 ;;
        --skip-synth) SKIP_SYNTH=1 ;;
        --jobs) JOBS=$2; shift ;;
        --threads) THREADS=$2; shift ;;
        --synth-jobs) SYNTH_JOBS=$2; shift ;;
        --ml) ML=$2; shift ;;
        --place-directives) PLACE_DIRECTIVES=$2; shift ;;
        --route-directives) ROUTE_DIRECTIVES=$2; shift ;;
        --ultrathreads) ULTRA=1 ;;
        --no-ultrathreads) ULTRA=0 ;;
        --resume) RESUME=1 ;;
        --dry-run) DRY_RUN=1 ;;
        -h|--help) sed -n '1,35p' "$0"; exit 0 ;;
        *) echo "ERROR: unknown option: $1" >&2; exit 2 ;;
    esac
    shift
done

mkdir -p "$BUILD_DIR"
export VIVADO_ROOT VIVADO_IMAGE

if [ "$DRY_RUN" = "0" ] && [ "$SKIP_CHISEL" = "0" ]; then
    echo "=== Chisel -> top.sv ==="
    make build
fi

if [ "$DRY_RUN" = "0" ] && [ "$SKIP_SYNTH" = "0" ]; then
    echo "=== synth + opt_design ==="
    ENVFILE="$BUILD_DIR/synth.env"
    cat > "$ENVFILE" <<EOF
NPU_STOP_AFTER_OPT=1
NPU_MAX_THREADS=$THREADS
NPU_ML=$ML
NPU_ULTRATHREADS=$ULTRA
VIVADO_JOBS=$SYNTH_JOBS
EOF
    bash tool/fpga/run_vivado_container.sh \
        "$ENVFILE" \
        "$REPO_ROOT/ip/vivado/xcvu9p/scripts/build_npu_vu9p.tcl" \
        "$BUILD_DIR/synth_opt.log" \
        "$BUILD_DIR/synth_opt.jou"
    if [ ! -f "$OPT_DCP" ]; then
        echo "ERROR: opt checkpoint not produced: $OPT_DCP" >&2
        exit 1
    fi
fi

if [ "$SKIP_SYNTH" = "1" ] && [ ! -f "$OPT_DCP" ]; then
    echo "ERROR: --skip-synth requested but $OPT_DCP does not exist" >&2
    exit 1
fi

echo "=== parallel place / phys_opt / route ==="
CMD=(python3 tool/fpga/parallel_impl.py
    --mode split
    --checkpoint "$OPT_DCP"
    --out-dir "$BUILD_DIR/impl"
    --bitstream-dst "$REPO_ROOT/ip/vivado/xcvu9p/top_npu_vu9p.bit"
    --jobs "$JOBS"
    --threads "$THREADS"
    --ml "$ML"
    --place-directives "$PLACE_DIRECTIVES"
    --route-directives "$ROUTE_DIRECTIVES")
if [ "$ULTRA" = "1" ]; then
    CMD+=(--ultrathreads)
else
    CMD+=(--no-ultrathreads)
fi
if [ "$RESUME" = "1" ]; then
    CMD+=(--resume)
fi
if [ "$DRY_RUN" = "1" ]; then
    CMD+=(--dry-run)
fi
"${CMD[@]}"
