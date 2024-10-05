package top

import chisel3._
import java.nio.file.{Paths, Files}
import java.nio.charset.StandardCharsets
import circt.stage.ChiselStage
import ncore._


object Main extends App {
  // These lines generate the Verilog output

  val hdl = ChiselStage.emitSystemVerilog(
    new NeuralCore(),
    firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info")
  )
  Files.write(Paths.get("top.v"), hdl.getBytes(StandardCharsets.UTF_8))
}