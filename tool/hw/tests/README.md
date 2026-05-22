# FPGA Hardware Tests

Python / pytest test suite for the `xc7k480tffg1156-2` FPGA verification
platform. All tests run on real silicon — the FPGA host must be powered,
booted, the bitstream flashed, the XDMA driver loaded, and PCIe enumerated.

The canonical bring-up flow is `tool/hw/bringup_flash.py`; see
`docs/implementations/FPGA_XC7K480T.md` for the end-to-end recipe.

## Quick start

```bash
# 1. Build a bitstream (see ip/vivado/xc7k480t/README.md):
~/Xilinx/2025.2/Vivado/bin/vivado -mode batch \
    -source ip/vivado/xc7k480t/scripts/build_npu.tcl

# 2. Flash + bring up + run 9 baseline smoke tests over serial:
python3 tool/hw/bringup_flash.py \
    ip/vivado/xc7k480t/top_npu.bit --max-attempts 6

# 3. Run the MMALU compute tests via pytest (requires SSH to FPGA host
#    or a serial-equivalent runner):
python3 -m pytest tool/hw/tests/ -v -m hw

# Skip slow bandwidth tests:
python3 -m pytest tool/hw/tests/ -v -m "hw and not slow"
```

## Test files

| File | What it tests | Marks |
|:-----|:--------------|:------|
| `test_pcie_link.py` | PCIe link status, driver load, dev nodes | `hw` |
| `test_bar_ctrl_lite.py` | BAR2 register access to ctrl_lite | `hw` |
| `test_ddr3_c0_loopback.py` | DDR3 C0 write/read loopback (1 KB, 1 MB, high addr) | `hw` |
| `test_ddr3_c0_bandwidth.py` | XDMA DMA throughput measurement | `hw`, `slow` |
| `test_npu_kick.py` | NPU FSM `start → done` (verified PASS on V10) | `hw` |
| `test_mmalu_compute.py` | End-to-end MMALU compute, 6 tests (all PASS on V10) | `hw` |

### `test_mmalu_compute.py` in detail

Six tests covering the MMALU compute path on the V10 (`axi_xbar` +
`npu_subsys`) bitstream:

| Test | Setup | Pass criterion |
|:-----|:------|:---------------|
| `test_mmalu_done_smoke` | (any) | FSM kick → `done` within 1 s |
| `test_mmalu_zero_in_zero_out` | A=0, B=0, ACCUM=0 | OUT = all 0 |
| `test_mmalu_accum_passthrough` | A=0, B=0, random ACCUM | OUT == ACCUM (exact) |
| `test_mmalu_zero_a_kills_multiplier` | A=0, B=1..32, random ACCUM | OUT == ACCUM |
| `test_mmalu_multiplier_alive` | A=10, B=7, ACCUM=0 | OUT has non-zero entries |
| `test_mmalu_outer_b_last` | random A, B, ACCUM | OUT[i] = A[i]·B[K-1] + ACCUM[i] |

The host writes A/B/ACCUM via `/dev/xdma0_h2c_0` to MIG C0 at
`0x4000_0000`, pulses `start=1` on ctrl_lite BAR2+0x0, polls `done`, then
reads OUT back via `/dev/xdma0_c2h_0`. The formula in
`test_mmalu_outer_b_last` follows from the one-shot kick semantics of the
current `npu_dma_master.v`.

## CLI options

```bash
python -m pytest tool/hw/tests/ \
    --fpga-host fpga              # SSH alias (default: fpga)
    --bitstream path/to/top.bit   # bitstream to program
    --skip-program                # skip JTAG programming step
```

## Environment variables

| Variable | Default | Description |
|:---------|:--------|:------------|
| `FPGA_HOST` | `fpga` | SSH target |
| `BITSTREAM` | `ip/vivado/xc7k480t/top_npu.bit` | Path to `.bit` file |
| `SKIP_PROGRAM` | (unset) | Set to `1` to skip flashing |
| `SERIAL_PORT` | `/dev/ttyUSB0` | Serial console (used by bringup_flash.py) |

## Hardware-level debug

If a regression in the NPU FSM or AXI handshake needs cycle-level
inspection, build the debug bitstream:

```bash
~/Xilinx/2025.2/Vivado/bin/vivado -mode batch \
    -source ip/vivado/xc7k480t/scripts/build_npu_with_ila.tcl
```

The resulting `top_npu_with_ila.bit` + `top_npu_with_ila.ltx` can be
loaded in Vivado HW Manager. `u_npu_ila` exposes
`state`/`beat_cnt`/`rpipe_valid`/`rdata_pipe` plus AXI handshake shadow
regs from `npu_dma_master`. See
`docs/implementations/FPGA_XC7K480T.md` § "ILA Debug Methodology" for a
worked headless-TCL capture example.
