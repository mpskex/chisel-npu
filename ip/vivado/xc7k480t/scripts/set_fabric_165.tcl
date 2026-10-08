################################################################################
# set_fabric_165.tcl — reconfigure clk_wiz_fabric for a 165 MHz fabric clock.
# v3: reset + regenerate through the parent BD (BD-nested IPs cannot be reset
# directly; v1 silently no-op'd and v2 errored).
################################################################################
set SCRIPT_DIR [file normalize [file dirname [info script]]]
set MIGRATE    [file normalize $SCRIPT_DIR/..]
source [file join $SCRIPT_DIR migrate_lib.tcl]

open_ref_project
open_bd_design [get_files {*/top.bd}]

set_property -dict [list CONFIG.CLKOUT1_REQUESTED_OUT_FREQ {165.000}] \
    [get_bd_cells clk_wiz_fabric]
validate_bd_design
save_bd_design

set bd_file [get_files {*/top.bd}]
reset_target all $bd_file
generate_target all $bd_file
puts "INFO: BD CLKOUT1 = [get_property CONFIG.CLKOUT1_REQUESTED_OUT_FREQ [get_bd_cells clk_wiz_fabric]] MHz"
puts "*** set_fabric_165 COMPLETE ***"
