# Decode/Dispatch Hardening + De-duplication — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make illegal/out-of-range decode states explicit in `NpuProgramEngine`, collapse the duplicated legality/decode rules onto `InstrTable`, document the engine-controlled MMA `keep`, and add engine-level regression tests — all behavior-preserving for legal programs.

**Architecture:** P1 hardens the scheduler (`illegalCond` operand range, a `NONE`-unit retire fallback, explicit `memWidth` branches) and locks it with a new `DecodeDispatchPolicySpec`. P2 adds Scala/Chisel legality helpers to `InstrTable` and folds `InstrDecoder`, `NpuDisassembler`, and `NpuProgramEngineFrontend` onto them. P3/P4 are documentation/comment corrections.

**Tech Stack:** Chisel 6.7.0, Scala 2.13.12, sbt 1.9.7, `chisel3.simulator.EphemeralSimulator`, ScalaTest `AnyFlatSpec`, `isa.NpuAssembler`, `engine.StreamHarness`.

**Spec:** `docs/designs/01.isa.md` (ISA), `docs/designs/04.streamed-issuing.md` (authoritative issuing model), `docs/designs/02.streamed-dispatch.md` (superseded overview), `src/main/scala/isa/{instrFormat,instSetArch,InstrTable,instrDecoder}.scala`, and the decode/dispatch review in this thread.

## Global Constraints

- Toolchain runs **only** in Docker: `make test`, `tool/test-specific-spec.sh <fq.Spec>`, `make build`. Bare-host `sbt` will not work.
- Parameters unchanged: `N`(bits)=8; `L = K`; top `K=16, W=4` (`top.scala`); test harness default `K=16`.
- Operand widths at the engine top: MMA `rd` is a **VR** index (`< L/4`); MMA `rs1`/`rs2` are **VX** (`< L`); `vs3==0` means "no C", else `vs3 < L/4`. LD/ST `rd` is a class index: VX `<L`, VE `<L/2`, VR `<L/4`; `rs1` is the section (`≤3`).
- Assemble words as Scala `Int`; poke as `(instr.toLong & 0xFFFFFFFFL).U`.
- ChiselEnum outputs compared via `field.peekValue().asBigInt`; `expect` only for UInt/Bool.
- Preserve every public `NpuAssembler` helper signature and all existing explanatory comments in touched files; do not reformat unrelated code.
- Commit after each task; never commit `top.sv`, `target/`, `test_run_dir/`.

## Review Focus

Ordered by likelihood of biting a user; each line gets an owning test unless marked defensive.

1. **Out-of-range operand with a legal `funct3` previously aliased silently** (scoreboard saw "no dependency"; the RF address truncated to a different register). Now rejected as illegal — software that relied on aliasing breaks. Owning test: Task 1.
2. **CVT `srcFmt=3` (F32) sets `funct7[1:0]=3`** — it must remain legal after `widthOK` is rewritten to use `InstrTable.widthExempt`. Owning test: Task 3 (`InstrDecoderSpec` CVT cases).
3. **`DispatchUnit.NONE` has no completion path** — latent wedge if the family→unit map changes. Defensive only; not reachable in tests. Owning task: Task 1 (fallback + assertion).
4. **NOP with `funct7[1:0]=3` / `funct7[6:5]=3` stays illegal** (current behavior; NOP is excluded from the def fold, so the exemption wires stay false). Owning test: Task 3.
5. **Frontend `isMmaLast` refactor must not change `frames_done`** for a malformed `0x03/funct3=1` word. Owning test: Task 5 (`NpuProgramEngineFrontendSpec` asserts `frames_done==1`).

---

### Task 1: Engine operand-range checks + scheduler safety

**Files:**
- Modify: `src/main/scala/engine/npuProgramEngine.scala` (`illegalCond` ~591-596; issue action ~436-471; `readSlotsL` ~207-224; `writeSlotsL` ~226-240)
- Test: `src/test/scala/engine/DecodeDispatchPolicySpec.scala` (create)

