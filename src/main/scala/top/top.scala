package top

import chisel3._
import java.nio.file.{Paths, Files}
import java.nio.charset.StandardCharsets
import circt.stage.ChiselStage
import engine._

object Main extends App {
  // Emit the program engine (frontend + execution core + DMA + MMALU).
  // K = systolic array side (16 on the FPGA: the K=32 engine + XDMA + MIG
  // exceeds the xc7k480t's routability — see build notes), N = 8 (byte lanes).
  val K = 16
  // W = dispatch-window depth.  W=8 did not close timing on xcvu9p at 200 MHz
  // (many deep, route-dominated scoreboard/issue cones); W=4 shrinks the engine
  // and its W-scaled logic so the design closes.  (Performance knob, not
  // correctness.)
  val hdl = ChiselStage.emitSystemVerilog(
    new NpuProgramEngineFrontend(K, 8, W = 4),
    firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info")
  )
  Files.write(Paths.get("top.sv"), hdl.getBytes(StandardCharsets.UTF_8))
}
