# L2 — count-based scoreboard (close K=32/W=16 timing at 200 MHz)

**Evidence:** the K=32/W=16 bitstream routed but failed timing: fabric WNS −5.463 ns,
112,934/380,839 failing endpoints; critical path `slots_12_priorSnap_reg[59]` →
`slotsReadyR_12_reg`, data delay 10.12 ns of which **9.03 ns is routing** (8 LUT levels,
1.09 ns logic). The T-bit `priorSnap` (128 b/slot) crossed with the L×T tag masks
(`tagWrMask/tagRdMask`) is too large/route-heavy.

**Goal:** replace the T-bit tag masks + per-slot `priorSnap` with small **per-register
counters** and per-slot **target + satisfied latch**, so the readiness path is a few small
comparisons instead of a 128-bit AND-reduce over a die-spanning mask. ISA / C semantics /
§13 unchanged. Preserve reduce-mode body (97%).

## Design
Per VX reg `i` (L): monotonic counters `wrIss(i)`, `wrDone(i)`, `rdIss(i)`, `rdDone(i)` (WC=9 bits).
At window entry: a reader records, for each read reg, `target = wrIss(reg)`; a writer records,
for each write reg, `target = rdIss(reg)`; increment `wrIss` for written regs and `rdIss` for
read regs (respecting the cap-store exclusion). Program order is preserved because entry is
in-order, so all counters captured at entry correspond to preceding ops.

Readiness (per slot): reader is ready when every read reg's `wrDone(reg)` has reached its target;
writer ready when every write reg's `rdDone(reg)` has reached its target. Implement with a
**per-target satisfied latch** `sat := sat || reached(done, tgt)`, where `reached` is
`(done - tgt) ∈ {0,1,2}` (counters increment ≤2/cycle and only the first crossing matters; the
latch makes it monotonic). At entry, `sat` initialises to `reached` (target already met).

Completion: increment `wrDone` for the written regs at MMA `clctD` (delayed completion stage)
and at DMA vle done; increment `rdDone` for the read regs at MMA feed and at DMA vse (RF-source)
done.

Keep the tag ring for `tagValid`/`tagFed` (mma.last ordering / occupancy) and the vd FIFO;
delete `tagWrMask`, `tagRdMask`, `priorSnap`, and their net updates. `wrCap`/`lastWrTag` for
`useCapData` stay.

## Constraints
- K=32 W=16 primary; `top/top.scala` untouched.
- ISA and `mma vd,vs1,vs2,C` unchanged; §13 determinism preserved.
- `tool/test-specific-spec.sh <Spec>`; Docker.
- Do NOT commit `target/`, `test_run_dir/`, `top.sv`.

## Tasks
- **L2a:** add the counters + per-slot targets/sat latches; route readiness through them; delete
  the masks/priorSnap. Acceptance: reduce-mode body ≥ 0.9, all determinism specs green, default
  mode ≥ 5%.
- **L2b:** rebuild `top.sv` and the vu9p bitstream; report WNS/TNS. Target fabric WNS ≥ 0 at 200 MHz.
