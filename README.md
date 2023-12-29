# Chisel workbench

This is a chisel workbench designed for someone who like docker containers and vscode dev container plugin.

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
```

Then you can use [vscode dev container plugin](https://marketplace.visualstudio.com/items?itemName=ms-vscode-remote.remote-containers) to connect this container. Happy coding (for chip)

## Useful Links

1. [Chisel project template](https://github.com/freechipsproject/chisel-template/tree/main#chisel-project-template)
2. [Chisel Bootcamp](https://mybinder.org/v2/gh/freechipsproject/chisel-bootcamp/master)
3. [ChiselTest](https://github.com/ucb-bar/chiseltest)
4. [Chisel Cheatsheet](https://github.com/freechipsproject/chisel-cheatsheet/releases/latest/download/chisel_cheatsheet.pdf)
5. [Chisel API Docs](https://javadoc.io/doc/org.chipsalliance/chisel_2.13/5.0.0/index.html)
