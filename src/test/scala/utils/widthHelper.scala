// Test helper: width constants matching VecWidth ChiselEnum values.
// NCoreVALUBundle.width is UInt(2.W); poke these instead of VecWidth.VX etc.
package testUtil

object WidthConst {
  val VX: Int = 0
  val VE: Int = 1
  val VR: Int = 2
}
