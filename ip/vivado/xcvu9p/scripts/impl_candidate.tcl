################################################################################
# impl_candidate.tcl — one staged implementation candidate for xcvu9p
#
# The build is deliberately split into stages so a long route can be retried
# from a checkpoint instead of re-running placement.  The host-side launcher
# tool/fpga/parallel_impl.py runs several candidates concurrently.
#
# Required environment:
#   CAND_STAGE              place | physopt | route | bitstream | full
#   CAND_TAG                short candidate name
#   CAND_OUT_DIR            output directory (created if absent)
#   CAND_CHECKPOINT         input DCP (opt, placed, physopt, or routed)
#
# Optional environment:
#   CAND_PLACE_DIRECTIVE    default ExtraTimingOpt
#   CAND_ROUTE_DIRECTIVE    default Default
#   CAND_PHYSOPT_DIRECTIVE  default Default
#   CAND_ULTRATHREADS       1 = place_design -ultrathreads (experimental;
#                           crashes in HAPLFSimPLFlow::collectControlSetInfo
#                           on this design/Vivado 2025.2; default 0)
#   CAND_ML                 on | off | hybrid (default on)
#   CAND_MAX_THREADS        general.maxThreads (default 8)
#   CAND_GATE_NS            post-route WNS gate (default -1.0)
#   CAND_BITSTREAM          output bitstream path
#
# Machine-readable output (also written to $CAND_OUT_DIR/result.env):
#   CAND_STATUS, CAND_STAGE, CAND_TAG, CAND_WNS, CAND_SECONDS,
#   CAND_UNROUTED, CAND_BITSTREAM
################################################################################

proc cand_env {name default} {
    if {[info exists ::env($name)] && $::env($name) ne ""} {
        return $::env($name)
    }
    return $default
}

proc cand_write_result {path pairs} {
    set fh [open $path w]
    foreach {k v} $pairs {
        puts $fh "$k=$v"
    }
    close $fh
}

proc cand_timing_wns {rpt} {
    if {![file exists $rpt]} { return "" }
    set fh [open $rpt r]
    set txt [read $fh]
    close $fh
    set wns ""
    if {[regexp {WNS\(ns\)[^\n]*TNS\(ns\)[^\n]*\n[^\n]*\n\s*([-+0-9.eE]+)} $txt -> w]} {
        set wns $w
    }
    return $wns
}

proc cand_set_ml {mode} {
    switch -- $mode {
        on {
            catch { set_param place.3d.congestionML 8193 }
            catch { set_param place.disableMlde 0 }
        }
        off {
            catch { set_param place.3d.congestionML 0 }
            catch { set_param place.disableMlde 1 }
        }
        hybrid {
            # Congestion model on, ML delay estimator off.
            catch { set_param place.3d.congestionML 8193 }
            catch { set_param place.disableMlde 1 }
        }
        default {
            puts "ERROR: CAND_ML must be on, off, or hybrid (got '$mode')"
            exit 1
        }
    }
    puts "CAND_ML_MODE=$mode"
}

set stage      [cand_env CAND_STAGE full]
set tag        [cand_env CAND_TAG candidate]
set out_dir    [file normalize [cand_env CAND_OUT_DIR [file join [pwd] build impl_candidate $tag]]]
set checkpoint [file normalize [cand_env CAND_CHECKPOINT ""]]
set place_dir  [cand_env CAND_PLACE_DIRECTIVE ExtraTimingOpt]
set route_dir  [cand_env CAND_ROUTE_DIRECTIVE Default]
set phys_dir   [cand_env CAND_PHYSOPT_DIRECTIVE Default]
set ultra      [cand_env CAND_ULTRATHREADS 0]
set ml         [cand_env CAND_ML on]
set threads    [cand_env CAND_MAX_THREADS 8]
set gate       [cand_env CAND_GATE_NS -1.0]
set bitstream  [file normalize [cand_env CAND_BITSTREAM [file join $out_dir candidate.bit]]]

file mkdir $out_dir
set result_file [file join $out_dir result.env]
set placed_dcp  [file join $out_dir placed.dcp]
set physopt_dcp [file join $out_dir physopt.dcp]
set routed_dcp  [file join $out_dir routed.dcp]

