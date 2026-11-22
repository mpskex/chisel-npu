"""NPUCTL v1 constants and decoders (mirror of the Chisel protocol object).

This module contains no board knowledge: no BAR offsets, no DDR sizes, no
XDMA.  The platform supplies those through a transport and PlatformInfo.
"""

from __future__ import annotations

from dataclasses import dataclass

ID_MAGIC = 0x4E505531

ID = 0x000
VERSION = 0x004
CAPS = 0x008
CONFIG = 0x00C
CONTROL = 0x010
STATUS = 0x014
IRQ_STATUS = 0x018
IRQ_ENABLE = 0x01C
ERROR_CODE = 0x020
ERROR_PC = 0x024
ERROR_INFO = 0x028
ERROR_ADDR = 0x02C
PROG_LEN = 0x030
ENTRY_PC = 0x034
SESSION_ID = 0x038
WATCHDOG = 0x03C
CYCLES = 0x040
MACS = 0x044
FETCH_STATS = 0x048
TIMESTAMP = 0x04C
TRACE_CTRL = 0x050
TRACE_STATUS = 0x054
TRACE_DATA = 0x058
TRACE_DROPS = 0x05C
SEG_TABLE = 0x100
SEG_STRIDE = 0x10
SCRATCH = 0x180

CTRL_START = 0
CTRL_STOP = 1
CTRL_SOFT_RESET = 2

STATUS_BUSY = 0
STATUS_DONE = 1
STATUS_ERROR = 2
STATUS_ABORTED = 3
IRQ_DONE = 0
IRQ_ERROR = 1
IRQ_TRACE_OVF = 2
IRQ_BUS_ERROR = 3

SEG_A = 0
SEG_B = 1
SEG_ACCUM = 2
SEG_OUT = 3
SEG_CODE = 4

ERROR_NONE = 0
ERROR_ILLEGAL = 1
ERROR_MEM_FAULT = 2
ERROR_WATCHDOG = 3
ERROR_BUS_ERROR = 4
ERROR_BAD_CONFIG = 5
ERROR_ABORTED = 6

# Default section windows (bytes); SEG_LIMIT == 0 selects these.
DEFAULT_LIMITS = {
    SEG_A: 0x400,
    SEG_B: 0x400,
    SEG_ACCUM: 0x80,
    SEG_OUT: 0x3780,
    SEG_CODE: 0x40000,
}


def seg_base_lo(seg: int) -> int:
    return SEG_TABLE + seg * SEG_STRIDE


def seg_base_hi(seg: int) -> int:
    return SEG_TABLE + seg * SEG_STRIDE + 4


def seg_limit(seg: int) -> int:
    return SEG_TABLE + seg * SEG_STRIDE + 8


@dataclass(frozen=True)
class DeviceInfo:
    version: int
    caps: int
    config: int
    K: int
    N: int
    L: int
    W: int
    num_seg: int
    trace_depth_log2: int
    axi_data_w: int
    protocol_major: int
    protocol_minor: int
    protocol_patch: int


def decode_version(v: int) -> tuple[int, int, int]:
    return (v >> 24) & 0xFF, (v >> 16) & 0xFF, (v >> 8) & 0xFF


def decode_caps(caps: int) -> tuple[int, int, int]:
    num_seg = (caps >> 8) & 0xF
    trace_depth_log2 = (caps >> 16) & 0xFF
    axi_data_w = (caps >> 28) & 0xF
    return num_seg, trace_depth_log2, axi_data_w


def decode_config(config: int) -> tuple[int, int, int, int]:
    return (
        config & 0xFF,
        (config >> 8) & 0xFF,
        (config >> 16) & 0xFF,
        (config >> 24) & 0xFF,
    )


def make_device_info(version: int, caps: int, config: int) -> DeviceInfo:
    major, minor, patch = decode_version(version)
    num_seg, trace_depth_log2, axi_data_w = decode_caps(caps)
    k, n, l, w = decode_config(config)
    return DeviceInfo(
        version=version, caps=caps, config=config,
        K=k, N=n, L=l, W=w,
        num_seg=num_seg, trace_depth_log2=trace_depth_log2,
        axi_data_w=axi_data_w,
        protocol_major=major, protocol_minor=minor, protocol_patch=patch,
    )
