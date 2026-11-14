#!/usr/bin/env python3
"""Multi-seed VX read test: structural vs data-dependent corruption."""
import time

import numpy as np

from chisel_npu_py import ChiselNPU, isa
from chisel_npu_py.config import (
    CTRL, CTRL_DONE_BIT, CTRL_START_BIT, PROG_LEN,
)
from chisel_npu_py.npu import _words_bytes

npu = ChiselNPU()
cfg = npu.cfg
dev = npu.dev
K = cfg.K


def run(words, mems):
    dev.write_staged("CODE", _words_bytes(words))
    for name, buf in mems.items():
        dev.write_staged(name, buf)
    dev.ctrl_write_reg(cfg.ctrl[PROG_LEN], len(words))
    dev.ctrl_write_reg(cfg.ctrl[CTRL], 1 << CTRL_START_BIT)
    dev.ctrl_write_reg(cfg.ctrl[CTRL], 0)
    t0 = time.monotonic()
    while time.monotonic() - t0 < 10:
        if (dev.ctrl_read_reg(cfg.ctrl[CTRL]) >> CTRL_DONE_BIT) & 1:
            return
        time.sleep(0.005)
    raise SystemExit("timeout")


for seed in (0xA5, 0x11, 0x77, 0x00, 0xFF):
    rng = np.random.default_rng(seed)
    data = rng.integers(0, 256, size=K, dtype=np.uint8)
    run([isa.vle8(0, isa.SECT_A, 0), isa.vse8(0, isa.SECT_A, 0)], {"A": data})
    got = np.empty(K, dtype=np.uint8)
    dev.read_staged("A", got)
    bad = [i for i in range(K) if got[i] != data[i]]
    xors = [f"{i}:{int(data[i]) ^ int(got[i]):02x}" for i in bad]
    print(f"seed=0x{seed:02x} bad={len(bad):2d} positions={bad}")
    print(f"   xors={xors}")
