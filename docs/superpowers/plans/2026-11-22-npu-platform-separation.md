# NPU Platform Separation and NPUCTL v1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship the NPU as a board-agnostic `NpuPeripheral` with NPUCTL v1 control, programmable segment bases, IRQ, watchdog, and trace, and split the board testbench into `~/chisel-npu-platform`.

**Architecture:** A Chisel `NpuControlHub` wraps the existing `NpuProgramEngineFrontend`. The core keeps its instruction model and emits a segmented AXI master (segment sideband + segment-relative offset); the hub translates to a 64-bit system AXI master using five active segment base/limit registers. The board repo instantiates the same `NpuPeripheral` with board memory models, XDMA, and DDR controllers.

**Tech Stack:** Chisel 6.7.0, Scala 2.13.12, sbt 1.9.7, EphemeralSimulator, Docker `fangruil/chisel-dev:amd64`, Python 3 driver.

**Spec:** `docs/superpowers/specs/2026-11-22-npu-platform-separation-design.md`

## Global Constraints

- **Frozen ISA / program model.** No assembler, decoder, disassembler, instruction-encoding, MMA, capture, scoreboard, or dispatch changes. Existing program words must remain bit-identical.
- **Baseline:** `NpuProgramEngineFrontend(K=32, N=8, W=8)` for vu9p.
- **Instruction reach is out of scope.** Keep the 2-bit `ld/st` section selector and 12-bit offset. Do not add wide offsets or extra segment encodings.
- **Five active segments:** 0 A, 1 B, 2 ACCUM, 3 OUT, 4 CODE. Entries 5..31 reserved.
- **Relative core layout:** `A=0x0000`, `B=0x0400`, `ACCUM=0x0800`, `OUT=0x0880`, `CODE=0x4000`. No `0x4000_0000` base in the core.
- **Segment sideband:** core AXI address is the segment-relative offset; `seg[2:0]` is a parallel sideband. The external AXI is standard 64-bit address / 512-bit data.
- **Alignment:** segment bases 64-byte aligned; limits multiples of 64.
- **Control protocol:** transport-agnostic valid/ready bus; AXI4-Lite adapter at the peripheral boundary. 32-bit registers, 4-byte aligned.
- **No memory controllers in `NpuPeripheral`.** DDR/MIG/XDMA/PCIe/clock/reset/ILA/pins live in the board repo.
- **Platform repo path:** `~/chisel-npu-platform` (as requested; exact spelling).
- **Tests:** every task adds a spec and runs it via `tool/test-specific-spec.sh <fully.qualified.Spec>`; the full suite runs via `make test`.
- **Commits:** one commit per task, conventional messages.

## Review Focus

- **Busy/START edge cases:** a `START` while busy must be ignored; a `STOP` in IDLE must not corrupt status; `SOFT_RESET` must preserve segment/IRQ config.
- **W1C race:** an event arriving in the same cycle as a W1C must survive the clear.
- **Segment limit boundaries:** offset exactly at `limit - vector_bytes` must pass; one byte past must fault; a 64-byte beat must not cross the segment.
- **Abort mid-burst:** abort must not yank `req_valid` from the DMA engine mid-burst; drain or reset only when idle.
- **Code fetch entry PC:** `ENTRY_PC != 0` must fetch the right lines and `DONE` at `ENTRY_PC + PROG_LEN`.
- **Trace overflow:** FIFO overflow must set the IRQ bit and increment `TRACE_DROPS`, not stall the core.
- **Driver no-board-knowledge:** the driver core must not reference BAR offsets, DDR sizes, XDMA, or K/N/W constants.

---

### Task 1: NPUCTL constants, control bus, AXI4-Lite adapter

**Files:**
- Create: `src/main/scala/peripheral/NpuCtrlProtocol.scala`
- Create: `src/main/scala/peripheral/NpuCtrlBus.scala`
- Create: `src/main/scala/peripheral/NpuCtrlAxiLite.scala`
- Test: `src/test/scala/peripheral/NpuCtrlBusSpec.scala`

