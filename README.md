# Chisel workbench for Open NPU design

[![Documentation Status](https://readthedocs.org/projects/chisel-opennpu/badge/?version=latest)](https://chisel-opennpu.readthedocs.io/en/latest/?badge=latest)

This is a chisel workbench designed for someone who like docker containers and vscode dev container plugin.

DEVELOP IN PROGRESS. COMMERCIAL USE IS NOT ALLOWED.

NO LICENSE PROVIDED CURRENTLY. 

USE AT YOUR OWN RISK.

## Usage

```bash
# Build docker image for chisel dev:
make image
# Create & Run the image as a container
make container
# Test chisel design
make test
# Build verilog design from chisel
make build
# Build docs, visit http://localhost:8000 to see the documentation
make docs
```

Then you can use [vscode dev container plugin](https://marketplace.visualstudio.com/items?itemName=ms-vscode-remote.remote-containers) to connect this container. Happy coding (for chip)

## Reference

1. [Chisel Matmul](https://github.com/kazutomo/Chisel-MatMul)
2. [Patmos VLIW processor](https://github.com/t-crest/patmos/tree/master/hardware)

## Useful Links

1. [Chisel project template](https://github.com/freechipsproject/chisel-template/tree/main#chisel-project-template)
2. [Chisel Bootcamp](https://mybinder.org/v2/gh/freechipsproject/chisel-bootcamp/master)
3. [ChiselTest](https://github.com/ucb-bar/chiseltest)
4. [Chisel Cheatsheet](https://github.com/freechipsproject/chisel-cheatsheet/releases/latest/download/chisel_cheatsheet.pdf)
5. [Chisel API Docs](https://javadoc.io/doc/org.chipsalliance/chisel_2.13/5.0.0/index.html)
