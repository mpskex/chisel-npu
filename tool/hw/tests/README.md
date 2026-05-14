# FPGA Hardware Tests

Python/pytest test suite for the xc7k480tffg1156-2 FPGA verification platform.

## Quick start

```bash
# From repo root — run all Phase 1 smoke tests:
python -m pytest tool/hw/tests/ -v

# Skip bandwidth tests (fast):
python -m pytest tool/hw/tests/ -v -m "not slow"

# Full bringup (program + reboot + test):
tool/hw/bringup_full.sh
```

## Test groups

| File | What it tests | Marks |
|---|---|---|
| `test_pcie_link.py` | PCIe Gen2×8 link status, driver load, dev nodes | `hw` |
| `test_bar_ctrl_lite.py` | BAR0 register access to ctrl_lite via reg_rw | `hw` |
| `test_ddr3_c0_loopback.py` | DDR3 C0 write/read loopback (1 KB, 1 MB, high addr) | `hw` |
| `test_ddr3_c0_bandwidth.py` | XDMA DMA throughput (≥ 2 GB/s H2C + C2H) | `hw`, `slow` |
| `test_npu_kick.py` | NPU FSM start→done (Phase 2, **xfail** until C1 path exists) | `hw`, `xfail` |

## CLI options

```bash
python -m pytest tool/hw/tests/ \
    --fpga-host fpga              # SSH alias (default: fpga)
    --bitstream path/to/top.bit   # bitstream to program (default: impl_1 output)
    --skip-program                # skip JTAG programming step
```

## Environment variables

| Variable | Default | Description |
|---|---|---|
| `FPGA_HOST` | `fpga` | SSH target |
| `BITSTREAM` | `ip/.../top_npu.bit` | Path to .bit file |
| `SKIP_PROGRAM` | (unset) | Set to `1` to skip flashing |
