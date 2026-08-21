// See README.md for license details.
// -----------------------------------------------------------------------------
//  npuDmaEngine.scala — DMA engine: moves vector data between the L3 DDR
//  DATA section and the VX/VE/VR register file.
//
//  Request-driven AXI master (128-bit):
//    L2R  (L3 → RF): burst read 2/4/8 beats (VX/VE/VR = 32/64/128 B), stage
//                    into a beat buffer, then ONE wide RF write per vector.
//    R2L  (RF → L3): combinational RF read, beat pack, AXI write with the
//                    wdata-preload fix (wvalid asserted together with awvalid).
//
//  Carries the hardened patterns from npu_dma_master.v:
//    - rready asserted at the first AR and held across all read phases
//      (rready continuity — prevents the xbar/dwidth converter from losing
//      the first beat of the next burst).
//    - read-timeout retry: if no beat arrives within `readTimeout` cycles,
//      re-issue the AR (16K cycles ≈ 82 µs at 200 MHz by default).
//
//  The RF interface exposes one write port per register class (L2R) and one
//  read port per class (R2L).  Exactly one write port pulses per L2R request;
//  read addresses are held for the whole R2L request.
// -----------------------------------------------------------------------------

package dma

import chisel3._
import chisel3.util._

class NpuDmaEngine(val K: Int = 32, val N: Int = 8, val readTimeout: Int = 16384) extends Module {

  require(N == 8, s"NpuDmaEngine: N=$N not supported (byte packing assumes 8)")
  require(K % 16 == 0, s"NpuDmaEngine: K=$K must be a multiple of 16")

  val VX_BYTES = K * N / 8          // 32  B
  val VE_BYTES = K * 2 * N / 8      // 64  B
  val VR_BYTES = K * 4 * N / 8      // 128 B
  val BEATS_VX = VX_BYTES / 16      // 2
  val BEATS_VE = VE_BYTES / 16      // 4
  val BEATS_VR = VR_BYTES / 16      // 8

  val VX_ADDR = log2Ceil(K)         // 5 bits (L = K = 32)
  val VE_ADDR = log2Ceil(K / 2)     // 4 bits
  val VR_ADDR = log2Ceil(K / 4)     // 3 bits

  val io = IO(new Bundle {
    // ---- Request (valid/ready) ----
    val req_valid = Input(Bool())
    val req_ready = Output(Bool())
    val req_dir   = Input(UInt(2.W))    // 0 = L2R (L3 → RF), 1 = R2L (RF → L3),
                                        // 2 = L3 → ACCUM buffer (MMA in_accum),
                                        // 3 = L3 → instruction line buffer (1 beat)
    val req_width = Input(UInt(2.W))    // 0 = VX, 1 = VE, 2 = VR (ACCUM/fetch: VR)
    val req_rf_addr = Input(UInt(5.W))  // VX/VE/VR register index
    val req_l3_addr = Input(UInt(32.W)) // absolute DDR address (16 B aligned)
    val busy      = Output(Bool())
    val done      = Output(Bool())      // one-cycle pulse on completion

    // ---- ACCUM buffer (dir=2; valid one cycle before done) ----
    val acc_valid = Output(Bool())
    val acc_out   = Output(Vec(K, UInt((4 * N).W)))

    // ---- Instruction line buffer (dir=3; valid one cycle before done) ----
    val instr_valid = Output(Bool())
    val instr_out   = Output(UInt(128.W))

    // ---- RF write ports (L2R; one pulses per request) ----
    val rf_w_vx_en   = Output(Bool())
    val rf_w_vx_addr = Output(UInt(VX_ADDR.W))
    val rf_w_vx_data = Output(Vec(K, UInt(N.W)))
    val rf_w_ve_en   = Output(Bool())
    val rf_w_ve_addr = Output(UInt(VE_ADDR.W))
    val rf_w_ve_data = Output(Vec(K, UInt((2 * N).W)))
    val rf_w_vr_en   = Output(Bool())
    val rf_w_vr_addr = Output(UInt(VR_ADDR.W))
    val rf_w_vr_data = Output(Vec(K, UInt((4 * N).W)))

    // ---- RF read ports (R2L; address held during the request) ----
    val rf_r_vx_addr = Output(UInt(VX_ADDR.W))
    val rf_r_vx_data = Input(Vec(K, UInt(N.W)))
    val rf_r_ve_addr = Output(UInt(VE_ADDR.W))
    val rf_r_ve_data = Input(Vec(K, UInt((2 * N).W)))
    val rf_r_vr_addr = Output(UInt(VR_ADDR.W))
    val rf_r_vr_data = Input(Vec(K, UInt((4 * N).W)))

    // ---- AXI4 master (128-bit, INCR, id = 0) ----
    val m_axi_awaddr  = Output(UInt(32.W))
    val m_axi_awlen   = Output(UInt(8.W))
    val m_axi_awsize  = Output(UInt(3.W))
    val m_axi_awburst = Output(UInt(2.W))
    val m_axi_awvalid = Output(Bool())
    val m_axi_awready = Input(Bool())
    val m_axi_wdata   = Output(UInt(128.W))
    val m_axi_wstrb   = Output(UInt(16.W))
    val m_axi_wlast   = Output(Bool())
    val m_axi_wvalid  = Output(Bool())
    val m_axi_wready  = Input(Bool())
    val m_axi_bvalid  = Input(Bool())
    val m_axi_bready  = Output(Bool())
    val m_axi_araddr  = Output(UInt(32.W))
    val m_axi_arlen   = Output(UInt(8.W))
    val m_axi_arsize  = Output(UInt(3.W))
    val m_axi_arburst = Output(UInt(2.W))
    val m_axi_arvalid = Output(Bool())
    val m_axi_arready = Input(Bool())
    val m_axi_rdata   = Input(UInt(128.W))
    val m_axi_rlast   = Input(Bool())
    val m_axi_rvalid  = Input(Bool())
    val m_axi_rready  = Output(Bool())
  })

