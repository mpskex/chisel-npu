################################################################################
# postroute_physopt.tcl — open the routed impl_1 DCP, run post-route phys_opt,
# re-time, and (if improved) write the bitstream.  Fast iteration on the last
# ~1.5 ns of setup slack without re-synthesis.
################################################################################
set SCRIPT_DIR [file normalize [file dirname [info script]]]
set XCVU9P     [file normalize $SCRIPT_DIR/..]
set PROJ       [file join $XCVU9P prj prj.xpr]
set BIT_DST    [file join $XCVU9P top_npu_vu9p.bit]

open_project $PROJ
open_run impl_1
puts "PHYSOPT_START"
phys_opt_design -directive AggressiveExplore
puts "PHYSOPT_DONE"
report_timing_summary -max_paths 5 -file [file join $XCVU9P prj prj.runs impl_1 hw_platform_timing_postphys.rpt]
report_utilization -file [file join $XCVU9P prj prj.runs impl_1 hw_platform_util_postphys.rpt]
write_bitstream -force [file join $XCVU9P prj prj.runs impl_1 hw_platform_postphys.bit]
file copy -force [file join $XCVU9P prj prj.runs impl_1 hw_platform_postphys.bit] $BIT_DST
puts "BIT=$BIT_DST"
puts "POSTROUTE_PHYSOPT_DONE"