**Interfaces:**
- Produces: `object NpuCtrlProtocol` with register offsets (`ID=0x000`, `VERSION=0x004`, `CAPS=0x008`, `CONFIG=0x00C`, `CONTROL=0x010`, `STATUS=0x014`, `IRQ_STATUS=0x018`, `IRQ_ENABLE=0x01C`, `ERROR_CODE=0x020`, `ERROR_PC=0x024`, `ERROR_INFO=0x028`, `ERROR_ADDR=0x02C`, `PROG_LEN=0x030`, `ENTRY_PC=0x034`, `SESSION_ID=0x038`, `WATCHDOG=0x03C`, `CYCLES=0x040`, `MACS=0x044`, `FETCH_STATS=0x048`, `TIMESTAMP=0x04C`, `TRACE_CTRL=0x050`, `TRACE_STATUS=0x054`, `TRACE_DATA=0x058`, `TRACE_DROPS=0x05C`, `SEG_TABLE=0x100`, `SEG_STRIDE=0x10`, `SCRATCH=0x180`).
- Produces: `class NpuCtrlReq extends Bundle { write, addr[12], wdata[32], wstrb[4] }`, `class NpuCtrlResp extends Bundle { rdata[32], error }`, `class NpuCtrlSlave extends Bundle { req: Decoupled[NpuCtrlReq]; resp: Decoupled[NpuCtrlResp] }`.
- Produces: `class NpuCtrlAxiLite(val ADDR_W: Int = 12) extends Module` with `io.s_axil_*` and `io.ctrl: NpuCtrlSlave`.

- [ ] **Step 1: Write the failing AXI4-Lite adapter test**

```scala
// NpuCtrlBusSpec.scala
package peripheral
import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec

class NpuCtrlAxiLiteSpec extends AnyFlatSpec {
  "NpuCtrlAxiLite" should "accept an AW/W write and return a response" in {
    simulate(new NpuCtrlAxiLite) { dut =>
      // drive AW+W, expect ctrl.req.valid with write, addr 0x10, wdata 0x1234
      // then complete ctrl.resp and expect BVALID
    }
  }
  it should "return read data from ctrl.resp on AR" in { /* ... */ }
}
```

- [ ] **Step 2: Run it and verify it fails because the module does not exist**

Run: `tool/test-specific-spec.sh peripheral.NpuCtrlAxiLiteSpec`
Expected: compile error `NpuCtrlAxiLite not found`.

- [ ] **Step 3: Implement the constants, bus bundles, and AXI4-Lite adapter**

Standard AXI4-Lite slave:
- Write: accept AW and W independently, hold `awaddr`/`wdata`, issue one `ctrl.req` with `write=true`, `wstrb`, then wait for `ctrl.resp`, drive `BVALID`/`BRESP=OKAY`.
- Read: accept AR, issue one `ctrl.req` with `write=false`, wait for `ctrl.resp`, drive `RVALID`/`RDATA`/`RRESP=OKAY`.
- No wait states beyond the ctrl bus handshake; byte address `addr[11:0]` with low two bits zero.

- [ ] **Step 4: Run the adapter test and verify it passes**

