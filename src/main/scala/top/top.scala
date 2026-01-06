package top

import chisel3._
import java.nio.file.{Paths, Files}
import java.nio.charset.StandardCharsets
import circt.stage.ChiselStage
import alu.mma._
import alu.pe._


object Main extends App {
  // These lines generate the Verilog output

  val hdl = ChiselStage.emitSystemVerilog(
    new MMALU(new MMPE(), 64, 8, 32),
    firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info", "--lowering-options=disallowLocalVariables,disallowPackedArrays,locationInfoStyle=none")
  )
  Files.write(Paths.get("top.v"), hdl.getBytes(StandardCharsets.UTF_8))
}
