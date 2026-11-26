package top

import chisel3._
import java.nio.file.{Paths, Files}
import java.nio.charset.StandardCharsets
import circt.stage.ChiselStage
import peripheral._

object Main extends App {
  // Emit the board-agnostic NPU peripheral: NPUCTL control hub + program
  // engine + segmented-AXI -> 64-bit system AXI translation.
  //
  // K = systolic array side, N = 8 (byte lanes), W = issue-window depth.
  // The vu9p baseline is K=32, N=8, W=8.  The release manifest records these
  // values; the driver reads CONFIG instead of assuming them.
  val hdl = ChiselStage.emitSystemVerilog(
    new NpuPeripheral(K = 32, N = 8, W = 8),
    firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info")
  )
  Files.write(Paths.get("top.sv"), hdl.getBytes(StandardCharsets.UTF_8))
}

object MainK16 extends App {
  // Smaller-device release of the same peripheral.  K=16 keeps the xc7k480t
  // image routable (the K=32 MMALU is ~4x the K=16 array area and exceeds the
  // 7-series part once XDMA + dual MIG are added).  The NPUCTL register map,
  // ISA, and port list are identical; CONFIG reports K=16/L=16.
  val hdl = ChiselStage.emitSystemVerilog(
    new NpuPeripheral(K = 16, N = 8, W = 8),
    firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info")
  )
  Files.write(Paths.get("top_k16.sv"), hdl.getBytes(StandardCharsets.UTF_8))
}
