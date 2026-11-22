#!/usr/bin/env bash
# Build the board-facing release manifest for NpuPeripheral.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
out="$root/build"
mkdir -p "$out"
cp "$root/top.sv" "$out/NpuPeripheral.sv"
rtl_sha=$(sha256sum "$out/NpuPeripheral.sv" | awk '{print $1}')
proto_sha=$(sha256sum "$root/src/main/scala/peripheral/NpuCtrlProtocol.scala" | awk '{print $1}')
git_rev=$(git -C "$root" rev-parse HEAD)
built=$(date -u +"%Y-%m-%dT%H:%M:%SZ")
cat > "$out/npu_peripheral.json" <<JSON
{
  "ip_version": "0.1.0",
  "protocol": {"major": 1, "minor": 0},
  "params": {"K": 32, "N": 8, "L": 32, "W": 8,
             "NUM_SEG": 5, "TRACE_DEPTH": 256,
             "ADDR_W": 64, "DATA_W": 512},
  "top": "NpuPeripheral",
  "git": "$git_rev",
  "built": "$built",
  "rtl_sha256": "$rtl_sha",
  "protocol_sha256": "$proto_sha"
}
JSON
echo "release: $out/NpuPeripheral.sv"
echo "manifest: $out/npu_peripheral.json"
