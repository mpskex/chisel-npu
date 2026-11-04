################################################################################
# build_npu_vu9p.tcl — NPU program-engine bitstream for xcvu9p-flgb2104-2-e
#
# Integrates the Chisel NpuProgramEngineFrontend (top.sv) with the Alivu9p
# reference platform (XDMA + 4x MIG DDR4 + axi_interconnect) via the
# NPU-integrated top `hw_platform` (ip/vivado/xcvu9p/src/hw_platform.v).
#
# Run `scripts/setup_platform.tcl` once first (DDR4 narrow burst, XDMA bypass,
# fabric MMCM, converter IPs).
#
# Usage:
#   vivado -mode batch -source ip/vivado/xcvu9p/scripts/build_npu_vu9p.tcl
#
# Output: ip/vivado/xcvu9p/top_npu_vu9p.bit
################################################################################

set SCRIPT_DIR [file normalize [file dirname [info script]]]
set XCVU9P     [file normalize $SCRIPT_DIR/..]
set REPO_ROOT  [file normalize [file join $XCVU9P ../../..]]
set SRC_DIR    [file join $XCVU9P src]
set PROJ       [file join $XCVU9P prj prj.xpr]
set BIT_DST    [file join $XCVU9P top_npu_vu9p.bit]

if {![file exists $PROJ]} { puts "ERROR: project not found: $PROJ"; exit 1 }
if {![file exists [file join $REPO_ROOT top.sv]]} { puts "ERROR: top.sv missing (run make build)"; exit 1 }

open_project $PROJ

# ── Add NPU RTL + Chisel top ────────────────────────────────────────────────
foreach f [list npu_engine_ctrl_lite.v npu_engine_subsys.v hw_platform.v] {
    set path [file join $SRC_DIR $f]
    if {[get_files -quiet -of_objects [get_filesets sources_1] $path] eq ""} {
        add_files -norecurse $path
    }
}
set top_sv [file join $REPO_ROOT top.sv]
# Force a re-read of a regenerated top.sv: remove then re-add so Vivado does
# not reuse a cached synthesized netlist (a stale W=8 netlist survived a
# reset_run when top.sv was only conditionally added).
set _existing_top [get_files -quiet -of_objects [get_filesets sources_1] $top_sv]
if {$_existing_top ne ""} { remove_files $_existing_top }
add_files -norecurse $top_sv
puts "INFO: top.sv re-added ([file size $top_sv] bytes)"
set_property top hw_platform [get_filesets sources_1]
update_compile_order -fileset sources_1
puts "TOP=[get_property top [get_filesets sources_1]]"

# ── Force npu_cc to its configured 512-bit width ────────────────────────────
# The engine AXI is 512 bits, but IP output products can be stale at 128 bits
# (setup_platform reconfigures CONFIG.DATA_WIDTH without regenerating).  A
# stale 128-bit npu_cc silently truncates every engine DMA transfer to the low
# 16 bytes (Synth 8-689 width mismatch).  Regenerate + resynthesize the IP so
# the stub and DCP match the 512-bit configuration.
proc _cc_stub_is512 {} {
    set pd [get_property DIRECTORY [current_project]]
    set cands [concat \
        [glob -nocomplain [file join $pd *.ip_user_files ip * npu_cc_stub.v]] \
        [glob -nocomplain [file join $pd prj.cache ip * * * * npu_cc_stub.v]] \
        [glob -nocomplain [file join [get_property IP_OUTPUT_DIR [get_ips npu_cc]] npu_cc_stub.v]]]
    foreach s $cands {
        if {![file exists $s]} { continue }
        set fh [open $s r]; set t [read $fh]; close $fh
        if {[string first "s_axi_wdata\[511:0\]" $t] >= 0} { return 1 }
    }
    return 0
}
if {[get_ips -quiet npu_cc] ne ""} {
    if {![_cc_stub_is512]} {
        puts "WARNING: npu_cc stub is stale (<512-bit); regenerating"
        reset_target all [get_ips npu_cc]
        set_property -dict [list CONFIG.DATA_WIDTH {512} CONFIG.ADDR_WIDTH {64} \
                                 CONFIG.ID_WIDTH {4}] [get_ips npu_cc]
        generate_target all [get_ips npu_cc]
        if {[get_runs -quiet npu_cc_synth_1] ne ""} { reset_run npu_cc_synth_1 }
    }
    if {![_cc_stub_is512]} {
        puts "ERROR: npu_cc stub is not 512-bit; run setup_platform.tcl cleanly"; exit 1
    }
    # Ensure the IP source-dir DCP (read by link_design) is the regenerated
    # 512-bit one; a stale 128-bit copy there silently truncates the AXI.
    set _src [file join [get_property IP_OUTPUT_DIR [get_ips npu_cc]] npu_cc.dcp]
    set _dst [file join [get_property IP_DIR        [get_ips npu_cc]] npu_cc.dcp]
    if {[file exists $_src]} { file copy -force $_src $_dst }
    puts "NPU_CC_WIDTH=512"
}

set jobs 4
if {[info exists ::env(VIVADO_JOBS)]} { set jobs $::env(VIVADO_JOBS) }

# ── Synthesis (all runs, incl. OOC IP) ──────────────────────────────────────
set_param general.maxThreads 8
# Disable auto-incremental synthesis: the stale reference checkpoint
# (prj.srcs/utils_1/imports/synth_1/hw_platform.dcp) otherwise reuses ~100% of
# a previous engine netlist, so a regenerated top.sv is silently ignored.
if {[llength [get_runs -quiet synth_1]] > 0} {
    catch { set_property AUTO_INCREMENTAL_CHECKPOINT 0 [get_runs synth_1] }
    catch { set_property INCREMENTAL_CHECKPOINT "" [get_runs synth_1] }
}
reset_run synth_1
launch_runs synth_1 -jobs 1
wait_on_run synth_1
set prog [get_property PROGRESS [get_runs synth_1]]
puts "SYNTH_PROGRESS=$prog"
if {$prog ne "100%"} { puts "SYNTH_STATUS=[get_property STATUS [get_runs synth_1]]"; exit 1 }

# Copy IP DCPs gen -> ip (link_design INBB-3 workaround)
foreach ip [get_ips -quiet] {
    set ip_dir     [get_property IP_DIR        [get_ips $ip]]
    set ip_out_dir [get_property IP_OUTPUT_DIR [get_ips $ip]]
    set src_dcp [file join $ip_out_dir ${ip}.dcp]
    set dst_dcp [file join $ip_dir     ${ip}.dcp]
    # Force-copy: a stale DCP in the IP source dir must not shadow the freshly
    # synthesised one (this is how the 128-bit npu_cc was silently reused).
    if {[file exists $src_dcp]} {
        file copy -force $src_dcp $dst_dcp
    }
}

# ── Implementation + bitstream ──────────────────────────────────────────────
set_param general.maxThreads 8
set_property STEPS.PLACE_DESIGN.ARGS.DIRECTIVE ExtraTimingOpt [get_runs impl_1]
reset_run impl_1
launch_runs impl_1 -to_step write_bitstream -jobs $jobs
wait_on_run impl_1
set iprog [get_property PROGRESS [get_runs impl_1]]
puts "IMPL_PROGRESS=$iprog"
if {$iprog ne "100%"} { puts "IMPL_STATUS=[get_property STATUS [get_runs impl_1]]"; exit 1 }

foreach b [glob -nocomplain [file join $XCVU9P prj prj.runs impl_1 *.bit]] {
    file copy -force $b $BIT_DST
    puts "BIT=$BIT_DST"
}
puts "BUILD_DONE"
