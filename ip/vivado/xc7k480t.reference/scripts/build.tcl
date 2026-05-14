################################################################################
# build.tcl — Impl + write_bitstream for the reference XDMA project
#
# Run AFTER create_project.tcl.  The project XPR is resolved the same way as
# create_project.tcl (XDMA_REF_XPR env var or auto-discovery).
#
# Run from repo root:
#   ~/Xilinx/2025.2/Vivado/bin/vivado -mode batch \
#       -source ip/vivado/xc7k480t.reference/scripts/build.tcl \
#       -journal ip/vivado/xc7k480t.reference/scripts/build.jou \
#       -log     ip/vivado/xc7k480t.reference/scripts/build.log
################################################################################

set SCRIPT_DIR [file normalize [file dirname [info script]]]
set REF_ROOT   [file normalize $SCRIPT_DIR/..]
set BIT_DST    $REF_ROOT/top_wrapper.bit

# ── Resolve XPR path (same logic as create_project.tcl) ──────────────────────
if {[info exists ::env(XDMA_REF_XPR)]} {
    set PROJ_XPR $::env(XDMA_REF_XPR)
} else {
    set REPO_ROOT [file normalize $SCRIPT_DIR/../../../..]
    set SEARCH_DIRS [list \
        [file join $REPO_ROOT .. XC7K480T_XDMA_Test XC7K480T_XDMA_Test.xpr] \
        [file join $REPO_ROOT .. XC7K480T_BOARD XC7K480T_XDMA_Test XC7K480T_XDMA_Test.xpr] \
    ]
    set PROJ_XPR ""
    foreach candidate $SEARCH_DIRS {
        set candidate [file normalize $candidate]
        if {[file exists $candidate]} { set PROJ_XPR $candidate; break }
    }
}

if {$PROJ_XPR eq "" || ![file exists $PROJ_XPR]} {
    puts "ERROR: Could not find XC7K480T_XDMA_Test.xpr."
    puts "       Set XDMA_REF_XPR env var to the full path."
    exit 1
}

puts "INFO: Opening project: $PROJ_XPR"
open_project $PROJ_XPR
puts "INFO: Project = [current_project]"
puts ""

# ── Verify synthesis ──────────────────────────────────────────────────────────
puts "=== Step 1: verify synthesis ==="
set synth_prog [get_property PROGRESS [get_runs synth_1]]
puts "INFO: synth_1 progress = $synth_prog"
if {$synth_prog ne "100%"} {
    puts "ERROR: Synthesis not complete. Run create_project.tcl first."
    exit 1
}
puts ""

# ── Implementation ─────────────────────────────────────────────────────────────
puts "=== Step 2: launch implementation ==="
reset_run impl_1
set_property strategy {Vivado Implementation Defaults} [get_runs impl_1]
launch_runs impl_1 -jobs 8
wait_on_run impl_1
set impl_prog [get_property PROGRESS [get_runs impl_1]]
set impl_stat [get_property STATUS   [get_runs impl_1]]
puts "INFO: impl_1 progress=$impl_prog  status=$impl_stat"
if {$impl_prog ne "100%"} {
    puts "ERROR: Implementation failed."; exit 1
}
puts "INFO: Implementation complete."
puts ""

# ── Timing check ──────────────────────────────────────────────────────────────
puts "=== Step 3: check timing ==="
open_run impl_1
set wns [get_property SLACK [get_timing_paths -max_paths 1 -nworst 1 -setup]]
puts "INFO: WNS = $wns ns"
if {$wns < 0} {
    puts "WARNING: Timing violation WNS=$wns — proceeding (reference design)"
}
puts ""

# ── Write bitstream ────────────────────────────────────────────────────────────
puts "=== Step 4: write_bitstream ==="
set RUNS_DIR [get_property DIRECTORY [get_runs impl_1]]
set BIT      $RUNS_DIR/top_wrapper.bit
write_bitstream -force $BIT
set sz [file size $BIT]
file copy -force $BIT $BIT_DST
puts ""
puts "*** BUILD COMPLETE ***"
puts "INFO: Bitstream (runs) : $BIT"
puts "INFO: Bitstream (copy) : $BIT_DST"
puts "INFO: Size             : $sz bytes ([expr {$sz / 1048576}] MB)"
