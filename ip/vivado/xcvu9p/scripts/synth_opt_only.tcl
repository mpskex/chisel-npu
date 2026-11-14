################################################################################
# synth_opt_only.tcl — re-synthesize (with the updated hw_platform.v) and run
# opt_design, stopping before place.  Produces hw_platform_opt.dcp for the
# pblock/route flow.
################################################################################
set SCRIPT_DIR [file normalize [file dirname [info script]]]
set XCVU9P     [file normalize [file join $SCRIPT_DIR ..]]
set REPO_ROOT  [file normalize [file join $XCVU9P ../../..]]
set SRC_DIR    [file join $XCVU9P src]
set PROJ       [file join $XCVU9P prj prj.xpr]

open_project $PROJ

foreach f [list npu_engine_ctrl_lite.v npu_engine_subsys.v hw_platform.v] {
    set path [file join $SRC_DIR $f]
    if {[get_files -quiet -of_objects [get_filesets sources_1] $path] eq ""} {
        add_files -norecurse $path
    }
}
set top_sv [file join $REPO_ROOT top.sv]
set _existing_top [get_files -quiet -of_objects [get_filesets sources_1] $top_sv]
if {$_existing_top ne ""} { remove_files $_existing_top }
add_files -norecurse $top_sv
set_property top hw_platform [get_filesets sources_1]
update_compile_order -fileset sources_1

# Re-read hw_platform.v explicitly so the BUFG change is picked up.
set hp [file join $SRC_DIR hw_platform.v]
set _hp [get_files -quiet -of_objects [get_filesets sources_1] $hp]
if {$_hp ne ""} { remove_files $_hp }
add_files -norecurse $hp

if {[llength [get_runs -quiet synth_1]] > 0} {
    catch { set_property AUTO_INCREMENTAL_CHECKPOINT 0 [get_runs synth_1] }
    catch { set_property INCREMENTAL_CHECKPOINT "" [get_runs synth_1] }
}
reset_run synth_1
reset_run impl_1
launch_runs impl_1 -to_step opt_design -jobs 1
wait_on_run impl_1
set prog [get_property PROGRESS [get_runs impl_1]]
puts "OPT_PROGRESS=$prog STATUS=[get_property STATUS [get_runs impl_1]]"
if {$prog ne "100%"} { exit 1 }
puts "SYNTH_OPT_DONE"