**Interfaces:**
- Consumes: `engine.StreamHarness`, `engine.StreamSpecUtil.{issue,preloadI8}`, `isa.NpuAssembler.{mma,mmaLast,vle8,vse16,vse32,vadd}`.
- Produces: `io.illegal_out` now pulses within a few cycles for out-of-range MMA/LD/ST operands and unsupported families; unchanged for in-range programs.

- [ ] **Step 1: Write the failing test**

```scala
package engine

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec
import isa._
import isa.NpuAssembler._

class DecodeDispatchPolicySpec extends AnyFlatSpec {
  "engine decode policy" should "reject out-of-range MMA operands" in {
    simulate(new StreamHarness(K = 16)) { dut =>
      dut.io.session_reset.poke(false.B)
      def expectIllegal(instr: Int): Unit = {
        StreamSpecUtil.issue(dut, instr)
        var n = 0
        while (!dut.io.illegal_out.peek().litToBoolean && n < 50) { dut.clock.step(); n += 1 }
        assert(dut.io.illegal_out.peek().litToBoolean, f"0x$instr%08x should be illegal")
        dut.io.session_reset.poke(true.B); dut.clock.step()
        dut.io.session_reset.poke(false.B)
      }
      expectIllegal(mma(rd = 4, vs1 = 4, vs2 = 8, vs3 = 0))    // rd  >= L/4
      expectIllegal(mma(rd = 0, vs1 = 16, vs2 = 8, vs3 = 0))   // rs1 >= L
      expectIllegal(mma(rd = 0, vs1 = 4, vs2 = 16, vs3 = 0))   // rs2 >= L
      expectIllegal(mma(rd = 0, vs1 = 4, vs2 = 8, vs3 = 4))    // vs3 >= L/4
    }
  }

  it should "reject out-of-range memory operands and unsupported families" in {
    simulate(new StreamHarness(K = 16)) { dut =>
      dut.io.session_reset.poke(false.B)
      def expectIllegal(instr: Int): Unit = {
        StreamSpecUtil.issue(dut, instr)
        var n = 0
        while (!dut.io.illegal_out.peek().litToBoolean && n < 50) { dut.clock.step(); n += 1 }
        assert(dut.io.illegal_out.peek().litToBoolean, f"0x$instr%08x should be illegal")
        dut.io.session_reset.poke(true.B); dut.clock.step()
        dut.io.session_reset.poke(false.B)
      }
      expectIllegal(vle8(rd = 16, sect = NpuSections.SECT_A, off = 0))   // VX >= L
      expectIllegal(vse16(src = 8, sect = NpuSections.SECT_OUT, off = 0)) // VE >= L/2
      expectIllegal(vse32(src = 4, sect = NpuSections.SECT_OUT, off = 0)) // VR >= L/4
      expectIllegal(vadd(rd = 0, rs1 = 1, rs2 = 2, width = NpuAssembler.VX)) // unsupported family
    }
  }

  it should "still accept and drain an in-range one-shot session" in {
    simulate(new StreamHarness(K = 16)) { dut =>
      dut.io.session_reset.poke(false.B)
      StreamSpecUtil.preloadI8(dut, NpuSections.A, Seq.tabulate(16)(i => i + 1))
      StreamSpecUtil.preloadI8(dut, NpuSections.B, Seq.tabulate(16)(i => 2))
      for (w <- Seq(vle8(4, NpuSections.SECT_A, 0), vle8(8, NpuSections.SECT_B, 0),
                     mma(2, 4, 8, 0), mmaLast(0, 0, 0, 0), vse32(2, NpuSections.SECT_OUT, 0)))
        StreamSpecUtil.issue(dut, w)
      var n = 0
      while (!dut.io.dbg_drain.peek().litToBoolean && n < 100000) { dut.clock.step(); n += 1 }
      assert(dut.io.dbg_drain.peek().litToBoolean, "session did not drain")
      assert(!dut.io.illegal_out.peek().litToBoolean, "in-range session flagged illegal")
    }
  }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `tool/test-specific-spec.sh engine.DecodeDispatchPolicySpec`
Expected: FAIL — out-of-range operands currently enter the window without `illegal_out`.

- [ ] **Step 3: Implement the checks**

In `npuProgramEngine.scala`, immediately before `illegalCond` (~591), add:
```scala
val mmaRangeBad =
  (dec.family === OpFamily.MMA) &&
  (dec.rd >= (L / 4).U || dec.rs1 >= L.U || dec.rs2 >= L.U ||
   (dec.rs3 =/= 0.U && dec.rs3 >= (L / 4).U))