  object DmaState extends ChiselEnum {
    val IDLE, AR, READ, RFW, ACCW, INSTRW, WAW, WDATA, WRESP, DONE = Value
  }
  val state = RegInit(DmaState.IDLE)

  // ---- Latched request ----
  val reqDir   = RegInit(0.U(2.W))
  val reqWidth = RegInit(0.U(2.W))
  val reqRfAddr = RegInit(0.U(5.W))
  val reqL3Addr = RegInit(0.U(32.W))

  val beatCnt  = RegInit(0.U(4.W))
  val buf      = RegInit(VecInit(Seq.fill(8)(0.U(128.W))))
  val timeoutCnt = RegInit(0.U(16.W))
  val awDone   = RegInit(false.B)

  def beatsOf(w: UInt): UInt =
    Mux(reqDir === 3.U, 1.U,  // fetch mode: single 16 B beat
      MuxLookup(w, BEATS_VX.U(4.W))(Seq(
        0.U -> BEATS_VX.U(4.W), 1.U -> BEATS_VE.U(4.W), 2.U -> BEATS_VR.U(4.W))))

  val beats = beatsOf(reqWidth)

  // ---- Defaults ----
  io.busy       := state =/= DmaState.IDLE
  io.done       := false.B
  io.acc_valid  := false.B
  io.instr_valid := false.B
  io.req_ready  := state === DmaState.IDLE
  io.rf_w_vx_en := false.B
  io.rf_w_ve_en := false.B
  io.rf_w_vr_en := false.B
  io.rf_w_vx_addr := reqRfAddr
  io.rf_w_ve_addr := reqRfAddr
  io.rf_w_vr_addr := reqRfAddr
  io.rf_r_vx_addr := reqRfAddr
  io.rf_r_ve_addr := reqRfAddr
  io.rf_r_vr_addr := reqRfAddr
  for (lane <- 0 until K) {
    io.rf_w_vx_data(lane) := 0.U
    io.rf_w_ve_data(lane) := 0.U
    io.rf_w_vr_data(lane) := 0.U
    io.acc_out(lane)      := 0.U
  }
  io.instr_out := 0.U

  // AXI defaults
  io.m_axi_awaddr  := reqL3Addr
  io.m_axi_awlen   := beats - 1.U
  io.m_axi_awsize  := 4.U             // 16 bytes per beat
  io.m_axi_awburst := 1.U             // INCR
  io.m_axi_awvalid := false.B
  io.m_axi_wdata   := 0.U
  io.m_axi_wstrb   := 0.U
  io.m_axi_wlast   := false.B
  io.m_axi_wvalid  := false.B
  io.m_axi_bready  := false.B
  io.m_axi_araddr  := reqL3Addr
  io.m_axi_arlen   := beats - 1.U
  io.m_axi_arsize  := 4.U
  io.m_axi_arburst := 1.U
  io.m_axi_arvalid := false.B
  io.m_axi_rready  := false.B

  // ---- L2R beat buffer write ----
  when (state === DmaState.READ && io.m_axi_rvalid && io.m_axi_rready) {
    buf(beatCnt) := io.m_axi_rdata
    beatCnt      := beatCnt + 1.U
  }

  // ---- R2L: beat pack from combinational RF reads ----
  // Byte-vector assembly (element connects are always writable).
  val wbytes = Wire(Vec(16, UInt(8.W)))
  for (b <- 0 until 16) wbytes(b) := 0.U
  when (reqWidth === 0.U) {  // VX: 16 lanes per beat
    for (i <- 0 until 16) {
      wbytes(i) := io.rf_r_vx_data((beatCnt << 4.U) + i.U)
    }
  }.elsewhen (reqWidth === 1.U) {  // VE: 8 lanes per beat
    for (i <- 0 until 8) {
      wbytes(2 * i)     := io.rf_r_ve_data((beatCnt << 3.U) + i.U)(7, 0)
      wbytes(2 * i + 1) := io.rf_r_ve_data((beatCnt << 3.U) + i.U)(15, 8)
    }
  }.otherwise {  // VR: 4 lanes per beat
    for (i <- 0 until 4) {
      for (b <- 0 until 4) {
        wbytes(4 * i + b) := io.rf_r_vr_data((beatCnt << 2.U) + i.U)(8 * b + 7, 8 * b)
      }
    }
  }
  io.m_axi_wdata := wbytes.asUInt

