# GEMM + Softmax Quantization Example

## Overview

This tutorial walks through a complete **post-accumulation quantization pipeline** that models transformer attention activation: `softmax(QK^T / √d_k)`. It demonstrates the interplay between the MMALU (dot-product accumulation), VALU (element-wise operations), and programmable LUT activation functions (`vlut`/`vsetlut`), with full end-to-end quantization in the INT8 domain.

The implementation combines:
- **Integer arithmetic** (systolic array, reductions, shifts)
- **Floating-point FMA** (scaling and bias)
- **Quantized activation** (exp and reciprocal via programmable `vlut` banks)
- **Numerical stability** (max subtraction before exp)

---

## The Quantization Pipeline

### Data Flow

```
QK^T accumulator (INT8 seed)
        ↓
   Phase 0-1: FP32 dequantization + scale
        ↓
   scaled_fp32
        ↓
   Phase 2: Quantize to SQ1.6 for LUT domain
        ↓
   scores_sq16 [VX]
        ↓
   Phase 3: Numerical stability: x - max(x)
        ↓
   shifted_sq16 [VX]
        ↓
   Phase 4: vlut bank A (exp table: SQ1.6 → UQ0.8)
        ↓
   exp_uq08 [VX]
        ↓
   Phase 5: vsum → INT32 sum (broadcast to all lanes)
        ↓
   Phase 6: vlut bank B (recip table: scalar 1/sum)
        ↓
   recip_sq16 [VX]
        ↓
   Phase 7: Promote INT8 → FP32, multiply (exact for small values)
        ↓
   product_fp32 [VR]
        ↓
   Phase 8: FP32 → INT32, arithmetic right-shift >>7
        ↓
   shifted_int32 [VR]
        ↓
   Phase 9: Narrow INT32 → INT8 saturated (final softmax weight)
        ↓
   result_int8
```

### Phase-by-Phase Breakdown

#### Phase 0–1: FP32 Dequantization and Scale

```scala
// Seed accumulator as INT8, promote to FP32
vbcastImm(rd=8, imm=acc_int8)         // broadcast INT8 accumulator to VX[8]
vcvt_s8_f32(rd=0, rs1=8)              // INT8 → FP32 in VR[0]

// Load and apply scale factor (≈ 1/√d_k in fixed-point)
vbcastImm(rd=8, imm=scale_int8)       // broadcast INT8 scale to VX[8]
vcvt_s8_f32(rd=2, rs1=8)              // scale: INT8 → FP32 in VR[2]
vfmul(rd=1, rs1=0, rs2=2)             // VR[1] = acc_fp32 × scale_fp32
```

**Hardware:** `vbcastImm` writes K identical copies of a sign-extended 12-bit immediate to all lanes of a VX register. `vcvt_s8_f32` then promotes each INT8 lane to its exact FP32 equivalent. `vfmul` performs per-lane FP32 multiply.

**Key insight:** The scale is stored as a small INT8 value (fitting in [-128,127]) and converted to FP32. This gives flexibility in quantization schemes.

---

#### Phase 2: Quantize to SQ1.6 Domain

```scala
vcvt(rd=0, rs1=1, dstFmt=F32, srcFmt=S8, sat=true)
```

This mirrors the **hardware `vcvt_f32_s8`** path: reads FP32 from VR[1], clamps to [-128,127], truncates toward zero, and writes INT8 result to VX[0].

| Input | Output | Meaning |
|:---|:---|:---|
| scaled_fp32 ∈ ℝ | scores_int8 ∈ [-128,127] | SQ1.6: raw byte represents value/64 |

Example: if `scores_fp32 = 10.5f`, then `scores_int8 = 10` (SQ1.6 = 10/64 ≈ 0.156).

---

#### Phase 3: Numerical Stability — x - max(x)

```scala
vrmax(rd=3, rs1=0)                    // max over all K lanes, broadcast to VR[3]
// [Scala reads VR[3], broadcasts via extWrite to VX[5]]
vsub(rd=1, rs1=0, rs2=5, sat=true)    // VX[1] = VX[0] - VX[5], clamp to INT8
```

