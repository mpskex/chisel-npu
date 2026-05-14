################################################################################
# build_v8_npu_stub.tcl — Step V8: Add Chisel Top stub to BD (same BD as V7)
#
# Delta from V7 (npu_dma_master → MIG C1):
#   - Add mmalu_stub.v as RTL source (replaces full Chisel top.sv)
#   - Instantiate the Chisel Top module stub directly as a BD module cell
#     (connected to dma_master's MMALU IO ports inside npu_dma_master)
#
# Actually: npu_dma_master.v internally drives/reads the MMALU ports
# (io_in_a_*, io_in_b_*, io_out_*, io_clct, etc.).  In the current test we
# keep npu_dma_master as a standalone BD cell.  We want to verify that ADDING
# mmalu_stub.v (the Chisel Top module) to the design does not break synthesis
# or PCIe training — even if it's not yet connected.
#
# BD changes from V7: NONE (same topology).
# RTL changes: add mmalu_stub.v as a source file (synthesised but unconnected).
#
# Hypothesis: Including the Chisel module stub in the netlist does not affect
#             PCIe cold-boot training.
#
# Success criterion: Same as V7 — 9/9 smoke tests pass.
################################################################################

set SCRIPT_DIR [file normalize [file dirname [info script]]]
set MIGRATE    [file normalize $SCRIPT_DIR/..]
set RTL_SRC    [file normalize [file join $MIGRATE src]]
set REPO_ROOT  [file normalize $SCRIPT_DIR/../../../..]
set BIT_DST    [file join $MIGRATE top_v8_npu_stub.bit]

source [file join $SCRIPT_DIR migrate_lib.tcl]
source [file join $SCRIPT_DIR _apply_v2.tcl]
source [file join $SCRIPT_DIR _apply_v5.tcl]
source [file join $SCRIPT_DIR _apply_v6.tcl]
source [file join $SCRIPT_DIR _apply_v7.tcl]

open_ref_project
assert_synth_done

# ── Add RTL sources ───────────────────────────────────────────────────────────
# npu_dma_master.v is needed for apply_v7_dma_master
set dma_v [file join $REPO_ROOT ip/vivado/xc7k480t/src npu_dma_master.v]
if {[file exists $dma_v]} { add_files -norecurse $dma_v }

# ctrl_lite is needed for apply_v2_bypass_ctrl
set ctrl_v [file join $RTL_SRC npu_ctrl_lite.v]
if {[file exists $ctrl_v]} { add_files -norecurse $ctrl_v }

# mmalu_stub.v — the Chisel Top module stub (no logic, just port declarations)
set stub [file join $RTL_SRC mmalu_stub.v]
if {[file exists $stub]} {
    add_files -norecurse $stub
    puts "INFO: added mmalu_stub.v (Chisel Top stub)"
}
update_compile_order -fileset sources_1

open_bd_design [get_files {*/top.bd}]

# ── Apply V1–V7 deltas (identical to V7 build) ───────────────────────────────
apply_v1_deletions
apply_v2_bypass_ctrl $RTL_SRC
apply_v3_mmcm_and_rst
apply_v4_byp_cdc
apply_v5_xdma_cc
apply_v6r_remove_smc
apply_v7_dma_master

# ── V8: No additional BD changes ─────────────────────────────────────────────
# mmalu_stub.v is added as an RTL source and will be synthesised.
# It is NOT instantiated in the BD — Vivado will synthesize it as a black-box
# stub.  The key test: does adding this module to the design break PCIe?
puts "INFO: V8 — mmalu_stub.v added to design (no BD changes from V7)."

save_bd

puts "=== In-session synthesis ==="
run_synthesis

run_impl_and_write_bit "v8_npu_stub" $BIT_DST