  // ---- L2R: assemble wide RF write from the beat buffer ----
  val rfWDataVx = Wire(Vec(K, UInt(N.W)))
  val rfWDataVe = Wire(Vec(K, UInt((2 * N).W)))
  val rfWDataVr = Wire(Vec(K, UInt((4 * N).W)))
  for (lane <- 0 until K) {
    rfWDataVx(lane) := buf(lane / 16)(8 * (lane % 16) + 7, 8 * (lane % 16))
    rfWDataVe(lane) := buf(lane / 8)(16 * (lane % 8) + 15, 16 * (lane % 8))
    rfWDataVr(lane) := buf(lane / 4)(32 * (lane % 4) + 31, 32 * (lane % 4))
  }

  switch (state) {
    is (DmaState.IDLE) {
      when (io.req_valid) {
        reqDir    := io.req_dir
        // ACCUM mode always reads a full VR vector (128 B, 8 beats);
        // fetch mode beats handled in beatsOf.
        reqWidth  := Mux(io.req_dir === 2.U, 2.U, io.req_width)
        reqRfAddr := io.req_rf_addr
        reqL3Addr := io.req_l3_addr
        beatCnt   := 0.U
        timeoutCnt := 0.U
        awDone    := false.B
        when (io.req_dir === 1.U) { state := DmaState.WAW }       // R2L
        .otherwise                  { state := DmaState.AR }       // L2R / ACCUM / fetch
      }
    }

    is (DmaState.AR) {
      io.m_axi_arvalid := true.B
      io.m_axi_rready  := true.B    // rready continuity: held through AR/READ
      when (io.m_axi_arready) { state := DmaState.READ }
    }

    is (DmaState.READ) {
      io.m_axi_rready := true.B
      when (io.m_axi_rvalid) {
        timeoutCnt := 0.U
        when (io.m_axi_rlast) {
          when (reqDir === 0.U)      { state := DmaState.RFW }
          .elsewhen (reqDir === 2.U) { state := DmaState.ACCW }
          .otherwise                 { state := DmaState.INSTRW }
        }
      } .otherwise {
        when (timeoutCnt === (readTimeout - 1).U) {
          // No beat arrived: re-issue the request (self-heal).
          beatCnt   := 0.U
          timeoutCnt := 0.U
          state := DmaState.AR
        } .otherwise {
          timeoutCnt := timeoutCnt + 1.U
        }
      }
    }

    is (DmaState.RFW) {
      // One wide RF write with the fully-assembled vector.
      when (reqWidth === 0.U) {
        io.rf_w_vx_en := true.B
        io.rf_w_vx_data := rfWDataVx
      }.elsewhen (reqWidth === 1.U) {
        io.rf_w_ve_en := true.B
        io.rf_w_ve_data := rfWDataVe
      }.otherwise {
        io.rf_w_vr_en := true.B
        io.rf_w_vr_data := rfWDataVr
      }
      state := DmaState.DONE
    }

    is (DmaState.ACCW) {
      // Publish the K×1 ACCUM vector (MMA in_accum) from the beat buffer.
      io.acc_valid := true.B
      for (lane <- 0 until K) {
        io.acc_out(lane) := buf(lane / 4)(32 * (lane % 4) + 31, 32 * (lane % 4))
      }
      state := DmaState.DONE
    }

    is (DmaState.INSTRW) {
      // Publish the fetched 16 B instruction line.
      io.instr_valid := true.B
      io.instr_out   := buf(0)
      state := DmaState.DONE
    }

    is (DmaState.WAW) {
      // AW together with W (beat 0 preloaded) — the S_WR_W preload fix.
      io.m_axi_awvalid := !awDone
      io.m_axi_wvalid  := beatCnt < beats
      io.m_axi_wdata   := wbytes.asUInt
      io.m_axi_wstrb   := 0xFFFF.U(16.W)
      io.m_axi_wlast   := beatCnt === beats - 1.U
      val wadv         = io.m_axi_wready && beatCnt < beats
      when (io.m_axi_awready) {
        awDone := true.B
        when (beatCnt + Mux(wadv, 1.U, 0.U) === beats) { state := DmaState.WRESP }
        .otherwise                                     { state := DmaState.WDATA }
      }
      when (wadv) { beatCnt := beatCnt + 1.U }
    }

    is (DmaState.WDATA) {
      io.m_axi_wvalid := beatCnt < beats
      io.m_axi_wdata  := wbytes.asUInt
      io.m_axi_wstrb  := 0xFFFF.U(16.W)
      io.m_axi_wlast  := beatCnt === beats - 1.U
      when (io.m_axi_wready && beatCnt < beats) {
        beatCnt := beatCnt + 1.U
        when (beatCnt === beats - 1.U) { state := DmaState.WRESP }
      }
    }

    is (DmaState.WRESP) {
      io.m_axi_bready := true.B
      when (io.m_axi_bvalid) { state := DmaState.DONE }
    }

    is (DmaState.DONE) {
      io.done := true.B
      state   := DmaState.IDLE
    }
  }
}
