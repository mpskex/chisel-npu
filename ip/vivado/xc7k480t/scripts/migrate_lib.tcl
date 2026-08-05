################################################################################
# migrate_lib.tcl — shared helpers for all migrate-step scripts
#
# Source this from each step script:
#   source [file join [file dirname [info script]] migrate_lib.tcl]
################################################################################

# ── Jobs count from env (VIVADO_JOBS, default 8) ──────────────────────────────
proc vivado_jobs {} {
    if {[info exists ::env(VIVADO_JOBS)]} {
        set n $::env(VIVADO_JOBS)
    } else {
        set n 8
    }
    puts "INFO: VIVADO_JOBS=$n"
    return $n
}

# ── Resolve the reference XPR ─────────────────────────────────────────────────
# Priority:
#   1. XDMA_REF_XPR env var (legacy escape hatch; points at external XPR)
#   2. Repo-local bootstrapped project (ip/vivado/xc7k480t/proj/)
#   3. Auto-discovery: sibling XC7K480T_XDMA_Test/ directory
#   4. Auto-bootstrap: call bootstrap_project.tcl to create #2 from scratch
proc resolve_ref_xpr {} {
    set script_dir [file normalize [file dirname [info script]]]
    set migrate    [file normalize [file join $script_dir ..]]

    # Priority 1: XDMA_REF_XPR env var
    if {[info exists ::env(XDMA_REF_XPR)] && [file exists $::env(XDMA_REF_XPR)]} {
        puts "INFO: Using XDMA_REF_XPR = $::env(XDMA_REF_XPR)"
        return $::env(XDMA_REF_XPR)
    }

    # Priority 2: repo-local bootstrapped project
    # create_project <name> <dir> places XPR at <dir>/<name>.xpr (no subdir)
    set local_xpr [file join $migrate proj npu_migrate.xpr]
    if {[file exists $local_xpr]} {
        puts "INFO: Using repo-local project: $local_xpr"
        return $local_xpr
    }

    # Priority 3: auto-discover external XPR next to repo
    set repo [file normalize [file join $script_dir ../../../..]]
    set candidates [list \
        [file join $repo .. XC7K480T_XDMA_Test XC7K480T_XDMA_Test.xpr] \
        [file join $repo .. XC7K480T_BOARD XC7K480T_XDMA_Test XC7K480T_XDMA_Test.xpr] \
    ]
    foreach c $candidates {
        set c [file normalize $c]
        if {[file exists $c]} {
            puts "INFO: Auto-discovered external XPR: $c"
            return $c
        }
    }

    # Priority 4: bootstrap a new repo-local project
    puts "INFO: No reference XPR found — bootstrapping repo-local project..."
    puts "INFO: This requires Vivado to regenerate all IPs (~25 min on cold cache)."
    source [file join $script_dir bootstrap_project.tcl]
    bootstrap_ref_project
    # Re-check after bootstrap (proj_dir/npu_migrate.xpr)
    if {[file exists $local_xpr]} { return $local_xpr }
    puts "ERROR: Bootstrap failed. Cannot find or create reference project."
    puts "       Set XDMA_REF_XPR to point at XC7K480T_XDMA_Test.xpr, or"
    puts "       run bootstrap_project.tcl manually."
    exit 1
}

