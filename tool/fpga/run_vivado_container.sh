#!/usr/bin/env bash
# Run one Vivado Tcl script inside the Ubuntu 22.04 runtime container.
#
# The host is Ubuntu 26.04/glibc 2.43; Vivado 2025.2 aborts on that glibc, so
# every build runs in the 22.04 container with the host install bind-mounted.
#
# Usage:
#   run_vivado_container.sh <env-file> <tcl-script> <log> <journal>
#
# Environment (host):
#   REPO_ROOT      repo path (default: current directory)
#   VIVADO_ROOT    Vivado install root (default: $HOME/Vivado/2025.2)
#   VIVADO_IMAGE   runtime image (default: vivado-runtime:22.04)
set -euo pipefail

envfile=${1:?env file}
tcl=${2:?tcl script}
log=${3:?log file}
journal=${4:?journal file}

REPO_ROOT=${REPO_ROOT:-$(pwd)}
VIVADO_ROOT=${VIVADO_ROOT:-$HOME/Vivado/2025.2}
VIVADO_IMAGE=${VIVADO_IMAGE:-vivado-runtime:22.04}
VIVADO_BIN="$VIVADO_ROOT/Vivado/bin/vivado"

if [ ! -x "$VIVADO_BIN" ]; then
    echo "ERROR: Vivado not found at $VIVADO_BIN" >&2
    exit 1
fi
if [ ! -f "$envfile" ]; then
    echo "ERROR: env file not found: $envfile" >&2
    exit 1
fi
RUN_NAME=$(sed -n 's/^NPU_CONTAINER_NAME=//p' "$envfile" | head -1)
if [ -z "$RUN_NAME" ]; then
    RUN_NAME="npu_impl_$(basename "$(dirname "$log")")_$(basename "${log%.log}")"
fi
mkdir -p "$(dirname "$log")" "$(dirname "$journal")"

# A previous attempt may have left the named container behind if the docker
# CLI was killed on timeout.  Remove it before reusing the name.
docker rm -f "$RUN_NAME" >/dev/null 2>&1 || true

exec docker run --rm --ipc=host --net=host \
    --name "$RUN_NAME" \
    -u "$(id -u):$(id -g)" \
    --env-file "$envfile" \
    -e HOME="$HOME" \
    -e XILINX_LOCAL_USER_DATA=no \
    -v "$VIVADO_ROOT:$VIVADO_ROOT" \
    -v "$HOME/.Xilinx:$HOME/.Xilinx" \
    -v "$REPO_ROOT:$REPO_ROOT" \
    -w "$REPO_ROOT" \
    "$VIVADO_IMAGE" \
    "$VIVADO_BIN" -mode batch \
        -source "$tcl" \
        -log "$log" \
        -journal "$journal"
