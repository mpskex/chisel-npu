################################################################################
# build_npu_engine_with_ila.tcl — Program-engine bitstream + ILA debugger core
#
# Identical to build_npu_engine.tcl except an ILA core (u_npu_ila) is inserted
# post-synth, wired to every (* mark_debug = "true" *) net in the design.
# The engine's NpuDmaEngine is instrumented in top.sv:
#   - state       (4 bits) DMA FSM state (IDLE=0 AR=1 READ=2 RFW=3 ACCW=4
#                  INSTRW=5 WAW=6 WDATA=7 WRESP=8 DONE=9)
#   - dbg_aw_hand / dbg_w_hand / dbg_b_hand — AW/W/B handshake pulse shadows
#   - dbg_awvalid/awready, dbg_wvalid/wready, dbg_bvalid/bready — raw signals
#   - dbg_awaddr   (32 bits) write address (expect 0x40000880 OUT for a vse)
#   - dbg_reqdir   (2 bits) request direction (1 = R2L store)
#   - dbg_beatcnt  (4 bits) write beat index
#
# Outputs:
#   ip/vivado/xc7k480t/top_npu_engine_with_ila.bit
#   ip/vivado/xc7k480t/top_npu_engine_with_ila.ltx
#
# Capture flow (a store-wedge at pc=4, DMA stuck in WAW/WDATA/WRESP):
#   1. Flash this bitstream, bring up PCIe/XDMA.
#   2. Vivado HW Manager: open target, load the .ltx, arm ILA with trigger
#      on state == 4'd6 (WAW) or 4'd7 (WDATA) or 4'd8 (WRESP), then run the
#      chisel_npu_py program; upload + dump the waveform.
################################################################################

set SCRIPT_DIR [file normalize [file dirname [info script]]]
set MIGRATE    [file normalize $SCRIPT_DIR/..]
set RTL_SRC    [file normalize [file join $MIGRATE src]]
set REPO_ROOT  [file normalize $SCRIPT_DIR/../../../..]
set BIT_DST    [file join $MIGRATE top_npu_engine_with_ila.bit]
set LTX_DST    [file join $MIGRATE top_npu_engine_with_ila.ltx]

source [file join $SCRIPT_DIR migrate_lib.tcl]
source [file join $SCRIPT_DIR _apply_npu_topology.tcl]
source [file join $SCRIPT_DIR _apply_npu_ila.tcl]

# The engine OOC synth (MMALU K=16 + RF + DMA) spawns many worker processes;
# cap threads and serialize OOC runs to avoid OOM on 19 GB hosts.
set_param general.maxThreads 2
if {![info exists ::env(VIVADO_JOBS)] || $::env(VIVADO_JOBS) > 2} {
    set ::env(VIVADO_JOBS) 2
}

open_ref_project

# ── Add engine RTL sources (replaces the legacy trio) ────────────────────────
foreach name {npu_engine_ctrl_lite.v npu_engine_subsys.v} {
    set f [file join $RTL_SRC $name]
    if {![file exists $f]} { puts "ERROR: $name missing"; exit 1 }
    if {[get_files -quiet -of_objects [get_filesets sources_1] $f] eq ""} {
        add_files -norecurse $f
        puts "INFO: added $name"
    }
}

# top.sv (engine build) is required — this is the mark_debug-instrumented copy
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
#    segments the legacy npu_subsys/m_axi had ─────────────────────────────────
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

# ── Insert ILA debugger core ─────────────────────────────────────────────────
puts "INFO: inserting ILA debugger core (u_npu_ila)..."
insert_npu_ila

npu_restore_mgmt_mode

set synth_dcp [file join [get_property DIRECTORY [get_runs synth_1]] top_wrapper.dcp]
write_checkpoint -force $synth_dcp
puts "INFO: merged synthesis checkpoint: [file size $synth_dcp] bytes"

set ::migrate_synth_was_launch_runs 1
run_impl_and_write_bit "npu_engine_with_ila" $BIT_DST

# ── Emit the .ltx probes file next to the bitstream ─────────────────────────
catch { open_run impl_1 -name impl_1 }
set runs_dir [get_property DIRECTORY [get_runs impl_1]]
set ltx_src  [file join $runs_dir top_wrapper.ltx]
if {[catch {write_debug_probes -force $ltx_src} _err]} {
    puts "WARN: write_debug_probes: $_err"
} else {
    if {[file exists $ltx_src]} {
        file copy -force $ltx_src $LTX_DST
        puts "INFO: ILA probes file: $LTX_DST ([file size $LTX_DST] bytes)"
    }
}

puts ""
puts "*** build_npu_engine_with_ila COMPLETE ***"
puts "INFO: Bitstream : $BIT_DST"
puts "INFO: .ltx file : $LTX_DST  (load into Vivado HW Manager alongside .bit)"
