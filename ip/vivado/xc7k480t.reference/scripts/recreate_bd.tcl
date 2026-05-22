
################################################################
# This is a generated script based on design: top
#
# Though there are limitations about the generated script,
# the main purpose of this utility is to make learning
# IP Integrator Tcl commands easier.
################################################################

namespace eval _tcl {
proc get_script_folder {} {
   set script_path [file normalize [info script]]
   set script_folder [file dirname $script_path]
   return $script_folder
}
}
variable script_folder
set script_folder [_tcl::get_script_folder]

################################################################
# Check if script is running in correct Vivado version.
################################################################
set scripts_vivado_version 2025.2
set current_vivado_version [version -short]

if { [string first $scripts_vivado_version $current_vivado_version] == -1 } {
   puts ""
   if { [string compare $scripts_vivado_version $current_vivado_version] > 0 } {
      catch {common::send_gid_msg -ssname BD::TCL -id 2042 -severity "ERROR" " This script was generated using Vivado <$scripts_vivado_version> and is being run in <$current_vivado_version> of Vivado. Sourcing the script failed since it was created with a future version of Vivado."}

   } else {
     catch {common::send_gid_msg -ssname BD::TCL -id 2041 -severity "ERROR" "This script was generated using Vivado <$scripts_vivado_version> and is being run in <$current_vivado_version> of Vivado. Please run the script in Vivado <$scripts_vivado_version> then open the design in Vivado <$current_vivado_version>. Upgrade the design by running \"Tools => Report => Report IP Status...\", then run write_bd_tcl to create an updated script."}

   }

   return 1
}

################################################################
# START
################################################################

# To test this script, run the following commands from Vivado Tcl console:
# source top_script.tcl


# The design that will be created by this Tcl script contains the following 
# module references:
# npu_ctrl_lite, npu_dma_master

# Please add the sources of those modules before sourcing this Tcl script.

# If there is no project opened, this script will create a
# project, but make sure you do not have an existing project
# <./myproj/project_1.xpr> in the current working folder.

set list_projs [get_projects -quiet]
if { $list_projs eq "" } {
   create_project project_1 myproj -part xc7k480tffg1156-2
}


# CHANGE DESIGN NAME HERE
variable design_name
set design_name top

# If you do not already have an existing IP Integrator design open,
# you can create a design using the following command:
#    create_bd_design $design_name

# Creating design if needed
set errMsg ""
set nRet 0

set cur_design [current_bd_design -quiet]
set list_cells [get_bd_cells -quiet]

if { ${design_name} eq "" } {
   # USE CASES:
   #    1) Design_name not set

   set errMsg "Please set the variable <design_name> to a non-empty value."
   set nRet 1

} elseif { ${cur_design} ne "" && ${list_cells} eq "" } {
   # USE CASES:
   #    2): Current design opened AND is empty AND names same.
   #    3): Current design opened AND is empty AND names diff; design_name NOT in project.
   #    4): Current design opened AND is empty AND names diff; design_name exists in project.

   if { $cur_design ne $design_name } {
      common::send_gid_msg -ssname BD::TCL -id 2001 -severity "INFO" "Changing value of <design_name> from <$design_name> to <$cur_design> since current design is empty."
      set design_name [get_property NAME $cur_design]
   }
   common::send_gid_msg -ssname BD::TCL -id 2002 -severity "INFO" "Constructing design in IPI design <$cur_design>..."

} elseif { ${cur_design} ne "" && $list_cells ne "" && $cur_design eq $design_name } {
   # USE CASES:
   #    5) Current design opened AND has components AND same names.

   set errMsg "Design <$design_name> already exists in your project, please set the variable <design_name> to another value."
   set nRet 1
} elseif { [get_files -quiet ${design_name}.bd] ne "" } {
   # USE CASES: 
   #    6) Current opened design, has components, but diff names, design_name exists in project.
   #    7) No opened design, design_name exists in project.

   set errMsg "Design <$design_name> already exists in your project, please set the variable <design_name> to another value."
   set nRet 2

} else {
   # USE CASES:
   #    8) No opened design, design_name not in project.
   #    9) Current opened design, has components, but diff names, design_name not in project.

   common::send_gid_msg -ssname BD::TCL -id 2003 -severity "INFO" "Currently there is no design <$design_name> in project, so creating one..."

   create_bd_design $design_name

   common::send_gid_msg -ssname BD::TCL -id 2004 -severity "INFO" "Making design <$design_name> as current_bd_design."
   current_bd_design $design_name

}