# ── Open reference project and restore BD from repo ───────────────────────────
# Restores the canonical BD BEFORE opening the project in Vivado, so the
# project opens with the clean BD already in place.  This prevents corruption
# carry-over from failed prior runs.
proc open_ref_project {} {
    set xpr [resolve_ref_xpr]

    # ── Pre-open BD restore (filesystem level, before open_project) ──────────
    set script_dir [file normalize [file dirname [info script]]]
    set repo_bd  [file normalize [file join $script_dir ../../xc7k480t.reference/src/bd/top.bd]]
    set repo_bda [file normalize [file join $script_dir ../../xc7k480t.reference/src/bd/top.bda]]
    set proj_root [file normalize [file join [file dirname $xpr] \
                       [file rootname [file tail $xpr]]]]
    set proj_bd_dir [file join ${proj_root}.srcs sources_1 bd top]
    set gen_hdl_dir [file join ${proj_root}.gen  sources_1 bd top hdl]

    # For the bootstrapped project, skip BD restore — the project's BD already
    # has the correct NPU topology from recreate_bd.tcl + bootstrap synthesis.
    # Only restore for the external XPR (which may have been polluted by prior runs).
    set is_bootstrapped_pre [expr {[string match */proj/npu_migrate.xpr $xpr]}]
    if {$is_bootstrapped_pre} {
        puts "INFO: Bootstrapped project — skipping BD restore."
    } elseif {[file exists $repo_bd] && [file exists $proj_bd_dir]} {
        puts "INFO: Pre-restore BD from $repo_bd"
        file copy -force $repo_bd  [file join $proj_bd_dir top.bd]
        if {[file exists $repo_bda]} {
            file copy -force $repo_bda [file join $proj_bd_dir top.bda]
        }
        # Overwrite stale HDL wrapper so synthesis reads the BD afresh.
        if {[file exists $gen_hdl_dir]} {
            foreach stale {top_wrapper.v top_wrapper.vhd top.v top.vhd} {
                set f [file join $gen_hdl_dir $stale]
                if {[file exists $f]} {
                    puts "INFO: Clearing stale wrapper: $f"
                    set fh [open $f w]; close $fh
                }
            }
        }
        puts "INFO: BD filesystem restore complete."
    } else {
        puts "WARNING: Could not locate repo BD — using project BD as-is."
    }

    puts "INFO: Opening reference project: $xpr"
    open_project $xpr

    # ── Post-open: determine if we need to regenerate BD targets ──────────────
    # For the external XPR: BD was restored from repo; regenerate targets+wrapper.
    # For the bootstrapped project: wrapper already in fileset; skip regeneration.
    set is_bootstrapped [expr {[string match */proj/npu_migrate.xpr $xpr]}]
    if {!$is_bootstrapped && [file exists $repo_bd] && [file exists $proj_bd_dir]} {
        puts "INFO: Regenerating BD targets + wrapper (external XPR)..."
        generate_target all [get_files {*/top.bd}]
        make_wrapper -files [get_files {*/top.bd}] -top -force

        # Explicitly add top_wrapper.v to sources fileset (filemgmt 20-742 fix)
        set wrapper_v [file join ${proj_root}.gen sources_1 bd top hdl top_wrapper.v]
        if {[file exists $wrapper_v]} {
            set existing [get_files -quiet -of_objects [get_filesets sources_1] $wrapper_v]
            if {$existing eq ""} {
                puts "INFO: Adding top_wrapper.v to sources fileset: $wrapper_v"
                add_files -fileset sources_1 $wrapper_v
            }
        }
        update_compile_order -fileset sources_1
        puts "INFO: Wrapper regenerated and registered."
    } else {
        puts "INFO: Using bootstrapped project — skipping BD target regeneration."
    }

    # Disable automatic hierarchy update so top_wrapper is accepted as top
    set_property source_mgmt_mode None [current_project]
    set_property top top_wrapper [get_filesets sources_1]

    # Remove RTL source files added by prior failed runs that cause duplicate
    # module references.  We only remove files from the migrate/src and
    # xc7k480t/src directories — not user-specified files like top.sv.
    set script_dir_abs [file normalize [file dirname [info script]]]
    set repo_root_abs  [file normalize [file join $script_dir_abs ../../../..]]
    set cleanup_dirs [list \
        [file join $repo_root_abs ip/vivado/xc7k480t/src] \
        [file join $repo_root_abs ip/vivado/xc7k480t/src] \
    ]
    foreach f [get_files -quiet -of_objects [get_filesets sources_1] \
                   -filter {FILE_TYPE == Verilog || FILE_TYPE == SystemVerilog || \
                            FILE_TYPE == VHDL}] {
        set fabs [file normalize $f]
        set in_cleanup_dir 0
        foreach d $cleanup_dirs {
            if {[string match ${d}* $fabs]} { set in_cleanup_dir 1; break }
        }
        if {$in_cleanup_dir} {
            puts "INFO: Removing external RTL source from prior run: $f"
            catch { remove_files -fileset sources_1 $f }
        }
    }
    update_compile_order -fileset sources_1

    # Disable automatic hierarchy update so Vivado accepts top_wrapper as top
    # even when it is a generated BD wrapper (not a hand-written source file).
    set_property source_mgmt_mode None [current_project]
    set_property top top_wrapper [get_filesets sources_1]
    update_compile_order -fileset sources_1

    puts "INFO: Project = [current_project]  Part = [get_property PART [current_project]]"
    return $xpr
}

