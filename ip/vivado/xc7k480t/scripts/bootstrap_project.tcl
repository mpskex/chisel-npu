################################################################################
# bootstrap_project.tcl — Create a self-contained NPU migration project
#
# Called automatically by migrate_lib.tcl when no reference XPR is found.
# Can also be run standalone to (re)create the local project:
#
#   ~/Xilinx/2025.2/Vivado/bin/vivado -mode batch \
#       -source ip/vivado/xc7k480t/scripts/bootstrap_project.tcl
#
# What this creates:
#   ip/vivado/xc7k480t/proj/npu_migrate/npu_migrate.xpr
#
# BD contents (from recreate_bd.tcl — captured after V9 migrate runs):
#   - xdma_0 (XDMA 4.2, PCIe Gen2 x8, 128-bit AXI @ 125 MHz)
#   - mig_7series_0 (MIG 7-series DDR3 dual channel)
#   - util_ds_buf (PCIe refclock IBUFDS)
#   - rst_mig_7series_0_133M / _1 (MIG C0/C1 reset domains)
#   - mig_c0/c1_ctrl_vip (AXI-Lite VIP stubs for MIG ECC status)
#   - clk_wiz_fabric (125 MHz regenerated fabric clock)
#   - rst_fabric_200M (fabric domain reset)
#   - axi_clkconv_byp (BYPASS CDC 125→125 wrapper)
#   - byp_dw / byp_pc / ctrl_lite (BYPASS CTRL register path)
#   - axi_cc_xdma_in (XDMA M_AXI passthrough)
#   - axi_clkconv_xdma (125→133 for XDMA→MIG C0)
#   - axi_dwidth_xdma (128→512 for XDMA→MIG C0)
#   - axi_clkconv_npu (125→133 for DMA→MIG C1)
#   - axi_dwidth_npu (128→512 for DMA→MIG C1)
#   - dma_master (NPU DMA master driving MIG C1)
#
# This is the V9 NPU topology (proven 9/9 PASS on xc7k480tffg1156-2).
################################################################################

