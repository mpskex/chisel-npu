"""Exception hierarchy for the chisel_npu_py driver."""


class XDMAError(RuntimeError):
    """Device-level error: driver, device nodes, or native module problems."""


class NPUError(XDMAError):
    """NPU protocol-level error (e.g. busy when a kick was attempted)."""


class NPUTimeoutError(NPUError):
    """The NPU did not assert done within the requested timeout."""
