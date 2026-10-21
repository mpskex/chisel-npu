// See README.md for license details.
// -----------------------------------------------------------------------------
//  npuDmaEngine.scala — DMA engine: moves vector data between the L3 DDR
//  DATA section and the VX/VE/VR register file.
//
//  Request-driven AXI master.  AXI data width is `DW` (default 512, matching
//  the 4x DDR4 MIG controller) so one vector is few beats:
//    VX = 32 B -> 1 beat (low 32 B valid, WSTRB), VE = 64 B -> 1 beat,
//    VR = 128 B -> 2 beats.  (At the old 128-bit width VR was 8 beats.)
//    L2R  (L3 -> RF): burst read the beats, stage into a beat buffer, then
//                    ONE wide RF write per vector.
//    R2L  (RF -> L3): combinational RF read, pack a DW-wide beat, AXI write
//                    with the wdata-preload fix (wvalid with awvalid).
//
//  Request pipelining (c7dae30): `req_ready` rises once the current burst's
//  data phase has started and a one-deep request buffer latches the next
//  request (its address phase overlaps the previous data phase).
//
//  Hardened patterns carried from npu_dma_master.v: rready continuity, and
//  read/write timeout retries.
// -----------------------------------------------------------------------------

package dma

import chisel3._
import chisel3.util._