puts "CAND_TAG=$tag"
puts "CAND_STAGE=$stage"
puts "CAND_OUT_DIR=$out_dir"
puts "CAND_CHECKPOINT=$checkpoint"
puts "CAND_PLACE_DIRECTIVE=$place_dir"
puts "CAND_ROUTE_DIRECTIVE=$route_dir"
puts "CAND_PHYSOPT_DIRECTIVE=$phys_dir"
puts "CAND_ULTRATHREADS=$ultra"
puts "CAND_ML=$ml"
puts "CAND_MAX_THREADS=$threads"
flush stdout

if {$stage ni {place physopt route bitstream full}} {
    puts "ERROR: unknown CAND_STAGE '$stage'"
    exit 1
}

set need_place   [expr {$stage eq "place" || $stage eq "full"}]
set need_physopt [expr {$stage eq "physopt" || $stage eq "full" || ($stage eq "route" && $checkpoint eq $placed_dcp && [cand_env CAND_ROUTE_FROM_PLACED 0] == 1)}]
set need_route   [expr {$stage eq "route" || $stage eq "full"}]
set need_bit     [expr {$stage eq "bitstream" || $stage eq "route" || $stage eq "full"}]

if {!$need_place && $checkpoint eq ""} {
    puts "ERROR: CAND_CHECKPOINT is required for stage '$stage'"
    exit 1
}
if {$need_place && $checkpoint eq ""} {
    puts "ERROR: CAND_CHECKPOINT is required for placement"
    exit 1
}
if {![file exists $checkpoint]} {
    puts "ERROR: checkpoint not found: $checkpoint"
    exit 1
}

set t_start [clock seconds]
open_checkpoint $checkpoint
set_param general.maxThreads $threads
cand_set_ml $ml
puts "CAND_OPENED=$checkpoint"
flush stdout

# ── Placement ───────────────────────────────────────────────────────────────
if {$need_place} {
    set t0 [clock seconds]
    if {$ultra == 1} {
        puts "CAND_PLACE_CMD=place_design -directive $place_dir -ultrathreads"
        place_design -directive $place_dir -ultrathreads
    } else {
        puts "CAND_PLACE_CMD=place_design -directive $place_dir"
        place_design -directive $place_dir
    }
    set t1 [clock seconds]
    puts "CAND_PLACE_SECONDS=[expr {$t1 - $t0}]"
    write_checkpoint -force $placed_dcp
    report_timing_summary -max_paths 10 -file [file join $out_dir post_place_timing.rpt]
    set place_wns [cand_timing_wns [file join $out_dir post_place_timing.rpt]]
    puts "CAND_POSTPLACE_WNS=$place_wns"
    cand_write_result $result_file [list \
        CAND_STATUS in_progress CAND_STAGE place CAND_TAG $tag \
        CAND_WNS $place_wns CAND_POSTPLACE_WNS $place_wns \
        CAND_PLACE_SECONDS [expr {$t1 - $t0}] CAND_PLACED_DCP $placed_dcp]
    flush stdout
    if {$stage eq "place"} {
        cand_write_result $result_file [list \
            CAND_STATUS ok CAND_STAGE place CAND_TAG $tag \
            CAND_WNS $place_wns CAND_POSTPLACE_WNS $place_wns \
            CAND_SECONDS [expr {[clock seconds] - $t_start}] CAND_PLACED_DCP $placed_dcp]
        puts "CAND_STATUS=ok"
        puts "CAND_DONE=$tag"
        exit 0
    }
}

# ── Physical optimization ───────────────────────────────────────────────────
if {$need_physopt} {
    set t0 [clock seconds]
    puts "CAND_PHYSOPT_CMD=phys_opt_design -directive $phys_dir"
    phys_opt_design -directive $phys_dir
    set t1 [clock seconds]
    puts "CAND_PHYSOPT_SECONDS=[expr {$t1 - $t0}]"
    write_checkpoint -force $physopt_dcp
    report_timing_summary -max_paths 10 -file [file join $out_dir post_physopt_timing.rpt]
    set phys_wns [cand_timing_wns [file join $out_dir post_physopt_timing.rpt]]
    puts "CAND_POSTPHYSOPT_WNS=$phys_wns"
    cand_write_result $result_file [list \
        CAND_STATUS in_progress CAND_STAGE physopt CAND_TAG $tag \
        CAND_WNS $phys_wns CAND_POSTPLACE_WNS $phys_wns \
        CAND_PHYSOPT_SECONDS [expr {$t1 - $t0}] CAND_PHYSOPT_DCP $physopt_dcp]
    flush stdout
    if {$stage eq "physopt"} {
        cand_write_result $result_file [list \
            CAND_STATUS ok CAND_STAGE physopt CAND_TAG $tag \
            CAND_WNS $phys_wns CAND_POSTPHYSOPT_WNS $phys_wns \
            CAND_SECONDS [expr {[clock seconds] - $t_start}] CAND_PHYSOPT_DCP $physopt_dcp]
        puts "CAND_STATUS=ok"
        puts "CAND_DONE=$tag"
        exit 0
    }
}

