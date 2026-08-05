---
name: build-fpga-xc7k480t
description: Use when building the NPU FPGA bitstream for xc7k480tffg1156-2 — includes the squashed Vivado build flow (build_npu.tcl and build_npu_with_ila.tcl), bootstrap_project.tcl, the consolidated _apply_npu_topology.tcl BD library, ILA insertion, bitstream configuration, MMALU pipeline timing closure, and Makefile quick-build targets.
---

# FPGA Bitstream Build — xc7k480t (Vivado 2025.2)

## Environment setup

Source `.env.sh` at repo root to set all paths:

```bash
source .env.sh    # sets VIVADO, FPGA_HOST, SSH_IDENTITY, CHIP, VIVADO_JOBS, VIVADO_IMPL_STRATEGY
```

## Quick build via Makefile

```bash
# Step 1: regenerate top.sv (if Chisel sources changed)
make build                     # runs sbt inside Docker

# Step 2: build bitstream via Vivado
make build-fpga                # uses $VIVADO, $VIVADO_JOBS, $VIVADO_IMPL_STRATEGY

# Step 3: build with ILA debugger
make build-fpga-debug

# Step 4: clean bootstrapped project
make build-fpga-clean
```

Override variables:

```bash
make build-fpga VIVADO=~/Vivado/2025.2/Vivado/bin/vivado VIVADO_JOBS=2 VIVADO_IMPL_STRATEGY=Performance_Explore
```

Output: `ip/vivado/xc7k480t/top_npu.bit` (~18 MB).

## Manual Vivado build

```bash
~/Xilinx/2025.2/Vivado/bin/vivado -mode batch \
    -source  ip/vivado/xc7k480t/scripts/build_npu.tcl \
    -journal build/build_npu_xc7k480t.jou \
    -log     build/build_npu_xc7k480t.log
```

## Environment variables

| Variable | Default | Description |
|:---------|:--------|:------------|
| `VIVADO` | `$HOME/Vivado/2025.2/Vivado/bin/vivado` | Vivado binary path |
| `VIVADO_JOBS` | `4` | Parallel OOC synth + impl jobs; lower to `1` or `2` on memory-constrained hosts |
| `VIVADO_IMPL_STRATEGY` | `Performance_Explore` | Vivado impl strategy; see Vivado docs for alternatives |
| `CHIP` | `xc7k480t` | Target chip directory under `ip/vivado/` |
| `FPGA_HOST` | `fpga` | SSH target for the FPGA server |
| `SSH_IDENTITY` | `$HOME/.ssh/id_fpga_local` | SSH private key for FPGA server access |
| `HW_SERVER` | `localhost:3121` | Vivado hw_server URL for JTAG |

## Architecture

### AXI topology (V10)

```
Host PCIe Gen2 x8
  │
  XDMA 4.2 (125 MHz)
  ├── M_AXI ─► axi_cc_xdma_in (125→200) ─► axi_clkconv_xdma (200→133) ─► axi_dwidth_xdma (128→512) ─► axi_xbar.S00
  │                                                                                                        │
  ├── M_AXI_BYPASS ─► axi_clkconv_byp (125→200) ─► byp_dw (128→32) ─► byp_pc ─► npu_subsys/s_axil        │
  │                                                                                                        │
  axi_xbar (2S:2M, 4 GB address space: 0x0000_0000 → C0, 0x8000_0000 → C1)
    ├── M00 ─► MIG C0 (DDR3, 2 GB)
    └── M01 ─► MIG C1 (DDR3, 2 GB)

npu_subsys (200 MHz fabric):
  ctrl_lite + npu_dma_master + MMALU(K=32, N=8, acc=32)
    m_axi ─► axi_clkconv_npu (200→133) ─► axi_dwidth_npu (128→512) ─► axi_xbar.S01
```

### Clock domains

| Domain | Frequency | Source |
|:-------|:---------:|:-------|
| axi_aclk | 125 MHz | XDMA PCIe refclock |
| fabric_aclk (clk_out1) | 200 MHz | clk_wiz_fabric MMCM (8×/5 from 125 MHz) |
| c0_ui_clk | 133 MHz | MIG C0 PLL |
| c1_ui_clk | 133 MHz | MIG C1 PLL |

## MMALU timing closure history

### Initial failure (2026-07-30)

First build of V10 (K=32) at 200 MHz showed:

| Metric | Value |
|:-------|:-----:|
| WNS (clk_out1) | -0.151 ns |
| TNS (clk_out1) | -1.203 ns |
| Failing endpoints | 24 |
| Critical path | `reg_h_731` → 8×CARRY4 + 5×LUT → `MMPE_755/res` |
| Logic levels | 13 |
| Route delay % | 60.8% |

**Root cause**: The systolic array horizontal register (`reg_h` at position 731 in the 32×32 array) drove a combinatorial path through the PE's multiply-accumulate chain (32-bit adder = 8 CARRY4 + multiplier logic) directly to the PE's `res` register. With span across the die, routing delay dominated.

### Fix: SA→PE pipeline registers

Added one pipeline stage between the `SystolicArray2D` outputs and the `MMPE` inputs in `src/main/scala/alu/mma/mma.scala`:

