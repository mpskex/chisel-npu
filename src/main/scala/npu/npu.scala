package npu

import chisel3._
import java.nio.file.{Paths, Files}
import java.nio.charset.StandardCharsets
import circt.stage.ChiselStage
import ncore.pe.PE

class NPU extends Module {

  val nbits: Int = 8
  val io = IO(new Bundle {
    val in_a        = Input(UInt(nbits.W))
    val in_b        = Input(UInt(nbits.W))
    val accum       = Input(Bool())
    val out         = Output(UInt((nbits*2).W))
  })  

  val pe = Module(new PE(8))
  
  // get value when ready
  pe.io.in_a := io.in_a
  pe.io.in_b := io.in_b
  pe.io.accum := io.accum
  io.out := pe.io.out
}

object Main extends App {
  // These lines generate the Verilog output

  val hdl = ChiselStage.emitSystemVerilog(
    new NPU(),
    firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info")
  )
  Files.write(Paths.get("npu.v"), hdl.getBytes(StandardCharsets.UTF_8))
}
