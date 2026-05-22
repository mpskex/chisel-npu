# Xilinx XDMA Linux Driver — Local Source Mirror

This directory is a source-only mirror of the Xilinx DMA IP Drivers for Linux
(`XDMA/linux-kernel`), used for the xc7k480t FPGA verification platform.

**Original location on FPGA box**: `<fpga-host>:~/dma_ip_drivers/XDMA/linux-kernel/`

Compiled binaries (`.o`, `.ko`, `dma_to_device`, `reg_rw`, `performance`, etc.) are
excluded — only source files are tracked here.

---

## Directory layout

```
driver/linux/
├── xdma/           Kernel module source (C, Makefiles)
├── tools/          Userspace DMA tools source (C)
├── tests/          Original Xilinx test shell scripts (reference)
├── include/        Shared headers
├── COPYING / LICENSE / RELEASE / readme.txt
└── README.md       This file
```

---

## Editing and redeploying the driver

1. **Edit** source files in this directory.

2. **Sync** to the FPGA box:
   ```bash
   rsync -av \
       --exclude='*.o' --exclude='*.ko' --exclude='*.mod*' \
       --exclude='.tmp_versions' --exclude='Module.symvers' \
       driver/linux/ fpga:~/dma_ip_drivers/XDMA/linux-kernel/
   ```

3. **Build** on the FPGA box:
   ```bash
   ssh fpga "make -C ~/dma_ip_drivers/XDMA/linux-kernel/xdma clean all"
   ssh fpga "make -C ~/dma_ip_drivers/XDMA/linux-kernel/tools clean all"
   ```

4. **Reload** the driver:
   ```bash
   tool/hw/reboot_and_load.sh
   ```

---

## License

All files in `xdma/`, `tools/`, `tests/`, and `include/` are covered by the
Xilinx license as documented in `LICENSE` and `COPYING`. No license terms have
been changed. New files added to this directory for NPU-specific integration
are covered by the same project license as the rest of this repository (GPLv2).