Run: `tool/test-specific-spec.sh peripheral.NpuCtrlAxiLiteSpec`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/scala/peripheral/NpuCtrlProtocol.scala src/main/scala/peripheral/NpuCtrlBus.scala src/main/scala/peripheral/NpuCtrlAxiLite.scala src/test/scala/peripheral/NpuCtrlBusSpec.scala
git commit -m "feat(peripheral): add NPUCTL transport and AXI4-Lite adapter"
```

---

### Task 2: Control hub register file, IRQ/W1C, status

**Files:**
- Create: `src/main/scala/peripheral/NpuControlHub.scala`
- Test: `src/test/scala/peripheral/NpuControlHubSpec.scala`

**Interfaces:**
- Consumes: `NpuCtrlProtocol`, `NpuCtrlSlave`.
- Produces: `class NpuControlHub(val K: Int = 32, val N: Int = 8, val L: Int = 32, val W: Int = 8, val NUM_SEG: Int = 5, val TRACE_DEPTH: Int = 256) extends Module` with `io.ctrl: NpuCtrlSlave`, `io.irq: Bool`, and core-facing signals (start/abort/prog_len/entry_pc/seg_limit/seg_base/seg_limit outputs; core_busy/done/illegal/mem_fault/status inputs). For this task, stub the core-facing outputs and test the register file only.

- [ ] **Step 1: Write failing register tests**

```scala
// ID magic, VERSION, CAPS.NUM_SEG=5, CONFIG.K=32/N=8/L=32/W=8
// SCRATCH0..3 read/write
// IRQ_ENABLE read/write; IRQ_STATUS W1C (write 1 clears, write 0 preserves)
// STATUS reset = IDLE, no DONE/ERROR
// IRQ = |(IRQ_STATUS & IRQ_ENABLE)
```

- [ ] **Step 2: Run and verify failure**

Run: `tool/test-specific-spec.sh peripheral.NpuControlHubSpec`
Expected: compile error / missing module.

- [ ] **Step 3: Implement the register file**

Use `RegInit` for all 32-bit registers. Decode `ctrl.req.addr` with `MuxLookup` plus a segment-table read path. W1C logic:

```scala
val clr = req.fire && req.bits.write && addr === IRQ_STATUS.U
val set = Cat(irqEventDone, irqEventError, irqEventTrace, irqEventBus)
irqStatus := (irqStatus & ~Mux(clr, req.bits.wdata, 0.U)) | set
```

- [ ] **Step 4: Run and verify pass**

Run: `tool/test-specific-spec.sh peripheral.NpuControlHubSpec`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/scala/peripheral/NpuControlHub.scala src/test/scala/peripheral/NpuControlHubSpec.scala
git commit -m "feat(peripheral): add NPUCTL register file and IRQ logic"
```

---

### Task 3: Control hub session FSM, watchdog, trace, segment translation

**Files:**
- Modify: `src/main/scala/peripheral/NpuControlHub.scala`
- Create: `src/main/scala/peripheral/NpuTrace.scala`
- Test: `src/test/scala/peripheral/NpuControlHubSessionSpec.scala`

**Interfaces:**
- Produces core-facing signals: `core_start`, `core_abort`, `core_prog_len[16]`, `core_entry_pc[16]`, `seg_limit[5][32]`, `seg_base[5][64]`; inputs `core_busy`, `core_done`, `core_illegal`, `core_mem_fault`, `core_pc[16]`, `core_err_instr[32]`, `core_cycles[32]`, `core_macs[32]`, `core_fetch_stats[32]`, `core_trace_retire`, `core_trace_pc[16]`.
- Produces external data master translation ports in `NpuPeripheral` (Task 6); the hub for this task exposes the translated address and validity.

- [ ] **Step 1: Write failing session tests**

- START with PROG_LEN=0 -> ERROR_CODE=5, IRQ.ERROR, no core_start.
- START with a valid program -> core_start pulse, BUSY, then core_done -> DONE + IRQ.DONE.
- STOP while running -> core_abort pulse, ABORTED, ERROR_CODE=6.
- START while busy -> ignored, no second core_start.
- WATCHDOG=10 -> core_abort, ERROR_CODE=3, IRQ.ERROR.
- Segment base misalignment -> ERROR_CODE=5.
- Segment translation: seg 1 base `0x1_0000_0000`, offset `0x400` -> `0x1_0000_0400`.
- Limit check: offset+bytes > limit -> `mem_fault`.

- [ ] **Step 2: Run and verify failure**

Run: `tool/test-specific-spec.sh peripheral.NpuControlHubSessionSpec`
Expected: FAIL on missing behavior.

- [ ] **Step 3: Implement the FSM, watchdog, trace FIFO, and translation**

FSM: IDLE -> RUN -> DONE; START snapshots active regs; STOP/SOFT_RESET abort. Watchdog counter only in RUN. Trace FIFO 256 x 64-bit, event encoder, overflow counter. Translation functions for AW/AR with 64-bit add and limit comparison. For the invalid case, expose `guard_fault` and clamp to the segment base.

- [ ] **Step 4: Run and verify pass**

