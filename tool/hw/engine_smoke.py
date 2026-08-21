#!/usr/bin/env python3
"""engine_smoke.py — silicon smoke test for the K=16 NpuProgramEngine bitstream.

Stages A/B/ACCUM + a hand-coded one-shot program via the XDMA char devices,
kicks the engine through the ctrl register, and verifies the pinned formula
    OUT[i] = A[i] · B[K-1] + ACCUM[i]   (signed int8 operands, int32 result)

Memory map (must match engine.NpuSections):
    A     @ 0x4000_0000  int8[16]   16 B
    B     @ 0x4000_0400  int8[16]   16 B
    ACCUM @ 0x4000_0800  int32[16]  64 B
    OUT   @ 0x4000_0880  int32[16]  64 B
    CODE  @ 0x4000_4000  4 words    16 B

ctrl (BAR bypass):
    0x00 [0]start [1]done [2]busy   0x08 STATUS ([31]illegal [30:16]frames [15:0]pc)
    0x0C ERR_INFO   0x10 FETCH_STATS   0x14 PROG_LEN

Program (NpuAssembler encodings, one-shot at K=16):
    vle8  rd=0, sect=A, off=0   -> 0x0000_0007
    vle8  rd=1, sect=B, off=0   -> 0x0000_8087
    mmaLast rd=2, rs1=0, rs2=1, keep=false -> 0x0410_1103
    vse32 src=2, sect=OUT, off=0 -> 0x0001_A127
"""

import os
import struct
import subprocess
import sys
import time

K = 16
BASE = "/dev/xdma0"

A_ADDR, B_ADDR, ACCUM_ADDR, OUT_ADDR, CODE_ADDR = (
    0x4000_0000, 0x4000_0400, 0x4000_0800, 0x4000_0880, 0x4000_4000)
OUT_N = K * 4

PROG = [0x0000_0007, 0x0000_8087, 0x0410_1103, 0x0001_A127]  # 4 words
PROG_LEN = len(PROG)


def s8(v: int) -> int:
    v &= 0xFF
    return v - 256 if v >= 128 else v


TOOLS = os.path.expanduser("~/dma_ip_drivers/XDMA/linux-kernel/tools")


def xfer(dev: str, addr: int, data: bytes, write: bool, n: int = 0) -> bytes:
    """h2c/c2d via the vendor dma_to_device/dma_from_device tools (the proven
    path — raw pread on the cdev returns errno 512)."""
    tmp = f"/tmp/xdma_smoke_{os.getpid()}.bin"
    if write:
        with open(tmp, "wb") as f:
            f.write(data)
        subprocess.check_call(
            [f"{TOOLS}/dma_to_device", "-d", dev, "-f", tmp,
             "-s", str(len(data)), "-a", hex(addr)],
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        return b""
    subprocess.check_call(
        [f"{TOOLS}/dma_from_device", "-d", dev, "-f", tmp,
         "-s", str(n), "-a", hex(addr)],
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    with open(tmp, "rb") as f:
        return f.read(n)


def reg_read(off: int) -> int:
    out = subprocess.check_output(
        [f"{TOOLS}/reg_rw", f"{BASE}_bypass", f"{off:#x}", "w"],
        text=True, stderr=subprocess.DEVNULL)
    # reg_rw prints "<addr> <value>"; take the last 0x token
    toks = [t for t in out.split() if t.startswith("0x")]
    return int(toks[-1], 16)


def reg_write(off: int, val: int) -> None:
    subprocess.check_call(
        [f"{TOOLS}/reg_rw", f"{BASE}_bypass", f"{off:#x}", "w", f"{val:#x}"],
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


def main() -> int:
    a = [i + 1 for i in range(K)]
    b = [(i * 3 + 1) & 0xFF for i in range(K)]
    acc = [100 * i + 7 for i in range(K)]

    print(f"[engine_smoke] staging A/B/ACCUM and the {PROG_LEN}-word program")
    xfer(f"{BASE}_h2c_0", A_ADDR, bytes(a), True)
    xfer(f"{BASE}_h2c_0", B_ADDR, bytes(b), True)
    xfer(f"{BASE}_h2c_0", ACCUM_ADDR,
         struct.pack("<16i", *acc), True)
    xfer(f"{BASE}_h2c_0", CODE_ADDR,
         struct.pack("<4I", *PROG), True)

    reg_write(0x14, PROG_LEN)   # PROG_LEN
    reg_write(0x00, 1)          # start
    reg_write(0x00, 0)

    deadline = time.monotonic() + 10.0
    done = False
    while time.monotonic() < deadline:
        ctrl = reg_read(0x00)
        if (ctrl >> 1) & 1:
            done = True
            break
        time.sleep(0.02)
    if not done:
        print("[engine_smoke] FAIL: engine never asserted done")
        return 1

    status = reg_read(0x08)
    illegal = (status >> 31) & 1
    pc = status & 0xFFFF
    frames = (status >> 16) & 0x7FFF
    err = reg_read(0x0C)
    stats = reg_read(0x10)
    print(f"[engine_smoke] done. STATUS=0x{status:08x} "
          f"(pc={pc} frames={frames} illegal={illegal}) "
          f"ERR_INFO=0x{err:08x} FETCH_STATS=0x{stats:08x}")
    if illegal:
        print("[engine_smoke] FAIL: illegal flag set")
        return 1

    out_raw = xfer(f"{BASE}_c2h_0", OUT_ADDR, None, False, n=OUT_N)
    out = struct.unpack(f"<{K}i", out_raw)

    nbad = 0
    for i in range(K):
        exp = a[i] * s8(b[K - 1]) + acc[i]
        if out[i] != exp:
            print(f"[engine_smoke] lane {i}: got {out[i]} want {exp}")
            nbad += 1
    if nbad:
        print("[engine_smoke] FAIL")
        return 1

    print("[engine_smoke] PASS: OUT[i] = A[i]·B[15] + ACCUM[i] for all lanes")
    print(f"[engine_smoke]        OUT[0..3] = {out[:4]}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
