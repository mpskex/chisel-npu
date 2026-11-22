# NPU IP / Board Separation and NPUCTL v1 Design

**Status:** approved 2026-11-22
**Scope:** IP-centric / board-centric split, control protocol, Chisel peripheral wrapper, memory-agnostic data path, testbench/model split.

## 1. Intent and why

Today the NPU program engine is the de-facto top: it exposes a raw 512-bit AXI
master, an ad-hoc 7-bit `ctrl_*` port, and debug ports. The platform-specific
AXI4-Lite adapter, MIG calibration ports, XDMA, DDR controllers, and Vivado
board files all live in this repository. Software bakes in absolute DDR
addresses. The result is that the IP cannot be reused on a second board, and
a board change requires touching the NPU RTL.

This work splits the system into two clean layers:

- **IP-centric (`chisel-npu`)**: the NPU engine, the standard control hub,
  the `NpuPeripheral` wrapper, the protocol definition, the IP-level driver
  core, and simulation models.
- **Board-centric (`npu-platform`)**: the XDMA/PCIe configuration, the MIG /
  DDR3 / DDR4 controllers, the AXI interconnect, clocking/reset, the ILA, the
  board constraints, the board testbench, and the host transport/allocator.

The NPU IP must be identical on the xc7k480t (DDR3) and vu9p (DDR4) boards.
Only the board top and the platform software differ.

## 2. Baseline that must not change

- The vu9p build is `NpuProgramEngineFrontend(K = 32, N = 8, W = 8)` as built
  by `src/main/scala/top/top.scala` and documented by
  `docs/implementations/FPGA_VU9P.md`. The stale K=16/W=4 references in
  `AGENTS.md` and `FPGA_XC7K480T.md` are documentation errors.
- The instruction program model is frozen: 32-bit instructions, the current
  ISA encoding, the current `ld/st` 2-bit section selectors and 12-bit
  offsets, the `ld/st.rs1 > 3` illegal rule, MMA / capture / scoreboard /
  dispatch semantics, the assembler, the decoder, and the disassembler.
- **Instruction reach scaling is out of scope.** The 4 KB per-instruction
  reach, wider offsets, and extra instruction-level segment selectors are a
  future IP-centric project. This design only relocates the existing sections
  and adds the control/trace/IRQ wrapper.

Existing program words must be bit-identical. Only the testbench memory-map
constants and the internal core-to-hub address interface change.

## 3. Goals, non-goals, anti-goals

### Goals

- One standard control protocol (`NPUCTL v1`) for the NPU.
- A Chisel `NpuPeripheral` wrapper that exposes the protocol, an AXI4 data
  master, an IRQ, and a trace stream, independent of any board.
- Programmable 64-bit segment bases so the NPU is memory-agnostic.
- Start / stop / abort / soft-reset, interrupts, watchdog, and trace.
- The same host driver works on xc7k480t and vu9p; only the transport and
  platform configuration differ.
- Board testbenches are memory-controller models; the platform implementation
  only maps the NPU to those models.
- A release artifact (`NpuPeripheral.sv` + manifest) that the platform repo
  pins.

### Non-goals

- Changing the ISA or the instruction stream.
- Scaling the 4 KB per-instruction reach.
- A memory-resident job descriptor / command queue (future v2).
- Trace-to-memory DMA owned by the IP (the platform may capture the stream).
- A full power-on reset controller inside the IP (the board owns reset).

### Anti-goals

- Putting DDR/MIG/XDMA/PCIe controllers inside `NpuPeripheral`; that would
  make the IP board-specific.
- A driver that knows BAR offsets, DDR sizes, or K/N/W constants.
- A board top that patches the NPU RTL.

## 4. Approaches considered

| Approach | Shape | Decision |
|---|---|---|
| A. AXI-native peripheral | AXI4-Lite control directly in the protocol; core keeps ctrl port | Rejected: ties the standard to AXI and leaks the bus into the IP |
| B. Transport-agnostic protocol + adapters | Valid/ready control bus, AXI4-Lite/APB adapters; AXI4 data plane | **Chosen** |
| C. Platform-owned hub | Core exposes native interface; platform implements the hub | Rejected: no reusable wrapper, every board reimplements control/trace/IRQ |

## 5. System architecture

