// See README.md for license details.

package isa.micro_op
import chisel3._
import chisel3.util._

class NCoreMMALUCtrlBundle () extends Bundle {
    val keep = Bool()
    val use_accum = Bool()
    val busy = Bool()
}