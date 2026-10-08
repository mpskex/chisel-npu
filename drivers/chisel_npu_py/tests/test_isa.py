"""Unit tests for the Python assembler (isa.py).

The expected words are cross-checked against the Scala NpuAssembler values
verified in the Chisel test suite and on silicon (engine_smoke4).
"""

from __future__ import annotations

import pytest

from chisel_npu_py import isa
from chisel_npu_py.isa import Program


def _fields(w: int):
    return {
        "opcode": w & 0x7F,
        "rd": (w >> 7) & 0x1F,
        "funct3": (w >> 12) & 0x7,
        "rs1": (w >> 15) & 0x1F,
        "rs2": (w >> 20) & 0x1F,
        "rs3": (w >> 27) & 0x1F,
    }


def test_nop_is_zero():
    assert isa.NOP == 0


def test_vle8_encoding():
    w = isa.vle8(0, isa.SECT_A, 0)
    assert w == 0x0000_0007
    f = _fields(w)
    assert f == {"opcode": 0x07, "rd": 0, "funct3": 0, "rs1": 0, "rs2": 0, "rs3": 0}


def test_vle8_section_b():
    # vle8 rd=1, sect=B, off=0 → 0x8087 (silicon-verified)
    assert isa.vle8(1, isa.SECT_B, 0) == 0x0000_8087


def test_vle32_encoding():
    # vle32 rd=1, sect=ACCUM, off=0 → 0x12087 (silicon-verified)
    assert isa.vle32(1, isa.SECT_ACCUM, 0) == 0x0001_2087


def test_vse32_encoding():
    # vse32 src=2, sect=OUT, off=0 → 0x1a127 (silicon-verified)
    assert isa.vse32(2, isa.SECT_OUT, 0) == 0x0001_A127


def test_mma_encoding():
    # mma(rd=1, vs1=0, vs2=1, vs3=0) → 0x100083 (silicon-verified)
    w = isa.mma(1, 0, 1, 0)
    assert w == 0x0010_0083
    f = _fields(w)
    assert f["opcode"] == 0x03 and f["funct3"] == 0
    assert f["rd"] == 1 and f["rs1"] == 0 and f["rs2"] == 1 and f["rs3"] == 0


def test_mma_last_encoding():
    # mma_last(rd=2, vs1=0, vs2=2, vs3=2) → 0x10201103 (silicon-verified)
    w = isa.mma_last(2, 0, 2, 2)
    assert w == 0x1020_1103
    f = _fields(w)
    assert f["opcode"] == 0x03 and f["funct3"] == 1
    assert f["rd"] == 2 and f["rs1"] == 0 and f["rs2"] == 2 and f["rs3"] == 2


def test_offsets_use_12_bit_field():
    w = isa.vle8(0, isa.SECT_A, 0xABC)
    assert (w >> 20) & 0xFFF == 0xABC
    # 12-bit truncation
    assert (isa.vle8(0, isa.SECT_A, 0x1234) >> 20) & 0xFFF == 0x234


def test_program_container():
    p = Program([1, 2]).append(isa.NOP).nops(3).append(0x7)
    assert len(p) == 7
    assert p.to_bytes() == b"\x01\x00\x00\x00\x02\x00\x00\x00" + b"\x00" * 16 + \
        b"\x07\x00\x00\x00"
    assert p.words[0] == 1


def test_program_masks_negative_words():
    p = Program([-1])
    assert p.words[0] == 0xFFFFFFFF
