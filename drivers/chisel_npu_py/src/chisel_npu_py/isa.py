"""isa — Python-side assembler for the NPU instruction set.

Mirrors ``src/main/scala/isa/NpuAssembler.scala`` (RISC-V-inspired 32-bit
words).  Pure encoding: no addresses, no hardware — the words can be
cross-checked against the Scala assembler in the Chisel test suite.

Formats:
    I-type  [imm[11:0](12) | rs1(5) | funct3(3) | rd(5) | opcode(7)]
    S-type  [rs3(5) | rnd(2) | rs2(5) | rs1(5) | funct3(3) | rd(5) | opcode(7)]

    LD  opcode 0x07  funct3 = transfer width (0=VX, 1=VE, 2=VR)
    ST  opcode 0x27  same
    MMA opcode 0x03  S-format: mma vd, vs1, vs2, vs3 ⇒ vd = A·B + C
"""

from __future__ import annotations

from typing import Iterable, Sequence

# ── opcodes ──────────────────────────────────────────────────────────────────
OP_NOP = 0x00
OP_MMA = 0x03
OP_LD = 0x07
OP_ST = 0x27

# ── LD/ST funct3 (transfer width / register class) ──────────────────────────
W_VX = 0
W_VE = 1
W_VR = 2

# ── MMA funct3 ───────────────────────────────────────────────────────────────
F3_MMA = 0
F3_MMA_LAST = 1

# ── LD/ST section selectors (rs1[1:0]) ───────────────────────────────────────
SECT_A = 0
SECT_B = 1
SECT_ACCUM = 2
SECT_OUT = 3

# ── word construction ────────────────────────────────────────────────────────


def _mask(x: int, bits: int) -> int:
    return x & ((1 << bits) - 1)


def enc_i(opcode: int, funct3: int, rd: int, rs1: int, imm: int) -> int:
    """I-type word: opcode | rd<<7 | funct3<<12 | rs1<<15 | imm<<20."""
    return (
        _mask(opcode, 7)
        | (_mask(rd, 5) << 7)
        | (_mask(funct3, 3) << 12)
        | (_mask(rs1, 5) << 15)
        | (_mask(imm, 12) << 20)
    )


def enc_s(opcode: int, funct3: int, rd: int, rs1: int, rs2: int, rs3: int) -> int:
    """S-type word: opcode | rd<<7 | funct3<<12 | rs1<<15 | rs2<<20 | rs3<<27."""
    return (
        _mask(opcode, 7)
        | (_mask(rd, 5) << 7)
        | (_mask(funct3, 3) << 12)
        | (_mask(rs1, 5) << 15)
        | (_mask(rs2, 5) << 20)
        | (_mask(rs3, 5) << 27)
    )


# ── NOP ──────────────────────────────────────────────────────────────────────

NOP = 0x0000_0000


# ── LD / ST (RVV-aligned vector transfers) ───────────────────────────────────


def vle8(rd: int, sect: int, off: int = 0) -> int:
    """vle8.v — load one K×N-byte vector into VX[rd] from section *sect*."""
    return enc_i(OP_LD, W_VX, rd, sect, off)


def vle16(rd: int, sect: int, off: int = 0) -> int:
    """vle16.v — load one K×2N-byte vector into VE[rd]."""
    return enc_i(OP_LD, W_VE, rd, sect, off)


def vle32(rd: int, sect: int, off: int = 0) -> int:
    """vle32.v — load one K×4N-byte vector into VR[rd]."""
    return enc_i(OP_LD, W_VR, rd, sect, off)


def vse8(src: int, sect: int, off: int = 0) -> int:
    """vse8.v — store VX[src] into section *sect*."""
    return enc_i(OP_ST, W_VX, src, sect, off)


def vse16(src: int, sect: int, off: int = 0) -> int:
    """vse16.v — store VE[src]."""
    return enc_i(OP_ST, W_VE, src, sect, off)


def vse32(src: int, sect: int, off: int = 0) -> int:
    """vse32.v — store VR[src]."""
    return enc_i(OP_ST, W_VR, src, sect, off)


# ── MMA (register-level MAC: vd = A·B + C) ───────────────────────────────────


def mma(rd: int, vs1: int, vs2: int, vs3: int) -> int:
    """mma vd, vs1, vs2, vs3 — one feed tick, one K×1 collect.

    vs1 = A (VX column), vs2 = B (VX row), vs3 = C (VR accumulator;
    x0 = zero).  vd[i] = A[i]·B[K−1] + C[i], 32-bit accumulate.
    """
    return enc_s(OP_MMA, F3_MMA, rd, vs1, vs2, vs3)


def mma_last(rd: int, vs1: int, vs2: int, vs3: int) -> int:
    """mma.last — same execution; the software group marker whose trailing
    nops cover the clct/drain window before vse32."""
    return enc_s(OP_MMA, F3_MMA_LAST, rd, vs1, vs2, vs3)


# ── Program container ────────────────────────────────────────────────────────


class Program:
    """An ordered list of instruction words with convenience helpers."""

    __slots__ = ("words",)

    def __init__(self, words: Sequence[int] = ()):
        self.words: list[int] = [int(w) & 0xFFFFFFFF for w in words]

    def append(self, word: int) -> "Program":
        self.words.append(int(word) & 0xFFFFFFFF)
        return self

    def extend(self, words: Iterable[int]) -> "Program":
        self.words.extend(int(w) & 0xFFFFFFFF for w in words)
        return self

    def nops(self, n: int) -> "Program":
        self.words.extend([NOP] * n)
        return self

    def to_bytes(self) -> bytes:
        """Little-endian word stream for the CODE section."""
        out = bytearray()
        for w in self.words:
            out += w.to_bytes(4, "little")
        return bytes(out)

    def __len__(self) -> int:
        return len(self.words)

    def __iter__(self):
        return iter(self.words)

    def __repr__(self) -> str:
        return f"Program({len(self.words)} words)"
