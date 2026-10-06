# Instruction Decoder Hardening + Table-Driven Refactor — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Eliminate silent misdecode in `InstrDecoder` (opcode aliasing, CVT default-to-`vadd`), close decode-test gaps, and make one authoritative decode table feed the decoder, assembler, and disassembler.

**Architecture:** Phase A fixes correctness in the existing hand-written decoder and locks it with tests; Phase B extracts a Scala-level `InstrTable` (the single source of truth) and folds `InstrDecoder`, `NpuAssembler`, and a new `NpuDisassembler` onto it. Behavior is preserved and proven by an exhaustive table-driven spec.

**Tech Stack:** Chisel 6.7.0, Scala 2.13.12, sbt 1.9.7, `chisel3.simulator.EphemeralSimulator`, ScalaTest `AnyFlatSpec`, `isa.NpuAssembler`.

**Spec:** `docs/designs/01.isa.md` (ISA), `src/main/scala/isa/instrFormat.scala` + `instSetArch.scala` (field semantics), and the decoder review in this thread.

## Global Constraints

- Toolchain: Chisel 6.7.0, Scala 2.13.12, sbt 1.9.7. Tests run **only** in Docker: `make test`, or `tool/test-specific-spec.sh <fq.Spec>`.
- Parameters: `N`(bits)=8 default; `L`=32; `K`=8 (tests) / 32 (top). Do not change these.
- Opcodes (verbatim): NOP 0x00, MMA 0x03, LD 0x07, VALU_ARITH 0x10, LOGIC 0x11, REDUCE 0x12, LUT 0x13, CVT 0x14, BCAST 0x15, FP 0x16, FP_FMA 0x17, MOV 0x18, ST 0x27.
- `funct7`: [1:0] width (VX0/VE1/VR2/reserved3), [3:2] round, [4] sat, [6:5] dtype (INT0/FP1/BF2/reserved3). CVT re-layouts funct7 ([2:0] src, [3] sat, [5:4] round, [6] BF8 variant).
- Assemble words as Scala `Int`; poke as `(instr.toLong & 0xFFFFFFFFL).U`.
- ChiselEnum outputs: compare with `field.peekValue().asBigInt` (the existing `enumLit` helper); `expect` works only for UInt/Bool.
- Preserve all existing public `NpuAssembler` helper names/signatures — many engine specs import them.
- Preserve existing explanatory comments in touched files; do not reformat unrelated code.
- Commit after each task; do not commit `top.sv`, `target/`, `test_run_dir/`.

## Review Focus

Ordered by likelihood of biting a user; each has an owning task below.

1. **Opcodes ≥ 0x40 aliasing onto valid families** (e.g. `0x40`→NOP, `0x50`→VALU_ARITH). Owning test: Task 1.
2. **CVT invalid/reserved `(dst,src)` pairs executing as `vadd`.** Owning test: Task 2.
3. **I-type instructions where `funct7` overlaps `imm`** (LD/ST/vsetlut/movi) tripping or leaking `width`/`dtype`/`sat`/`round`. Owning tests: Task 3 (per-family width/dtype-illegal), Task 6 (parity).
4. **`illegal` outputs must be deterministic** (no X/undefined) so a flushed `DecodedMicroOp` can't corrupt downstream. Owning test: Task 3.
5. **`dtype` for integer ops is class-only (defaults `S8C4`)** — consumers must not infer element width. Owning test: Task 3 pins it.

---

### Task 1: Fix opcode bit-6 aliasing

**Files:**
- Modify: `src/main/scala/isa/instrDecoder.scala:76-85`
- Test: `src/test/scala/isa/InstrDecoderSpec.scala` (append before line 482 `}`)

**Interfaces:**
- Produces: `familyOK: Bool`, `family: OpFamily.Type` driven from the full 7-bit `opBits` (unchanged names).

- [ ] **Step 1: Write the failing test**