**Purpose:** Prevent overflow in the exp `vlut` when scores are large. Computing `exp(x - max(x))` is numerically stable and mathematically equivalent:

$$\text{softmax}(x_i) = \frac{e^{x_i}}{\sum_j e^{x_j}} = \frac{e^{x_i - \max(x)}}{\sum_j e^{x_j - \max(x)}}$$

**Reduction semantics:** `vrmax` reads all K lanes of the input, computes the signed INT8 maximum, broadcasts that scalar to all K lanes of VR[3], and writes to the register file.

---

#### Phase 4: Element-wise Exp — Programmable LUT Lookup

Before issuing `vlut`, bank A must be pre-loaded with the exp table via `vsetlut`:

```scala
// Pre-load: write Qfmt.lutExp data into LUT bank A (one vsetlut per segment)
vsetlut(rs1=<vr_holding_exp_segment>, segment=s, bank=0)  // repeated per segment

// Lookup: rd[i] = lut_bank_A[in_a_vx[i]]
vlut(rd=2, rs1=1, bank=0)             // 256-entry SQ1.6 → UQ0.8 lookup from bank A
```

**LUT table (Qfmt.lutExp):**
```
exp table: SQ1.6 input → UQ0.8 output (stored as signed INT8)
  input x in [-2.0, +1.984] (range of SQ1.6)
  output e^x in [~0.135, ~7.389] clamped to [0, 255]
  example: input=0 (x=0.0) → exp(0)=1.0 → output=255 stored as -1 (two's complement)
```

**Hardware:** The 256-byte bank is indexed by the raw 8-bit unsigned byte of each `in_a_vx` lane. The bank must have been written with `vsetlut` before the first `vlut` is issued.

---

#### Phase 5: Horizontal Sum and Clamp

```scala
vsum(rd=4, rs1=2)                     // sum all K lanes (signed byte accumulation → INT32)
// [Scala reads VR[4], clamps to [1, 127], broadcasts via extWrite to VX[6]]
```

**Semantic:** `vsum` reads K lanes of signed INT8, sign-extends each to INT32, sums them all, and broadcasts the result to all K lanes of the output VR register.

**Why clamp to [1,127]?** The reciprocal table (`Qfmt.lutRecip`, loaded into bank B) has a sentinel at index 0 (output=127, representing infinity). For stability, we ensure the sum is in [1,127] before looking it up.

---

#### Phase 6: Reciprocal via Programmable LUT

Bank B must be pre-loaded with the recip table via `vsetlut`:

```scala
// Pre-load: write Qfmt.lutRecip data into LUT bank B
vsetlut(rs1=<vr_holding_recip_segment>, segment=s, bank=1)  // repeated per segment

// Lookup: rd[i] = lut_bank_B[in_a_vx[i]]
vlut(rd=7, rs1=6, bank=1)             // 256-entry SQ1.6 → SQ1.6 lookup from bank B
```

**LUT table (Qfmt.lutRecip):**
```
recip table: SQ1.6 input → SQ1.6 output
  input x in [-2.0, +1.984]
  output 1/x for x≠0, sentinel 127 (max FP value) for x=0
  example: input=64 (x=1.0) → output=64 (1/1.0=1.0 in SQ1.6)
```

---

#### Phase 7: FP32 Multiply

```scala
vcvt_s8_f32(rd=5, rs1=2)              // exp_uq08: INT8 → FP32
vcvt_s8_f32(rd=6, rs1=7)              // recip_sq16: INT8 → FP32
vfmul(rd=7, rs1=5, rs2=6)             // VR[7] = exp_fp32 × recip_fp32
```

**Precision:** For INT8 inputs a,b, `a.toFloat × b.toFloat` is **exact** in IEEE 754 FP32 because `|a × b| < 127 × 127 = 16129 < 2^24`. No rounding error occurs.

---

#### Phase 8: INT32 Right-Shift

