#!/usr/bin/env bash
# Build the board-facing release manifest for NpuPeripheral.
#
# Defaults emit the K=32 vu9p release.  The xc7k480t build overrides:
#   K=16 SV=top_k16.sv OUT_SV=NpuPeripheral_K16.sv \
#       OUT_JSON=npu_peripheral_k16.json tool/release_manifest.sh
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
out="$root/build"
mkdir -p "$out"
sv="${SV:-$root/top.sv}"
out_sv="${OUT_SV:-NpuPeripheral.sv}"
out_json="${OUT_JSON:-npu_peripheral.json}"
k="${K:-32}"
l="${L:-$k}"
w="${W:-8}"
n="${N:-8}"

cp "$sv" "$out/$out_sv"
rtl_sha=$(sha256sum "$out/$out_sv" | awk '{print $1}')
proto_sha=$(sha256sum "$root/src/main/scala/peripheral/NpuCtrlProtocol.scala" | awk '{print $1}')
git_rev=$(git -C "$root" rev-parse HEAD)
built=$(date -u +"%Y-%m-%dT%H:%M:%SZ")
cat > "$out/$out_json" <<JSON
{
  "ip_version": "0.1.0",
  "protocol": {"major": 1, "minor": 0},
  "params": {"K": $k, "N": $n, "L": $l, "W": $w,
             "NUM_SEG": 5, "TRACE_DEPTH": 256,
             "ADDR_W": 64, "DATA_W": 512},
  "top": "NpuPeripheral",
  "git": "$git_rev",
  "built": "$built",
  "rtl_sha256": "$rtl_sha",
  "protocol_sha256": "$proto_sha"
}
JSON
echo "release: $out/$out_sv"
echo "manifest: $out/$out_json"
