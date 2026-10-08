# xcvu9p NPU platform — build & remote bring-up

NPU program-engine (`NpuProgramEngineFrontend`, `top.sv`) integrated with the
Alivu9p reference platform on `xcvu9p-flgb2104-2-e`, running on the
`mpsk-vu9p-server` (10.16.0.35).

Chisel RTL and the `chisel_npu_py` userspace driver are unchanged from the
xc7k480t configuration. Only the FPGA wrapper differs (UltraScale+ XDMA +
4× MIG DDR4 + GTY PCIe instead of 7-series XDMA + MIG DDR3).

## Hardware

| Item | Value |
|:-----|:------|
| FPGA | `xcvu9p-flgb2104-2-e` |
| PCIe | XDMA v4.2, Gen3 ×8 (8 GT/s), BDF `10:00.0` behind root port `00:01.1` |
| VID:DID | `10ee:903f`, subsystem `0007` |
| DDR4 | 4× MIG UltraScale+ (MT40A1G16WBU-083E, 72-bit, ECC), ui_clk 300 MHz |
| Fabric clock | 200 MHz (`clk_wiz_fabric` MMCM from `axi_aclk` 250 MHz) |
| JTAG | Digilent on server, `hw_server` at `10.16.0.35:3121`, device `xcvu9p_0` |

## Architecture (`src/hw_platform.v` = NPU-integrated top)

```
XDMA M_AXI (512b @250) ─────────────────────────────► ddr_top S00 ─► 4× DDR4
XDMA M_AXI_BYPASS (512b @250)
  └► byp_dw (512→32) ─► byp_cc (250→200) ─► byp_pc (AXI4→Lite) ─► ctrl_lite
NPU engine (128b @200)
  └► npu_cc (200→250) ─► npu_dw (128→512 @250) ─────► ddr_top S01 ─► DDR4 xbar
```

## Key integration fixes

1. **DDR4 MIG narrow bursts** — the engine's AXI master is 128-bit, so it
   issues 16-byte beats. The DDR4 MIG defaults to `AXI_Narrow_Burst=false`
   (full-width only); without narrow-burst support the engine's instruction
   fetch / single-beat vector transfers read `0xFFFFFFFF` (illegal
   instruction). `scripts/setup_platform.tcl` sets
   `CONFIG.C0.DDR4_AxiNarrowBurst=true` and re-synthesizes the MIG.
2. **No converter packing** — `npu_dw` uses `PACKING_LEVEL=1` (default). With
   the MIG accepting narrow bursts, packing is unnecessary.
3. **200 MHz engine clock** — the engine only works at 200 MHz; a 100 MHz
   fabric clock breaks the frontend fetch. Do not lower `clk_wiz_fabric`.

All 7 `chisel_npu_py` hardware tests pass.

## Build (local Vivado 2025.2)

```bash
make build                                  # Chisel -> top.sv (Docker)

# one-time platform IP setup (DDR4 narrow, XDMA bypass, converters, 200 MHz clk)
~/Vivado/2025.2/Vivado/bin/vivado -mode batch \
    -source ip/vivado/xcvu9p/scripts/setup_platform.tcl

# bitstream
~/Vivado/2025.2/Vivado/bin/vivado -mode batch \
    -source ip/vivado/xcvu9p/scripts/build_npu_vu9p.tcl
# -> ip/vivado/xcvu9p/top_npu_vu9p.bit  (~80 MB)
```

Runtime: ~25 min synthesis + ~2–3 h placement/routing (the NPU buses make the
GTY-left-edge region congested; `AltSpreadLogic_high` is used). Timing closes
with positive slack, 0 routing errors.

## Remote programming (no local JTAG)

Program from the **local** dev host through the server's `hw_server`:

```tcl
open_hw_manager
connect_hw_server -url 10.16.0.35:3121 -allow_non_jtag
current_hw_target [lindex [get_hw_targets] 0]
open_hw_target
current_hw_device [lindex [get_hw_devices xcvu9p_0] 0]
set_property PROGRAM.FILE {ip/vivado/xcvu9p/top_npu_vu9p.bit} [current_hw_device]
program_hw_devices [current_hw_device]
```

`xsdb` cannot list pure-FPGA targets; use Vivado `connect_hw_server`.

## Host bring-up (server)

The AMD FCH does not enumerate the card after a hot JTAG load. Sequence:

1. JTAG-load the bitstream (above).
2. `sudo /sbin/reboot` — FPGA SRAM survives a warm reboot; PCIe then trains
   (`10ee:903f` at `10:00.0`).
3. Load the **out-of-tree** XDMA driver (the in-kernel `xdma.ko` is a
   dmaengine-only driver with no `/dev/xdma0_*` char nodes):
   ```bash
   cd ~/dma_ip_drivers/XDMA/linux-kernel/xdma   # git clone Xilinx/dma_ip_drivers
   cp -n ../include/libxdma_api.h .             # modern kbuild ignores EXTRA_CFLAGS
   make
   sudo rmmod xdma 2>/dev/null; sudo insmod xdma.ko
   ```
4. Install udev/permissions (`make py-deploy` does this).

The driver must be reloaded after every reboot (`insmod xdma.ko`).

## Driver + tests

```bash
FPGA_HOST=mpsk@10.16.0.35 make py-deploy     # rsync + build + udev + selftest
FPGA_HOST=mpsk@10.16.0.35 make py-test-hw    # 7/7 PASS
```

## Repo layout

```
ip/vivado/xcvu9p/
├── README.md
├── ip/            ← reference IP (XDMA, DDR4 MIG + axi_interconnect)
├── prj/           ← Vivado project (gitignored generated tree)
├── src/
│   ├── hw_platform.v           ← NPU-integrated top (module hw_platform)
│   ├── npu_engine_subsys.v     ← NPU wrapper (from xc7k480t)
│   ├── npu_engine_ctrl_lite.v
│   ├── ddr/ ddr_test.v
│   └── pin.xdc
├── scripts/
│   ├── setup_platform.tcl      ← one-time IP setup (DDR4 narrow, bypass, clks, converters)
│   └── build_npu_vu9p.tcl
└── top_npu_vu9p.bit            ← built bitstream (gitignored)
```