```
Board-centric repo (npu-platform)
  ┌──────────────────────────────────────────────────────────────────────┐
  │  PCIe/XDMA  ──  AXI interconnect  ──  DDR3/DDR4/HBM controller       │
  │                         │                                            │
  │                  NpuBoardTop                                          │
  │                  ┌──────────────────────────────────────────────┐     │
  │                  │  NpuPeripheral (IP release, identical)        │     │
  │                  │  ┌────────────────────────────────────────┐  │     │
  │                  │  │ NpuControlHub                          │  │     │
  │                  │  │  NPUCTL registers                      │  │     │
  │                  │  │  segment base/limit table              │  │     │
  │                  │  │  start/stop FSM, IRQ, watchdog         │  │     │
  │                  │  │  trace FIFO / stream                   │  │     │
  │                  │  │  local→system AXI translation          │  │     │
  │                  │  └───────────────┬────────────────────────┘  │     │
  │                  │                  │ native ctrl + segmented AXI│     │
  │                  │  ┌───────────────▼────────────────────────┐  │     │
  │                  │  │ NpuIp (NpuProgramEngineFrontend)       │  │     │
  │                  │  └────────────────────────────────────────┘  │     │
  │                  └──────────────────────────────────────────────┘     │
  └──────────────────────────────────────────────────────────────────────┘
```

`NpuPeripheral` contains the NPU IP, the control hub, the segment table, the
local→system translator, the IRQ controller, the watchdog, the trace FIFO,
the AXI4 memory **port**, and the control-bus slave. It does **not** contain
DDR/PCIe/clock/reset/ILA/pins.

`NpuBoardTop` contains the board IP and one `NpuPeripheral`. This is the only
place where xc7k480t and vu9p differ.

## 6. NPUCTL v1 protocol

### 6.1 Transport

Transport-agnostic control bus:

```
req_valid, req_write, req_addr[11:0], req_wdata[31:0], req_wstrb[3:0] -> req_ready
resp_valid, resp_rdata[31:0], resp_error <- resp_ready
```

- 32-bit registers, little-endian, 4-byte aligned.
- One-cycle register access; the AXI4-Lite / APB adapters add handshakes.
- The data plane is a separate AXI4 master: 64-bit system address, 512-bit
  data, INCR bursts. The IP manifest declares the width; a board may insert a
  width/clock converter.
- The IRQ is a level-sensitive output. The trace plane is an optional
  `valid/ready/data[63:0]` stream.

### 6.2 Register map

| Offset | Name | Access | Semantics |
|---|---|---|---|
| `0x000` | `ID` | RO | magic `0x4E505531` ("NPU1") |
| `0x004` | `VERSION` | RO | major[31:24] / minor[23:16] / patch[15:8] / reserved[7:0] |
| `0x008` | `CAPS` | RO | feature bits; `NUM_SEG`; `LOCAL_MAP`; trace depth; IRQ; watchdog; AXI64 |
| `0x00C` | `CONFIG` | RO | K, N, L, W, AXI data width |
| `0x010` | `CONTROL` | W | bit0 `START`; bit1 `STOP/ABORT`; bit2 `SOFT_RESET` (all self-clearing) |
| `0x014` | `STATUS` | RO | bit0 `BUSY`; bit1 `DONE`; bit2 `ERROR`; bit3 `ABORTED`; state[7:4]; IRQ live at [31] |
| `0x018` | `IRQ_STATUS` | W1C | bit0 `DONE`; bit1 `ERROR`; bit2 `TRACE_OVF`; bit3 `BUS_ERROR` |
| `0x01C` | `IRQ_ENABLE` | RW | same bits |
| `0x020` | `ERROR_CODE` | RO | 0 none, 1 illegal, 2 mem fault, 3 watchdog, 4 bus error, 5 bad config, 6 aborted |
| `0x024` | `ERROR_PC` | RO | faulting PC (16-bit) |
| `0x028` | `ERROR_INFO` | RO | faulting instruction word |
| `0x02C` | `ERROR_ADDR` | RO | faulting segment/offset |
| `0x030` | `PROG_LEN` | RW | instruction count in words |
| `0x034` | `ENTRY_PC` | RW | entry word offset (default 0) |
| `0x038` | `SESSION_ID` | RW | host cookie |
| `0x03C` | `WATCHDOG` | RW | cycles, 0 = off |
| `0x040` | `CYCLES` | RO | last session cycles |
| `0x044` | `MACS` | RO | last session useful MACs |
| `0x048` | `FETCH_STATS` | RO | prefetches[31:16] / misses[15:0] |
| `0x04C` | `TIMESTAMP` | RO | free-running 32-bit counter |
| `0x050` | `TRACE_CTRL` | RW | bit0 enable; mode[3:1]; divider[15:8] |
| `0x054` | `TRACE_STATUS` | RO | count[15:0]; overflow bit16; full bit17 |
| `0x058` | `TRACE_DATA` | RO | read pops one 32-bit word |
| `0x05C` | `TRACE_DROPS` | RO | dropped record count |
| `0x100 + 16*i` | `SEG_BASE_LO` | RW | segment i 64-bit system base, 64-byte aligned |
| `0x104 + 16*i` | `SEG_BASE_HI` | RW | |
| `0x108 + 16*i` | `SEG_LIMIT` | RW | segment i byte limit, multiple of 64; 0 = default |
| `0x180..0x18C` | `SCRATCH0..3` | RW | software scratch |