Run: `tool/test-specific-spec.sh peripheral.NpuControlHubSessionSpec`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/scala/peripheral/NpuControlHub.scala src/main/scala/peripheral/NpuTrace.scala src/test/scala/peripheral/NpuControlHubSessionSpec.scala
git commit -m "feat(peripheral): add NPUCTL session FSM, watchdog, trace, translation"
```

---

### Task 4: Core relative sections and segment sideband

**Files:**
- Modify: `src/main/scala/engine/npuProgramEngine.scala`
- Modify: `src/main/scala/dma/npuDmaEngine.scala`
- Modify: `src/main/scala/engine/npuFrontend.scala`
- Modify: `src/test/scala/dma/NpuDmaEngineSpec.scala`
- Modify: `src/test/scala/engine/StreamHarness.scala` and engine specs (constants only)

**Interfaces:**
- `NpuDmaEngine` gains `req_seg: Input(UInt(3.W))`, `m_axi_awseg: Output(UInt(3.W))`, `m_axi_arseg: Output(UInt(3.W))`.
- `NpuProgramEngine` outputs `m_axi_awseg`/`m_axi_arseg` through the frontend.
- `NpuSections`: `A=0x0L`, `B=0x400L`, `ACCUM=0x800L`, `OUT=0x880L`, `CODE_BASE=0x4000L`; remove `DATA_BASE`.

- [ ] **Step 1: Write a failing sideband test**

Assert that a `vle8` from A drives `m_axi_arseg=0` with address `off`; a `vle8` from B drives `arseg=1`; a code fetch drives `arseg=4` with offset `0x4000` (or the test-relative code offset); the stored AXI address is the segment-relative offset, not the old absolute address.

- [ ] **Step 2: Run and verify failure**

Run: `tool/test-specific-spec.sh dma.NpuDmaEngineSpec`
Expected: FAIL / compile error.

- [ ] **Step 3: Implement**

- `NpuDmaEngine`: latch `reqSeg`, output it with AW/AR; no change to the FSM or data path.
- `NpuProgramEngine`: `dmaL3 = NpuSections.<section> + slots.off` (flat local); `reqSeg = Mux(dir === 3, 4.U, slots.sect)`.
- `npuFrontend`: `fetch_fill_addr := CODE_BASE + (fillLine << 6)`.
- `NpuSections` relative constants; update comments.

- [ ] **Step 4: Run the DMA spec and the frontend spec**

Run: `tool/test-specific-spec.sh dma.NpuDmaEngineSpec`
Run: `tool/test-specific-spec.sh engine.NpuProgramEngineFrontendSpec`
Expected: PASS (only test constants changed).

- [ ] **Step 5: Commit**

```bash
git add src/main/scala/engine/npuProgramEngine.scala src/main/scala/dma/npuDmaEngine.scala src/main/scala/engine/npuFrontend.scala src/test/scala/dma/NpuDmaEngineSpec.scala src/test/scala/engine src/test/scala/utils
git commit -m "refactor(engine): emit segmented relative addresses"
```

---

### Task 5: Core limits, direct control/status, entry PC, trace

**Files:**
- Modify: `src/main/scala/engine/npuProgramEngine.scala`
- Modify: `src/main/scala/engine/npuFrontend.scala`
- Test: `src/test/scala/engine/NpuCoreControlSpec.scala`

**Interfaces:**
- `NpuProgramEngineFrontend` new IO:
  - Inputs: `core_start`, `core_abort`, `core_prog_len[16]`, `core_entry_pc[16]`, `seg_limit[5][32]`, `mem_fault`.
  - Outputs: `core_busy`, `core_done`, `core_illegal`, `core_pc[16]`, `core_err_instr[32]`, `core_cycles[32]`, `core_macs[32]`, `core_fetch_stats[32]`, `trace_retire`, `trace_pc[16]`, `mem_fault_out`, `fault_seg[3]`, `fault_addr[32]`.
- The existing `ctrl_*` port remains for tests/debug; the direct inputs take precedence when driven.

- [ ] **Step 1: Write failing tests**

- `core_start` alone starts the program (no ctrl write); `core_done` asserts.
- `core_abort` during RUN returns to idle and `core_busy=0`.
- `core_prog_len`/`core_entry_pc` select a nonzero entry and finish at `entry+len-1`.
- A data access at `offset + vector_bytes > seg_limit` raises `mem_fault_out` with the segment and offset; no AXI request is issued for that access.
- `trace_retire` pulses once per issued instruction with the PC.

- [ ] **Step 2: Run and verify failure**

Run: `tool/test-specific-spec.sh engine.NpuCoreControlSpec`
Expected: FAIL.

- [ ] **Step 3: Implement**

- Frontend: OR `core_start` into the start pulse; add an abort path that clears IFQ/fill/warm and returns `RUN_IDLE`; use `endPc = entryPc + progLen` for `fetchActive`/`lastLine`; warm line = `entryPc >> 6`; direct status/trace outputs.
- Engine: check `slots.off + vectorBytes(memWidth) <= seg_limit(sect)` at DMA issue; on failure, set `mem_fault_out`, `fault_seg`, `fault_addr`, and mark the slot illegal/done. Do not enqueue a DMA request.
- Frontend: check the code fetch offset against `seg_limit(4)`; a violation sets `mem_fault_out`.
- Keep the existing `illegal_out` path for decode-illegal instructions.

- [ ] **Step 4: Run and verify pass**

Run: `tool/test-specific-spec.sh engine.NpuCoreControlSpec`
Run: `tool/test-specific-spec.sh engine.NpuProgramEngineFrontendSpec`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/scala/engine/npuProgramEngine.scala src/main/scala/engine/npuFrontend.scala src/test/scala/engine/NpuCoreControlSpec.scala
git commit -m "feat(engine): add core limits, direct control, entry PC, and trace"
```

