#!/usr/bin/env python3
"""engine_smoke4.py — register-level MAC (S-format) on silicon.

Validates the new mma model:  mma vd, vs1, vs2, vs3  ⇒  vd = A·B + C
  OUT[i] = A[i]·B[15] + C[i]  (one K×1 collect per mma; K = 16)

  Test 1: one-shot MAC with C from VR (vle32-loaded)
  Test 2: chained 16×32×32×16 GEMM (2-wide rounds, C-chaining, 8 groups)
  Test 3: full 16×256×256×16 GEMM (2 chunks × 8 groups, L3 C round-trip)
  Test 4: mma.reset → illegal halt with ERR_INFO

Run on the FPGA host:  python3 engine_smoke4.py
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

NOP = 0


def s8(v: int) -> int:
    v &= 0xFF
    return v - 256 if v >= 128 else v


# ---- ISA encodings (K=16, S-format mma) -------------------------------------

def vle8(rd: int, sect: int, off: int) -> int:
    return 0x07 | (rd << 7) | (sect << 15) | (off << 20)


def vle32(rd: int, sect: int, off: int) -> int:
    return 0x07 | (rd << 7) | (2 << 12) | (sect << 15) | (off << 20)


def vse32(src: int, sect: int, off: int) -> int:
    return 0x27 | (src << 7) | (2 << 12) | (sect << 15) | (off << 20)


def mma(rd: int, vs1: int, vs2: int, vs3: int) -> int:
    return 0x03 | (rd << 7) | (vs1 << 15) | (vs2 << 20) | (vs3 << 27)


def mma_last(rd: int, vs1: int, vs2: int, vs3: int) -> int:
    return mma(rd, vs1, vs2, vs3) | (1 << 12)


def mma_reset() -> int:
    return 0x03 | (0 << 7) | (2 << 12) | (1 << 15) | (2 << 20)


# ---- DDR / ctrl helpers -----------------------------------------------------

def h2c(addr: int, data: bytes) -> None:
    with open("/tmp/xs4.bin", "wb") as f:
        f.write(data)
    subprocess.run([f"{TOOLS}/dma_to_device", "-d", f"{BASE}_h2c_0",
                    "-f", "/tmp/xs4.bin", "-s", str(len(data)), "-a", hex(addr)],
                   capture_output=True, check=True)


def c2h(addr: int, n: int) -> bytes:
    """c2h reads are chunked at 64 B (larger single transfers fail on
    addresses whose DDR is not yet written)."""
    out = bytearray()
    for off in range(0, n, 64):
        subprocess.run([f"{TOOLS}/dma_from_device", "-d", f"{BASE}_c2h_0",
                        "-f", "/tmp/xs4r.bin", "-s", "64", "-a", hex(addr + off)],
                       capture_output=True, check=True)
        with open("/tmp/xs4r.bin", "rb") as f:
            out += f.read(64)
    return bytes(out[:n])


def regr(off: int) -> int:
    r = subprocess.run([f"{TOOLS}/reg_rw", f"{BASE}_bypass", hex(off), "w"],
                       capture_output=True, text=True)
    return int(r.stdout.split()[-1], 16)


def regw(off: int, v: int) -> None:
    subprocess.run([f"{TOOLS}/reg_rw", f"{BASE}_bypass", hex(off), "w", hex(v)],
                   capture_output=True)


def run_program(prog: list, timeout_s: float = 10.0) -> tuple:
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
    print(f"[engine_smoke4] {'PASS' if ok else 'FAIL'}: {label} {detail}")
    if not ok:
        sys.exit(1)


def nops(n: int) -> list:
    return [NOP] * n


# ---- Program builders -------------------------------------------------------

def one_shot_prog(c_vr: int, out_off: int, vd_vr: int) -> list:
    """vle8 A→VX[0]; vle8 B→VX[1]; vle32 C→VR[c_vr]; mma.last; nops; vse32."""
    return [
        vle8(0, 0, 0),
        vle8(1, 1, 0),
        vle32(c_vr, 2, 0),
        mma_last(vd_vr, 0, 1, c_vr),
    ] + nops(2 * K + 8) + [
        vse32(vd_vr, 3, out_off),
    ]


def group_prog(k_lo: int, k_hi: int, a_off: int, b_off: int,
               c_from_out: bool, col: int, b_vx0: int, b_vx1: int) -> list:
    """One 2-wide output group: rounds k_lo..k_hi-1.

    A[:,k] → VX[0] (off a_off + k*16); B0[k] → VX[b_vx0] (off b_off + k*32);
    B1[k] → VX[b_vx1] (off b_off + k*32 + 16).  Outputs VR[1] (col) and
    VR[2] (col+1).  C = 0 on the first round, self-chained after; if
    c_from_out, C is first vle32-loaded from the OUT section (cross-chunk).
    """
    prog = []
    if c_from_out:
        prog.append(vle32(1, 3, col * 64))
        prog.append(vle32(2, 3, (col + 1) * 64))
    for k in range(k_lo, k_hi):
        c0 = 0 if (k == k_lo and not c_from_out) else 1
        c1 = 0 if (k == k_lo and not c_from_out) else 2
        prog.append(vle8(0, 0, a_off + k * 16))
        prog.append(vle8(b_vx0, 1, b_off + k * 32))
        prog.append(vle8(b_vx1, 1, b_off + k * 32 + 16))
        prog.append(mma(1, 0, b_vx0, c0))
        prog.append(mma_last(2, 0, b_vx1, c1))
        prog += nops(2 * K + 8)
    prog.append(vse32(1, 3, col * 64))
    prog.append(vse32(2, 3, (col + 1) * 64))
    return prog


# ---- References -------------------------------------------------------------

def gemm_ref(a: list, b: list, nk: int, ncol: int) -> list:
    """out[i][j] = Σ_k s8(A[i][k]) · s8(B[k][j]) — a = row-major A[i][k]."""
    out = [[0] * K for _ in range(ncol)]
    for i in range(K):
        for j in range(ncol):
            out[j][i] = sum(s8(a[i][k]) * s8(b[k][j]) for k in range(nk))
    return out


# =============================================================================

def test1() -> None:
    print("=== Test 1: one-shot MAC with C from VR ===")
    a = [i + 1 for i in range(K)]
    b = [(i * 3 + 1) & 0xFF for i in range(K)]
    c = [100 * i + 7 for i in range(K)]
    h2c(A_ADDR, bytes(a))
    h2c(B_ADDR, bytes(b))
    h2c(ACCUM_ADDR, struct.pack("<16i", *c))

    prog = one_shot_prog(c_vr=1, out_off=0, vd_vr=2)
    status, err, _ = run_program(prog)
    check("no illegal", ((status >> 31) & 1) == 0, f"status=0x{status:08x}")

    out = struct.unpack("<16i", c2h(OUT_ADDR, K * 4))
    nbad = sum(1 for i in range(K) if out[i] != a[i] * s8(b[15]) + c[i])
    check("one-shot MAC bit-exact", nbad == 0)


def test2() -> None:
    print("=== Test 2: chained 16×32×32×16 GEMM (8 groups) ===")
    NI, NK = 16, 32
    a = [[(r * 7 + c * 3 + 1) & 0xFF for c in range(NK)] for r in range(K)]
    b = [[(k * 11 + j * 5 + 3) & 0xFF for j in range(K)] for k in range(NK)]

    a_flat = bytes(v for col in zip(*a) for v in col)   # A[:,k] columns
    h2c(A_ADDR, a_flat)

    ref = gemm_ref(a, b, NK, K)
    for g in range(8):
        col = 2 * g
        b_flat = bytearray(2 * NK * K)
        for k in range(NK):
            b_flat[k * 32 + 15] = b[k][col] & 0xFF
            b_flat[k * 32 + 16 + 15] = b[k][col + 1] & 0xFF
        h2c(B_ADDR, bytes(b_flat))

        prog = group_prog(0, NK, 0, 0, c_from_out=False, col=col, b_vx0=1, b_vx1=2)
        status, err, _ = run_program(prog)
        check(f"group {g} no illegal", ((status >> 31) & 1) == 0,
              f"status=0x{status:08x}")

        out = struct.unpack("<32i", c2h(OUT_ADDR + col * 64, 2 * K * 4))
        nbad = 0
        for jj in range(2):
            for i in range(K):
                if out[jj * K + i] != ref[col + jj][i]:
                    nbad += 1
                    if nbad < 4:
                        print(f"  col {col+jj} lane {i}: got {out[jj*K+i]} "
                              f"want {ref[col+jj][i]}")
        check(f"group {g} bit-exact (32 lanes × 2 cols)", nbad == 0)


def test3() -> None:
    print("=== Test 3: full 16×256×256×16 GEMM (chunked staging) ===")
    # Fixed section windows: A usable ≤ 1 KiB (64 vectors), B usable ≤ 1152 B
    # (72 vectors, before the OUT section at 0x880).  Per group (2 cols), the
    # inner 256 is staged as 8 chunks of 32 rounds: A[:,k] chunk (512 B) + B
    # chunk (64 vectors = 1024 B), C round-tripped through the OUT section.
    NK = 256
    CH = 32
    a = [[(r * 13 + c * 3 + 1) & 0xFF for c in range(NK)] for r in range(K)]
    b = [[(k * 17 + j * 7 + 5) & 0xFF for j in range(K)] for k in range(NK)]

    ref = gemm_ref(a, b, NK, K)
    for g in range(8):
        col = 2 * g
        for ci in range(NK // CH):
            k_lo, k_hi = ci * CH, ci * CH + CH
            a_chunk = bytes(v for k in range(k_lo, k_hi) for v in [a[i][k] for i in range(K)])
            b_flat = bytearray(2 * CH * K)
            for k in range(CH):
                b_flat[k * 32 + 15] = b[k_lo + k][col] & 0xFF
                b_flat[k * 32 + 16 + 15] = b[k_lo + k][col + 1] & 0xFF
            h2c(A_ADDR, a_chunk)
            h2c(B_ADDR, bytes(b_flat))

            prog = group_prog(0, CH, 0, 0, c_from_out=(ci > 0), col=col, b_vx0=1, b_vx1=2)
            status, err, _ = run_program(prog)
            check(f"group {g} chunk {ci} no illegal", ((status >> 31) & 1) == 0,
                  f"status=0x{status:08x}")

        out = struct.unpack("<32i", c2h(OUT_ADDR + col * 64, 2 * K * 4))
        nbad = 0
        for jj in range(2):
            for i in range(K):
                if out[jj * K + i] != ref[col + jj][i]:
                    nbad += 1
                    if nbad < 4:
                        print(f"  col {col+jj} lane {i}: got {out[jj*K+i]} "
                              f"want {ref[col+jj][i]}")
        check(f"group {g} bit-exact (256-term sum, 32 lanes)", nbad == 0)


def test4() -> None:
    print("=== Test 4: mma.reset → illegal halt with ERR_INFO ===")
    prog = [vle8(0, 0, 0), mma_reset(), vse32(0, 3, 0)]
    status, err, _ = run_program(prog)
    check("illegal flag set", ((status >> 31) & 1) == 1, f"status=0x{status:08x}")
    check("pc stops at the faulting word", (status & 0xFFFF) == 1,
          f"pc={(status & 0xFFFF)}")
    check("ERR_INFO holds the faulting word", err == mma_reset(),
          f"err=0x{err:08x} want=0x{mma_reset():08x}")


def main() -> int:
    test1()
    test2()
    test3()
    test4()
    print("[engine_smoke4] ALL PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