Segment indices: 0 A, 1 B, 2 ACCUM, 3 OUT, 4 CODE. Entries 5..31 are reserved
for future IP variants. `CAPS.NUM_SEG = 5` for this variant.

### 6.3 Control semantics

- `START` requires `PROG_LEN > 0` and aligned segment bases/limits. It
  snapshots the segment table, `PROG_LEN`, `ENTRY_PC`, and `WATCHDOG` into
  active shadows, clears `DONE/ERROR/ABORTED/IRQ_STATUS`, and pulses the core
  start.
- `DONE` is sticky until the next `START` or `SOFT_RESET`. `BUSY` is live.
- `STOP` aborts: pulse core abort/session reset, set `ABORTED`,
  `ERROR_CODE = 6`, raise `IRQ.ERROR`.
- `SOFT_RESET` clears session status/error/IRQ and aborts the core; segment
  bases, IRQ enable, and trace config are preserved. It is a session reset,
  not a power-on reset.
- `START` while busy is ignored.
- Segment-table writes while busy are allowed but do not affect the running
  session (active shadows latch at `START`).
- Writes to a segment base must be 64-byte aligned; `SEG_LIMIT` must be a
  multiple of 64. A violation at `START` sets `ERROR_CODE = 5` and raises
  `IRQ.ERROR` without starting.
- `ERROR_*` latches the first fault; later faults do not overwrite it.
- `WATCHDOG != 0` counts cycles while busy; expiry aborts the core and sets
  `ERROR_CODE = 3`.

### 6.4 Trace V1

Three separate observability pieces:

| Piece | What | Where |
|---|---|---|
| Events | start, done, error, abort, watchdog, trace overflow | trace FIFO / stream |
| Retire PC | one record per retired instruction, carrying its PC | trace FIFO / stream |
| Counters | `CYCLES`, `MACS`, `FETCH_STATS`, `TRACE_DROPS` | normal registers, latched at done |

64-bit record:

```
[63:32] timestamp
[47:32] pc
[55:48] event   (1 start, 2 retire, 3 done, 4 error, 5 watchdog, 6 abort, 7 overflow)
[63:56] payload (error code / session id low byte)
```

Modes: 0 off, 1 events only, 2 events + retire PC (default), 3 events +
retire PC + done snapshot. The FIFO holds 256 records; overflow sets
`IRQ.TRACE_OVF`. The stream is optional for platform capture. Full instruction
words / data addresses are a future mode with 128-bit records.

## 7. Memory model: segment sideband, base/limit translation

The core never sees a system address. The core-to-hub interface is a
**segmented AXI** master:

```
core AXI address  = flat local offset (A=0, B=0x400, ACCUM=0x800,
                    OUT=0x880, CODE=0x4000)
core AXI sideband = seg[2:0]
```

The core keeps the existing relative layout:
`A = 0x0000`, `B = 0x0400`, `ACCUM = 0x0800`, `OUT = 0x0880`,
`CODE = 0x4000`. `DATA_BASE = 0x40000000` is removed. Instruction words and
section offsets are unchanged.

The hub translates:

```
rel      = core_offset - LOCAL_BASE[seg]
sys_addr = SEG_BASE[seg] + rel
valid    = (core_offset >= LOCAL_BASE[seg]) &&
           (rel + burst_bytes <= SEG_LIMIT[seg])
```

