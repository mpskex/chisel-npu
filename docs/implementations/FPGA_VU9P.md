# NPU Bring-up on Virtex UltraScale+ (`xcvu9p` / Alivu9p)

Worked example of bringing the Chisel program engine up on a **Virtex
UltraScale+ `xcvu9p-flgb2104-2-e`** board (the "Alivu9p" platform) and
programming it **remotely** over a networked `hw_server` — no local JTAG.

The Chisel RTL (`top.sv`, the K=16 program engine at window depth **W=4**) and
the [`chisel_npu_py`](https://github.com/mpskex/chisel-npu/blob/main/drivers/chisel_npu_py/README.md) userspace driver
are the same as the Kintex-7 platform; only the FPGA wrapper differs.

Backing design notes: [`ip/vivado/xcvu9p/README.md`](https://github.com/mpskex/chisel-npu/blob/main/ip/vivado/xcvu9p/README.md)
(full build/remote-programming recipe).

## Platform

| Item | Value |
|:-----|:------|
| FPGA | `xcvu9p-flgb2104-2-e` |
| PCIe | XDMA v4.2, Gen3 ×8 (8 GT/s), BDF `10:00.0` (root port `00:01.1`) |
| VID:DID | `10ee:903f`, subsystem `0007` |
| DDR4 | 4× MIG UltraScale+ (`MT40A1G16WBU-083E`, 72-bit, ECC), 300 MHz ui_clk |
| Fabric clock | 200 MHz (`clk_wiz_fabric` MMCM from `axi_aclk` 250 MHz) |
| JTAG | Digilent cable on the server, `hw_server` at `<server>:3121` |

## Architecture

The NPU is the **S01** user master of the reference DDR4 crossbar; the XDMA
host path is S00:

```
XDMA M_AXI (512b @250) ─────────────────────────────► ddr_top S00 ─► 4× DDR4
XDMA M_AXI_BYPASS (512b @250)
  └► byp_dw (512→32) ─► byp_cc (250→200) ─► byp_pc (AXI4→Lite) ─► ctrl_lite
NPU engine (128b @200)
  └► npu_cc (200→250) ─► npu_dw (128→512 @250) ─────► ddr_top S01 ─► DDR4 xbar
```

The engine runs at **200 MHz** (the 250 MHz `axi_aclk` is too fast for the
K=16 engine); `clk_wiz_fabric` + AXI clock/width converters provide the CDC.

## The integration fixes

### 1. DDR4 MIG rejected narrow bursts

The engine's AXI master is **128-bit** and therefore issues **16-byte** beats.
The DDR4 MIG defaults to `AXI_Narrow_Burst=false` (full-width 512-bit bursts
only). With narrow bursts disabled:

- the engine's `illegal instruction at pc=65535 (word=0xffffffff)` was the
  instruction **fetch** reading back `0xFFFFFFFF` (the MIG dropped the
  sub-512-bit read);
- multi-beat vector transfers (`vle16/32`, `vse16/32`) were truncated to their
  first 16 bytes — the "only 4 of 16 lanes moved" symptom.

**Fix**: `CONFIG.C0.DDR4_AxiNarrowBurst = true` on the `ddr4_0` MIG, then
re-synthesize its OOC checkpoint. The `128→512` converter then stays unpacked
(`PACKING_LEVEL=1`).

A tempting but **wrong** workaround is to force the converter to pack
(`PACKING_LEVEL=2`): a single 16-byte request cannot pack into a 512-bit beat,
so it re-breaks the fetch.

### 2. The engine only runs at 200 MHz

Running the engine on a 100 MHz fabric clock (chosen once to relieve routing
congestion) makes the frontend fetch fail with the same illegal-instruction
signature. Keep `clk_wiz_fabric` at **200 MHz**.

### 3. Stale incremental synthesis silently ignored `top.sv`

`synth_1` had **auto-incremental synthesis** enabled with a reference checkpoint
under `prj.srcs/utils_1/imports/synth_1/`.  After regenerating `top.sv`, the
tool reused ~99.9996% of a previous engine netlist (the log shows
`NpuProgramEngineFrontend__GC0_#REUSE#`), so RTL changes never reached the
bitstream.  `build_npu_vu9p.tcl` now clears `AUTO_INCREMENTAL_CHECKPOINT` and
forces a re-read of `top.sv` (remove + re-add).

### 4. Fabric reset-synchronizer recovery violation

`hw_platform.v` used the high-fanout XDMA `axi_aresetn` (`axi_aclk` domain,
fo ≈ 1600) as the async reset of the `clk_fabric` reset synchronizer, giving a
−0.87 ns recovery violation across 82 endpoints.  Fix: register a local
`ASYNC_REG` copy (`axi_aresetn_loc`) so the cross-domain path is short, and
`set_false_path` the synchronizer's async reset (`pin.xdc`) — its 2-FF chain
resolves the CDC.  Combined with the placement directive below, the design now
meets all user timing constraints.

### Placement / timing note (multi-SLR)

`xcvu9p` is a multi-SLR device. The extra NPU buses make the GTY/PCIe-left-edge
region congested under the default placer, so the build uses
`place_design -directive ExtraTimingOpt` (set in `build_npu_vu9p.tcl`).
A Pblock around the NPU does **not** help — it forces SLR (Laguna) crossings and
the routing blows up; the engine was made to fit by reducing it (W=4 + the
2-stage feed pipeline), not by floorplanning.

With that, the engine fabric domain closes at 200 MHz (WNS ≈ +0.05 ns, 0 failing
endpoints) and the whole design reports *"All user specified timing constraints
are met."*

## Build

```bash
make build                                   # Chisel -> top.sv

# one-time IP setup: DDR4 narrow burst, XDMA bypass, 200 MHz MMCM, converters
vivado -mode batch -source ip/vivado/xcvu9p/scripts/setup_platform.tcl

# bitstream
vivado -mode batch -source ip/vivado/xcvu9p/scripts/build_npu_vu9p.tcl
# -> ip/vivado/xcvu9p/top_npu_vu9p.bit
```

## Remote programming (no local JTAG)

The board is programmed from the **dev host** through the server's
`hw_server`; `xsdb` cannot enumerate pure-FPGA targets, so use Vivado:

```tcl
open_hw_manager
connect_hw_server -url <server>:3121 -allow_non_jtag
current_hw_target [lindex [get_hw_targets] 0]
open_hw_target
current_hw_device [lindex [get_hw_devices xcvu9p_0] 0]
set_property PROGRAM.FILE {ip/vivado/xcvu9p/top_npu_vu9p.bit} [current_hw_device]
program_hw_devices [current_hw_device]
```

## Host bring-up (`<server>`)

The AMD FCH does not enumerate the card after a hot JTAG load:

1. JTAG-load the bitstream (above).
2. `sudo /sbin/reboot` — FPGA SRAM survives a warm reboot; PCIe then trains.
3. Load the **out-of-tree** Xilinx XDMA driver. The in-kernel `xdma.ko` is a
   dmaengine-only driver with **no** `/dev/xdma0_*` char nodes:
   ```bash
   git clone https://github.com/Xilinx/dma_ip_drivers
   cd dma_ip_drivers/XDMA/linux-kernel/xdma
   cp -n ../include/libxdma_api.h .    # modern kbuild ignores EXTRA_CFLAGS
   make
   sudo rmmod xdma 2>/dev/null; sudo insmod xdma.ko
   ```
4. Install udev/permissions — `make py-deploy` does this. Reload the module
   after every reboot.

## Validation

```bash
FPGA_HOST=mpsk@<server> make py-deploy     # rsync + build + udev + selftest
FPGA_HOST=mpsk@<server> make py-test-hw    # 7/7 PASS
```

Selftest: section round-trip + program run `pc=7 OK`. Hardware suite:

| Test | Result |
|:-----|:-------|
| `test_copy_program_roundtrip` | PASS |
| `test_vr_roundtrip_through_run` | PASS |
| `test_transfer_validation` | PASS |
| `test_one_shot_session` | PASS |
| `test_session_captures_columns` | PASS |
| `test_illegal_instruction_halts` | PASS |
| `test_status_after_program` | PASS |
