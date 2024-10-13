// // See README.md for license details.

// package ncore.mmu

// import chisel3._
// import chisel3.util._
// import isa.micro_op._
// import ncore._


// class OffsetGenerator(val n: Int = 8) extends Module {
//     val io = IO(new Bundle {
//         val keep    = Input(Vec(n, Bool()))
//         val out     = Output(Vec(n, UInt(log2Ceil(n * n).W)))
//     })
//     val init_value = Seq.tabulate(n)(i => (n * i).U(log2Ceil(n * n).W))
//     val regs = RegInit(VecInit(init_value))

//     for (i <- 0 until n){
//         when (io.keep(i)) {
//             regs(i) := init_value(i)
//         }.otherwise {
//             regs(i) := (regs(i) + 1.U) % (n * n).U
//         }
//         io.out(i) := regs(i)
//     }
// }

// /**
//  * This is the neural core design
//  */
// class MemoryManageUnit(
//     val n: Int = 8, 
//     val size: Int = 4096
//     ) extends Module {
//     val io = IO(new Bundle {
//         val ctrl            = Input(Vec(n * n, new MMUCtrlBundle(size)))
//         val op_a            = Output(Vec(n * n, UInt(log2Ceil(size).W)))
//         val op_b            = Output(Vec(n * n, UInt(log2Ceil(size).W)))
//         val res             = Output(Vec(n * n, UInt(log2Ceil(size).W)))
//     })

//     val offsetgen_a = new OffsetGenerator(n)
//     val offsetgen_b = new OffsetGenerator(n)

//     // Create 2d register for horizontal & vertical
//     val reg_h = RegInit(VecInit(Seq.fill((n - 1) * n)(0.U(log2Ceil(size).W))))
//     val reg_r = RegInit(VecInit(Seq.fill((n - 1) * n)(0.U(log2Ceil(size).W))))
//     val reg_v = RegInit(VecInit(Seq.fill((n - 1) * n)(0.U(log2Ceil(size).W))))

//     for (i <- 0 until n){
//         for (j <- 0 until n) {
//             offsetgen_a.io.keep(i) := io.ctrl(n * i).offset_keep
//             offsetgen_b.io.keep(i) := io.ctrl(j).offset_kee
//             // ==== INPUT ====
//             // vertical
//             if (i==0) {
//                 when (io.ctrl(n * i + j).h_only) {
//                     io.op_b(j) := io.ctrl(n * i + j).in_addr + offsetgen_b.io.out(j)
//                 } .otherwise {
//                     io.op_b(j) := io.ctrl(n * i + j).in_addr + offsetgen_b.io.out(j)
//                 }
//             } else {
//                 io.op_b(0)(n * i + j) := reg_v(n * (i - 1) + j)
//             }
//             if (i < n - 1 && j < n)
//                 reg_v(n * i + j) := io.op_b(n * i + j)

//             // horizontal & result
//             if (j==0) {
//                 io.op_a(n * i) := io.ctrl(n * i + j).in_addr + offsetgen_a.io.out(i)
//                 io.res(n * i) := io.ctrl(n * i + j).out_addr + offsetgen_a.io.out(i)
//             } else {
//                 io.op_a(n * i + j) := reg_h((n - 1) * i + (j - 1))
//                 io.res(n * i + j) := reg_r((n - 1) * i + (j - 1))
//             }
//             if (i < n && j < n - 1) {
//                 reg_h((n - 1) * i + j) := io.op_a(n * i + j)
//                 reg_r((n - 1) * i + j) := io.res(n * i + j)
//             }
//         }
//     }

// }