- `SEG_BASE` is 64-byte aligned; `SEG_LIMIT` is a multiple of 64.
- The core checks `slots.off + vector_bytes <= seg_limit[seg]` at DMA issue
  and raises `mem_fault` with the segment/offset. The hub's START validation
  checks the code window against `seg_limit[CODE]`. The hub drives the active
  limits into the core.
- The hub also guards the translated burst; if the core ever issues an
  out-of-range request the translator reports `BUS_ERROR` and clamps to the
  segment base, and the session aborts. The core check is the normal path;
  the hub guard is defense in depth.
- Data burst bytes: VX = K bytes, VE = 2K, VR = 4K; the AXI beat is 64 B and
  the core aligns down, so the limit/base alignment rules keep every beat
  inside the segment.
- Code fetch: segment 4, offset `line * 64`, limit 256 KB.

The segment sideband is internal to the core/hub boundary. The external AXI4
master is standard and carries only the translated 64-bit system address.

## 8. Chisel units

### 8.1 New files

- `src/main/scala/peripheral/NpuCtrlProtocol.scala` — register offsets,
  field constants, segment indices, error codes, trace events.
- `src/main/scala/peripheral/NpuCtrlBus.scala` — transport bundle and the
  AXI4-Lite adapter.
- `src/main/scala/peripheral/NpuControlHub.scala` — register file, start/stop
  FSM, IRQ/W1C, watchdog, trace FIFO, segment table, translation.
- `src/main/scala/peripheral/NpuPeripheral.scala` — hub + core, external
  AXI4-Lite control, external AXI4 data master, IRQ, trace stream.
- `src/test/scala/peripheral/NpuControlHubSpec.scala`,
  `NpuPeripheralSpec.scala`.
- `src/test/scala/utils/SegmentedAxiRam.scala` (or an extension of the
  existing model) for core-side tests.

### 8.2 Core changes (no ISA / program-model change)

- `NpuSections` becomes relative (`A=0`, `B=0x400`, `ACCUM=0x800`, `OUT=0x880`,
  `CODE=0x4000`); `DATA_BASE` removed.
- `NpuDmaEngine` gains `req_seg[2:0]`, `m_axi_awseg[2:0]`,
  `m_axi_arseg[2:0]`; the AXI address remains the segment-relative offset.
  Fetch requests use segment 4.
- `NpuProgramEngine` forms `dmaL3 = slots.off` (relative), passes
  `seg = slots.sect`, adds the segment-limit check and `mem_fault` /
  `fault_seg` / `fault_addr` outputs.
- `NpuProgramEngineFrontend`:
  - code fetch offset `fillLine * 64`, segment 4;
  - direct `core_start`, `core_abort`, `core_prog_len`, `core_entry_pc`,
    `seg_limit[5]`, `mem_fault` inputs;
  - direct `core_busy`, `core_done`, `core_illegal`, `core_pc`,
    `core_err_instr`, `core_err_pc`, `core_cycles`, `core_macs`,
    `core_fetch_stats` outputs;
  - trace retire pulse and PC;
  - `END_PC = ENTRY_PC + PROG_LEN` for fetch/done checks;
  - keep the existing `ctrl_*` port for tests and debug; the hub uses the
    direct signals.
- `NpuDmaEngine`'s hardened FSM, timeout/retry, 512-bit data path, and
  wdata-preload fix are untouched.

## 9. Testbench and model split

- The IP repo defines a testbench-only `NpuMemModel` interface: AXI4 slave +
  base/size/read-latency/write-latency/width parameters. `AxiRamModel` is the
  generic implementation. `NpuPeripheralSpec` wires the peripheral's system
  AXI master to it and sets the segment bases to model addresses.
- The board repo provides board memory-controller models (DDR3/DDR4/MIG
  behavioral or AXI VIP) and maps them to the board: address map,
  width/clock converters, XDMA model, ILA. `NpuBoardTopSpec` instantiates the
  same `NpuPeripheral` and a board model. No NPU RTL changes.
- The platform implementation only maps the model to the platform. The
  platform-specific code is the board top, the memory model configuration,
  and the host transport/allocator.
