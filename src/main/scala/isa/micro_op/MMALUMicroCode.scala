// See README.md for license details.

package isa.micro_op
import chisel3._
import chisel3.util._

class NCoreMMALUBundle () extends Bundle {
    val accum = Bool()
    val dat_collect = Bool()
}