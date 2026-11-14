package top

import chisel3._
import java.nio.file.{Paths, Files}
import java.nio.charset.StandardCharsets
import circt.stage.ChiselStage
import engine._

object Main extends App {
  // Emit the program engine (frontend + execution core + DMA + MMALU) for the
  // xcvu9p build.
  // K = systolic array side, N = 8 (byte lanes).  K=32 matches the Phase-2
  // L1/512-bit-AXI configuration (T = 128 tag ring, VR = 2 AXI beats).
  val K = 32
  // W = issue-window depth.  L1 (retire-at-issue + T=128 tag ring) carries the
  // completion/hazard tags, so W no longer determines throughput.  W=8 is
  // sim-verified to keep the 95.5% reduce body (ReduceModeSpec) and halves the
  // wHead->issueSel cone for 200 MHz timing on the FPGA.
  val hdl = ChiselStage.emitSystemVerilog(
    new NpuProgramEngineFrontend(K, 8, W = 8),
    firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info")
  )
  Files.write(Paths.get("top.sv"), hdl.getBytes(StandardCharsets.UTF_8))
}