---

### Task 6: `NpuPeripheral` wrapper and integration test

**Files:**
- Create: `src/main/scala/peripheral/NpuPeripheral.scala`
- Modify: `src/main/scala/top/top.scala`
- Test: `src/test/scala/peripheral/NpuPeripheralSpec.scala`
- Test utility: `src/test/scala/utils/axiRam.scala` (add a 64-bit-address wrapper or a `base` parameter)

**Interfaces:**
- `NpuPeripheral` external: `s_axil_*` (12-bit/32-bit), `m_axi_*` (64-bit address/512-bit data), `irq`, `trace_valid/ready/data[63:0]`.
- Internally instantiates `NpuCtrlAxiLite`, `NpuControlHub`, `NpuProgramEngineFrontend`; connects the segmented core AXI to the hub translator.

- [ ] **Step 1: Write the failing integration test**

Drive AXI4-Lite to:
- read ID/CONFIG;
- program SEG_BASE for A/B/ACCUM/OUT/CODE to distinct system addresses (e.g., `0x1000_0000`, `0x1000_1000`, ...);
- stage a small one-shot MMA program and its operands into a 64-bit AXI RAM model at those addresses;
- write PROG_LEN, START;
- wait for IRQ/STATUS.DONE;
- read OUT through the system AXI RAM at the OUT base.

- [ ] **Step 2: Run and verify failure**

Run: `tool/test-specific-spec.sh peripheral.NpuPeripheralSpec`
Expected: FAIL / missing module.

- [ ] **Step 3: Implement the wrapper and translation**

- Instantiate the hub and core; wire direct control/status and segment limits.
- Translate the core's AW/AR `{seg, offset}` to `SEG_BASE[seg] + offset|, 64-bit.
- Route invalid bursts to a guard path: assert `mem_fault` and clamp to the segment base; the hub aborts the session.
- Connect `irq` and the trace stream.
- `top.Main` emits `NpuPeripheral` with `K=32, N=8, W=8`.

- [ ] **Step 4: Run and verify pass**

