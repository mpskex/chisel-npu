# Reduce-only mode + VR→vse dataflow — implementation plan

## Context
The engine has two capture-commit policies under `reduce_mode` (ctrl 0x48):
- default: per-feed capture (every non-mma.last feed pushes its running-sum
  column; the chained vse stores each) — pays the 128 B/feed store roofline;
- reduce (store-sparse): the K feeds accumulate, only the round's `mma.last`
  pushes the accumulated column, one trailing vse stores it.

We standardize on **reduce mode only** and simplify the dataflow to the
intended model: the MMA writes the output column into the **vector register**
`VR[vd]`; a `vse` reads `VR[vd]` directly and writes it to memory. The
`capQ`/`useCapData` capture buffer is removed.

## Target semantics (authoritative)
1. `mma`/`mma.last` are dispatched to the MMALU strictly **in order**; each
   is one skewed vector-input pulse. After the array latency (~3K−2) the
   output column is written into `VR[vd]`. A `vse` then stores `VR[vd]` to
   external memory (no capture queue in between).
2. Each matrix multiplication is isolated by `mma.last`; `K×mma + mma.last`
   chunks may be **back-to-back** (pipelined).
3. The decode/issue dispatches only `mma`/`mma.last` to the MMALU, in order.

## Global Constraints (binding)
- **No ISA change.** No new ctrl-bundle field; `keep`/`use_accum`/`busy`
  suffice. `mma.last` keeps its encoding.
- `mma.last` must not reset the PE before its own capture is snapshotted; the
  next round's `keep=0` (first feed) resets per-PE in the wavefront.
- `vse` must read `VR[vd]` **after** the capture writes it: the scoreboard
  (`wrIss`/`wrDone`) must order the store's RF read after the VR write.
- Preserve the achieved reduce-mode performance: `CrossMatmulPipelineSpec`
  K=32 R=64 drain-inclusive IPC ≥ 90% (was 92.1%); `ReduceModeSpec` body
  ≥ 95.5%; correct per-round captures; `maxBnd==0`.
- Remove the `reduce_mode` switch entirely: the reduce policy is the only
  policy. Remove the ctrl 0x48 path and the per-feed default branch/dead code.
- Do NOT rebuild the FPGA bitstream in the sim tasks (rebuild is a later step).

## Task 1: RTL — reduce-only + remove capQ (vse reads VR[vd])

Files: `src/main/scala/engine/npuProgramEngine.scala`,
`src/main/scala/engine/npuFrontend.scala`,
`src/main/scala/dma/npuDmaEngine.scala` (if `req_store_data` becomes unused).

Requirements:
1. Capture commit: `capturePush = compValid && compLast` (only the round's
   `mma.last`); delete the `reduce_mode` mux and the per-feed default branch.
2. The capture writes the accumulated column into `VR[capVd]` (keep the
   existing `outBuf`/`capVd`/`clctPrev` RF write). Remove `capQ`, `capQHead`,
   `capQTail`, `capQCount`, `capPush`/`capPop`, `vsePopCap`/`vsePopData`, the
   `useCapData`/`wrCap` detection, and the DMA `storeDataValid`/`req_store_data`
   path. The DMA store reads the RF `rf_r_vr_data(1)` (VR[vd]) directly.
3. Ordering: the `vse` store's read of `VR[vd]` must wait for the capture's
   RF write. Wire the scoreboard so the store's `rdTgt`/`wrDone` (and the
   RF write at `clctPrev`) guarantee the read-after-write. Add an assertion
   (or a test) that a vse immediately after `mma.last` reads the correct
   column.
4. Remove `reduce_mode` from the engine io and the frontend (ctrl 0x48
   register, decode, readback) — the reduce policy is unconditional. Remove
   dead default-mode logic (`feedReset` reduce branch becomes unconditional,
   etc.).
5. `boundaryCnt` may remain 0 (session drain already uses fifo/tag counts).

Verification: `tool/test-specific-spec.sh engine.CrossMatmulPipelineSpec`
(K=32 ≥90%, distinct per-round captures, maxBnd==0), `engine.ReduceModeSpec`
(body ≥95.5%), and the engine specs after Task 2's test conversions.

## Task 2: Convert remaining default-mode tests + Python/HW to reduce-only

Files: `src/test/scala/engine/StreamedChainedSpec.scala`,
`src/test/scala/engine/ChainedCaptureSpec.scala`,
`src/test/scala/engine/ReduceModeSpec.scala`, and the Python
`drivers/chisel_npu_py/tests/test_program_engine.py`
(`test_one_shot_session`, `test_session_captures_columns`) + `npu.py` /
`config.py` if they reference reduce_mode.

Requirements:
1. Rewrite the default-mode programs as reduce-mode **matmul** programs:
   `K` `mma` feeds (accumulate) + `mma.last` (capture) + one `vse` (store the
   column), per round; back-to-back rounds give distinct per-round columns.
2. Remove the `reduce_mode` config/staging from the Python driver (it is now
   unconditional); the HW tests assert the matmul columns.
3. Keep the cross-session regression (`ChainedCaptureSpec`): one session then
   another must each capture correctly (VR[vd] path, no stale accumulators).
4. Verify all engine specs + the Python unit tests (mock) pass.

## Verification (run)
- `tool/test-specific-spec.sh engine.CrossMatmulPipelineSpec` (K=32 ≥90%)
- `tool/test-specific-spec.sh engine.ReduceModeSpec` (body ≥95.5%)
- `tool/test-specific-spec.sh engine.StreamedChainedSpec engine.ChainedCaptureSpec engine.NpuProgramEngineFrontendSpec engine.CaptureDeterminismSpec engine.StreamedNSessionSpec engine.BodyPipelineSpec`
- `PYTHONPATH=drivers/chisel_npu_py/src python3 -m pytest drivers/chisel_npu_py/tests -m "not hw" -q` (host has Python; else report skipped)

## After the sim tasks
Scoped re-review → FPGA rebuild (200 MHz, DMA-fixed + boundaryCnt + reduce-only
+ VR→vse) → flash → HW run (7 tests, repeatable).