val classSize =
  MuxLookup(dec.mem_width, L.U)(Seq(1.U -> (L / 2).U, 2.U -> (L / 4).U))
val memRangeBad =
  (dec.family === OpFamily.LD || dec.family === OpFamily.ST) &&
  (dec.rd >= classSize)
```
and OR `mmaRangeBad || memRangeBad` into `illegalCond`.

In the issue action `when (issueSel(s))` (~436), add a final branch:
```scala
.otherwise { slots(s).done := true.B }   // DispatchUnit.NONE: retire defensively
```

In `readSlotsL` and `writeSlotsL`, change the final `.otherwise` VR branch to `.elsewhen (memWidth === 2.U)` so a `memWidth == 3` leaves the mask 0 (the `WireDefault(0.U)` covers it).

- [ ] **Step 4: Run to verify pass**

Run: `tool/test-specific-spec.sh engine.DecodeDispatchPolicySpec`
Expected: PASS. Then `tool/test-specific-spec.sh engine.StreamedNSessionSpec` and `engine.CaptureDeterminismSpec` — PASS (no over-rejection).

- [ ] **Step 5: Commit**

```bash
git add src/main/scala/engine/npuProgramEngine.scala src/test/scala/engine/DecodeDispatchPolicySpec.scala
git commit -m "fix(engine): reject out-of-range operands; retire NONE slots; explicit memWidth"
```

---

### Task 2: `InstrTable` legality helpers

**Files:**
- Modify: `src/main/scala/isa/InstrTable.scala` (add `import chisel3._`, `import chisel3.util._`; append helpers to `object InstrTable`)
- Test: `src/test/scala/isa/InstrTableSpec.scala` (append)

**Interfaces:**
- Produces:
  - `InstrTable.widthExempt(d: InstrDef): Boolean` — true for `fmt == Fmt.I` or opcode `0x16`/`0x17`
  - `InstrTable.dtypeExempt(d: InstrDef): Boolean` — true for `fmt == Fmt.I`
  - `InstrTable.isMmaLastWord(w: UInt): Bool` — `(w & 0x7F) === 0x03 && ((w >> 12) & 7) === 1`
  - `InstrTable.isMmaLastWord(w: Int): Boolean` — same, Scala-level
- Consumes: `InstrTable.byMnemonic`, `isa.NpuAssembler.{mma,mmaLast}`.

- [ ] **Step 1: Write the failing test**

```scala
"InstrTable" should "classify attribute exemptions and mma.last" in {
  val r  = InstrTable.byMnemonic("vadd")
  assert(!InstrTable.widthExempt(r) && !InstrTable.dtypeExempt(r))
  val i  = InstrTable.byMnemonic("vle8")
  assert(InstrTable.widthExempt(i) && InstrTable.dtypeExempt(i))
  val fp = InstrTable.byMnemonic("vfadd")
  assert(InstrTable.widthExempt(fp) && !InstrTable.dtypeExempt(fp))
  assert(InstrTable.isMmaLastWord(NpuAssembler.mmaLast(0, 0, 0, 0)))
  assert(!InstrTable.isMmaLastWord(NpuAssembler.mma(0, 0, 0, 0)))
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `tool/test-specific-spec.sh isa.InstrTableSpec`
Expected: FAIL — `widthExempt` not defined.

- [ ] **Step 3: Implement the helpers** in `InstrTable` (signatures above; bodies are the single boolean expressions).

- [ ] **Step 4: Run to verify pass**

Run: `tool/test-specific-spec.sh isa.InstrTableSpec`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/scala/isa/InstrTable.scala src/test/scala/isa/InstrTableSpec.scala
git commit -m "feat(isa): InstrTable attribute-exempt + mma.last helpers"
```

---

### Task 3: Fold `InstrDecoder` width/dtype exemptions onto the helpers

**Files:**
- Modify: `src/main/scala/isa/instrDecoder.scala` (replace `isIFmt` ~103; fold loop ~104-112; `widthOK` ~152-157; `dtypeOK` ~177-179)

**Interfaces:**
- Consumes: `InstrTable.widthExempt`, `InstrTable.dtypeExempt`.
- Produces: identical `io.illegal` / `DecodedMicroOp` (behavior-preserving).

- [ ] **Step 1: Lock behavior**

Run: `tool/test-specific-spec.sh isa.InstrDecoderSpec` → PASS (oracle; this includes the CVT `srcFmt=3` and NOP cases of Review Focus #2/#4).

- [ ] **Step 2: Refactor**

Replace the `isIFmt` wire with two exemption wires set from the matched def:
```scala
val widthExemptD = WireDefault(false.B)
val dtypeExemptD = WireDefault(false.B)
// inside `for (d <- defs) when (f3 === d.funct3.U) { ... }`:
widthExemptD := InstrTable.widthExempt(d).B
dtypeExemptD := InstrTable.dtypeExempt(d).B
```
Rewrite:
```scala
val widthOK = !((f7Width === 3.U) && !widthExemptD &&
  (family =/= OpFamily.VALU_FP) && (family =/= OpFamily.VALU_FP_FMA) &&
  (family =/= OpFamily.VALU_CVT))
val dtypeOK = !((f7Dtype === 3.U) && !dtypeExemptD)
```
Keep the explicit `VALU_CVT` exemption in `widthOK` (CVT `srcFmt=3` sets `f7Width=3`) and do **not** add a CVT exemption to `dtypeOK` (preserves current behavior).

- [ ] **Step 3: Run**

Run: `tool/test-specific-spec.sh isa.InstrDecoderSpec` then `isa.InstrTableSpec`
Expected: PASS (no behavior change).

- [ ] **Step 4: Commit**

```bash
git add src/main/scala/isa/instrDecoder.scala
git commit -m "refactor(isa): InstrDecoder exemptions from InstrTable"
```

---

### Task 4: Fold `NpuDisassembler.attrOK` onto the helpers

**Files:**
- Modify: `src/main/scala/isa/NpuDisassembler.scala:73-77`

**Interfaces:**
- Consumes: `InstrTable.widthExempt`, `InstrTable.dtypeExempt`.
- Produces: identical `NpuDisassembler(word: Int): String`.

- [ ] **Step 1: Lock behavior**

Run: `tool/test-specific-spec.sh isa.NpuDisassemblerSpec` → PASS (oracle).

- [ ] **Step 2: Refactor**

```scala
private def attrOK(d: InstrDef, width: Int, dtype: Int): Boolean =
  (InstrTable.widthExempt(d) || width != 3) &&
  (InstrTable.dtypeExempt(d) || dtype != 3)
```

- [ ] **Step 3: Run**

Run: `tool/test-specific-spec.sh isa.NpuDisassemblerSpec` then `isa.InstrDecoderSpec`
Expected: PASS (decoder/disassembler legality stay in lockstep).

- [ ] **Step 4: Commit**

```bash
git add src/main/scala/isa/NpuDisassembler.scala
git commit -m "refactor(isa): disassembler attrOK from InstrTable"
```

---

### Task 5: Frontend shares `InstrTable.isMmaLastWord`

**Files:**
- Modify: `src/main/scala/engine/npuFrontend.scala` (add `import isa._`; replace the raw-bit `isMmaLast` ~159)

**Interfaces:**
- Consumes: `InstrTable.isMmaLastWord(w: UInt): Bool`.
- Produces: identical `frames_done` / STATUS behavior.

- [ ] **Step 1: Lock behavior**

Run: `tool/test-specific-spec.sh engine.NpuProgramEngineFrontendSpec` → PASS (asserts `frames_done == 1` at `:230,:273`).

- [ ] **Step 2: Replace the decode**

```scala
val isMmaLast = InstrTable.isMmaLastWord(issuedWord)
```

- [ ] **Step 3: Run**

Run: `tool/test-specific-spec.sh engine.NpuProgramEngineFrontendSpec` then `engine.StreamedNSessionSpec`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add src/main/scala/engine/npuFrontend.scala
git commit -m "refactor(engine): frontend shares InstrTable.isMmaLastWord"
```

---

### Task 6: Document engine-controlled MMA `keep` (P3, docs only)

**Files:**
- Modify: `src/main/scala/engine/npuProgramEngine.scala` (comment at the `mmalu.io.ctrl.keep := !boundaryPulse` line ~515)
- Modify: `src/main/scala/isa/instrDecoder.scala` (comment in the MMA-control block ~185-194)
- Modify: `docs/designs/01.isa.md` (MMA `funct7[4]` semantics)
- Modify: `docs/designs/04.streamed-issuing.md` (§6.2 / §9)

**Interfaces:** none (documentation only).

- [ ] **Step 1: State the semantics once**

In all four places, state: "The program engine drives MMALU `keep` from the boundary gate (`keep := !boundaryPulse`); the decoded `mma_keep` (funct7[4]) is retained for `NCoreBackend` but is ignored at the engine top."

- [ ] **Step 2: Verify no functional change**

Run: `make test`
Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add src/main/scala/engine/npuProgramEngine.scala src/main/scala/isa/instrDecoder.scala docs/designs/01.isa.md docs/designs/04.streamed-issuing.md
git commit -m "docs(engine): engine controls mma keep via boundary gate"
```

---

### Task 7: Doc/comment hygiene (P4)

**Files:**
- Modify: `src/main/scala/isa/instrFormat.scala:8` and `src/main/scala/isa/instSetArch.scala:17` ("64 at top" → "16 at top (FPGA program engine)")
- Modify: `src/main/scala/backend/SimpleBackend.scala:7` (drop the stale "top level K = 64")
- Modify: `docs/designs/02.streamed-dispatch.md:150` (fix the malformed chaining-table row)

**Interfaces:** none (documentation only).

- [ ] **Step 1: Apply the corrections** (text only).

- [ ] **Step 2: Verify elaboration still succeeds**

Run: `make build`
Expected: writes `top.sv`; no elaboration error.

- [ ] **Step 3: Commit**

```bash
git add src/main/scala/isa/instrFormat.scala src/main/scala/isa/instSetArch.scala src/main/scala/backend/SimpleBackend.scala docs/designs/02.streamed-dispatch.md
git commit -m "docs: correct top-level K and fix dispatch table row"
```

---

## Self-Review

- **Spec coverage:** P1.1 → Task 1; P1.2 → Task 1; P1.3 → Task 1; P2 helpers → Task 2; decoder fold → Task 3; disassembler fold → Task 4; frontend fold → Task 5; P3 keep → Task 6; P4 hygiene → Task 7. All in the chosen "Everything (P1–P5)" scope.
- **Step scan:** each code step names the exact file/line and signature; test steps carry the assertions; no step says "handle edge cases".
- **Type consistency:** `widthExempt`/`dtypeExempt`/`isMmaLastWord` are introduced in Task 2 and consumed with those exact names in Tasks 3–5; `illegalCond`, `readSlotsL`/`writeSlotsL` keep their names.
- **Review Focus:** lines 1, 2, 4, 5 have owning tests (Tasks 1, 3, 3, 5); line 3 is explicitly defensive with a fallback + no reachable test, noted here rather than silently skipped.
- **Proportion:** signature/assertion level, not full bodies; the one body shown (range checks) is an algorithm the signatures don't determine.
