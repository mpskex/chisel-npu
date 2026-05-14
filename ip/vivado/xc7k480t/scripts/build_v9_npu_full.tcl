################################################################################
# build_v9_npu_full.tcl — Step V9: Real Chisel top.sv with V7 BD topology
#
# Delta from V8 (mmalu_stub.v in V7 BD):
#   - Replace mmalu_stub.v with the real Chisel-generated top.sv (NCoreBackend)
#   - BD topology identical to V7 (npu_dma_master → MIG C1)
#   - npu_dma_master.v internally drives the MMALU ports that top.sv exposes
#
# The Chisel Top module is synthesised as part of the design netlist.
# If npu_dma_master.v instantiates Top internally, it will resolve here.
# If not, Top is compiled as an unused black-box (still synthesises cleanly).
#
# Hypothesis: Adding the full Chisel netlist (1.7 MB top.sv) does not break
#             PCIe cold-boot training or DDR3 functionality.
#
# Success criterion: 9/9 smoke tests pass (same as V7/V8).
################################################################################

set SCRIPT_DIR [file normalize [file dirname [info script]]]
set MIGRATE    [file normalize $SCRIPT_DIR/..]
set RTL_SRC    [file normalize [file join $MIGRATE src]]
set REPO_ROOT  [file normalize $SCRIPT_DIR/../../../..]
set BIT_DST    [file join $MIGRATE top_v9_npu_full.bit]

source [file join $SCRIPT_DIR migrate_lib.tcl]
source [file join $SCRIPT_DIR _apply_v2.tcl]
source [file join $SCRIPT_DIR _apply_v5.tcl]
source [file join $SCRIPT_DIR _apply_v6.tcl]
source [file join $SCRIPT_DIR _apply_v7.tcl]

open_ref_project
assert_synth_done

# ── Add RTL sources: npu_ctrl_lite.v, npu_dma_master.v, top.sv ───────────────
# For the bootstrapped project: BD already contains ctrl_lite + dma_master.
# For the external XPR: BD deltas are applied below.
set dma_v [file join $REPO_ROOT ip/vivado/xc7k480t/src npu_dma_master.v]
if {[file exists $dma_v]} { add_files -norecurse $dma_v }

set ctrl_v [file join $RTL_SRC npu_ctrl_lite.v]
if {[file exists $ctrl_v]} { add_files -norecurse $ctrl_v }

set top_sv [file join $REPO_ROOT top.sv]
if {![file exists $top_sv]} {
    puts "ERROR: top.sv not found. Run 'make build' first to generate Chisel output."
    exit 1
}
add_files -norecurse $top_sv
puts "INFO: added Chisel top.sv ([file size $top_sv] bytes)"
update_compile_order -fileset sources_1

# ── BD deltas: only apply if the BD doesn't already have V9 topology ─────────
# The bootstrapped project has recreate_bd.tcl baked in (V9 state).
# The external XPR needs V1-V7 applied.
open_bd_design [get_files {*/top.bd}]

# Check if the bootstrapped BD already has the V9 NPU topology baked in
set has_v9_topology [expr {[get_bd_cells -quiet /axi_clkconv_xdma] ne ""}]
puts "INFO: BD has V9 topology (axi_clkconv_xdma present): $has_v9_topology"
if {!$has_v9_topology} {
    # External XPR: apply BD deltas, synthesize with top.sv
    puts "INFO: Applying V1-V7 deltas (external XPR)..."
    apply_v1_deletions
    apply_v2_bypass_ctrl $RTL_SRC
    apply_v3_mmcm_and_rst
    apply_v4_byp_cdc
    apply_v5_xdma_cc
    apply_v6r_remove_smc
    apply_v7_dma_master
    save_bd
    puts "=== In-session synthesis ==="
    run_synthesis
} else {
    # Bootstrapped project: V9 BD already in place + synthesis DCP already exists.
    # assert_synth_done loaded the merged OOC DCP; use launch_runs impl_1 path.
    puts "INFO: V9 topology present (bootstrapped) — using existing synthesis DCP."
}

puts "INFO: V9 — BD cells: [llength [get_bd_cells]], Chisel top.sv added."

run_impl_and_write_bit "v9_npu_full" $BIT_DST
