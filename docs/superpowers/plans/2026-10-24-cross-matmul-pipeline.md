# Cross-matmul pipelining (K=32, 200 MHz) — implementation plan

## Context
The engine serializes consecutive matmul rounds behind a 6K-1 `boundaryCnt`
countdown after each `mma.last` (drain ~3K-1 + idle `keep=0` reset propagate
~2K-1). Body IPC is 95.5% inside a round, but the per-round tail dwarfs the
K-feed body, so overall feeds/total is ~15%. Goal: pipeline rounds so the
overall IPC (feeds/total, including head/tail) is >90%, with correct
per-round captures.

## Global Constraints (binding)
- **No ISA change.** No new field in `NCoreMMALUCtrlBundle`; it already has
  `{keep, use_accum, busy}`. `keep` is the PE accumulate/reset control
  (`keep ? res+a*b : a*b`) — the first feed of each matmul round uses `keep=0`.
- `busy` is the feed window. `DataCollector.dat_clct` must be `OR(busy)`, NOT
  `OR(keep)`.
- Preserve single-round behaviour: `ReduceModeSpec` body = 95.5%; all engine
  specs (`StreamedChainedSpec`, `ChainedCaptureSpec`, `NpuProgramEngineFrontendSpec`,
  `CaptureDeterminismSpec`, `CaptureTraceSpec`, `LateStoreCaptureSpec`,
  `StreamedNSessionSpec`, `BodyPipelineSpec`) pass.
- Do NOT rebuild the FPGA bitstream in this task (simulation only).
- `mma.last` zero-forces operands and must NOT reset the PE before its own
  capture is taken.

## Task 1: pipelined cross-matmul control + collector (sim)

Files: `src/main/scala/alu/mma/cu/controlUnit.scala`,
`src/main/scala/alu/mma/sa/dataCollector.scala`,
`src/main/scala/engine/npuProgramEngine.scala`.

Requirements:
1. `ControlUnit`: drive `dat_clct` from `OR(busy)` over the 2n-1 control
   pipeline (not `OR(keep)`).
2. Engine: track `roundOpen` (false at session start and after an `mma.last`
   feed; true after any non-last MMA feed). Combustionally
   `feedFirst = feedValid && !feedMmaLast && !roundOpen`
   and drive `mmalu.io.ctrl.keep := !feedFirst`. Remove the inter-round
   boundary gate from `gateOk`/`issueSel` and stop arming `boundaryCnt` at
   `mma.last`/session start; keep the session drain working via the existing
   fifo/tag/capQ counts (`boundaryCnt` stays 0).
3. `DataCollector`: make the collection `cnt`/capture **per round** so
   back-to-back rounds each capture their own running sum (the next round's
   `keep=0` reset must land after the previous round's snapshot). Use only the
   existing `busy`/`keep`/`use_accum` inputs.
4. Make `engine.CrossMatmulPipelineSpec` GREEN: for K in {16,32} and rounds in
   {4,8}, overall `100*feeds/total >= 90` and every round's capture is the
   K-feed running-sum column. Remove the `ignore` on that spec.
5. Keep `ph.tail` small (the shrinking/removal of the 6K-1 stall).

## Verification (run)
- `tool/test-specific-spec.sh engine.CrossMatmulPipelineSpec` (must pass, >=90%)
- `tool/test-specific-spec.sh engine.ReduceModeSpec` (body 95.5%)
- the engine specs listed above
