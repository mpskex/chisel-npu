// See README.md for license details.
// -----------------------------------------------------------------------------
//  NpuCtrlProtocol.scala — NPUCTL v1 register map and field constants.
//
//  This is the single source of truth for the control-plane protocol.  The
//  Chisel hub, the Python driver and the docs mirror these values.
// -----------------------------------------------------------------------------

package peripheral

object NpuCtrlProtocol {
  // ---- identity -----------------------------------------------------------
  val ID_MAGIC: Long = 0x4E505531L // "NPU1"
  val VERSION_MAJOR: Int = 1
  val VERSION_MINOR: Int = 0
  val VERSION_PATCH: Int = 0

  // ---- register byte offsets ---------------------------------------------
  val ID           = 0x000
  val VERSION      = 0x004
  val CAPS         = 0x008
  val CONFIG       = 0x00C
  val CONTROL      = 0x010
  val STATUS       = 0x014
  val IRQ_STATUS   = 0x018
  val IRQ_ENABLE   = 0x01C
  val ERROR_CODE   = 0x020
  val ERROR_PC     = 0x024
  val ERROR_INFO   = 0x028
  val ERROR_ADDR   = 0x02C
  val PROG_LEN     = 0x030
  val ENTRY_PC     = 0x034
  val SESSION_ID   = 0x038
  val WATCHDOG     = 0x03C
  val CYCLES       = 0x040
  val MACS         = 0x044
  val FETCH_STATS  = 0x048
  val TIMESTAMP    = 0x04C
  val TRACE_CTRL   = 0x050
  val TRACE_STATUS = 0x054
  val TRACE_DATA   = 0x058
  val TRACE_DROPS  = 0x05C

  // ---- segment table ------------------------------------------------------
  val SEG_TABLE  = 0x100
  val SEG_STRIDE = 0x10
  val NUM_SEG    = 32
  val NUM_ACTIVE_SEG = 5

  val SCRATCH    = 0x180

  object Seg {
    val A     = 0
    val B     = 1
    val ACCUM = 2
    val OUT   = 3
    val CODE  = 4
  }

  def segBaseLo(i: Int): Int  = SEG_TABLE + i * SEG_STRIDE
  def segBaseHi(i: Int): Int  = SEG_TABLE + i * SEG_STRIDE + 4
  def segLimit(i: Int): Int   = SEG_TABLE + i * SEG_STRIDE + 8

  // ---- CONTROL bits -------------------------------------------------------
  val CTRL_START      = 0
  val CTRL_STOP       = 1
  val CTRL_SOFT_RESET = 2

  // ---- STATUS bits --------------------------------------------------------
  val STATUS_BUSY    = 0
  val STATUS_DONE    = 1
  val STATUS_ERROR   = 2
  val STATUS_ABORTED = 3
  // state is [7:4]

  object State {
    val IDLE    = 0
    val RUN     = 1
    val DONE    = 2
    val ERROR   = 3
    val ABORTED = 4
  }

  // ---- IRQ bits -----------------------------------------------------------
  val IRQ_DONE      = 0
  val IRQ_ERROR     = 1
  val IRQ_TRACE_OVF = 2
  val IRQ_BUS_ERROR = 3

  // ---- error codes --------------------------------------------------------
  object Error {
    val NONE       = 0
    val ILLEGAL    = 1
    val MEM_FAULT  = 2
    val WATCHDOG   = 3
    val BUS_ERROR  = 4
    val BAD_CONFIG = 5
    val ABORTED    = 6
  }

  // ---- trace events -------------------------------------------------------
  object Event {
    val START    = 1
    val RETIRE   = 2
    val DONE     = 3
    val ERROR    = 4
    val WATCHDOG = 5
    val ABORT    = 6
    val OVERFLOW = 7
  }

  // ---- CAPS bits ----------------------------------------------------------
  val CAPS_IRQ       = 0
  val CAPS_TRACE     = 1
  val CAPS_WATCHDOG  = 2
  val CAPS_AXI64     = 3
  val CAPS_NUM_SEG_SHIFT = 8    // [11:8]
  val CAPS_LOCAL_MAP_SHIFT = 12 // [15:12]
  val CAPS_TRACE_DEPTH_SHIFT = 16 // [23:16]
  val CAPS_AXI_DATA_W_SHIFT = 28 // [31:28], log2(bytes per beat), 512 B/? => 6

  // ---- CONFIG fields ------------------------------------------------------
  // 32-bit word: K[7:0], N[15:8], L[23:16], W[31:24].
  def configWord(k: Int, n: Int, l: Int, w: Int): BigInt = {
    (BigInt(k & 0xFF) << 0) |
      (BigInt(n & 0xFF) << 8) |
      (BigInt(l & 0xFF) << 16) |
      (BigInt(w & 0xFF) << 24)
  }
}
