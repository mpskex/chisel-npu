"""Exception hierarchy for the chisel_npu_py driver."""


class XDMAError(RuntimeError):
    """Device-level error: driver, device nodes, or native module problems."""


class NPUError(XDMAError):
    """NPU protocol-level error (e.g. busy when a kick was attempted)."""


class NPUTimeoutError(NPUError):
    """The engine did not assert done within the requested timeout."""


class NPUProgramError(NPUError):
    """The engine halted on an illegal instruction (STATUS.illegal=1)."""

    def __init__(self, message: str, pc: int = 0, err_info: int = 0):
        super().__init__(message)
        self.pc = pc
        self.err_info = err_info
