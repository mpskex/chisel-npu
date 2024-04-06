// See README.md for license details.

package isa.backend
import chisel3._
import chisel3.util._

class NCoreCUBundle (val size: Int = 4096) extends Bundle {
    val accum = Bool()
}