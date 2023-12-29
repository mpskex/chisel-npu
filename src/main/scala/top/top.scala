package top

import chisel3._
import java.nio.file.{Paths, Files}
import java.nio.charset.StandardCharsets
import circt.stage.ChiselStage
import gcd.DecoupledGcd

class Top extends Module {

  val io = IO(new Bundle {
    val value1        = Input(UInt(16.W))
    val value2        = Input(UInt(16.W))
    val inputValid    = Input(Bool())
    val outputGCD     = Output(UInt(16.W))
  })  

  val gcd = Module(new DecoupledGcd(16))
  
  // get value when ready
  val output_bundle = gcd.output.deq()
  gcd.input.bits.value1 := io.value1
  gcd.input.bits.value2 := io.value2
  io.outputGCD := output_bundle.gcd

  gcd.input.valid := io.inputValid
  gcd.input.ready := DontCare
}

object Main extends App {
  // These lines generate the Verilog output

  val hdl = ChiselStage.emitSystemVerilog(
    new Top(),
    firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info")
  )
  Files.write(Paths.get("top.v"), hdl.getBytes(StandardCharsets.UTF_8))
}