Run: `tool/test-specific-spec.sh peripheral.NpuPeripheralSpec`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/scala/peripheral/NpuPeripheral.scala src/main/scala/top/top.scala src/test/scala/peripheral/NpuPeripheralSpec.scala src/test/scala/utils
git commit -m "feat(peripheral): add NpuPeripheral and integration test"
```

---

### Task 7: Release manifest, protocol doc, stale-doc fixes

**Files:**
- Create: `docs/designs/07.npuctl-v1.md` (normative register map and semantics)
- Create: `src/main/scala/peripheral/NpuRelease.scala` or a small sbt task
- Modify: `Makefile` (add `release`)
- Modify: `AGENTS.md`, `docs/implementations/FPGA_XC7K480T.md` (K=32/W=8 baseline; platform paths move)

- [ ] **Step 1: Write the protocol doc and release metadata**
- [ ] **Step 2: Run `make release` and inspect `build/NpuPeripheral.sv` + `build/npu_peripheral.json`**
- [ ] **Step 3: Commit**

```bash
git add docs/designs/07.npuctl-v1.md Makefile AGENTS.md docs/implementations/FPGA_XC7K480T.md
git commit -m "docs: add NPUCTL v1 spec and release metadata"
```

---

### Task 8: Platform repo `~/chisel-npu-platform` and testbench skeletons

**Files (new repo):**
- `README.md`
- `.gitignore`
- `rtl/NpuBoardTop.sv` (placeholder instantiating `NpuPeripheral`, board IP ports)
- `rtl/mem/NpuMemModel.sv` (AXI4 slave behavioral model with base/size/latency)
- `tb/NpuBoardTopSpec.scala` or a SystemVerilog testbench skeleton
- `sw/npu_platform/{transport.py,platform_info.py,allocator.py}` (XDMA placeholders)
- `boards/xc7k480t/README.md`, `boards/vu9p/README.md`

- [ ] **Step 1: `git init` the repo and add the skeleton**
- [ ] **Step 2: Add the generic memory model and a smoke test**
- [ ] **Step 3: Document how the board top maps the model to DDR3/DDR4**
- [ ] **Step 4: Commit**

```bash
cd ~/chisel-npu-platform && git init && git add . && git commit -m "chore: bootstrap board testbench repo"
```

---

### Task 9: Driver protocol core and transport split (Phase 4)

**Files:**
- Modify: `drivers/chisel_npu_py/src/chisel_npu_py/config.py`
- Modify: `drivers/chisel_npu_py/src/chisel_npu_py/npu.py`
- Modify: `drivers/chisel_npu_py/src/chisel_npu_py/backend.py`
- Add: `drivers/chisel_npu_py/src/chisel_npu_py/protocol.py`
- Add: `drivers/chisel_npu_py/src/chisel_npu_py/transport.py`
- Tests: `drivers/chisel_npu_py/tests/test_protocol.py`, update `test_npu_mock.py`

- [ ] **Step 1: Write failing tests for ID/CAPS/CONFIG validation, segment programming, and IRQ/poll status**
- [ ] **Step 2: Implement `NpuDriverCore` with a `Transport` protocol and `FakeTransport`**
- [ ] **Step 3: Make `NPUConfig` protocol-only; move system bases/BAR/DDR to `PlatformInfo`**
- [ ] **Step 4: Run `make py-test-unit`**
- [ ] **Step 5: Commit**

---

### Task 10: Full regression and branch finish

- [ ] `make test` (full Chisel suite)
- [ ] `make release`
- [ ] `make py-test-unit`
- [ ] Review `git diff` for accidental ISA/program-model changes
- [ ] Commit any fixes; summarize.

## Self-Review

- Spec coverage: protocol (Tasks 1-3), wrapper/translation (Tasks 3,6), memory-agnostic core (Tasks 4-5), testbench split (Task 8), driver (Task 9), release/docs (Task 7).
- The frozen ISA is explicitly protected in Tasks 4-5: only address formation and test constants change.
- No task adds a memory controller to `NpuPeripheral`.
- The platform repo path uses the user-supplied spelling.
- Out-of-scope instruction reach scaling is not implemented.

## Execution status (2026-11-22)

- Tasks 1-9 completed on branch `fangrui/platform-cleanup`.
- Relevant regression: `peripheral.NpuCtrlAxiLiteSpec` (2), `peripheral.NpuControlHubSpec` (7),
  `dma.NpuDmaEngineSpec` (13), `engine.NpuProgramEngineFrontendSpec` (14),
  `engine.NpuCoreControlSpec` (3), `peripheral.NpuPeripheralSpec` (1),
  `engine.StreamedChainedSpec` (1), `engine.FrontendPerfCounterSpec` (1),
  non-hw Python tests (28), platform Verilator lint + identity smoke.
- The board repo is bootstrapped at `~/chisel-npu-platform` and pins
  `releases/NpuPeripheral.sv` + `releases/npu_peripheral.json`.
- Review-driven fixes landed: broken direct-engine specs, native START cache
  invalidation, abort-drain, trace FIFO count/half-read/event IDs, AXI4-Lite
  serialization, local AXI error responses, code-fetch limit, data-plane
  BRESP/RRESP errors, and driver start/wait/run semantics. See the branch
  commits after `fc07dfe`.
- Deferred: full `sbt test` (long simulation), deletion of the legacy
  `ip/vivado/**` / `tool/hw/**` trees, a registered 64-bit translation stage
  (v1 is combinational; translator test covers >4GB), a full-session board
  smoke, and wiring the legacy `ChiselNPU` user API onto `NpuDriverCore`.

## Execution status update (2026-11-23)

### Board build verification

- **xc7k480t**: verified and programmed. The K=32 image is unroutable on this
  part (NPU 175,734 LUT / 58.9%; total 240,921/298,600 = 80.7%; router reports
  global congestion level 6). The board now pins
  `releases/NpuPeripheral_K16.sv` (K=16/L=16, same ports, NPUCTL map and ISA).
  Final image: WNS **+0.030 ns**, WHS **+0.015 ns**, 0 failing endpoints,
  115,364 LUTs (38.6%), 156,058 FF (26.1%), 54 BRAM (5.7%), 18.7 MB bitstream.
- **vu9p**: first build completed and programmed, but it is a **100 MHz**
  image: `build_npu_vu9p.tcl` linked a stale `clk_wiz_fabric` OOC DCP (100 MHz)
  even though the XCI/RTL requested 200 MHz. The reported WNS +0.003 / WHS
  +0.010 and 326,402 LUTs (27.6%) are therefore a 100 MHz build, not 200 MHz
  signoff. The 100 MHz image is the **correct K=32 operating
  point**: the worst intra-fabric path is 9.624 ns (~104 MHz). A 200 MHz K=32
  build would need an NPU pipeline stage (out of scope here); the earlier
  200 MHz claim came from the K16 design. The 100 MHz image is programmed
  (`End of startup status: HIGH`, 4 MIG cores); the flow now pins the clock IP
  to 100 MHz and asserts a 10.000 ns fabric period before implementation.
- **K=16 release**: `top.MainK16` + `make release-k16` produce
  `build/NpuPeripheral_K16.sv` and `build/npu_peripheral_k16.json`
  (`K=16, N=8, L=16, W=8`). RTL hash
  `ee3e0b93a2e624584ffc1ebabe1fb14c1c0797b19addd95eb8046fe11cb0f872`.

### Vivado 2025.2 workarounds (recorded in the platform scripts)

1. `FLATTEN_HIERARCHY full` on the NPU OOC and top synthesis runs — the
   partition merge/timing-genome pass hangs (4 h, no progress) or segfaults.
2. Launch synth runs with `-jobs 1` — the file-integrity registry false-positive
   ("libxv_*.so is corrupt") aborts when several synthesis processes start
   together.
3. Drive the NPU reset through a `BUFG` — `phys_opt_design` segfaults in
   `HAPSFanOpt::cellDupForEachBin` replicating the high-fanout
   `calib_sync[1]` reset.
4. Run xc7k480t implementation on one thread — the post-route bus-skew timing
   update segfaults in a timing worker thread at >1. The vu9p image routed at
   8 threads with WNS +0.003 ns.

### Regression

- Full `sbt test`: 175/176 passed. The single failure
  (`engine.FrontendThroughputSpec`) was a Verilator internal compiler fault;
  the spec passes 1/1 when rerun alone (4m43s).
- Platform Python tests: 3/3 passed. Targeted Chisel suites and the non-HW
  driver tests remain green.

### Deferred (unchanged)

- Board full-session smoke (NPUCTL segment programming + IRQ/trace on silicon).
- Porting the legacy `ChiselNPU` user API onto `NpuDriverCore`.
- Registered 64-bit translation stage.
- PCIe enumeration on both lab hosts after JTAG programming requires a host
  warm reboot; SBR/rescan alone did not train the link. The boards are
  programmed (startup HIGH); the host reboot is intentionally left to the user.

### Cleanup (done)

- The legacy `ip/vivado/**`, `tool/hw/**`, board `FPGA_*.md` docs and the three
  board skills were removed from the IP repository in commit `b2a5dd2`, after
  both bitstreams were verified. The platform repo is the sole owner of board
  Vivado flows, constraints, bitstreams, docs and hardware tests.
- The IP repo now carries only the IP, driver core, ISA/design docs and the
  release targets (`make release`, `make release-k16`).
