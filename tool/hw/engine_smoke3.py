#!/usr/bin/env python3
"""engine_smoke3.py — full 16×16 matrix case on silicon.

  Pass 1: OUT[i][j] = A[i]·B[j][15] + ACCUM[i]   (all 16 columns, 256 outputs)
          program = 1 + 16×3 = 49 instructions (13 cache lines)
  Pass 2: A2 differs, ACCUM = OUT1 (previous pass) →
          OUT2[i][j] = A2[i]·B[j][15] + OUT1[i][j]
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
    with open("/tmp/xs3.bin", "wb") as f:
        f.write(data)
    subprocess.run([f"{TOOLS}/dma_to_device", "-d", f"{BASE}_h2c_0",
                    "-f", "/tmp/xs3.bin", "-s", str(len(data)), "-a", hex(addr)],
                   capture_output=True, check=True)


def c2h(addr: int, n: int) -> bytes:
    """c2h reads are chunked at 64 B (larger single transfers fail on
    addresses whose DDR is not yet written)."""
    out = bytearray()
    for off in range(0, n, 64):
        subprocess.run([f"{TOOLS}/dma_from_device", "-d", f"{BASE}_c2h_0",
                        "-f", "/tmp/xs3r.bin", "-s", "64", "-a", hex(addr + off)],
                       capture_output=True, check=True)
        with open("/tmp/xs3r.bin", "rb") as f:
            out += f.read(64)
    return bytes(out[:n])


def regr(off: int) -> int:
    r = subprocess.run([f"{TOOLS}/reg_rw", f"{BASE}_bypass", hex(off), "w"],
                       capture_output=True, text=True)
    return int(r.stdout.split()[-1], 16)


def regw(off: int, v: int) -> None:
    subprocess.run([f"{TOOLS}/reg_rw", f"{BASE}_bypass", hex(off), "w", hex(v)],
                   capture_output=True)


def run_program(prog: list, timeout_s: float = 8.0) -> tuple:
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
    print(f"[engine_smoke3] {'PASS' if ok else 'FAIL'}: {label} {detail}")
    if not ok:
        sys.exit(1)


def full_gemm_prog() -> list:
    """vle8 A; for each B row j: vle8 B row j, mmaLast rd=2, vse32 col j."""
    prog = [0x0000_0007]                                   # vle8 rd=0, sect=A, off=0
    for j in range(K):
        prog.append(0x8087 | (j * K << 20))                # vle8 rd=1, sect=B, off=j*16
        prog.append(0x0410_1103)                           # mmaLast rd=2, rs1=0, rs2=1, keep=0
        prog.append(0x0001_A127 | (j * 64 << 20))          # vse32 src=2, sect=OUT, off=j*64
    return prog


def main() -> int:
    a1 = [i + 1 for i in range(K)]
    a2 = [(i * 5 + 3) & 0xFF for i in range(K)]
    b = [(j * 40 + i) & 0xFF for j in range(K) for i in range(K)]  # B[j][i]
    acc = [100 * i + 7 for i in range(K)]

    # ---------------------------------------------------------------
    # Pass 1: full 16×16 GEMM
    # ---------------------------------------------------------------
    h2c(A_ADDR, bytes(a1))
    h2c(B_ADDR, bytes(b))
    h2c(ACCUM_ADDR, struct.pack("<16i", *acc))

    prog = full_gemm_prog()
    check(f"program length {len(prog)} (expect 49)", len(prog) == 49)
    status, err, stats = run_program(prog)
    pc = status & 0xFFFF
    frames = (status >> 16) & 0x7FFF
    check(f"pc={pc} want=48", pc == 48)
    check(f"frames={frames} want=16", frames == 16)
    check("no illegal", ((status >> 31) & 1) == 0, f"status=0x{status:08x}")

    out1 = struct.unpack(f"<{K * K}i", c2h(OUT_ADDR, K * K * 4))
    nbad = 0
    for j in range(K):
        bj = s8(b[j * K + 15])
        for i in range(K):
            exp = a1[i] * bj + acc[i]
            if out1[j * K + i] != exp:
                nbad += 1
                if nbad < 4:
                    print(f"  pass1 col {j} lane {i}: got {out1[j*K+i]} want {exp}")
    check(f"pass1 OUT bit-exact ({K*K} lanes)", nbad == 0)

    misses = stats & 0xFFFF
    prefetches = (stats >> 16) & 0xFFFF
    check(f"misses={misses} (≤13)", 0 < misses <= 13)
    check(f"prefetches={prefetches} (>0)", prefetches > 0)

    # ---------------------------------------------------------------
    # Pass 2: new A, ACCUM = OUT1 → layer-style accumulation
    # ---------------------------------------------------------------
    h2c(A_ADDR, bytes(a2))
    h2c(ACCUM_ADDR, struct.pack("<16i", *out1[:K]))
    status, err, stats = run_program(prog)
    check("pass2 no illegal", ((status >> 31) & 1) == 0, f"status=0x{status:08x}")

    out2 = struct.unpack(f"<{K * K}i", c2h(OUT_ADDR, K * K * 4))
    nbad = 0
    for j in range(K):
        bj = s8(b[j * K + 15])
        for i in range(K):
            exp = a2[i] * bj + out1[i]   # ACCUM = K×1 bias (col 0)
            if out2[j * K + i] != exp:
                nbad += 1
                if nbad < 4:
                    print(f"  pass2 col {j} lane {i}: got {out2[j*K+i]} want {exp}")
    check(f"pass2 OUT bit-exact ({K*K} lanes)", nbad == 0)

    print("[engine_smoke3] ALL PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
