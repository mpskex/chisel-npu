#!/usr/bin/env bash
# deploy.sh — build + install chisel_npu_py on the FPGA host.
#
# Steps (all idempotent):
#   1. rsync sources + tests to ~/chisel_npu_py on the FPGA host
#   2. ensure python3-dev (needed for the pybind11 build; apt install if missing)
#   3. create a venv and pip-install numpy + pytest
#   4. pip install . (PEP 517 pulls pybind11; compiles chisel_npu_py._native)
#   5. install a udev rule granting rw access to /dev/xdma0_* (no sudo needed
#      afterwards), chmod the live nodes, verify permissions
#   6. run `python -m chisel_npu_py selftest`
#
# Env: FPGA_HOST (SSH target), SSH_IDENTITY (optional key path) — see .env.sh.

set -euo pipefail

PY_DIR="$(cd "$(dirname "$0")/.." && pwd)"
REPO_ROOT="$(cd "$PY_DIR/../.." && pwd)"

if [ -z "${FPGA_HOST:-}" ]; then
  # shellcheck source=/dev/null
  source "$REPO_ROOT/.env.sh" >/dev/null 2>&1 || true
fi
FPGA_HOST="${FPGA_HOST:?FPGA_HOST not set — source .env.sh or export it}"
SSH_IDENTITY="${SSH_IDENTITY:-$HOME/.ssh/id_fpga_local}"
SSH_OPTS=(-o BatchMode=yes -o ConnectTimeout=15 -o StrictHostKeyChecking=no)
[ -f "$SSH_IDENTITY" ] && SSH_OPTS+=(-i "$SSH_IDENTITY")

REMOTE_DIR="~/chisel_npu_py"

echo "==> [1/6] rsync sources to ${FPGA_HOST}:${REMOTE_DIR}"
rsync -az --delete \
  --exclude .venv --exclude dist --exclude __pycache__ \
  --exclude '*.so' --exclude '*.pyc' --exclude '.pytest_cache' \
  -e "ssh ${SSH_OPTS[*]}" \
  "$PY_DIR/" "${FPGA_HOST}:${REMOTE_DIR}/"

echo "==> [2-6/6] remote setup (venv, build, udev, selftest)"
REMOTE_SCRIPT=$(cat <<'REMOTE_EOF'
set -euo pipefail
cd ~/chisel_npu_py
PY=python3

echo "---- ensure python3-dev"
INC=$($PY -c 'import sysconfig; print(sysconfig.get_paths()["include"])')
if [ ! -f "$INC/Python.h" ]; then
  sudo apt-get install -y python3-dev
fi

echo "---- create venv"
if [ ! -x .venv/bin/pip ]; then
  rm -rf .venv
  if ! $PY -m venv .venv; then
    sudo apt-get install -y python3-venv
    $PY -m venv .venv
  fi
fi
.venv/bin/pip install --upgrade pip wheel >/dev/null
# numpy<2: FPGA host CPU (AMD G-T56N) lacks SSE4.2; numpy 2.x SIGILLs here.
.venv/bin/pip install pytest 'numpy<2' >/dev/null

echo "---- build + install chisel-npu-py (pybind11 via PEP 517)"
.venv/bin/pip install . 

echo "---- udev rule (xdma nodes user-rw, no sudo)"
echo 'SUBSYSTEM=="xdma", MODE="0666"' | sudo tee /etc/udev/rules.d/99-xdma.rules >/dev/null
sudo udevadm control --reload-rules
sudo udevadm trigger --subsystem-match=xdma || true
# Belt and suspenders: udev may not re-run on already-created nodes.
sudo chmod 666 /dev/xdma0_* 2>/dev/null || true
ls -l /dev/xdma0_h2c_0 /dev/xdma0_c2h_0 /dev/xdma0_bypass

echo "---- selftest"
.venv/bin/python -m chisel_npu_py selftest
REMOTE_EOF
)
ssh "${SSH_OPTS[@]}" "$FPGA_HOST" "bash -s" <<< "$REMOTE_SCRIPT"
echo "==> done. Run the hardware tests with: make py-test-hw"