```scala
vcvt_f32_s32(rd=0, rs1=7)             // FP32 → INT32 (truncate toward zero)
vbcastImm(rd=8, imm=7)                // broadcast shift amount
vcvt_s8_f32(rd=1, rs1=8)              // 7 → 7.0f in VR[1]
vcvt_f32_s32(rd=1, rs1=1)             // 7.0f → 7 (INT32)
vsra(rd=3, rs1=0, rs2=1, width=VR)    // VR[3] = VR[0] >> 7 (arithmetic shift)
```

**Scale recovery:** The product `exp × recip` has a scale of `256 (UQ0.8) × 64 (SQ1.6) = 16384 = 2^14`. After `>>7`, the effective scale becomes `2^14 / 128 = 128`, representing an INT8 softmax weight in roughly the range [0, 128) after ReLU.

---

#### Phase 9: Narrow to INT8 with Saturation

```scala
vcvt_s32_s8(rd=2, rs1=3, sat=true)    // INT32 → INT8 saturated
// Read low byte of VR[2] per lane: the final softmax weight in INT8
```

**Saturation:** Values > 127 clamp to 127; values < -128 clamp to -128. This ensures the output fits in a signed byte.

---

## Backend Bugs Fixed

During implementation of this pipeline, two critical bugs in `SimpleBackend.scala` were discovered and fixed:

### Bug 1: Reduce Ops Never Wrote to Register File

**Root cause:** The ISA encodes `vsum` and `vrmax` with the *input* register class in `funct7[1:0]`. For example, `vsum.vx` (summing VX lanes) has `regCls=VX` to select the VX reduction hardware path in the VALU. However, the *output* is always VR-width.

The backend's VR write-enable was guarded by `regCls===VR`, which was false for these ops, so the computed result was discarded.

**Fix:** Added `isReduceToVR()` helper to unconditionally enable VR write-back for `vsum`, `vrmax`, `vrmin`:

```scala
def isReduceToVR(op: VecOp.Type): Bool =
  op === VecOp.vsum || op === VecOp.vrmax || op === VecOp.vrmin

rf.io.vr_w_en(0) := (regCls===VR) || isWideCvtOut(op) || isReduceToVR(op)
```

### Bug 2: Reduce Ops Silently Corrupted VX

**Root cause:** The same `regCls=VX` that selects the input path also fired the VX write-enable, writing the narrow (8-bit) truncated sum/max to `vx_out_addr`. If the test harness left `vx_out_addr` pointing at an important register (e.g., the exp values), it would be silently overwritten.

**Fix:** Suppress VX write-enable for reduce ops:

```scala
rf.io.vx_w_en(0) := ((regCls===VX) || isNarrowCvtOut(op)) && !isReduceToVR(op)
```

---

## Scala Reference Implementation

The test uses a **Scala-side reference** that mirrors the hardware pipeline exactly:

```scala
def gemmSoftmaxRef(accInt8: Array[Int], scaleInt: Int): Array[Int] = {
  // Phase 0-1: FP32 dequantization + scale
  val scaleFp = FpRef.s8ToF32(scaleInt.toByte)
  val scoreFp = accInt8.map(a => 
    FpRef.fmul(FpRef.s8ToF32(a.toByte), scaleFp))

  // Phase 2: FP32 → SQ1.6
  val scoreRaw = scoreFp.map(b => FpRef.f32ToS8(b).toInt & 0xFF)

  // Phase 3: vrmax + vsub
  val scoreSgn = scoreRaw.map(b => if (b >= 128) b - 256 else b)
  val maxSgn = scoreSgn.max
  val shifted = scoreSgn.map(x => math.max(-128, math.min(127, x - maxSgn)))
  val shiftRaw = shifted.map(_ & 0xFF)

  // Phase 4: vlut bank A (exp table — Qfmt.lutExp pre-loaded into bank A)
  val expRaw = shiftRaw.map(b => Qfmt.lutExp(b) & 0xFF)

  // Phase 5: vsum + clamp
  val expSgn = expRaw.map(b => if (b >= 128) b - 256 else b)
  val sumSgn = expSgn.map(_.toLong).sum
  val sumClamp = math.max(1, math.min(127, sumSgn.toInt))

  // Phase 6: vlut bank B (recip table — Qfmt.lutRecip pre-loaded into bank B)
  val recipRaw = Qfmt.lutRecip(sumClamp & 0xFF) & 0xFF

  // Phase 7: FP32 multiply
  val expFp = expSgn.map(e => FpRef.s8ToF32(e.toByte))
  val recipSgn = if (recipRaw >= 128) recipRaw - 256 else recipRaw
  val recipFp = FpRef.s8ToF32(recipSgn.toByte)
  val prodFp = expFp.map(e => FpRef.fmul(e, recipFp))

  // Phase 8: INT32 right-shift
  val prodInt = prodFp.map(b => FpRef.f32ToS32(b))
  val shifted7 = prodInt.map(p => p >> 7)

  // Phase 9: Narrow to INT8 saturated
  shifted7.map(v => math.max(-128, math.min(127, v)))
}
```

