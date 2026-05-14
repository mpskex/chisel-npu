################################################################################
# create_project.tcl — Open the reference project and run synthesis
#
# Strategy: open the original XC7K480T_XDMA_Test.xpr directly (it has all IPs
# resolved, XCIs generated, and pin paths correct).  We update IP if needed,
# then launch synthesis.
#
# The project location is resolved via the XDMA_REF_XPR environment variable,
# or discovered automatically as a sibling of this repo.  Override with:
#
#   export XDMA_REF_XPR=/path/to/XC7K480T_XDMA_Test/XC7K480T_XDMA_Test.xpr
#
# Run from repo root:
#   ~/Xilinx/2025.2/Vivado/bin/vivado -mode batch \
#       -source ip/vivado/xc7k480t.reference/scripts/create_project.tcl \
#       -journal ip/vivado/xc7k480t.reference/scripts/create_project.jou \
#       -log     ip/vivado/xc7k480t.reference/scripts/create_project.log
################################################################################

set SCRIPT_DIR [file normalize [file dirname [info script]]]
set REF_ROOT   [file normalize $SCRIPT_DIR/..]

# ── Resolve XPR path ─────────────────────────────────────────────────────────
# Priority: env var > auto-discovery next to repo root > error
if {[info exists ::env(XDMA_REF_XPR)]} {
    set ORIG_XPR $::env(XDMA_REF_XPR)
} else {
    # Auto-discover: look for XC7K480T_XDMA_Test.xpr as a sibling of the repo
    set REPO_ROOT [file normalize $SCRIPT_DIR/../../../..]
    set SEARCH_DIRS [list \
        [file join $REPO_ROOT .. XC7K480T_XDMA_Test XC7K480T_XDMA_Test.xpr] \
        [file join $REPO_ROOT .. XC7K480T_BOARD XC7K480T_XDMA_Test XC7K480T_XDMA_Test.xpr] \
    ]
    set ORIG_XPR ""
    foreach candidate $SEARCH_DIRS {
        set candidate [file normalize $candidate]
        if {[file exists $candidate]} {
            set ORIG_XPR $candidate
            break
        }
    }
}

if {$ORIG_XPR eq "" || ![file exists $ORIG_XPR]} {
    puts "ERROR: Could not find XC7K480T_XDMA_Test.xpr."
    puts "       Set the XDMA_REF_XPR environment variable to the full path, e.g.:"
    puts "         export XDMA_REF_XPR=/path/to/XC7K480T_XDMA_Test/XC7K480T_XDMA_Test.xpr"
    exit 1
}

puts "INFO: Opening reference project: $ORIG_XPR"
open_project $ORIG_XPR
puts "INFO: Project = [current_project]"
puts "INFO: Part    = [get_property PART [current_project]]"
puts ""

# ── Upgrade any IPs that need updating ───────────────────────────────────────
puts "=== Step 1: upgrade IPs ==="
upgrade_ip -quiet [get_ips]
puts "INFO: IP upgrade pass done."
puts ""

# ── Generate targets for block design ────────────────────────────────────────
puts "=== Step 2: generate_target ==="
generate_target all [get_files {*/top.bd}]
puts "INFO: generate_target done."
puts ""

# ── Make sure the wrapper is up to date ──────────────────────────────────────
puts "=== Step 3: make_wrapper ==="
make_wrapper -files [get_files {*/top.bd}] -top -force
puts "INFO: make_wrapper done."
puts ""

# ── Update compile order ──────────────────────────────────────────────────────
update_compile_order -fileset sources_1
set top [get_property top [current_fileset]]
puts "INFO: Top = $top"
puts ""

# ── Synthesis ────────────────────────────────────────────────────────────────
puts "=== Step 4: launch synthesis ==="
reset_run synth_1
launch_runs synth_1 -jobs 8
wait_on_run synth_1
set prog [get_property PROGRESS [get_runs synth_1]]
set stat [get_property STATUS   [get_runs synth_1]]
puts "INFO: synth_1 progress=$prog  status=$stat"
if {$prog ne "100%"} {
    puts "ERROR: Synthesis failed."
    exit 1
}
puts ""
puts "*** SYNTHESIS COMPLETE — run build.tcl next ***"