class NpuDmaEngine(val K: Int = 32, val N: Int = 8, val readTimeout: Int = 16384,
                   val DW: Int = 512) extends Module {

  require(N == 8, s"NpuDmaEngine: N=$N not supported (byte packing assumes 8)")
  require(K % 16 == 0, s"NpuDmaEngine: K=$K must be a multiple of 16")
  require(DW == 512, s"NpuDmaEngine: DW=$DW not supported (DW=512 target)")

  val DBYTES = DW / 8               // 64 bytes per AXI beat
  val VX_BYTES = K * N / 8          // 32  B
  val VE_BYTES = K * 2 * N / 8      // 64  B
  val VR_BYTES = K * 4 * N / 8      // 128 B
  def ceilDiv(a: Int, b: Int) = (a + b - 1) / b
  val BEATS_VX = ceilDiv(VX_BYTES, DBYTES)   // 1
  val BEATS_VE = ceilDiv(VE_BYTES, DBYTES)   // 1
  val BEATS_VR = ceilDiv(VR_BYTES, DBYTES)   // 2
  val MAX_BEATS = math.max(BEATS_VR, math.max(BEATS_VE, BEATS_VX))
  val SZ = log2Ceil(DW / 8)         // 6 (awsize)

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
    val req_l3_addr = Input(UInt(32.W)) // absolute DDR address (aligned)
    val req_store_data_valid = Input(Bool())
    val req_store_data = Input(Vec(K, UInt((4 * N).W)))
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

    // ---- AXI4 master (DW-bit, INCR, id = 0) ----
    val m_axi_awaddr  = Output(UInt(32.W))
    val m_axi_awlen   = Output(UInt(8.W))
    val m_axi_awsize  = Output(UInt(3.W))
    val m_axi_awburst = Output(UInt(2.W))
    val m_axi_awvalid = Output(Bool())
    val m_axi_awready = Input(Bool())
    val m_axi_wdata   = Output(UInt(DW.W))
    val m_axi_wstrb   = Output(UInt((DW / 8).W))
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
    val m_axi_rdata   = Input(UInt(DW.W))
    val m_axi_rlast   = Input(Bool())
    val m_axi_rvalid  = Input(Bool())
    val m_axi_rready  = Output(Bool())
  })

  object DmaState extends ChiselEnum {
    val IDLE, AR, READ, RFW, ACCW, INSTRW, WAW, WDATA, WRESP, DONE = Value
  }
  val state = RegInit(DmaState.IDLE)

  // ---- Latched request (in-flight) ----
  val reqDir   = RegInit(0.U(2.W))
  val reqWidth = RegInit(0.U(2.W))
  val reqRfAddr = RegInit(0.U(5.W))
  val reqL3Addr = RegInit(0.U(32.W))
  val reqStoreDataValid = RegInit(false.B)
  val reqStoreData = RegInit(VecInit(Seq.fill(K)(0.U((4 * N).W))))

  val beatCnt  = RegInit(0.U(log2Ceil(MAX_BEATS + 1).W))
  val buf      = RegInit(VecInit(Seq.fill(MAX_BEATS)(0.U(DW.W))))
  val timeoutCnt = RegInit(0.U(16.W))
  val awDone   = RegInit(false.B)
  val wtoCnt   = RegInit(0.U(16.W))   // write-phase timeout: re-issue the write
  val wtoRetry = RegInit(0.U(3.W))    // WRESP retries before forcing done

  // ---- One-deep pipelined next-request buffer (c7dae30) ----
  val bufValid  = RegInit(false.B)
  val bufDir    = RegInit(0.U(2.W))
  val bufWidth  = RegInit(0.U(2.W))
  val bufRfAddr = RegInit(0.U(5.W))
  val bufL3Addr = RegInit(0.U(32.W))
  val bufStoreDataValid = RegInit(false.B)
  val bufStoreData = RegInit(VecInit(Seq.fill(K)(0.U((4 * N).W))))

  def beatsOf(w: UInt): UInt =
    Mux(reqDir === 3.U, 1.U,
      MuxLookup(w, BEATS_VX.U)(Seq(
        0.U -> BEATS_VX.U, 1.U -> BEATS_VE.U, 2.U -> BEATS_VR.U)))

  val beats = beatsOf(reqWidth)

  // ---- Defaults ----
  // busy must include a buffered-but-unpromoted request, else a caller that
  // drains on !busy exits with a request still queued.
  io.busy       := (state =/= DmaState.IDLE) || bufValid
  io.done       := false.B
  io.acc_valid  := false.B
  io.instr_valid := false.B
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

  // AXI defaults.  The bus is 512-bit and ALL transfers use SIZE=6 (one 64-byte
  // beat), so the AXI address MUST be 64-byte aligned.  The engine's addresses
  // are only 16-byte (instruction lines) or 32-byte (VX) aligned, so align the
  // AXI address down and recover the requested bytes with `rdOff` (the byte
  // offset within the beat).  The old code issued SIZE=6 at the unaligned
  // address and assumed the data was in the low lanes: on silicon that is an
  // AXI protocol violation / wrong lane (VX at offset 32, CODE lines at 16/32/
  // 48), while the Chisel RAM models ignored size and always returned low
  // lanes, hiding it.
  val beatMask  = "hFFFFFFC0".U(32.W)
  val rdOff     = reqL3Addr(5, 0)             // byte offset inside the 64-B beat
  // VX is VX_BYTES-aligned: select its VX_BYTES chunk within the 64-B beat
  // (2-way for K=32, 4-way for K=16) instead of a 512-bit barrel shifter.
  val vxChunks  = (DW / 8) / VX_BYTES
  val vxLo      = log2Ceil(VX_BYTES)
  val vxSel     = if (vxChunks > 1) rdOff(vxLo + log2Ceil(vxChunks) - 1, vxLo) else 0.U
  // VE is VE_BYTES-aligned as well (1 beat: 1 chunk for K=32, 2 for K=16).
  val veChunks  = (DW / 8) / VE_BYTES
  val veLo      = log2Ceil(VE_BYTES)
  val veSel     = if (veChunks > 1) rdOff(veLo + log2Ceil(veChunks) - 1, veLo) else 0.U
  io.m_axi_awaddr  := (reqL3Addr & beatMask)
  io.m_axi_awlen   := beats - 1.U
  io.m_axi_awsize  := SZ.U
  io.m_axi_awburst := 1.U             // INCR
  io.m_axi_awvalid := false.B
  io.m_axi_wdata   := 0.U
  io.m_axi_wstrb   := 0.U
  io.m_axi_wlast   := false.B
  io.m_axi_wvalid  := false.B
  io.m_axi_bready  := false.B
  io.m_axi_araddr  := (reqL3Addr & beatMask)
  io.m_axi_arlen   := beats - 1.U
  io.m_axi_arsize  := SZ.U
  io.m_axi_arburst := 1.U
  io.m_axi_arvalid := false.B
  io.m_axi_rready  := false.B

  // ---- L2R beat buffer write ----
  when (state === DmaState.READ && io.m_axi_rvalid && io.m_axi_rready) {
    buf(beatCnt) := io.m_axi_rdata
    beatCnt      := beatCnt + 1.U
  }

  // ---- R2L: pack a DW-wide beat from combinational RF reads ----
  // Byte-vector assembly (element assignable), then one whole-word connect.
  val fullStrb = ((BigInt(1) << (DW / 8)) - 1).U((DW / 8).W)
  val wbytes = Wire(Vec(DW / 8, UInt(8.W)))
  for (b <- 0 until DW / 8) wbytes(b) := 0.U
  val wstrb = WireDefault(0.U((DW / 8).W))
  when (reqWidth === 0.U) {          // VX: K lanes x 8 bit, 1 beat
    // Place the VX_BYTES at its aligned chunk of the 64-B beat.
    for (c <- 0 until vxChunks) {
      when (vxSel === c.U) {
        for (i <- 0 until K) wbytes(c * VX_BYTES + i) := io.rf_r_vx_data(i)
      }
    }
    wstrb := MuxLookup(vxSel, 0.U((DW / 8).W))(
      (0 until vxChunks).map(c =>
        c.U -> (((BigInt(1) << VX_BYTES) - 1).U << (c * VX_BYTES))))
  }.elsewhen (reqWidth === 1.U) {    // VE: K lanes x 16 bit, 1 beat
    for (c <- 0 until veChunks) {
      when (veSel === c.U) {
        for (i <- 0 until K) {
          wbytes(c * VE_BYTES + 2 * i)     := io.rf_r_ve_data(i)(7, 0)
          wbytes(c * VE_BYTES + 2 * i + 1) := io.rf_r_ve_data(i)(15, 8)
        }
      }
    }
    wstrb := MuxLookup(veSel, 0.U((DW / 8).W))(
      (0 until veChunks).map(c =>
        c.U -> (((BigInt(1) << VE_BYTES) - 1).U << (c * VE_BYTES))))
  }.otherwise {                      // VR: 16 lanes x 32 bit per beat, 2 beats
    val lanesPerBeat = DW / 32
    for (i <- 0 until lanesPerBeat) {
      val lane = beatCnt * lanesPerBeat.U + i.U
      val v = Mux(reqStoreDataValid, reqStoreData(lane), io.rf_r_vr_data(lane))
      for (b <- 0 until 4) wbytes(4 * i + b) := v(8 * b + 7, 8 * b)
    }
    wstrb := fullStrb
  }
  io.m_axi_wdata := wbytes.asUInt
  io.m_axi_wstrb := wstrb

  // ---- L2R: assemble wide RF writes from the beat buffer ----
  val rfWDataVx = Wire(Vec(K, UInt(N.W)))
  val rfWDataVe = Wire(Vec(K, UInt((2 * N).W)))
  val rfWDataVr = Wire(Vec(K, UInt((4 * N).W)))
  // The AXI read is 64-B aligned, so the requested vector starts at byte
  // `rdOff` of the beat.  Select the aligned VX chunk (small mux, K-exact).
  val vxBeat = MuxLookup(vxSel, 0.U((VX_BYTES * 8).W))(
    (0 until vxChunks).map(c =>
      c.U -> buf(0)((c + 1) * VX_BYTES * 8 - 1, c * VX_BYTES * 8)))
  val veBeat = MuxLookup(veSel, 0.U((VE_BYTES * 8).W))(
    (0 until veChunks).map(c =>
      c.U -> buf(0)((c + 1) * VE_BYTES * 8 - 1, c * VE_BYTES * 8)))
  for (lane <- 0 until K) {
    rfWDataVx(lane) := vxBeat(8 * lane + 7, 8 * lane)
    rfWDataVe(lane) := veBeat(16 * lane + 15, 16 * lane)
    rfWDataVr(lane) := buf(lane / (DW / 32))(32 * (lane % (DW / 32)) + 31,
                                             32 * (lane % (DW / 32)))
  }

  switch (state) {
    is (DmaState.IDLE) {
      when (bufValid) {
        // Promote the buffered request.
        reqDir    := bufDir
        reqWidth  := bufWidth
        reqRfAddr := bufRfAddr
        reqL3Addr := bufL3Addr
        reqStoreDataValid := bufStoreDataValid
        for (lane <- 0 until K) reqStoreData(lane) := bufStoreData(lane)
        bufValid  := false.B
        beatCnt   := 0.U
        timeoutCnt := 0.U
        awDone    := false.B
        wtoCnt    := 0.U
        wtoRetry  := 0.U
        state := Mux(bufDir === 1.U, DmaState.WAW, DmaState.AR)
      } .elsewhen (io.req_valid) {
        reqDir    := io.req_dir
        reqWidth  := Mux(io.req_dir === 2.U, 2.U, io.req_width)
        reqRfAddr := io.req_rf_addr
        reqL3Addr := io.req_l3_addr
        reqStoreDataValid := io.req_store_data_valid
        for (lane <- 0 until K) reqStoreData(lane) := io.req_store_data(lane)
        beatCnt   := 0.U
        timeoutCnt := 0.U
        awDone    := false.B
        wtoCnt    := 0.U
        wtoRetry  := 0.U
        state := Mux(io.req_dir === 1.U, DmaState.WAW, DmaState.AR)
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
          beatCnt   := 0.U
          timeoutCnt := 0.U
          state := DmaState.AR
        } .otherwise {
          timeoutCnt := timeoutCnt + 1.U
        }
      }
    }

    is (DmaState.RFW) {
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
      io.acc_valid := true.B
      for (lane <- 0 until K) {
        io.acc_out(lane) := buf(lane / (DW / 32))(32 * (lane % (DW / 32)) + 31,
                                                  32 * (lane % (DW / 32)))
      }
      state := DmaState.DONE
    }

    is (DmaState.INSTRW) {
      io.instr_valid := true.B
      // 16-byte instruction line at byte offset rdOff within the 64-B beat.
      // rdOff is a multiple of 16, so a 4:1 select of the 128-bit quarters.
      io.instr_out := MuxLookup(rdOff(5, 4), buf(0)(127, 0))(Seq(
        0.U -> buf(0)(127, 0),
        1.U -> buf(0)(255, 128),
        2.U -> buf(0)(383, 256),
        3.U -> buf(0)(511, 384)))
      state := DmaState.DONE
    }

    is (DmaState.WAW) {
      io.m_axi_awvalid := !awDone
      io.m_axi_wvalid  := beatCnt < beats
      io.m_axi_wlast   := beatCnt === beats - 1.U
      val wadv         = io.m_axi_wready && beatCnt < beats
      when (io.m_axi_awready) {
        awDone := true.B
        wtoCnt := 0.U
        when (beatCnt + Mux(wadv, 1.U, 0.U) === beats) { state := DmaState.WRESP }
        .otherwise                                     { state := DmaState.WDATA }
      } .elsewhen (wtoCnt === (readTimeout - 1).U) {
        when (wtoRetry === 7.U) {
          state := DmaState.DONE
        } .otherwise {
          wtoRetry := wtoRetry + 1.U
          awDone   := false.B
          beatCnt  := 0.U
          wtoCnt   := 0.U
        }
      } .otherwise {
        wtoCnt := wtoCnt + 1.U
      }
      when (wadv) { beatCnt := beatCnt + 1.U }
    }

    is (DmaState.WDATA) {
      io.m_axi_wvalid := beatCnt < beats
      io.m_axi_wlast  := beatCnt === beats - 1.U
      when (io.m_axi_wready && beatCnt < beats) {
        beatCnt := beatCnt + 1.U
        wtoCnt  := 0.U
        when (beatCnt === beats - 1.U) { state := DmaState.WRESP }
      } .elsewhen (wtoCnt === (readTimeout - 1).U) {
        awDone   := false.B
        beatCnt  := 0.U
        wtoCnt   := 0.U
        state    := DmaState.WAW
      } .otherwise {
        wtoCnt := wtoCnt + 1.U
      }
    }

    is (DmaState.WRESP) {
      io.m_axi_bready := true.B
      when (io.m_axi_bvalid) {
        state    := DmaState.DONE
        wtoCnt   := 0.U
        wtoRetry := 0.U
      } .elsewhen (wtoCnt === (readTimeout - 1).U) {
        when (wtoRetry === 7.U) {
          state := DmaState.DONE
        } .otherwise {
          wtoRetry := wtoRetry + 1.U
          awDone   := false.B
          beatCnt  := 0.U
          wtoCnt   := 0.U
          state    := DmaState.WAW
        }
      } .otherwise {
        wtoCnt := wtoCnt + 1.U
      }
    }

    is (DmaState.DONE) {
      io.done := true.B
      state   := DmaState.IDLE
    }
  }

  // ---- Request acceptance: one-deep buffer; `req_ready` rises once the
  //      current burst's data phase has started (address/data overlap). ----
  val dataPhaseStarted =
    (state === DmaState.READ) || (state === DmaState.WDATA) ||
    (state === DmaState.WRESP) || (state === DmaState.WAW)
  val wantBuffer = io.req_valid && !bufValid && !io.done && state =/= DmaState.IDLE
  // Never advertise readiness while a buffered request is waiting to be
  // promoted: in IDLE-with-buffer the new request would be silently dropped
  // (the promote wins, and `wantBuffer` excludes IDLE).
  io.req_ready := !bufValid && ((state === DmaState.IDLE) || dataPhaseStarted)
  when (wantBuffer) {
    bufValid  := true.B
    bufDir    := io.req_dir
    bufWidth  := Mux(io.req_dir === 2.U, 2.U, io.req_width)
    bufRfAddr := io.req_rf_addr
    bufL3Addr := io.req_l3_addr
    bufStoreDataValid := io.req_store_data_valid
    for (lane <- 0 until K) bufStoreData(lane) := io.req_store_data(lane)
  }
}
