################################################################################
# build_npu_engine.tcl — Program-engine bitstream build
#
# Swaps the legacy npu_subsys cell (ctrl_lite + dma_master + MMALU) for the
# Chisel NpuProgramEngineFrontend (top.sv) wrapped by npu_engine_subsys.v +
# npu_engine_ctrl_lite.v.  Everything else (axi_xbar 2S:2M, 4 GB map, MIG C0/C1)
# is unchanged.
#
# Prerequisites:
#   - 'make build' has generated top.sv from the program-engine Chisel sources
#   - the bootstrapped Vivado project exists (bootstrap_project.tcl)
#
# Outputs: ip/vivado/xc7k480t/top_npu_engine.bit
#
# Companion (ILA debugger core): build_npu_engine_with_ila.tcl
################################################################################

set SCRIPT_DIR [file normalize [file dirname [info script]]]
set MIGRATE    [file normalize $SCRIPT_DIR/..]
set RTL_SRC    [file normalize [file join $MIGRATE src]]
set REPO_ROOT  [file normalize $SCRIPT_DIR/../../../..]
set BIT_DST    [file join $MIGRATE top_npu_engine.bit]

source [file join $SCRIPT_DIR migrate_lib.tcl]
source [file join $SCRIPT_DIR _apply_npu_topology.tcl]

# The engine OOC synth (MMALU K=32 + RF + DMA) spawns many worker processes;
# cap threads and serialize OOC runs to avoid OOM on 19 GB hosts.
set_param general.maxThreads 2
if {![info exists ::env(VIVADO_JOBS)] || $::env(VIVADO_JOBS) > 2} {
    set ::env(VIVADO_JOBS) 2
}

open_ref_project
# NOTE: assert_synth_done is intentionally NOT called here — the engine BD
# surgery below must happen before any synthesis, and this script always
# drives synthesis through launch_runs (no in-session path).

# ── Add engine RTL sources (replaces the legacy trio) ────────────────────────
foreach name {npu_engine_ctrl_lite.v npu_engine_subsys.v} {
    set f [file join $RTL_SRC $name]
    if {![file exists $f]} { puts "ERROR: $name missing"; exit 1 }
    if {[get_files -quiet -of_objects [get_filesets sources_1] $f] eq ""} {
        add_files -norecurse $f
        puts "INFO: added $name"
    }
}

# top.sv (engine build) is required
set top_sv [file join $REPO_ROOT top.sv]
if {![file exists $top_sv]} {
    puts "ERROR: top.sv not found. Run 'make build' first."
    exit 1
}
if {[get_files -quiet -of_objects [get_filesets sources_1] $top_sv] eq ""} {
    add_files -norecurse $top_sv
    puts "INFO: added Chisel top.sv ([file size $top_sv] bytes)"
}
update_compile_order -fileset sources_1

# ── BD surgery: replace npu_subsys with npu_engine_subsys ────────────────────
open_bd_design [get_files {*/top.bd}]
set_property source_mgmt_mode All [current_project]

if {[get_bd_cells -quiet npu_engine_subsys] ne ""} {
    puts "INFO: engine topology already present."
} else {
    if {[get_bd_cells -quiet npu_subsys] ne ""} {
        puts "INFO: deleting legacy npu_subsys cell..."
        delete_bd_objs [get_bd_cells npu_subsys]
    }
    puts "INFO: creating npu_engine_subsys cell..."
    create_bd_cell -type module -reference npu_engine_subsys npu_engine_subsys

    # ── Clock / reset / calibration ──
    connect_bd_net [get_bd_pins clk_wiz_fabric/clk_out1]            [get_bd_pins npu_engine_subsys/aclk]
    connect_bd_net [get_bd_pins rst_fabric_200M/peripheral_aresetn] [get_bd_pins npu_engine_subsys/aresetn]
    connect_bd_net [get_bd_pins mig_7series_0/c0_init_calib_complete] [get_bd_pins npu_engine_subsys/c0_init_calib_complete]
    connect_bd_net [get_bd_pins mig_7series_0/c1_init_calib_complete] [get_bd_pins npu_engine_subsys/c1_init_calib_complete]

    # ── AXI4 master → axi_clkconv_npu (S01 of the xbar) ──
    connect_bd_intf_net [get_bd_intf_pins npu_engine_subsys/m_axi] [get_bd_intf_pins axi_clkconv_npu/S_AXI]

    # ── AXI4-Lite slave from byp_pc/M_AXI (pin-by-pin) ──
    set pc  [get_bd_cells byp_pc]
    set sub [get_bd_cells npu_engine_subsys]
    connect_bd_net [get_bd_pins $pc/m_axi_awaddr]    [get_bd_pins $sub/s_axil_awaddr]
    connect_bd_net [get_bd_pins $pc/m_axi_awprot]    [get_bd_pins $sub/s_axil_awprot]
    connect_bd_net [get_bd_pins $pc/m_axi_awvalid]   [get_bd_pins $sub/s_axil_awvalid]
    connect_bd_net [get_bd_pins $sub/s_axil_awready] [get_bd_pins $pc/m_axi_awready]
    connect_bd_net [get_bd_pins $pc/m_axi_wdata]     [get_bd_pins $sub/s_axil_wdata]
    connect_bd_net [get_bd_pins $pc/m_axi_wstrb]     [get_bd_pins $sub/s_axil_wstrb]
    connect_bd_net [get_bd_pins $pc/m_axi_wvalid]    [get_bd_pins $sub/s_axil_wvalid]
    connect_bd_net [get_bd_pins $sub/s_axil_wready]  [get_bd_pins $pc/m_axi_wready]
    connect_bd_net [get_bd_pins $sub/s_axil_bresp]   [get_bd_pins $pc/m_axi_bresp]
    connect_bd_net [get_bd_pins $sub/s_axil_bvalid]  [get_bd_pins $pc/m_axi_bvalid]
    connect_bd_net [get_bd_pins $pc/m_axi_bready]    [get_bd_pins $sub/s_axil_bready]
    connect_bd_net [get_bd_pins $pc/m_axi_araddr]    [get_bd_pins $sub/s_axil_araddr]
    connect_bd_net [get_bd_pins $pc/m_axi_arprot]    [get_bd_pins $sub/s_axil_arprot]
    connect_bd_net [get_bd_pins $pc/m_axi_arvalid]   [get_bd_pins $sub/s_axil_arvalid]
    connect_bd_net [get_bd_pins $sub/s_axil_arready] [get_bd_pins $pc/m_axi_arready]
    connect_bd_net [get_bd_pins $sub/s_axil_rdata]   [get_bd_pins $pc/m_axi_rdata]
    connect_bd_net [get_bd_pins $sub/s_axil_rresp]   [get_bd_pins $pc/m_axi_rresp]
    connect_bd_net [get_bd_pins $sub/s_axil_rvalid]  [get_bd_pins $pc/m_axi_rvalid]
    connect_bd_net [get_bd_pins $pc/m_axi_rready]    [get_bd_pins $sub/s_axil_rready]

    puts "INFO: engine topology applied."
    save_bd
}

