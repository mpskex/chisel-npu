open_hw_manager
connect_hw_server -url 127.0.0.1:3121
current_hw_target [get_hw_targets */xilinx_tcf/Xilinx/*]
open_hw_target
current_hw_device [get_hw_devices xc7k480t_0]
refresh_hw_device -update_hw_probes false [current_hw_device]
set_property PROBES.FILE      {__LTX__} [current_hw_device]
set_property FULL_PROBES.FILE {__LTX__} [current_hw_device]
refresh_hw_device [current_hw_device]
set ila [lindex [get_hw_ilas -of_objects [current_hw_device]] 0]
set_property CONTROL.DATA_DEPTH       4096 $ila
set_property CONTROL.TRIGGER_POSITION 1024 $ila
set c [get_hw_probes -of_objects $ila -filter {NAME =~ *dclct/cnt}]
set_property TRIGGER_COMPARE_VALUE eq4'b1000 [lindex $c 0]
run_hw_ila $ila