```scala
val pipe_a    = RegInit(VecInit(Seq.fill(n * n)(0.S(nbits.W))))
val pipe_b    = RegInit(VecInit(Seq.fill(n * n)(0.S(nbits.W))))
val pipe_accum = RegInit(VecInit(Seq.fill(n)(0.S(accum_nbits.W))))
val pipe_ctrl = RegInit(VecInit(Seq.fill(n * n)(0.U.asTypeOf(new NCoreMMALUCtrlBundle()))))
val pipe_dat_clct  = RegNext(false.B)
val pipe_use_accum = RegNext(false.B)
val pipe_clct      = RegNext(false.B)
```

This breaks the critical path into two segments of ~6-7 logic levels each. Adds 1 cycle to MMALU latency (3n−1 instead of 3n−2).

Pipe registers also added for:
- `accum_in` (pipe_accum) — matches the data pipeline delay
- `dat_clct`, `use_accum`, `clct` — matches the control pipeline delay

### Test impact

The MMALU test specs in `MMALUSpec.scala` and `MMALUStreamReduceSpec.scala` expect output at specific tick counts. All output windows shifted by +1:

- `2n−2` → `2n−1` (first output tick)
- `3n−2` → `3n−1` (loop bound and second window)
- `4n−2` → `4n−1` (second output window end)
- `(f+1)·K − 2` → `(f+1)·K − 1` (stream reduce frame base)

### Result

| Metric | Before | After | Improvement |
|:-------|:------:|:-----:|:-----------:|
| WNS | -0.151 ns | -0.051 ns | 66% reduction |
| TNS | -1.203 ns | -2.712 ns | — |
| Logic levels | 13 | 6-7 | 50% reduction |
| Latency (K=32) | 3n−2 = 94 cyc | 3n−1 = 95 cyc | +1 cycle |

### Remaining timing issues

The remaining -0.051 ns violation may be further reduced by:
- Adding another pipeline stage in the PE multiplier (register the multiply result)
- Reducing the fabric clock to 175 MHz (5.714 ns period, giving ~0.7 ns margin)
- Using `Performance_ExtraTimingOpt` impl strategy
- Setting `phys_opt_design -retime` in the Vivado script

## Memory management for OOC synthesis

The XDMA IP OOC synthesis requires ~3.4 GB peak. With 20 OOC IPs running in parallel:

| `VIVADO_JOBS` | Peak memory | Build time (first, cold cache) |
|:-------------:|:-----------:|:------------------------------:|
| 8 | >19 GB (OOM) | — |
| 4 | ~15 GB | OOM on XDMA |
| 2 | ~10 GB | ~110 min |
| 1 | ~7 GB | ~140 min |

**Recommendation**: `VIVADO_JOBS=2` for the first build (cold cache), `VIVADO_JOBS=4` for incremental builds on the bootstrapped project.

## Bring-up flow (SSH-based)

After bitstream built, use `python3 tool/hw/bringup_ssh.py`:

```bash
source .env.sh
# Flash BPI + JTAG SRAM + reboot + run tests:
python3 tool/hw/bringup_ssh.py --host "$FPGA_HOST" ip/vivado/xc7k480t/top_npu.bit
# Skip flash, skip JTAG (if bitstream already loaded):
python3 tool/hw/bringup_ssh.py --host "$FPGA_HOST" --skip-flash --skip-jtag ip/vivado/xc7k480t/top_npu.bit
```

Or step by step:

```bash
make test-hw FPGA_HOST=10.16.0.31   # run pytest HW tests
```

## Troubleshooting

| Problem | Fix |
|:--------|:----|
| Build exits immediately — no project | Run `bootstrap_project.tcl` manually, or `rm -rf proj/` and re-run `build_npu.tcl` |
| OOC XDMA gets killed (OOM) | Reduce `VIVADO_JOBS` to 1 or 2 |
| `reset_run synth_1` fails (process not found) | `rm -f proj/npu_migrate.runs/synth_1/*.wdf` |
| `reset_run` followed by `launch_runs` fails | `rm -f proj/.../runs/synth_1/top_wrapper.dcp` first |
| WNS < 0 after impl on MMALU PE path | Expected; pipeline registers in `mma.scala` mitigate. For full closure, add PE multiplier pipeline or reduce fabric clock to 175 MHz |
| Black-box errors (`INBB-3`) during impl | IP DCPs not copied to `IP_DIR`; verify `proj/.../sources_1/bd/top/ip/*/dcp` exist after synth |
| `module top_wrapper not found` | `top_wrapper.v` not in fileset; re-run bootstrap |
| `is not addressable by /xdma_0/M_AXI_BYPASS` | Non-fatal info from V1 step |
| Sources already connected during BD apply | `rm -rf proj/` and rebuild from scratch |
| PCIe not found after warm reboot | Passwordless sudo not configured on FPGA host: `echo "mpsk ALL=(ALL) NOPASSWD: ALL" \| sudo tee /etc/sudoers.d/mpsk` |
| xdma.ko `Invalid module format` | Kernel updated; rebuild: `cd ~/dma_ip_drivers/XDMA/linux-kernel/xdma && make` |
