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
upload_hw_ila_data $ila
write_hw_ila_data -file {__OUT__}
puts CAPTURE_DONE
