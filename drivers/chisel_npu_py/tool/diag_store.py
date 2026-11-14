#!/usr/bin/env python3
"""Read-path diagnostic: is the corruption in the AXI read or the VX extraction?

  A. vle8  A[0:32]    -> vse8  A      (VX read, in-place)
  D. vle32 A[0:128]   -> vse32 OUT    (VR read of the SAME A data)
  F. vle8  A[64:96]   -> vse8  OUT    (VX read at a different beat)
  E. vle8  ACCUM[0:32]-> vse8  OUT    (VX read from ACCUM, not A)
"""
import time

import numpy as np

from chisel_npu_py import ChiselNPU, isa
from chisel_npu_py.config import (
    CTRL, CTRL_DONE_BIT, CTRL_START_BIT, PROG_LEN, STATUS,
)
from chisel_npu_py.npu import _words_bytes

npu = ChiselNPU()
cfg = npu.cfg
dev = npu.dev
K = cfg.K
WINDOW = cfg.sections["OUT"].window // 4


def run(words, mems, prefill_byte=None):
    dev.write_staged("CODE", _words_bytes(words))
    for name, buf in mems.items():
        dev.write_staged(name, buf)
    if prefill_byte is not None:
        dev.write_staged("OUT", np.full(WINDOW, prefill_byte * 0x01010101,
                                        dtype=np.uint32))
    dev.ctrl_write_reg(cfg.ctrl[PROG_LEN], len(words))
    dev.ctrl_write_reg(cfg.ctrl[CTRL], 1 << CTRL_START_BIT)
    dev.ctrl_write_reg(cfg.ctrl[CTRL], 0)
    t0 = time.monotonic()
    while time.monotonic() - t0 < 10:
        if (dev.ctrl_read_reg(cfg.ctrl[CTRL]) >> CTRL_DONE_BIT) & 1:
            return
        time.sleep(0.005)
    raise SystemExit("timeout")


def read_section(name, nbytes):
    buf = np.empty(nbytes, dtype=np.uint8)
    dev.read_staged(name, buf)
    return buf


def read_out():
    out = np.empty(WINDOW, dtype=np.int32)
    dev.read_staged("OUT", out)
    return out.view(np.uint8)


def report(tag, exp, got, prefill=None):
    exp = np.asarray(exp, dtype=np.uint8)
    got = np.asarray(got, dtype=np.uint8)
    bad = []
    for i in range(len(exp)):
        e, g = int(exp[i]), int(got[i])
        if g != e:
            bad.append((i, e, g, "UNWRITTEN" if prefill is not None and g == prefill else "WRONG"))
    print(f"--- {tag}: bad={len(bad)}/{len(exp)}")
    for i, e, g, s in bad[:24]:
        print(f"  [{i:3d}] exp={e:3d} got={g:3d} xor=0x{e ^ g:02x} {s}")


rng = np.random.default_rng(0xA5)
data = rng.integers(0, 256, size=4 * K, dtype=np.uint8)  # 128 bytes
print("K =", K, "VX_BYTES =", K * cfg.N // 8)

# A: VX read of A[0:32], in-place
run([isa.vle8(0, isa.SECT_A, 0), isa.vse8(0, isa.SECT_A, 0)], {"A": data})
report("A: vle8 A[0:32] -> vse8 A", data[:K], read_section("A", 4 * K)[:K])

# D: VR read of the SAME A[0:128]
run([isa.vle32(1, isa.SECT_A, 0), isa.vse32(1, isa.SECT_OUT, 0)],
    {"A": data}, prefill_byte=0x55)
report("D: vle32 A[0:128] -> vse32 OUT", data, read_out()[:4 * K], 0x55)

# F: VX read of A[64:96] (different beat)
run([isa.vle8(0, isa.SECT_A, 64), isa.vse8(0, isa.SECT_OUT, 0)],
    {"A": data}, prefill_byte=0xAA)
report("F: vle8 A[64:96] -> vse8 OUT", data[64:96], read_out()[:K], 0xAA)

# E: VX read from ACCUM[0:32]
accum = rng.integers(0, 256, size=4 * K, dtype=np.uint8)
run([isa.vle8(0, isa.SECT_ACCUM, 0), isa.vse8(0, isa.SECT_OUT, 0)],
    {"ACCUM": accum}, prefill_byte=0xAA)
report("E: vle8 ACCUM[0:32] -> vse8 OUT", accum[:K], read_out()[:K], 0xAA)