common::send_gid_msg -ssname BD::TCL -id 2005 -severity "INFO" "Currently the variable <design_name> is equal to \"$design_name\"."

if { $nRet != 0 } {
   catch {common::send_gid_msg -ssname BD::TCL -id 2006 -severity "ERROR" $errMsg}
   return $nRet
}

set bCheckIPsPassed 1
##################################################################
# CHECK IPs
##################################################################
set bCheckIPs 1
if { $bCheckIPs == 1 } {
   set list_check_ips "\ 
xilinx.com:ip:mig_7series:4.2\
xilinx.com:ip:xdma:4.2\
xilinx.com:ip:util_ds_buf:2.2\
xilinx.com:ip:proc_sys_reset:5.0\
xilinx.com:ip:axi_vip:1.1\
xilinx.com:ip:axi_dwidth_converter:2.1\
xilinx.com:ip:axi_protocol_converter:2.1\
xilinx.com:ip:clk_wiz:6.0\
xilinx.com:ip:axi_clock_converter:2.1\
"

   set list_ips_missing ""
   common::send_gid_msg -ssname BD::TCL -id 2011 -severity "INFO" "Checking if the following IPs exist in the project's IP catalog: $list_check_ips ."

   foreach ip_vlnv $list_check_ips {
      set ip_obj [get_ipdefs -all $ip_vlnv]
      if { $ip_obj eq "" } {
         lappend list_ips_missing $ip_vlnv
      }
   }

   if { $list_ips_missing ne "" } {
      catch {common::send_gid_msg -ssname BD::TCL -id 2012 -severity "ERROR" "The following IPs are not found in the IP Catalog:\n  $list_ips_missing\n\nResolution: Please add the repository containing the IP(s) to the project." }
      set bCheckIPsPassed 0
   }

}

##################################################################
# CHECK Modules
##################################################################
set bCheckModules 1
if { $bCheckModules == 1 } {
   set list_check_mods "\ 
npu_ctrl_lite\
npu_dma_master\
"

   set list_mods_missing ""
   common::send_gid_msg -ssname BD::TCL -id 2020 -severity "INFO" "Checking if the following modules exist in the project's sources: $list_check_mods ."

   foreach mod_vlnv $list_check_mods {
      if { [can_resolve_reference $mod_vlnv] == 0 } {
         lappend list_mods_missing $mod_vlnv
      }
   }

   if { $list_mods_missing ne "" } {
      catch {common::send_gid_msg -ssname BD::TCL -id 2021 -severity "ERROR" "The following module(s) are not found in the project: $list_mods_missing" }
      common::send_gid_msg -ssname BD::TCL -id 2022 -severity "INFO" "Please add source files for the missing module(s) above."
      set bCheckIPsPassed 0
   }
}

if { $bCheckIPsPassed != 1 } {
  common::send_gid_msg -ssname BD::TCL -id 2023 -severity "WARNING" "Will not continue with creation of design due to the error(s) above."
  return 3
}

##################################################################
# DESIGN PROCs
##################################################################



