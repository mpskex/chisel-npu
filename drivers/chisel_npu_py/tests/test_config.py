"""Unit tests for NPUConfig validation and derived quantities."""

from __future__ import annotations

import pytest

from chisel_npu_py.config import NPUConfig, Section, default_config


def test_default_config_is_sane():
    cfg = default_config()
    cfg.validate()
    assert cfg.K == 16
    assert cfg.nop_wait == 2 * cfg.K + 8
    assert set(cfg.sections) == {"A", "B", "ACCUM", "OUT", "CODE"}
    assert cfg.ctrl["CTRL"] == 0x00
    assert cfg.ctrl["PROG_LEN"] == 0x14


def test_usable_windows_avoid_next_section():
    cfg = default_config()
    assert cfg.usable("A") == cfg.sections["B"].base - cfg.sections["A"].base
    assert cfg.usable("A") == 0x400
    # B's next section is ACCUM (0x800) — the binding constraint
    assert cfg.usable("B") == cfg.sections["ACCUM"].base - cfg.sections["B"].base
    assert cfg.usable("B") == 0x400
    # the last section caps at its own window
    assert cfg.usable("CODE") == cfg.sections["CODE"].window


def test_validate_rejects_bad_config():
    cfg = default_config()
    bad = NPUConfig(
        K=16,
        sections={"A": Section(0x1001, 0x1000)},  # misaligned base
        ctrl=cfg.ctrl,
    )
    with pytest.raises(ValueError):
        bad.validate()

    bad2 = NPUConfig(
        K=0,
        sections=cfg.sections,
        ctrl=cfg.ctrl,
    )
    with pytest.raises(ValueError):
        bad2.validate()


