#!/usr/bin/env python3
"""engine_smoke2.py — stage-2 silicon verification for the K=16 engine:
  1. per-column K×K program (4 columns, 13 instructions)
  2. cross-program accumulation via the L3 ACCUM operand
  3. illegal instruction → ERR_INFO + STATUS.illegal
  4. fetch stats sanity (misses == lines, prefetches > 0)
"""
import os
import struct
import subprocess
import sys
import time

K = 16
BASE = "/dev/xdma0"
TOOLS = os.path.expanduser("~/dma_ip_drivers/XDMA/linux-kernel/tools")

A_ADDR, B_ADDR, ACCUM_ADDR, OUT_ADDR, CODE_ADDR = (
    0x4000_0000, 0x4000_0400, 0x4000_0800, 0x4000_0880, 0x4000_4000)


def s8(v: int) -> int:
    v &= 0xFF
    return v - 256 if v >= 128 else v


def h2c(addr: int, data: bytes) -> None:
    with open("/tmp/xs2.bin", "wb") as f:
        f.write(data)
    subprocess.run([f"{TOOLS}/dma_to_device", "-d", f"{BASE}_h2c_0",
                    "-f", "/tmp/xs2.bin", "-s", str(len(data)), "-a", hex(addr)],
                   capture_output=True, check=True)


def c2h(addr: int, n: int) -> bytes:
    subprocess.run([f"{TOOLS}/dma_from_device", "-d", f"{BASE}_c2h_0",
                    "-f", "/tmp/xs2r.bin", "-s", str(n), "-a", hex(addr)],
                   capture_output=True, check=True)
    with open("/tmp/xs2r.bin", "rb") as f:
        return f.read(n)


def regr(off: int) -> int:
    r = subprocess.run([f"{TOOLS}/reg_rw", f"{BASE}_bypass", hex(off), "w"],
                       capture_output=True, text=True)
    return int(r.stdout.split()[-1], 16)


def regw(off: int, v: int) -> None:
    subprocess.run([f"{TOOLS}/reg_rw", f"{BASE}_bypass", hex(off), "w", hex(v)],
                   capture_output=True)


def run_program(prog: list, timeout_s: float = 5.0) -> tuple:
    h2c(CODE_ADDR, struct.pack(f"<{len(prog)}I", *prog))
    regw(0x14, len(prog))
    regw(0x00, 1)
    regw(0x00, 0)
    deadline = time.monotonic() + timeout_s
    while time.monotonic() < deadline:
        if (regr(0x00) >> 1) & 1:
            return regr(0x08), regr(0x0C), regr(0x10)
        time.sleep(0.02)
    raise TimeoutError("engine did not finish")


def check(label: str, ok: bool, detail: str = "") -> None:
    print(f"[engine_smoke2] {'PASS' if ok else 'FAIL'}: {label} {detail}")
    if not ok:
        sys.exit(1)


def main() -> int:
    # ---------------------------------------------------------------
    # 1) per-column K×K program: OUT[i][j] = A[i]·B_row_j[15] + ACCUM[i]
    # ---------------------------------------------------------------
    a = [i + 1 for i in range(K)]
    b = [(j * 40 + i) & 0xFF for j in range(K) for i in range(K)]  # B[j][i]
    acc = [100 * i + 7 for i in range(K)]
    h2c(A_ADDR, bytes(a))
    h2c(B_ADDR, bytes(b))
    h2c(ACCUM_ADDR, struct.pack("<16i", *acc))

    cols = 4
    prog = [0x0000_0007]  # vle8 rd=0, sect=A, off=0
    for j in range(cols):
        prog.append(0x8087 | (j * K << 20))                   # vle8 rd=1, sect=B, off=j*16
        prog.append(0x0410_1103)                              # mmaLast rd=2, rs1=0, rs2=1, keep=0
        prog.append(0x0001_A127 | (j * 64 << 20))             # vse32 src=2, OUT, off=j*64
    status, err, stats = run_program(prog)
    pc = status & 0xFFFF
    frames = (status >> 16) & 0x7FFF
    illegal = (status >> 31) & 1
    check(f"K×K pc={pc} want={len(prog)-1}", pc == len(prog) - 1)
    check(f"K×K frames={frames} want={cols}", frames == cols)
    check("K×K no illegal", illegal == 0)

    out = struct.unpack(f"<{K * cols}i", c2h(OUT_ADDR, K * cols * 4))
    nbad = 0
    for j in range(cols):
        for i in range(K):
            exp = a[i] * s8(b[j * K + 15]) + acc[i]
            if out[j * K + i] != exp:
                nbad += 1
                if nbad < 4:
                    print(f"  col {j} lane {i}: got {out[j*K+i]} want {exp}")
    check(f"K×K OUT bit-exact ({K*cols} lanes)", nbad == 0)

    # ---------------------------------------------------------------
    # 2) cross-program accumulation: ACCUM = OUT1, run again → OUT2 = A·B[15]+OUT1
    # ---------------------------------------------------------------
    out1 = out[:K]
    h2c(ACCUM_ADDR, struct.pack("<16i", *out1))
    prog1 = [0x0000_0007, 0x0000_8087, 0x0410_1103, 0x0001_A127]
    status, err, stats = run_program(prog1)
    check("accum no illegal", ((status >> 31) & 1) == 0)
    out2 = struct.unpack("<16i", c2h(OUT_ADDR, 64))
    nbad = 0
    for i in range(K):
        exp = a[i] * s8(b[15]) + out1[i]
        if out2[i] != exp:
            nbad += 1
    check("cross-program accumulation bit-exact", nbad == 0)

    # ---------------------------------------------------------------
    # 3) illegal instruction → ERR_INFO + STATUS.illegal
    # ---------------------------------------------------------------
    bad = 0x0010_2010                                     # vsub (VALU family, unsupported)
    status, err, stats = run_program([0x0000_0007, bad])
    check(f"illegal flag set status=0x{status:08x}", ((status >> 31) & 1) == 1)
    check("pc stopped at the illegal instruction", (status & 0xFFFF) == 1)
    check(f"ERR_INFO holds the faulting word err=0x{err:08x}", err == bad)

    # ---------------------------------------------------------------
    # 4) fetch stats: K×K program = 4 lines → misses ≤ 4, prefetches > 0
    # ---------------------------------------------------------------
    misses = stats & 0xFFFF
    prefetches = (stats >> 16) & 0xFFFF
    check(f"K×K misses in range misses={misses}", 0 < misses <= 4)
    check(f"K×K prefetches > 0 prefetches={prefetches}", prefetches > 0)

    print("[engine_smoke2] ALL PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