# Procedure to create entire design; Provide argument to make
# procedure reusable. If parentCell is "", will use root.
proc create_root_design { parentCell } {

  variable script_folder
  variable design_name

  if { $parentCell eq "" } {
     set parentCell [get_bd_cells /]
  }

  # Get object for parentCell
  set parentObj [get_bd_cells $parentCell]
  if { $parentObj == "" } {
     catch {common::send_gid_msg -ssname BD::TCL -id 2090 -severity "ERROR" "Unable to find parent cell <$parentCell>!"}
     return
  }

  # Make sure parentObj is hier blk
  set parentType [get_property TYPE $parentObj]
  if { $parentType ne "hier" } {
     catch {common::send_gid_msg -ssname BD::TCL -id 2091 -severity "ERROR" "Parent <$parentObj> has TYPE = <$parentType>. Expected to be <hier>."}
     return
  }

  # Save current instance; Restore later
  set oldCurInst [current_bd_instance .]

  # Set parent object as current
  current_bd_instance $parentObj


  # Create interface ports
  set pcie_7x_mgt_rtl_0 [ create_bd_intf_port -mode Master -vlnv xilinx.com:interface:pcie_7x_mgt_rtl:1.0 pcie_7x_mgt_rtl_0 ]

  set diff_clock_rtl_0 [ create_bd_intf_port -mode Slave -vlnv xilinx.com:interface:diff_clock_rtl:1.0 diff_clock_rtl_0 ]
  set_property -dict [ list \
   CONFIG.FREQ_HZ {100000000} \
   ] $diff_clock_rtl_0

  set C0_SYS_CLK_0 [ create_bd_intf_port -mode Slave -vlnv xilinx.com:interface:diff_clock_rtl:1.0 C0_SYS_CLK_0 ]

  set C1_SYS_CLK_0 [ create_bd_intf_port -mode Slave -vlnv xilinx.com:interface:diff_clock_rtl:1.0 C1_SYS_CLK_0 ]

  set C0_DDR3_0 [ create_bd_intf_port -mode Master -vlnv xilinx.com:interface:ddrx_rtl:1.0 C0_DDR3_0 ]

  set C1_DDR3_0 [ create_bd_intf_port -mode Master -vlnv xilinx.com:interface:ddrx_rtl:1.0 C1_DDR3_0 ]


  # Create ports
  set reset_rtl_0 [ create_bd_port -dir I -type rst reset_rtl_0 ]
  set_property -dict [ list \
   CONFIG.POLARITY {ACTIVE_LOW} \
 ] $reset_rtl_0

  # Create instance: mig_7series_0, and set properties
  set mig_7series_0 [ create_bd_cell -type ip -vlnv xilinx.com:ip:mig_7series:4.2 mig_7series_0 ]

  # Copy the PRJ File for MIG
  set folder $script_folder
  set mig_file "top_mig_7series_0_0_mig_a.prj"
  set mig_file_path [glob $folder/$mig_file]
  if { [file exists "$mig_file_path"] == 1 } { 
     set str_mig_folder [get_property IP_DIR [ get_ips [ get_property CONFIG.Component_Name $mig_7series_0 ] ] ]
     common::send_gid_msg -ssname BD::TCL -id 2080 -severity "INFO" "Copying <$mig_file_path> to <$str_mig_folder/mig_a.prj>..."
     file mkdir "$str_mig_folder"
     file copy -force $mig_file_path "$str_mig_folder/mig_a.prj"
  } else {
     catch {common::send_gid_msg -ssname BD::TCL -id 2081 -severity "ERROR" "Unable to find the PRJ file <$mig_file>!"}
  }

  set_property -dict [list \
    CONFIG.BOARD_MIG_PARAM {Custom} \
    CONFIG.RESET_BOARD_INTERFACE {Custom} \
    CONFIG.XML_INPUT_FILE {mig_a.prj} \
  ] $mig_7series_0


  # Create instance: xdma_0, and set properties
  set xdma_0 [ create_bd_cell -type ip -vlnv xilinx.com:ip:xdma:4.2 xdma_0 ]
  set_property -dict [list \
    CONFIG.PF0_DEVICE_ID_mqdma {9028} \
    CONFIG.PF0_SRIOV_VF_DEVICE_ID {A038} \
    CONFIG.PF2_DEVICE_ID_mqdma {9228} \
    CONFIG.PF3_DEVICE_ID_mqdma {9328} \
    CONFIG.axi_bypass_64bit_en {true} \
    CONFIG.axi_data_width {128_bit} \
    CONFIG.axist_bypass_en {true} \
    CONFIG.axisten_freq {250} \
    CONFIG.enable_gtwizard {false} \
    CONFIG.mode_selection {Basic} \
    CONFIG.pcie_extended_tag {true} \
    CONFIG.pcie_id_if {false} \
    CONFIG.pf0_base_class_menu {Memory_controller} \
    CONFIG.pf0_class_code_base {05} \
    CONFIG.pf0_class_code_interface {00} \
    CONFIG.pf0_class_code_sub {80} \
    CONFIG.pf0_device_id {7028} \
    CONFIG.pf0_interrupt_pin {NONE} \
    CONFIG.pf0_msix_cap_pba_offset {00008FE0} \
    CONFIG.pf0_msix_cap_table_offset {00008000} \
    CONFIG.pf0_msix_cap_table_size {01F} \
    CONFIG.pf0_msix_enabled {true} \
    CONFIG.pf0_sub_class_interface_menu {Other_memory_controller} \
    CONFIG.pl_link_cap_max_link_speed {5.0_GT/s} \
    CONFIG.pl_link_cap_max_link_width {X8} \
    CONFIG.xdma_axi_intf_mm {AXI_Memory_Mapped} \
    CONFIG.xdma_pcie_64bit_en {false} \
    CONFIG.xdma_rnum_chnl {2} \
    CONFIG.xdma_wnum_chnl {2} \
  ] $xdma_0


  # Create instance: util_ds_buf, and set properties
  set util_ds_buf [ create_bd_cell -type ip -vlnv xilinx.com:ip:util_ds_buf:2.2 util_ds_buf ]
  set_property CONFIG.C_BUF_TYPE {IBUFDSGTE} $util_ds_buf


  # Create instance: rst_mig_7series_0_133M, and set properties
  set rst_mig_7series_0_133M [ create_bd_cell -type ip -vlnv xilinx.com:ip:proc_sys_reset:5.0 rst_mig_7series_0_133M ]

  # Create instance: rst_mig_7series_0_133M_1, and set properties
  set rst_mig_7series_0_133M_1 [ create_bd_cell -type ip -vlnv xilinx.com:ip:proc_sys_reset:5.0 rst_mig_7series_0_133M_1 ]

  # Create instance: mig_c0_ctrl_vip, and set properties
  set mig_c0_ctrl_vip [ create_bd_cell -type ip -vlnv xilinx.com:ip:axi_vip:1.1 mig_c0_ctrl_vip ]
  set_property -dict [list \
    CONFIG.ADDR_WIDTH {32} \
    CONFIG.DATA_WIDTH {32} \
    CONFIG.HAS_BRESP {1} \
    CONFIG.HAS_PROT {1} \
    CONFIG.HAS_RRESP {1} \
    CONFIG.HAS_WSTRB {1} \
    CONFIG.INTERFACE_MODE {MASTER} \
    CONFIG.PROTOCOL {AXI4LITE} \
    CONFIG.READ_WRITE_MODE {READ_WRITE} \
  ] $mig_c0_ctrl_vip


  # Create instance: mig_c1_ctrl_vip, and set properties
  set mig_c1_ctrl_vip [ create_bd_cell -type ip -vlnv xilinx.com:ip:axi_vip:1.1 mig_c1_ctrl_vip ]
  set_property -dict [list \
    CONFIG.ADDR_WIDTH {32} \
    CONFIG.DATA_WIDTH {32} \
    CONFIG.HAS_BRESP {1} \
    CONFIG.HAS_PROT {1} \
    CONFIG.HAS_RRESP {1} \
    CONFIG.HAS_WSTRB {1} \
    CONFIG.INTERFACE_MODE {MASTER} \
    CONFIG.PROTOCOL {AXI4LITE} \
    CONFIG.READ_WRITE_MODE {READ_WRITE} \
  ] $mig_c1_ctrl_vip


  # Create instance: byp_dw, and set properties
  set byp_dw [ create_bd_cell -type ip -vlnv xilinx.com:ip:axi_dwidth_converter:2.1 byp_dw ]
  set_property -dict [list \
    CONFIG.MI_DATA_WIDTH {32} \
    CONFIG.SI_DATA_WIDTH {128} \
    CONFIG.SI_ID_WIDTH {4} \
  ] $byp_dw


  # Create instance: byp_pc, and set properties
  set byp_pc [ create_bd_cell -type ip -vlnv xilinx.com:ip:axi_protocol_converter:2.1 byp_pc ]
  set_property -dict [list \
    CONFIG.DATA_WIDTH {32} \
    CONFIG.ID_WIDTH {4} \
    CONFIG.MI_PROTOCOL {AXI4LITE} \
    CONFIG.SI_PROTOCOL {AXI4} \
  ] $byp_pc


  # Create instance: ctrl_lite, and set properties
  set block_name npu_ctrl_lite
  set block_cell_name ctrl_lite
  if { [catch {set ctrl_lite [create_bd_cell -type module -reference $block_name $block_cell_name] } errmsg] } {
     catch {common::send_gid_msg -ssname BD::TCL -id 2095 -severity "ERROR" "Unable to add referenced block <$block_name>. Please add the files for ${block_name}'s definition into the project."}
     return 1
   } elseif { $ctrl_lite eq "" } {
     catch {common::send_gid_msg -ssname BD::TCL -id 2096 -severity "ERROR" "Unable to referenced block <$block_name>. Please add the files for ${block_name}'s definition into the project."}
     return 1
   }
  
  # Create instance: clk_wiz_fabric, and set properties
  set clk_wiz_fabric [ create_bd_cell -type ip -vlnv xilinx.com:ip:clk_wiz:6.0 clk_wiz_fabric ]
  set_property -dict [list \
    CONFIG.CLKOUT1_REQUESTED_OUT_FREQ {200.000} \
    CONFIG.PRIMITIVE {MMCM} \
    CONFIG.PRIM_IN_FREQ {250.000} \
    CONFIG.RESET_TYPE {ACTIVE_LOW} \
    CONFIG.USE_LOCKED {true} \
    CONFIG.USE_RESET {true} \
  ] $clk_wiz_fabric


  # Create instance: rst_fabric_200M, and set properties
  set rst_fabric_200M [ create_bd_cell -type ip -vlnv xilinx.com:ip:proc_sys_reset:5.0 rst_fabric_200M ]

  # Create instance: axi_clkconv_byp, and set properties
  set axi_clkconv_byp [ create_bd_cell -type ip -vlnv xilinx.com:ip:axi_clock_converter:2.1 axi_clkconv_byp ]

  # Create instance: axi_cc_xdma_in, and set properties
  set axi_cc_xdma_in [ create_bd_cell -type ip -vlnv xilinx.com:ip:axi_clock_converter:2.1 axi_cc_xdma_in ]

  # Create instance: axi_clkconv_xdma, and set properties
  set axi_clkconv_xdma [ create_bd_cell -type ip -vlnv xilinx.com:ip:axi_clock_converter:2.1 axi_clkconv_xdma ]

  # Create instance: axi_dwidth_xdma, and set properties
  set axi_dwidth_xdma [ create_bd_cell -type ip -vlnv xilinx.com:ip:axi_dwidth_converter:2.1 axi_dwidth_xdma ]
  set_property -dict [list \
    CONFIG.MI_DATA_WIDTH {512} \
    CONFIG.SI_DATA_WIDTH {128} \
    CONFIG.SI_ID_WIDTH {4} \
  ] $axi_dwidth_xdma


  # Create instance: axi_clkconv_npu, and set properties
  set axi_clkconv_npu [ create_bd_cell -type ip -vlnv xilinx.com:ip:axi_clock_converter:2.1 axi_clkconv_npu ]

  # Create instance: axi_dwidth_npu, and set properties
  set axi_dwidth_npu [ create_bd_cell -type ip -vlnv xilinx.com:ip:axi_dwidth_converter:2.1 axi_dwidth_npu ]
  set_property -dict [list \
    CONFIG.MI_DATA_WIDTH {512} \
    CONFIG.SI_DATA_WIDTH {128} \
    CONFIG.SI_ID_WIDTH {4} \
  ] $axi_dwidth_npu


  # Create instance: dma_master, and set properties
  set block_name npu_dma_master
  set block_cell_name dma_master
  if { [catch {set dma_master [create_bd_cell -type module -reference $block_name $block_cell_name] } errmsg] } {
     catch {common::send_gid_msg -ssname BD::TCL -id 2095 -severity "ERROR" "Unable to add referenced block <$block_name>. Please add the files for ${block_name}'s definition into the project."}
     return 1
   } elseif { $dma_master eq "" } {
     catch {common::send_gid_msg -ssname BD::TCL -id 2096 -severity "ERROR" "Unable to referenced block <$block_name>. Please add the files for ${block_name}'s definition into the project."}
     return 1
   }
  
  # Create interface connections
  connect_bd_intf_net -intf_net C0_SYS_CLK_0_1 [get_bd_intf_ports C0_SYS_CLK_0] [get_bd_intf_pins mig_7series_0/C0_SYS_CLK]
  connect_bd_intf_net -intf_net C1_SYS_CLK_0_1 [get_bd_intf_ports C1_SYS_CLK_0] [get_bd_intf_pins mig_7series_0/C1_SYS_CLK]
  connect_bd_intf_net -intf_net axi_cc_xdma_in_M_AXI [get_bd_intf_pins axi_cc_xdma_in/M_AXI] [get_bd_intf_pins axi_clkconv_xdma/S_AXI]
  connect_bd_intf_net -intf_net axi_clkconv_byp_M_AXI [get_bd_intf_pins axi_clkconv_byp/M_AXI] [get_bd_intf_pins byp_dw/S_AXI]
  connect_bd_intf_net -intf_net axi_clkconv_npu_M_AXI [get_bd_intf_pins axi_clkconv_npu/M_AXI] [get_bd_intf_pins axi_dwidth_npu/S_AXI]
  connect_bd_intf_net -intf_net axi_clkconv_xdma_M_AXI [get_bd_intf_pins axi_clkconv_xdma/M_AXI] [get_bd_intf_pins axi_dwidth_xdma/S_AXI]
  connect_bd_intf_net -intf_net axi_dwidth_xdma_M_AXI [get_bd_intf_pins axi_dwidth_xdma/M_AXI] [get_bd_intf_pins mig_7series_0/S0_AXI]
  connect_bd_intf_net -intf_net byp_dw_M_AXI [get_bd_intf_pins byp_dw/M_AXI] [get_bd_intf_pins byp_pc/S_AXI]
  connect_bd_intf_net -intf_net diff_clock_rtl_0_1 [get_bd_intf_ports diff_clock_rtl_0] [get_bd_intf_pins util_ds_buf/CLK_IN_D]
  connect_bd_intf_net -intf_net dma_master_m_axi [get_bd_intf_pins dma_master/m_axi] [get_bd_intf_pins axi_clkconv_npu/S_AXI]
  connect_bd_intf_net -intf_net mig_7series_0_C0_DDR3 [get_bd_intf_ports C0_DDR3_0] [get_bd_intf_pins mig_7series_0/C0_DDR3]
  connect_bd_intf_net -intf_net mig_7series_0_C1_DDR3 [get_bd_intf_ports C1_DDR3_0] [get_bd_intf_pins mig_7series_0/C1_DDR3]
  connect_bd_intf_net -intf_net mig_c0_ctrl_vip_M_AXI [get_bd_intf_pins mig_c0_ctrl_vip/M_AXI] [get_bd_intf_pins mig_7series_0/S0_AXI_CTRL]
  connect_bd_intf_net -intf_net mig_c1_ctrl_vip_M_AXI [get_bd_intf_pins mig_c1_ctrl_vip/M_AXI] [get_bd_intf_pins mig_7series_0/S1_AXI_CTRL]
  connect_bd_intf_net -intf_net mig_c1_data_vip_M_AXI [get_bd_intf_pins axi_dwidth_npu/M_AXI] [get_bd_intf_pins mig_7series_0/S1_AXI]
  connect_bd_intf_net -intf_net xdma_0_M_AXI [get_bd_intf_pins xdma_0/M_AXI] [get_bd_intf_pins axi_cc_xdma_in/S_AXI]
  connect_bd_intf_net -intf_net xdma_0_M_AXI_BYPASS [get_bd_intf_pins xdma_0/M_AXI_BYPASS] [get_bd_intf_pins axi_clkconv_byp/S_AXI]
  connect_bd_intf_net -intf_net xdma_0_pcie_mgt [get_bd_intf_ports pcie_7x_mgt_rtl_0] [get_bd_intf_pins xdma_0/pcie_mgt]

  # Create port connections
  connect_bd_net -net Net  [get_bd_pins mig_7series_0/c1_ui_clk] \
  [get_bd_pins rst_mig_7series_0_133M_1/slowest_sync_clk] \
  [get_bd_pins mig_c1_ctrl_vip/aclk] \
  [get_bd_pins axi_clkconv_npu/m_axi_aclk] \
  [get_bd_pins axi_dwidth_npu/s_axi_aclk]
  connect_bd_net -net byp_pc_m_axi_araddr  [get_bd_pins byp_pc/m_axi_araddr] \
  [get_bd_pins ctrl_lite/s_axil_araddr]
  connect_bd_net -net byp_pc_m_axi_arprot  [get_bd_pins byp_pc/m_axi_arprot] \
  [get_bd_pins ctrl_lite/s_axil_arprot]
  connect_bd_net -net byp_pc_m_axi_arvalid  [get_bd_pins byp_pc/m_axi_arvalid] \
  [get_bd_pins ctrl_lite/s_axil_arvalid]
  connect_bd_net -net byp_pc_m_axi_awaddr  [get_bd_pins byp_pc/m_axi_awaddr] \
  [get_bd_pins ctrl_lite/s_axil_awaddr]
  connect_bd_net -net byp_pc_m_axi_awprot  [get_bd_pins byp_pc/m_axi_awprot] \
  [get_bd_pins ctrl_lite/s_axil_awprot]
  connect_bd_net -net byp_pc_m_axi_awvalid  [get_bd_pins byp_pc/m_axi_awvalid] \
  [get_bd_pins ctrl_lite/s_axil_awvalid]
  connect_bd_net -net byp_pc_m_axi_bready  [get_bd_pins byp_pc/m_axi_bready] \
  [get_bd_pins ctrl_lite/s_axil_bready]
  connect_bd_net -net byp_pc_m_axi_rready  [get_bd_pins byp_pc/m_axi_rready] \
  [get_bd_pins ctrl_lite/s_axil_rready]
  connect_bd_net -net byp_pc_m_axi_wdata  [get_bd_pins byp_pc/m_axi_wdata] \
  [get_bd_pins ctrl_lite/s_axil_wdata]
  connect_bd_net -net byp_pc_m_axi_wstrb  [get_bd_pins byp_pc/m_axi_wstrb] \
  [get_bd_pins ctrl_lite/s_axil_wstrb]
  connect_bd_net -net byp_pc_m_axi_wvalid  [get_bd_pins byp_pc/m_axi_wvalid] \
  [get_bd_pins ctrl_lite/s_axil_wvalid]
  connect_bd_net -net clk_wiz_fabric_clk_out1  [get_bd_pins clk_wiz_fabric/clk_out1] \
  [get_bd_pins rst_fabric_200M/slowest_sync_clk] \
  [get_bd_pins axi_clkconv_byp/m_axi_aclk] \
  [get_bd_pins byp_dw/s_axi_aclk] \
  [get_bd_pins byp_pc/aclk] \
  [get_bd_pins ctrl_lite/axi_aclk] \
  [get_bd_pins axi_cc_xdma_in/m_axi_aclk] \
  [get_bd_pins axi_clkconv_xdma/s_axi_aclk] \
  [get_bd_pins axi_clkconv_npu/s_axi_aclk] \
  [get_bd_pins dma_master/aclk]
  connect_bd_net -net clk_wiz_fabric_locked  [get_bd_pins clk_wiz_fabric/locked] \
  [get_bd_pins rst_fabric_200M/dcm_locked]
  connect_bd_net -net ctrl_busy_const_dout  [get_bd_pins dma_master/busy] \
  [get_bd_pins ctrl_lite/busy]
  connect_bd_net -net ctrl_done_const_dout  [get_bd_pins dma_master/done] \
  [get_bd_pins ctrl_lite/done]
  connect_bd_net -net ctrl_lite_s_axil_arready  [get_bd_pins ctrl_lite/s_axil_arready] \
  [get_bd_pins byp_pc/m_axi_arready]
  connect_bd_net -net ctrl_lite_s_axil_awready  [get_bd_pins ctrl_lite/s_axil_awready] \
  [get_bd_pins byp_pc/m_axi_awready]
  connect_bd_net -net ctrl_lite_s_axil_bresp  [get_bd_pins ctrl_lite/s_axil_bresp] \
  [get_bd_pins byp_pc/m_axi_bresp]
  connect_bd_net -net ctrl_lite_s_axil_bvalid  [get_bd_pins ctrl_lite/s_axil_bvalid] \
  [get_bd_pins byp_pc/m_axi_bvalid]
  connect_bd_net -net ctrl_lite_s_axil_rdata  [get_bd_pins ctrl_lite/s_axil_rdata] \
  [get_bd_pins byp_pc/m_axi_rdata]
  connect_bd_net -net ctrl_lite_s_axil_rresp  [get_bd_pins ctrl_lite/s_axil_rresp] \
  [get_bd_pins byp_pc/m_axi_rresp]
  connect_bd_net -net ctrl_lite_s_axil_rvalid  [get_bd_pins ctrl_lite/s_axil_rvalid] \
  [get_bd_pins byp_pc/m_axi_rvalid]
  connect_bd_net -net ctrl_lite_s_axil_wready  [get_bd_pins ctrl_lite/s_axil_wready] \
  [get_bd_pins byp_pc/m_axi_wready]
  connect_bd_net -net ctrl_lite_start  [get_bd_pins ctrl_lite/start] \
  [get_bd_pins dma_master/start]
  connect_bd_net -net mig_7series_0_c0_mmcm_locked  [get_bd_pins mig_7series_0/c0_mmcm_locked] \
  [get_bd_pins rst_mig_7series_0_133M/dcm_locked]
  connect_bd_net -net mig_7series_0_c0_ui_clk  [get_bd_pins mig_7series_0/c0_ui_clk] \
  [get_bd_pins rst_mig_7series_0_133M/slowest_sync_clk] \
  [get_bd_pins mig_c0_ctrl_vip/aclk] \
  [get_bd_pins axi_clkconv_xdma/m_axi_aclk] \
  [get_bd_pins axi_dwidth_xdma/s_axi_aclk]
  connect_bd_net -net mig_7series_0_c0_ui_clk_sync_rst  [get_bd_pins mig_7series_0/c0_ui_clk_sync_rst] \
  [get_bd_pins rst_mig_7series_0_133M/ext_reset_in]
  connect_bd_net -net mig_7series_0_c1_mmcm_locked  [get_bd_pins mig_7series_0/c1_mmcm_locked] \
  [get_bd_pins rst_mig_7series_0_133M_1/dcm_locked]
  connect_bd_net -net mig_7series_0_c1_ui_clk_sync_rst  [get_bd_pins mig_7series_0/c1_ui_clk_sync_rst] \
  [get_bd_pins rst_mig_7series_0_133M_1/ext_reset_in]
  connect_bd_net -net reset_rtl_0_1  [get_bd_ports reset_rtl_0] \
  [get_bd_pins mig_7series_0/sys_rst] \
  [get_bd_pins xdma_0/sys_rst_n] \
  [get_bd_pins rst_fabric_200M/ext_reset_in]
  connect_bd_net -net rst_fabric_200M_peripheral_aresetn  [get_bd_pins rst_fabric_200M/peripheral_aresetn] \
  [get_bd_pins axi_clkconv_byp/m_axi_aresetn] \
  [get_bd_pins byp_dw/s_axi_aresetn] \
  [get_bd_pins byp_pc/aresetn] \
  [get_bd_pins ctrl_lite/axi_aresetn] \
  [get_bd_pins axi_cc_xdma_in/m_axi_aresetn] \
  [get_bd_pins axi_clkconv_xdma/s_axi_aresetn] \
  [get_bd_pins axi_clkconv_npu/s_axi_aresetn] \
  [get_bd_pins dma_master/aresetn]
  connect_bd_net -net rst_mig_7series_0_133M_1_peripheral_aresetn  [get_bd_pins rst_mig_7series_0_133M_1/peripheral_aresetn] \
  [get_bd_pins mig_7series_0/c1_aresetn] \
  [get_bd_pins mig_c1_ctrl_vip/aresetn] \
  [get_bd_pins axi_clkconv_npu/m_axi_aresetn] \
  [get_bd_pins axi_dwidth_npu/s_axi_aresetn]
  connect_bd_net -net rst_mig_7series_0_133M_peripheral_aresetn  [get_bd_pins rst_mig_7series_0_133M/peripheral_aresetn] \
  [get_bd_pins mig_7series_0/c0_aresetn] \
  [get_bd_pins mig_c0_ctrl_vip/aresetn] \
  [get_bd_pins axi_clkconv_xdma/m_axi_aresetn] \
  [get_bd_pins axi_dwidth_xdma/s_axi_aresetn]
  connect_bd_net -net util_ds_buf_IBUF_OUT  [get_bd_pins util_ds_buf/IBUF_OUT] \
  [get_bd_pins xdma_0/sys_clk]
  connect_bd_net -net xdma_0_axi_aclk  [get_bd_pins xdma_0/axi_aclk] \
  [get_bd_pins clk_wiz_fabric/clk_in1] \
  [get_bd_pins axi_clkconv_byp/s_axi_aclk] \
  [get_bd_pins axi_cc_xdma_in/s_axi_aclk]
  connect_bd_net -net xdma_0_axi_aresetn  [get_bd_pins xdma_0/axi_aresetn] \
  [get_bd_pins clk_wiz_fabric/resetn] \
  [get_bd_pins axi_clkconv_byp/s_axi_aresetn] \
  [get_bd_pins axi_cc_xdma_in/s_axi_aresetn]

  # Create address segments

  # Exclude Address Segments
  # C0 is excluded from M_AXI (address space goes via M_AXI → axi_cc_xdma_in → ... → MIG C0)
  # but C1 may not be addressable from M_AXI (it's behind axi_dwidth_npu only via DMA master);
  # use catch to tolerate the BD 5-295 error on C1.
  catch { exclude_bd_addr_seg -offset 0x00000000 -range 0x80000000 -target_address_space [get_bd_addr_spaces xdma_0/M_AXI] [get_bd_addr_segs mig_7series_0/c0_memmap/c0_memaddr] }
  catch { exclude_bd_addr_seg -offset 0x80000000 -range 0x80000000 -target_address_space [get_bd_addr_spaces xdma_0/M_AXI] [get_bd_addr_segs mig_7series_0/c1_memmap/c1_memaddr] }


  # Restore current instance
  current_bd_instance $oldCurInst

  validate_bd_design
  save_bd_design
}
# End of create_root_design()


##################################################################
# MAIN FLOW
##################################################################

create_root_design ""