# ── Verify synthesis complete (or auto-recover) ───────────────────────────────
# If synth_1 is not at 100%, check if a DCP exists (bootstrap left it there)
# and load it.  Otherwise re-run synthesis in-session.
proc assert_synth_done {} {
    # Default: in-session synthesis (no black boxes)
    set ::migrate_synth_was_launch_runs 0

    set prog [get_property PROGRESS [get_runs synth_1]]

    # Check if a synthesis DCP exists (either from bootstrap launch_runs or
    # from a prior in-session run_synthesis).
    set runs_dir [get_property DIRECTORY [get_runs synth_1]]
    set dcp [file join $runs_dir top_wrapper.dcp]

    if {$prog eq "100%" || [file exists $dcp]} {
        puts "INFO: synth_1 complete (progress=$prog, DCP=[file exists $dcp])"
        if {$prog eq "100%"} {
            # Synthesis was done via launch_runs — mark for run_impl_and_write_bit
            # to use launch_runs impl_1 (avoids black-box DRC errors)
            set ::migrate_synth_was_launch_runs 1
            # Still open the run so timing queries work after impl
        } else {
            # DCP exists but progress not marked — bootstrap case
            set ::migrate_synth_was_launch_runs 1
        }
        set or_err [catch { open_run synth_1 -name synth_1 } or_msg]
        if {$or_err != 0} {
            puts "WARNING: open_run synth_1: $or_msg"
        } else {
            puts "INFO: synth_1 run opened."
        }
        return
    }
    # Synthesis will be run in-session — no black boxes
    set ::migrate_synth_was_launch_runs 0

    # No DCP and progress not 100% — run synthesis in-session
    puts "INFO: synth_1 not complete ($prog) — running in-session synthesis..."
    set_property top top_wrapper [current_fileset]
    update_compile_order -fileset sources_1
    synth_design -top top_wrapper -part [get_property PART [current_project]]
    puts "INFO: In-session synthesis complete."
}

# ── Run synthesis in current session (avoids child-Vivado stale TCL issues) ──
# Call this after BD edits + save_bd.
# Regenerates IP targets, calls synth_design inline, writes DCP for impl.
proc run_synthesis {} {
    puts "=== In-session synthesis ==="
    # In-session synthesis produces a fully-elaborated design (no black boxes)
    set ::migrate_synth_was_launch_runs 0

    # Regenerate IP targets for the EDITED BD.
    # First remove any stale XCI references left by deleted BD cells —
    # generate_target fails if the project's source list still has XCI paths
    # for cells that were deleted from the BD.
    puts "INFO: Removing stale XCI references for deleted BD cells..."
    foreach xci [get_files -quiet -filter {FILE_TYPE == IP && !FILE_EXISTS}] {
        puts "INFO: Removing missing XCI: $xci"
        catch { remove_files -quiet $xci }
    }
    puts "INFO: Regenerating IP targets for edited BD..."
    catch { generate_target all [get_files {*/top.bd}] } gt_err
    if {$gt_err ne ""} {
        puts "WARNING: generate_target returned: $gt_err (continuing)"
    }
    make_wrapper -files [get_files {*/top.bd}] -top -force

    set_property top top_wrapper [current_fileset]
    update_compile_order -fileset sources_1

    set runs_dir [get_property DIRECTORY [get_runs synth_1]]
    set dcp_out  [file join $runs_dir top_wrapper.dcp]

    synth_design -top top_wrapper -part [get_property PART [current_project]]
    write_checkpoint -force $dcp_out

    # Note: PROGRESS and STATUS are read-only on run objects; skip set_property
    puts "INFO: Synthesis complete. DCP: $dcp_out"
}