proc bootstrap_ref_project {} {
    set script_dir [file normalize [file dirname [info script]]]
    set migrate    [file normalize [file join $script_dir ..]]
    set repo_root  [file normalize [file join $migrate ../../..]]
    set ref_dir    [file join $repo_root ip/vivado/xc7k480t.reference]
    set proj_dir   [file join $migrate proj]
    set proj_name  npu_migrate
    # create_project places XPR at <proj_dir>/<proj_name>.xpr (no extra subdir)
    set local_xpr  [file join $proj_dir ${proj_name}.xpr]

    puts "=== Bootstrapping NPU migration project ==="
    puts "INFO: repo_root = $repo_root"
    puts "INFO: project   = $local_xpr"

    # Idempotent: start fresh
    file delete -force $proj_dir

    # Create project
    create_project $proj_name $proj_dir -part xc7k480tffg1156-2 -force
    set_property TARGET_LANGUAGE Verilog [current_project]
    puts "INFO: Project created: [current_project]"

    # Add IO pin constraints
    add_files -fileset constrs_1 -norecurse \
        [file join $ref_dir constrs IO_Port.xdc]
    puts "INFO: IO_Port.xdc added."

    # Add NPU RTL modules referenced by the BD (ctrl_lite, dma_master)
    # These must be visible before create_root_design runs.
    set npu_rtl_dir [file join $repo_root ip/vivado/xc7k480t/src]
    foreach f {npu_ctrl_lite.v npu_dma_master.v} {
        set fp [file join $npu_rtl_dir $f]
        if {[file exists $fp]} {
            add_files -norecurse $fp
            puts "INFO: Added RTL: $fp"
        } else {
            puts "ERROR: RTL file not found: $fp"
            close_project; return 0
        }
    }
    update_compile_order -fileset sources_1

    # Recreate BD from TCL recipe (no design_info dependency)
    set recreate_tcl [file join $ref_dir scripts recreate_bd.tcl]
    if {![file exists $recreate_tcl]} {
        puts "ERROR: recreate_bd.tcl not found: $recreate_tcl"
        puts "       Generate it with: ip/vivado/xc7k480t/scripts/bootstrap_project.tcl"
        close_project; return 0
    }
    puts "INFO: Sourcing BD recipe from $recreate_tcl"
    # recreate_bd.tcl calls create_root_design "" at its bottom — self-executing
    source $recreate_tcl
    puts "INFO: BD recreated."

    # Generate IP HDL targets (~25 min cold cache; fast if already cached)
    puts "INFO: Generating IP targets (may take 25+ min on first run)..."
    catch { generate_target all [get_files {*/top.bd}] } gt_err
    if {$gt_err ne ""} {
        puts "WARNING: generate_target warning: $gt_err (continuing)"
    }
    puts "INFO: IP targets done."

    # Create and register BD wrapper
    make_wrapper -files [get_files {*/top.bd}] -top -force
    set wrapper [lindex [glob -nocomplain \
        [file join $proj_dir *.gen sources_1 bd top hdl top_wrapper.v]] 0]
    if {$wrapper eq "" || ![file exists $wrapper]} {
        puts "ERROR: top_wrapper.v not found after make_wrapper"
        close_project; return 0
    }
    set ex [get_files -quiet -of_objects [get_filesets sources_1] $wrapper]
    if {$ex eq ""} { add_files -fileset sources_1 $wrapper }
    set_property top top_wrapper [get_filesets sources_1]
    update_compile_order -fileset sources_1
    puts "INFO: Wrapper registered: $wrapper"

    # Run synthesis — launch all OOC sub-runs + top_wrapper synthesis
    puts "INFO: Running synthesis (synth_1 + all OOC sub-runs)..."
    reset_run synth_1
    launch_runs synth_1 -jobs 8

    # Wait for ALL synthesis runs (OOC sub-runs + top_wrapper synth_1)
    # so the DCP doesn't have black boxes when impl opens it.
    set all_synth_runs [get_runs -filter {IS_SYNTHESIS == 1}]
    puts "INFO: Waiting for [llength $all_synth_runs] synthesis run(s)..."
    wait_on_run $all_synth_runs

    set prog [get_property PROGRESS [get_runs synth_1]]
    if {$prog ne "100%"} {
        puts "ERROR: synth_1 failed ($prog)"
        close_project; return 0
    }
    puts "INFO: All synthesis runs complete. synth_1 progress=$prog."

    # Copy IP synthesis DCPs from IP_OUTPUT_DIR (gen/) → IP_DIR (srcs/)
    # so that Vivado's link_design in impl can find them at IP_DIR/<ip>.dcp.
    # This must run AFTER OOC synthesis completes so the DCPs exist in gen/.
    puts "INFO: Copying IP synthesis DCPs from gen/ to srcs/ (IP_DIR fix)..."
    set n_copied 0
    foreach ip [get_ips -quiet] {
        set ip_dir     [get_property IP_DIR        [get_ips $ip]]
        set ip_out_dir [get_property IP_OUTPUT_DIR [get_ips $ip]]
        set src_dcp  [file join $ip_out_dir ${ip}.dcp]
        set dst_dcp  [file join $ip_dir     ${ip}.dcp]
        if {[file exists $src_dcp] && ![file exists $dst_dcp]} {
            file copy -force $src_dcp $dst_dcp
            puts "INFO:   $ip → IP_DIR (DCP copied)"
            incr n_copied
        } elseif {[file exists $dst_dcp]} {
            # already there (re-run case)
        } elseif {![file exists $src_dcp]} {
            puts "WARNING: $ip has no DCP in IP_OUTPUT_DIR"
        }
    }
    puts "INFO: $n_copied IP DCP(s) copied. impl link_design will now resolve them."

    # Re-open synth_1 so it collects the OOC DCP results into the design
    open_run synth_1 -name synth_1
    puts "INFO: synth_1 opened with OOC results collected."

    # Write the merged synthesis checkpoint (OOC IPs now embedded, no black boxes)
    set synth_dcp [file join $proj_dir ${proj_name}.runs synth_1 top_wrapper.dcp]
    write_checkpoint -force $synth_dcp
    puts "INFO: Merged synthesis checkpoint written: [file size $synth_dcp] bytes"

    # Save project state and close
    catch { save_project_as -force $proj_name $proj_dir }
    close_project
    puts ""
    puts "*** BOOTSTRAP COMPLETE ***"
    puts "INFO: Project : $local_xpr"
    puts "INFO: No external XDMA_REF_XPR needed — use the project above."
    return 1
}

# ── If sourced standalone (not via resolve_ref_xpr), run bootstrap directly ──
if {[info script] eq [info nameofexecutable] ||
    [file tail [info script]] eq "bootstrap_project.tcl"} {
    if {[bootstrap_ref_project]} {
        puts "Bootstrap succeeded."
    } else {
        puts "Bootstrap FAILED."
        exit 1
    }
}
