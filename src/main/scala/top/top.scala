package top

import chisel3._
import java.nio.file.{Paths, Files}
import java.nio.charset.StandardCharsets
import circt.stage.ChiselStage
import procElem.PE

class Top extends Module {

  val nbits: Int = 8
  val io = IO(new Bundle {
    val top_in      = Input(UInt(nbits.W))
    val left_in     = Input(UInt(nbits.W))
    val accum       = Input(Bool())
    val bottom_out  = Output(UInt((nbits*2).W))
    val right_out   = Output(UInt((nbits*2).W))
    val out         = Output(UInt((nbits*2).W))
  })  

  val pe = Module(new PE(8))
  
  // get value when ready
  pe.io.top_in := io.top_in
  pe.io.left_in := io.left_in
  pe.io.accum := io.accum
  io.out := pe.io.out
  io.bottom_out := pe.io.bottom_out
  io.right_out := pe.io.right_out
}

object Main extends App {
  // These lines generate the Verilog output

  val hdl = ChiselStage.emitSystemVerilog(
    new Top(),
    firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info")
  )
  Files.write(Paths.get("top.v"), hdl.getBytes(StandardCharsets.UTF_8))
}