# ── Run impl + write bitstream ────────────────────────────────────────────────
# step_name : short label (e.g. "v1_no_mb") used in messages
# bit_dst   : canonical destination path for the bitstream
proc run_impl_and_write_bit {step_name bit_dst} {
    puts "=== \[$step_name\] Implementation ==="

    # Determine if we should use launch_runs (bootstrapped project where
    # synth_1 was run via launch_runs leaving IPs as black-boxes in DCP)
    # or in-session opt/place/route (migrate flow where synth_design left
    # a fully-elaborated design in memory with all IPs resolved).
    #
    # Heuristic: if current_design exists AND the synth DCP was written by
    # an in-session synth_design call (not launch_runs), the design is fully
    # elaborated.  If synth_1 was run via launch_runs (bootstrap), use launch_runs impl_1.
    # We detect the bootstrap case by checking if synth_1 PROGRESS was already
    # 100% when assert_synth_done was called (set by ::migrate_synth_was_launch_runs).
    set use_launch_runs 0
    if {[info exists ::migrate_synth_was_launch_runs] && $::migrate_synth_was_launch_runs} {
        set use_launch_runs 1
        puts "INFO: Bootstrap project (synth via launch_runs) — using launch_runs impl_1"
    } elseif {[catch {current_design} cd_err]} {
        set use_launch_runs 1
        puts "INFO: No in-session design — using launch_runs impl_1"
    }

    if {$use_launch_runs} {
        # Bootstrap case: use Vivado's run manager which properly links OOC IPs
        reset_run impl_1
        set strategy_name [expr {[info exists ::env(VIVADO_IMPL_STRATEGY)] ? $::env(VIVADO_IMPL_STRATEGY) : "Vivado Implementation Defaults"}]
        puts "INFO: Implementation strategy: $strategy_name"
        set_property strategy $strategy_name [get_runs impl_1]
        set_property STEPS.ROUTE_DESIGN.ARGS.DIRECTIVE Explore [get_runs impl_1]
        set_property STEPS.POST_ROUTE_PHYS_OPT_DESIGN.IS_ENABLED true [get_runs impl_1]
        launch_runs impl_1 -jobs [vivado_jobs]
        wait_on_run impl_1
        set prog [get_property PROGRESS [get_runs impl_1]]
        if {$prog ne "100%"} {
            puts "ERROR: impl_1 failed (progress=$prog)"
            exit 1
        }
        open_run impl_1 -name impl_1
        set wns [get_property SLACK [get_timing_paths -max_paths 1 -nworst 1 -setup]]
        puts "INFO: \[$step_name\] WNS = $wns ns"
        if {$wns < 0} { puts "WARNING: WNS=$wns — proceeding" }

        set_property CONFIG_MODE                 SPIx1 [current_design]
        set_property BITSTREAM.CONFIG.CONFIGRATE 3     [current_design]
        set runs_dir [get_property DIRECTORY [get_runs impl_1]]
        set top      [get_property top [current_fileset]]
        set bit_src  [file join $runs_dir ${top}.bit]
        write_bitstream -force $bit_src

    } else {
        # In-session case: read XDC, run opt/place/route inline
        foreach xdc [get_files -of_objects [get_filesets constrs_1] -filter {FILE_TYPE == XDC}] {
            read_xdc $xdc
        }
        opt_design
        place_design
        phys_opt_design
        route_design

        set wns [get_property SLACK [get_timing_paths -max_paths 1 -nworst 1 -setup]]
        puts "INFO: \[$step_name\] WNS = $wns ns"
        if {$wns < 0} { puts "WARNING: WNS=$wns — proceeding" }

        set_property CONFIG_MODE                 SPIx1 [current_design]
        set_property BITSTREAM.CONFIG.CONFIGRATE 3     [current_design]
        set runs_dir [get_property DIRECTORY [get_runs impl_1]]
        set top      [get_property top [current_fileset]]
        set bit_src  [file join $runs_dir ${top}.bit]
        write_bitstream -force $bit_src
    }

    file copy -force $bit_src $bit_dst
    set sz [file size $bit_dst]
    puts ""
    puts "*** \[$step_name\] BUILD COMPLETE ***"
    puts "INFO: Bitstream : $bit_dst"
    puts "INFO: Size      : $sz bytes ([expr {$sz / 1048576}] MB)"
    puts "INFO: WNS       : $wns ns"
    return $wns
}

# ── BD helper: delete cells + dangling nets ───────────────────────────────────
proc safe_delete_bd {args} {
    foreach obj $args {
        set found [get_bd_cells -quiet $obj]
        if {$found ne ""} {
            puts "INFO: deleting BD cell $obj"
            delete_bd_objs $found
        } else {
            puts "INFO: BD cell $obj not found (already gone?)"
        }
    }
}

# ── Tidy up after BD edits (validate + save) ─────────────────────────────────
# validate_bd_design runs non-fatally so warnings about optional unconnected
# ports (e.g. MIG S_AXI_CTRL after MB removal) don't abort the script.
proc save_bd {} {
    puts "INFO: validating + saving BD"
    set rc [catch { validate_bd_design } err]
    if {$rc != 0} {
        puts "WARNING: validate_bd_design returned errors (proceeding):"
        puts "  $err"
    }
    save_bd_design
}
