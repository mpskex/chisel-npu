################################################################################

# This XDC is used only for OOC mode of synthesis, implementation
# This constraints file contains default clock frequencies to be used during
# out-of-context flows such as OOC Synthesis and Hierarchical Designs.
# This constraints file is not used in normal top-down synthesis (default flow
# of Vivado)
################################################################################
create_clock -name ACLK -period 4 [get_ports ACLK]
create_clock -name M00_ACLK -period 4 [get_ports M00_ACLK]
create_clock -name M01_ACLK -period 4 [get_ports M01_ACLK]
create_clock -name M02_ACLK -period 4 [get_ports M02_ACLK]
create_clock -name M03_ACLK -period 4 [get_ports M03_ACLK]
create_clock -name S00_ACLK -period 4 [get_ports S00_ACLK]
create_clock -name S01_ACLK -period 4 [get_ports S01_ACLK]

################################################################################