# ── Address map (idempotent): the engine master MUST get the same 4 GB
#    segments the legacy npu_subsys/m_axi had — assign_bd_address to the
#    deleted cell leaves the new master unmapped (AXI goes nowhere).
#    The C0/C1 segments are already included (xdma_0/M_AXI uses them), so
#    only the assignment is needed here.
set c0_seg [get_bd_addr_segs mig_7series_0/c0_memmap/c0_memaddr]
set c1_seg [get_bd_addr_segs mig_7series_0/c1_memmap/c1_memaddr]
if {$c0_seg ne ""} {
    assign_bd_address -target_address_space /npu_engine_subsys/m_axi $c0_seg -range 2G -offset 0x00000000 -force
}
if {$c1_seg ne ""} {
    assign_bd_address -target_address_space /npu_engine_subsys/m_axi $c1_seg -range 2G -offset 0x80000000 -force
}
puts "INFO: engine master mapped to the 4 GB C0/C1 window."

update_compile_order -fileset sources_1

# ── Regenerate IP targets ────────────────────────────────────────────────────
puts "INFO: regenerating IP targets..."
catch { generate_target all [get_files {*/top.bd}] } gt_err
if {$gt_err ne ""} { puts "WARNING: generate_target: $gt_err" }
make_wrapper -files [get_files {*/top.bd}] -top -force

# Explicitly register the regenerated wrapper and pin it as top — with
# source_mgmt_mode All the auto-selected top can otherwise drift to the
# engine itself (544 pins → IO overutilization at placement).
set proj_dir [get_property DIRECTORY [current_project]]
set proj_name [get_property NAME [current_project]]
set wrapper_v [file join $proj_dir ${proj_name}.gen sources_1 bd top hdl top_wrapper.v]
if {[file exists $wrapper_v]} {
    if {[get_files -quiet -of_objects [get_filesets sources_1] $wrapper_v] eq ""} {
        add_files -fileset sources_1 $wrapper_v
        puts "INFO: added regenerated wrapper: $wrapper_v"
    }
}
set_property source_mgmt_mode All [current_project]
set_property top top_wrapper [get_filesets sources_1]
update_compile_order -fileset sources_1

# ── Synthesis + implementation ───────────────────────────────────────────────
puts "INFO: launching synth_1 + OOC sub-runs..."
reset_run [get_runs -filter {IS_SYNTHESIS == 1}]
launch_runs [get_runs -filter {IS_SYNTHESIS == 1}] -jobs [vivado_jobs]
set all_synth [get_runs -filter {IS_SYNTHESIS == 1}]
puts "INFO: waiting for [llength $all_synth] synthesis run(s)..."
wait_on_run $all_synth

set prog [get_property PROGRESS [get_runs synth_1]]
if {$prog ne "100%"} { puts "ERROR: synth_1 failed (PROGRESS=$prog)"; exit 1 }
puts "INFO: synth_1 done."

# Copy IP DCPs gen/ → srcs/ (link_design INBB-3 workaround)
foreach ip [get_ips -quiet] {
    set ip_dir     [get_property IP_DIR        [get_ips $ip]]
    set ip_out_dir [get_property IP_OUTPUT_DIR [get_ips $ip]]
    set src_dcp [file join $ip_out_dir ${ip}.dcp]
    set dst_dcp [file join $ip_dir     ${ip}.dcp]
    if {[file exists $src_dcp] && ![file exists $dst_dcp]} {
        file copy -force $src_dcp $dst_dcp
    }
}

open_run synth_1 -name synth_1
puts "INFO: synth_1 opened (merged OOC DCPs)."

npu_restore_mgmt_mode

set synth_dcp [file join [get_property DIRECTORY [get_runs synth_1]] top_wrapper.dcp]
write_checkpoint -force $synth_dcp
puts "INFO: merged synthesis checkpoint: [file size $synth_dcp] bytes"

set ::migrate_synth_was_launch_runs 1
run_impl_and_write_bit "npu_engine" $BIT_DST

puts ""
puts "*** build_npu_engine COMPLETE ***"
puts "INFO: Bitstream  : $BIT_DST"