- The IP repo's engine tests use the generic `AxiRamModel` with the relative
  offsets; the segment sideband is ignored (the offsets are distinct). The
  integration tests use the hub, which translates to system addresses.

## 10. Driver architecture

```
ChiselNPU.run()                user API
        │
NpuDriverCore                  NPUCTL only: ID/CONFIG validation, segment
        │                      programming, start, IRQ/status, error decode
NpuTransport                   reg_read/reg_write, mem_read/mem_write, wait_irq
  ├─ XdmaTransport             board repo: /dev/xdma0_bypass + h2c/c2h
  ├─ FakeTransport             IP repo unit tests
  └─ future transports
PlatformInfo                    BAR offset, memory aperture, IRQ mode, alignment
NpuAllocator                    board DDR allocator → system addresses
```

- `NpuDriverCore` knows only NPUCTL; no PCIe/XDMA/BAR/DDR sizes/K/N/W
  constants.
- The driver reads `ID/VERSION/CAPS/CONFIG` at open and validates
  compatibility.
- `NpuConfig` splits into `ProtocolConfig` (register offsets, segment IDs,
  local offsets) in the IP repo and `PlatformConfig` (system bases, BAR,
  aperture, IRQ) in the board repo.
- The same `ChiselNPU.run()` works on xc7k480t and vu9p. The board package
  constructs `XdmaTransport`, `PlatformInfo`, and the allocator.
- The XDMA C++ backend moves to the board repo; the IP repo keeps a fake
  transport for tests.

## 11. IP variant switching

`NpuPeripheral` is a stable shell around an `NpuIp` native interface:

```
NpuIp: start, abort, prog_len, entry_pc, seg_limit[5], busy, done, error,
       pc, instr, cycles, macs, trace events, segmented AXI master
```

The current `NpuProgramEngineFrontend` implements it. A future engine (new
scheduler, VALU, different K/N/W, wider segment offsets) implements the same
interface and plugs into the same shell. `CONFIG`/`CAPS` advertise the
variant; the driver adapts or rejects. The external ports and NPUCTL stay
fixed. The release manifest records IP version, protocol version, parameters,
register-map hash, and git revision.

## 12. Release and versioning

- `make release` emits `build/NpuPeripheral.sv` and
  `build/npu_peripheral.json` (IP version, protocol major/minor, K/N/L/W,
  AXI widths, `NUM_SEG`, register-map hash, git revision).
- The platform repo pins a release (git tag or artifact). Board CI builds the
  bitstream against that release.
- Protocol major changes break compatibility; minor changes are additive in
  reserved registers/bits. The driver checks `VERSION.major` and `CAPS`.

## 13. Phases

1. Protocol + hub skeleton (register file, IRQ, watchdog, trace, 5 active
   segments, 32 reserved). Standalone tests with the generic memory model.
2. Core base relocation + segment sideband + limits + direct status/abort/
   trace. No ISA change. Existing engine specs pass.
3. `NpuPeripheral` + translation + integration tests + release artifact.
4. Driver core + transport + `PlatformInfo` + allocator; fake transport.
5. Platform repo (`~/chisel-npu-paltform`): board tops, memory-controller
   models, XDMA transport, board tests, hardware bring-up.
6. Future (out of scope): instruction reach scaling.

## 14. Risks

- 64-bit translation timing: register the translated AW/AR address at the hub
  boundary; keep the data path pass-through.
- DMA hardening regression: only address formation changes; keep the FSM,
  timeout/retry, and wdata-preload fixes; run the DMA specs.
- Abort mid-burst: drain the DMA queue before reset, or add a DMA reset gated
  by idle; do not yank `req_valid` mid-burst.
- Clean break: tag `pre-platform-split`; keep the old bitstream until the new
  board flow passes.
- Repo split: use filtered history for moved paths; verify the platform repo
  before deleting files from the IP repo.
- Trace bandwidth: default to events + retire PC; overflow IRQ.

## 15. References

- `docs/designs/04.streamed-issuing.md` — engine issuing model (unchanged).
- `docs/implementations/FPGA_VU9P.md` — vu9p baseline (`K=32, N=8, W=8`).
- `docs/implementations/FPGA_XC7K480T.md` — board flow (to move).
- `drivers/chisel_npu_py/src/chisel_npu_py/config.py` — current register map
  and section layout (to be replaced by NPUCTL).
