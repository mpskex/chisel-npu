# XC7K480T Reference Design

Headless TCL clone of the proven `XC7K480T_XDMA_Test` reference project
(board: xc7k480tffg1156-2 PCIe carrier). This design reliably cold-boots PCIe on the
AMD FCH host because it uses Vivado's default
bitstream configuration settings (**no `PERSIST=YES`**, no `CONFIG_MODE` override).

## Design contents

- `xdma_0` — XDMA 4.2 PCIe Gen2 ×8, 128-bit AXI @ 250 MHz
- `mig_7series_0` — Dual DDR3 MIG (C0 + C1)
- `microblaze_0` — Soft processor (boots with default BRAM, no ELF needed)
- `axi_smc` — SmartConnect: XDMA M_AXI → MIG
- `clk_wiz` — Clock generator

## Build (headless, ~50 minutes total)

```bash
# Step 1: Create project + run synthesis (~15 min)
~/Xilinx/2025.2/Vivado/bin/vivado -mode batch \
    -source ip/vivado/xc7k480t.reference/scripts/create_project.tcl \
    -journal ip/vivado/xc7k480t.reference/scripts/create_project.jou \
    -log     ip/vivado/xc7k480t.reference/scripts/create_project.log

# Step 2: Implementation + write_bitstream (~35 min)
~/Xilinx/2025.2/Vivado/bin/vivado -mode batch \
    -source ip/vivado/xc7k480t.reference/scripts/build.tcl \
    -journal ip/vivado/xc7k480t.reference/scripts/build.jou \
    -log     ip/vivado/xc7k480t.reference/scripts/build.log
```

Output: `xc7k480t.reference/xc7k480t_ref/xc7k480t_ref.runs/impl_1/top_wrapper.bit`

## Flash

```bash
cd /path/to/chisel-npu
tool/hw/program_flash.sh \
    ip/vivado/xc7k480t.reference/xc7k480t_ref/xc7k480t_ref.runs/impl_1/top_wrapper.bit
```

## Hardware bring-up

After flashing, warm-reboot the FPGA host and verify:

```bash
python3 tool/hw/serial_console.py "sudo lspci -nn | grep 10ee"
# Expected: 01:00.0 Memory controller [0580]: Xilinx Corporation Device [10ee:7028]
```

## Root cause note

`BITSTREAM.CONFIG.PERSIST=YES` sets `COR0[22]` (DRIVEDONE), delaying the
FPGA DONE pin assertion by one startup cycle. This causes the PCIe hard
block to miss the AMD FCH cold-boot link-training window. This design uses
Vivado defaults (PERSIST=NO) and cold-boots reliably.
