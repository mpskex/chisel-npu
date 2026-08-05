"""Build configuration for the chisel_npu_py extension module.

The compiled extension is `chisel_npu_py._native` (pybind11).  It is built
on the FPGA host inside the target venv (ABI must match the interpreter);
this file is the standard pybind11 setuptools glue.
"""

from pybind11.setup_helpers import Pybind11Extension, build_ext
from setuptools import setup

ext_modules = [
    Pybind11Extension(
        "chisel_npu_py._native",
        ["src/chisel_npu_py/native_src/native.cpp"],
        cxx_std=17,
    ),
]

setup(ext_modules=ext_modules, cmdclass={"build_ext": build_ext})