# ── Routing and bitstream ───────────────────────────────────────────────────
if {$need_route} {
    set t0 [clock seconds]
    puts "CAND_ROUTE_CMD=route_design -directive $route_dir"
    route_design -directive $route_dir
    set t1 [clock seconds]
    puts "CAND_ROUTE_SECONDS=[expr {$t1 - $t0}]"

    set unrouted [get_property UNROUTED_NETS [current_design]]
    set n_unrouted [llength $unrouted]
    puts "CAND_UNROUTED=$n_unrouted"

    write_checkpoint -force $routed_dcp
    report_timing_summary -max_paths 20 -file [file join $out_dir post_route_timing.rpt]
    report_drc -file [file join $out_dir post_route_drc.rpt]
    set route_wns [cand_timing_wns [file join $out_dir post_route_timing.rpt]]
    puts "CAND_POSTROUTE_WNS=$route_wns"

    if {$n_unrouted != 0} {
        cand_write_result $result_file [list \
            CAND_STATUS unrouted CAND_STAGE route CAND_TAG $tag \
            CAND_WNS $route_wns CAND_UNROUTED $n_unrouted \
            CAND_SECONDS [expr {[clock seconds] - $t_start}] CAND_ROUTED_DCP $routed_dcp]
        puts "CAND_STATUS=unrouted"
        exit 1
    }
    if {$route_wns eq "" || ![string is double -strict $route_wns]} {
        cand_write_result $result_file [list \
            CAND_STATUS no_timing CAND_STAGE route CAND_TAG $tag \
            CAND_UNROUTED $n_unrouted \
            CAND_SECONDS [expr {[clock seconds] - $t_start}] CAND_ROUTED_DCP $routed_dcp]
        puts "CAND_STATUS=no_timing"
        exit 1
    }
    if {$route_wns < $gate} {
        cand_write_result $result_file [list \
            CAND_STATUS wns_gate CAND_STAGE route CAND_TAG $tag \
            CAND_WNS $route_wns CAND_UNROUTED $n_unrouted \
            CAND_SECONDS [expr {[clock seconds] - $t_start}] CAND_ROUTED_DCP $routed_dcp]
        puts "CAND_STATUS=wns_gate"
        exit 1
    }
    cand_write_result $result_file [list \
        CAND_STATUS in_progress CAND_STAGE route CAND_TAG $tag \
        CAND_WNS $route_wns CAND_UNROUTED $n_unrouted \
        CAND_ROUTE_SECONDS [expr {$t1 - $t0}] CAND_ROUTED_DCP $routed_dcp]
    flush stdout
}

if {$need_bit} {
    puts "CAND_BITSTREAM=$bitstream"
    write_bitstream -force $bitstream
}

set final_wns ""
if {[info exists route_wns]} { set final_wns $route_wns }
set final_unrouted ""
if {[info exists n_unrouted]} { set final_unrouted $n_unrouted }
cand_write_result $result_file [list \
    CAND_STATUS ok CAND_STAGE $stage CAND_TAG $tag \
    CAND_WNS $final_wns \
    CAND_UNROUTED $final_unrouted \
    CAND_SECONDS [expr {[clock seconds] - $t_start}] \
    CAND_BITSTREAM $bitstream \
    CAND_PLACED_DCP [file join $out_dir placed.dcp] \
    CAND_PHYSOPT_DCP [file join $out_dir physopt.dcp] \
    CAND_ROUTED_DCP [file join $out_dir routed.dcp]]
puts "CAND_STATUS=ok"
puts "CAND_DONE=$tag"
exit 0