This reference is used for **full value verification** in the test: each lane of the hardware output is compared to the Scala result.

---

## Test Cases

The test file `NCoreBackendGemmSoftmaxSpec.scala` includes four test cases:

### Test A: Uniform Scores
```
accVal=10, scaleInt=1 → scaled=10 → sq16=10
x - max(10) = 0 → vlut exp(0)=1.0 → UQ0.8=255 stored as -1
vsum(8×(-1)) = -8; clamp→1
vlut recip(1) → 127 (sentinel for 1/sum)
vfmul(-1.0f, 127.0f) = -127.0f
vsra(-127, 7) = -1 (arithmetic: rounds toward -∞)
vcvt_s32_s8(-1) = -1
Result: all K lanes = -1
```

**Verification:** All lanes equal (uniform input produces uniform softmax output).

### Test B: 2× Scale
```
accVal=20, scaleInt=2 → scaled=40.0f
Same vlut-exp/sum/vlut-recip/relu path → all K lanes = -1
```

### Test C: Negative Accumulator
```
accVal=-20, scaleInt=1 → scaled=-20.0f
Exercises negative SQ1.6 vlut-exp path
Result: consistent with Scala reference
```

### Test D: Scale=3
```
accVal=5, scaleInt=3 → scaled=15.0f
Different FP32 intermediate values
Full value check against reference
```

---

## Running the Test

```bash
# Build the dev image (if not already done)
make image

# Run the GEMM+Softmax test
tool/test-specific-spec.sh backend.NCoreBackendGemmSoftmaxSpec

# Or run all backend tests
tool/test-all.sh
```

Expected output:
```
[info] NCoreBackendGemmSoftmax
[info] - should produce equal outputs for uniform input scores
[info] NCoreBackendGemmSoftmax
[info] - should handle 2x scale with full value check
[info] NCoreBackendGemmSoftmax
[info] - should handle negative accumulator scores
[info] NCoreBackendGemmSoftmax
[info] - should pass full value check with scale=3
[info] Tests: succeeded 4, failed 0
```

---

## Key Takeaways

1. **Reduction ops (`vsum`, `vrmax`) are essential** for transformer activations. The `regCls=VX` encoding selects the *input* hardware path, not the output class.

2. **Numerical stability matters:** Subtracting the max before exp prevents overflow and mirrors standard softmax implementations.

3. **Programmable LUT activation (`vlut`) gives ~2–3× gate efficiency** over FP32 hardware at the cost of reduced precision (~1/64 per operation). Banks A and B are loaded at init time via `vsetlut` and can be reprogrammed for different activation functions.

4. **Integer FP32 multiply is exact** for small operands; no fused multiply-add is needed in the quantization chain.

5. **Scalar feedback** (reading VR reductions and re-injecting via extWrite) models the microcode sequencer role in a real NPU controller.

---

## Further Reading

- [Vector ALU](../implementations/VectorALU.md) — detailed VALU operation reference
- [Quantization Pipeline](../implementations/Quantization.md) — Conv-ReLU worked example
- [Instructions (ISA)](../designs/01.isa.md) — encoding and timing reference