```scala
"InstrDecoder" should "flag opcodes with bit 6 set as illegal (no aliasing)" in {
  simulate(new InstrDecoder) { dut =>
    for (op <- Seq(0x40, 0x41, 0x50, 0x60, 0x7F)) {
      val instr = encR(op, 0, f7(VX), 0, 1, 2)
      dut.io.instr.poke((instr.toLong & 0xFFFFFFFFL).U)
      dut.clock.step(0)
      assert(dut.io.illegal.peek().litToBoolean,
        s"opcode 0x${op.toHexString} must be illegal, not alias")
    }
  }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `tool/test-specific-spec.sh isa.InstrDecoderSpec`
Expected: FAIL on `0x40` (currently decodes legal as NOP).

- [ ] **Step 3: Fix the decode**

In `instrDecoder.scala`, delete `OP_FAMILY_BITS`/`opBitsTrunc` (lines ~81-82) and decode the full field:
```scala
val familyOpt = OpFamily.safe(opBits)   // opBits is the full 7-bit field
val familyOK  = familyOpt._2
val family    = familyOpt._1
```
Confirm at elaboration that `OpFamily` is 7-bit (values declared `.U(7.W)`); if it is instead 6-bit, replace `safe` with an explicit `Seq(...).map(o => opBits === o.U).reduce(_||_)` membership test.

- [ ] **Step 4: Run to verify pass**

Run: `tool/test-specific-spec.sh isa.InstrDecoderSpec`
Expected: PASS (all existing cases still pass).

- [ ] **Step 5: Commit**

```bash
git add src/main/scala/isa/instrDecoder.scala src/test/scala/isa/InstrDecoderSpec.scala
git commit -m "fix(isa): decode full 7-bit opcode; unaliased reserved opcodes"
```

---

### Task 2: Make CVT decode total (no silent `vadd`)

**Files:**
- Modify: `src/main/scala/isa/instrDecoder.scala:146-168`
- Test: `src/test/scala/isa/InstrDecoderSpec.scala`

**Interfaces:**
- Produces: CVT `vecOp` for the 12 valid `(dst,src)` pairs; `f3Valid := false` for reserved dst `6/7` or any unmatched pair.

- [ ] **Step 1: Write the failing test**

```scala
"InstrDecoder" should "flag invalid CVT combinations as illegal" in {
  simulate(new InstrDecoder) { dut =>
    for (dst <- Seq(6, 7)) // reserved dst format codes
      check(dut, encR(0x14, dst, f7Cvt(srcFmt = S32), 0, 1, 0),
        OpFamily.VALU_CVT, VecOp.vadd, expectIllegal = true)
    for ((dst, src) <- Seq((S16, S8), (F32, S16), (S8, S16))) // uncorrelated
      check(dut, encR(0x14, dst, f7Cvt(srcFmt = src), 0, 1, 0),
        OpFamily.VALU_CVT, VecOp.vadd, expectIllegal = true)
  }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `tool/test-specific-spec.sh isa.InstrDecoderSpec`
Expected: FAIL (currently legal, `op=vadd`).

- [ ] **Step 3: Implement validity**

Replace the single `when (dst === src)` guard with a valid-pair set:
```scala
val cvtValid = Seq(
  (FmtCode.S8, FmtCode.S32), (FmtCode.S32, FmtCode.S8),
  (FmtCode.S32, FmtCode.F32), (FmtCode.F32, FmtCode.S32),
  (FmtCode.F32, FmtCode.S8), (FmtCode.S8, FmtCode.F32),
  (FmtCode.F32, FmtCode.BF16), (FmtCode.BF16, FmtCode.F32),
  (FmtCode.F32, FmtCode.BF8), (FmtCode.BF8, FmtCode.F32),
  (FmtCode.S16, FmtCode.S32), (FmtCode.S32, FmtCode.S16)
).map { case (d, s) => (f3 === d && f7CvtSrc === s) }.reduce(_ || _)
when (!cvtValid) { f3Valid := false.B }
```
Keep the existing `MuxCase` for `vecOp` (its `vadd` default is suppressed by `illegal`).

- [ ] **Step 4: Run to verify pass**

Run: `tool/test-specific-spec.sh isa.InstrDecoderSpec`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/scala/isa/instrDecoder.scala src/test/scala/isa/InstrDecoderSpec.scala
git commit -m "fix(isa): CVT decode is total; invalid pairs illegal"
```

---

### Task 3: Assert `valu.op`/`dtype`; broaden decoder coverage; pin illegal outputs

**Files:**
- Modify: `src/test/scala/isa/InstrDecoderSpec.scala:23-51` and append tests

**Interfaces:**
- Produces: `check(...)` asserts `valu.op` (via `enumLit`) and a new optional `expDtype: Option[VecDType.Type]`; existing callers already pass a valid `expOp`, so this is drop-in.

- [ ] **Step 1: Enable the assertions in `check`**

Inside the `else` branch after the field checks, add:
```scala
val gotOp = enumLit(dut, dut.io.decoded.valu.op)
assert(gotOp == expOp.litValue,
  s"op mismatch for 0x${instr.toHexString}: got $gotOp want ${expOp.litValue}")
expDtype.foreach { d =>
  val gotD = enumLit(dut, dut.io.decoded.valu.dtype)
  assert(gotD == d.litValue, s"dtype mismatch for 0x${instr.toHexString}")
}
```
Add `expDtype: Option[VecDType.Type] = None` to the parameter list. No existing call site changes (non-VALU families already pass `VecOp.vadd`, the decoder default).

- [ ] **Step 2: Run — expect existing suite green**

Run: `tool/test-specific-spec.sh isa.InstrDecoderSpec`
Expected: PASS. Any failure reveals an existing op-mapping bug — fix the decoder, not the assertion.

- [ ] **Step 3: Add the missing cases**

Append tests covering, with exact expected values:
- REDUCE `vrmin/vrand/vror/vrxor` → `VecOp.vrmin/vrand/vror/vrxor`.
- FP_FMA `fms/nfma/nfms` (S-format, `rs3`, `round=RTZ`) → matching `VecOp`.
- `mma_keep`: `encR(0x03,0,f7(VR,sat=true),...)` (or S-form with sat) → `decoded.mma_keep.expect(true.B)`.
- `width=3` illegal for REDUCE, LUT, BCAST, MOV, MMA, FP.
- `dtype=3` illegal for LOGIC, REDUCE, BCAST, FP, MOV.
- `dtype` pin: `vadd(..., width=VX)` → `VecDType.S8C4`; `vfadd` → `VecDType.FP32C1`; `vcvt_f32_bf8` → `VecDType.BF8E4M3`.
- Illegal determinism: for a reserved word, assert `illegal` true and that `valu.op`, `valu.regCls`, `rd` read back as stable values across two `step(0)`s.

- [ ] **Step 4: Run to verify pass**

Run: `tool/test-specific-spec.sh isa.InstrDecoderSpec`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/test/scala/isa/InstrDecoderSpec.scala
git commit -m "test(isa): assert decoded op/dtype; broaden decoder coverage"
```

---

### Task 4: Single-pass illegal computation

**Files:**
- Modify: `src/main/scala/isa/instrDecoder.scala:278-298`

**Interfaces:**
- Produces: unchanged `io.illegal`; internal `f3OK`/`widthOK`/`dtypeOK` Bools computed once.

- [ ] **Step 1: Add a guard test first (behavior lock)**

Add to `InstrDecoderSpec` a case asserting every currently-illegal pattern still returns `illegal` (reuse the existing loops) — i.e. the suite as-is is the oracle. Run: `tool/test-specific-spec.sh isa.InstrDecoderSpec` → PASS before editing.

- [ ] **Step 2: Refactor**

Compute `f3OK` per family in the existing `switch` (set `true.B` in the matched branches; NOP is `true.B` for all `funct3`, matching today), define `widthOK`/`dtypeOK` from the raw `f7Width`/`f7Dtype`, and end with one expression:
```scala
val illegal = !(familyOK && f3OK && widthOK && dtypeOK)
io.illegal := illegal
```
Remove all `f3Valid` mutation-after-read and the repeated `when(!f3Valid)` ORs. Keep CVT/LUT validity inside `f3OK`.

- [ ] **Step 3: Run**

Run: `tool/test-specific-spec.sh isa.InstrDecoderSpec`
Expected: PASS (no behavior change).

- [ ] **Step 4: Commit**

```bash
git add src/main/scala/isa/instrDecoder.scala
git commit -m "refactor(isa): compute decoder illegal in a single pass"
```

---

### Task 5: Introduce the authoritative `InstrTable`

**Files:**
- Create: `src/main/scala/isa/InstrTable.scala`
- Test: `src/test/scala/isa/InstrTableSpec.scala`

**Interfaces:**
- Produces:
  - `sealed trait Fmt` with `R`, `I`, `S`, `NoFmt`
  - `sealed trait Attr` with `AttrStd`, `AttrCvt`, `AttrMem`, `AttrNone`
  - `case class InstrDef(opcode: Int, funct3: Int, mnemonic: String, fmt: Fmt, op: VecOp.Type, attr: Attr = AttrStd)`
  - `case class CvtPair(dst: Int, src: Int, op: VecOp.Type)`
  - `val InstrTable.defs: Seq[InstrDef]` (every `(family,funct3)` incl. NOP)
  - `val InstrTable.cvtPairs: Seq[CvtPair]` (the 12 valid pairs)
  - `val InstrTable.byOpcode: Map[Int, Seq[InstrDef]]`
  - `val InstrTable.byMnemonic: Map[String, InstrDef]` (unique mnemonics; bank-suffixed: `"vlut.A"`, `"vlut.B"`, `"vsetlut.A"`, `"vsetlut.B"`)

- [ ] **Step 1: Write the failing test (table invariants + exhaustive illegal)**

```scala
"InstrTable" should "be internally consistent" in {
  assert(InstrTable.byMnemonic.size == InstrTable.defs.size)
  for (d <- InstrTable.defs) assert(d.opcode <= 0x7F && d.funct3 >= 0 && d.funct3 <= 7)
  assert(InstrTable.cvtPairs.map(p => (p.dst, p.src)).distinct.size == InstrTable.cvtPairs.size)
}
```
Add an exhaustive decode test: for each `d <- InstrTable.defs` (and each `cvtPairs`), assemble via the generic encoder and assert `!illegal`, `family==d.opcode`, `valu.op==d.op`; for every `(opcode, f3)` combination not in the table, assert `illegal` (sweep `opcode` 0..0x7F × `f3` 0..7 using a decoder model). This sweep also pins Review Focus #1.

- [ ] **Step 2: Run to verify it fails**

Run: `tool/test-specific-spec.sh isa.InstrTableSpec`
Expected: FAIL (`InstrTable` not defined).

- [ ] **Step 3: Implement `InstrTable.scala`**

Populate `defs` verbatim from `instSetArch.scala` (all families, all funct3, matching `op` from the decoder's current switch). Wrap construction with `require(byMnemonic.size == defs.size, "duplicate mnemonic")` and `require(cvtPairs.distinct.size == cvtPairs.size)`.

- [ ] **Step 4: Run to verify pass**

Run: `tool/test-specific-spec.sh isa.InstrTableSpec`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/scala/isa/InstrTable.scala src/test/scala/isa/InstrTableSpec.scala
git commit -m "feat(isa): authoritative InstrTable + exhaustive decode test"
```

---

### Task 6: Fold `InstrDecoder` onto `InstrTable`

**Files:**
- Modify: `src/main/scala/isa/instrDecoder.scala:87-203` (the `switch(family){switch(f3)}` block)

**Interfaces:**
- Consumes: `InstrTable.byOpcode`, `InstrDef`, `CvtPair`.
- Produces: identical `DecodedMicroOp`; `vecOp`/`f3OK` now derived from the table.

- [ ] **Step 1: Lock behavior**

Run: `tool/test-specific-spec.sh isa.InstrDecoderSpec` → PASS (oracle).

- [ ] **Step 2: Replace the switches**

Build the op map from `InstrTable.byOpcode` (skip opcode `0x14`; use `InstrTable.cvtPairs` for CVT), setting `vecOp` and `f3OK` from table membership:
```scala
val vecOp = WireDefault(VecOp.vadd)
val f3OK  = WireDefault(false.B)
for ((opcode, defs) <- InstrTable.byOpcode if opcode != 0x14) {
  when (opBits === opcode.U) {
    for (d <- defs) when (f3 === d.funct3.U) { vecOp := d.op; f3OK := true.B }
  }
}
when (opBits === 0x14.U) {
  for (p <- InstrTable.cvtPairs)
    when (f3 === p.dst.U && f7CvtSrc === p.src.U) { vecOp := p.op; f3OK := true.B }
}
// NOP: funct3 don't-care (preserves current behavior)
when (opBits === 0x00.U) { f3OK := true.B }
```
Fold the LD/ST/CVT/attributes invalid handling into the Task 4 single-pass `illegal`. The MMA control block (`:263-276`) can also be driven from `byOpcode(0x03)`.

- [ ] **Step 3: Run both specs**

Run: `tool/test-specific-spec.sh isa.InstrDecoderSpec` then `tool/test-specific-spec.sh isa.InstrTableSpec`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add src/main/scala/isa/instrDecoder.scala
git commit -m "refactor(isa): drive InstrDecoder from InstrTable"
```

---

### Task 7: Fold `NpuAssembler` onto `InstrTable`

**Files:**
- Modify: `src/main/scala/isa/NpuAssembler.scala` (helper bodies only; keep `encR`/`encI`/`encS`/`f7`/`f7Cvt` and every public signature)
- Test: `src/test/scala/isa/InstrTableSpec.scala` (round-trip within Task 5's sweep already covers this)

**Interfaces:**
- Consumes: `InstrTable.byMnemonic`.
- Produces: same helper signatures; opcode/funct3 no longer hard-coded.

- [ ] **Step 1: Refactor one family as the pattern**

Rewrite e.g. `vadd` to:
```scala
def vadd(rd: Int, rs1: Int, rs2: Int, width: Int = VX, sat: Boolean = false): Int = {
  val d = InstrTable.byMnemonic("vadd")
  encR(d.opcode, d.funct3, f7(width, sat = sat), rd, rs1, rs2)
}
```

- [ ] **Step 2: Apply to every helper** (arith, logic, reduce, lut incl. bank-suffixed mnemonics, cvt via `byMnemonic`, bcast, fp, fma, mov, mma, mem). Keep signatures and defaults identical.

- [ ] **Step 3: Run**

Run: `tool/test-specific-spec.sh isa.InstrTableSpec` then `tool/test-specific-spec.sh isa.InstrDecoderSpec` then `make test`
Expected: PASS (engine specs depend on the assembler; the full suite is the real check).

- [ ] **Step 4: Commit**

```bash
git add src/main/scala/isa/NpuAssembler.scala
git commit -m "refactor(isa): NpuAssembler encodes from InstrTable"
```

---

### Task 8: Disassembler + round-trip

**Files:**
- Create: `src/main/scala/isa/NpuDisassembler.scala`
- Test: `src/test/scala/isa/NpuDisassemblerSpec.scala`

**Interfaces:**
- Consumes: `InstrTable.byOpcode`, `byMnemonic`, `InstrDef`.
- Produces: `object NpuDisassembler { def apply(word: Int): String }` — `"<mnem> rd(<n>), rs1(<n>), rs2(<n>)"` for the `<mnemonic>` when the table matches and the word is legal, else `"illegal"`.

- [ ] **Step 1: Write the failing test**

```scala
"NpuDisassembler" should "round-trip every table entry" in {
  for (d <- InstrTable.defs) {
    val w = /* assemble via NpuAssembler generic path for d */
    assert(NpuDisassembler(w).startsWith(d.mnemonic))
  }
  assert(NpuDisassembler(0x7F) == "illegal")
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `tool/test-specific-spec.sh isa.NpuDisassemblerSpec`
Expected: FAIL (not defined).

- [ ] **Step 3: Implement `NpuDisassembler`** (decode fields from the word, match `byOpcode`, format; return `"illegal"` on no match).

- [ ] **Step 4: Run to verify pass**

Run: `tool/test-specific-spec.sh isa.NpuDisassemblerSpec`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/scala/isa/NpuDisassembler.scala src/test/scala/isa/NpuDisassemblerSpec.scala
git commit -m "feat(isa): table-driven disassembler"
```

---

### Task 9: Reconcile docs and AGENTS notes

**Files:**
- Modify: `docs/designs/01.isa.md:150,157,169-171`
- Modify: `AGENTS.md` (opcode-truncation gotcha)
- Modify: `docs/implementations/NeuralCore.md:50-56` (illegal list)

**Interfaces:** none (documentation only).

- [ ] **Step 1: Fix stale ISA facts** — LD=0x07, ST=0x27; LD/ST `funct3` = 0/1/2 (VX/VE/VR); `mma.reset` (funct3=2) is decoded legal with `mma_reset=1` and rejected by the *engine*, not the decoder; MOV is implemented and tested (remove "unverified").
- [ ] **Step 2:** Update the AGENTS gotcha to state the decoder decodes the full 7-bit opcode and the enum holds `0x00..0x27`.
- [ ] **Step 3: Run the full suite**

Run: `make test`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add docs/designs/01.isa.md docs/implementations/NeuralCore.md AGENTS.md
git commit -m "docs(isa): reconcile decoder facts with implementation"
```

---

## Self-Review

- **Spec coverage:** P0 #1 (Task 1), P0 #2 (Task 2), P1 coverage+illegal (Tasks 3–4), table single-source (Tasks 5–7), RISC-V-style disassembler/round-trip (Task 8), doc drift (Task 9). The "production VALU path" decision was explicitly excluded by the chosen scope.
- **Type consistency:** `InstrDef`/`CvtPair`/`byMnemonic` introduced in Task 5 and consumed with the same names in Tasks 6–8; `f3OK`/`familyOK` used consistently from Task 4 onward.
- **Review Focus:** each of the five lines has an owning test (Tasks 1, 2, 3, 3, 3).
- **Open item for execution:** whether to newly tighten NOP to `funct3==0`. The plan preserves current behavior (don't-care); say so if you want it strict.
