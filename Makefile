.PHONY: docs build build-fpga build-fpga-debug build-fpga-clean test-hw py-build py-deploy py-test-unit py-test-hw

export ARCH=`uname -m`
# Currently the image does not support arm64, 
# We have to hard code the arch into amd64 as a workaround
# export ARCH=amd64
export VER=0.4

export SBT_OPTS="-Xmx8G -Xss2M"

image:
	make image-${ARCH}

image-arm64:
	docker build docker -t fangruil/chisel-dev:arm64 -t fangruil/chisel-dev:arm64-${VER} --platform linux/arm64

image-x86_64:
	make image-amd64

image-amd64:
	docker build docker -t fangruil/chisel-dev:amd64 -t fangruil/chisel-dev:amd64-${VER} --platform linux/amd64

container:
	echo ${ARCH};
	if [ ${ARCH} = "arm64" ]; then docker run -u $(id -u):$(id -g) --rm -i -v ${PWD}:/workspace/ fangruil/chisel-dev:arm64 bash; else docker run -u $(id -u):$(id -g) --rm -i -v ${PWD}:/workspace/ fangruil/chisel-dev:amd64 bash; fi

test:
	if [ ${ARCH} = "arm64" ]; then docker run -u $(id -u):$(id -g) --rm -i --env SBT_OPTS=${SBT_OPTS} -v ${PWD}:/workspace/ fangruil/chisel-dev:arm64 sbt test; else docker run -u $(id -u):$(id -g) --rm -i --env SBT_OPTS=${SBT_OPTS} -v ${PWD}:/workspace/ fangruil/chisel-dev:amd64 sbt test; fi

top.v:
	if [ ${ARCH} = "arm64" ]; then docker run -u $(id -u):$(id -g) --rm -i -v ${PWD}:/workspace/ fangruil/chisel-dev:arm64 sbt run; else docker run -u $(id -u):$(id -g) --rm -i -v ${PWD}:/workspace/ fangruil/chisel-dev:amd64 sbt run; fi

build: top.v

build-sc: top.v
	if [ ${ARCH} = "arm64" ]; then docker run -u $(id -u):$(id -g) --rm -i -v ${PWD}:/workspace/ fangruil/chisel-dev:arm64 verilator top.v -sc -Mdir systemc; else docker run -u $(id -u):$(id -g) --rm -i -v ${PWD}:/workspace/ fangruil/chisel-dev:amd64 verilator top.v -sc -Mdir systemc; fi

# Vivado FPGA build (host tool, not Docker)
VIVADO        ?= $(HOME)/Xilinx/2025.2/Vivado/bin/vivado
CHIP          ?= xc7k480t
VIVADO_LOGDIR ?= build

build-fpga: top.v
	mkdir -p $(VIVADO_LOGDIR)
	$(VIVADO) -mode batch \
		-source ip/vivado/$(CHIP)/scripts/build_npu.tcl \
		-log $(VIVADO_LOGDIR)/build_npu_$(CHIP).log \
		-journal $(VIVADO_LOGDIR)/build_npu_$(CHIP).jou

build-fpga-debug: top.v
	mkdir -p $(VIVADO_LOGDIR)
	$(VIVADO) -mode batch \
		-source ip/vivado/$(CHIP)/scripts/build_npu_with_ila.tcl \
		-log $(VIVADO_LOGDIR)/build_npu_$(CHIP)_debug.log \
		-journal $(VIVADO_LOGDIR)/build_npu_$(CHIP)_debug.jou

build-fpga-clean:
	rm -rf ip/vivado/$(CHIP)/proj

# FPGA hardware tests (run against live FPGA host via SSH)
FPGA_HOST ?= fpga

test-hw:
	python3 -m pytest tool/hw/tests/ -v -m hw \
		--fpga-host $(FPGA_HOST) --skip-program

# Python userspace driver (drivers/chisel_npu_py)
PY_DIR      := drivers/chisel_npu_py
PY_PYTHON   ?= $(HOME)/miniconda3/bin/python

py-build: ## build the sdist for chisel_npu_py (dev host; extension builds on FPGA host)
	$(PY_PYTHON) -m build --sdist $(PY_DIR) --outdir $(PY_DIR)/dist

py-deploy: ## rsync + install driver/tests/udev on the FPGA host (needs .env.sh env)
	bash $(PY_DIR)/tool/deploy.sh

py-test-unit: ## unit/mock tests, no hardware needed (dev host)
	PYTHONPATH=$(PY_DIR)/src $(PY_PYTHON) -m pytest $(PY_DIR)/tests -m "not hw" -v

py-test-hw: ## run the hw suite natively on the FPGA host (via SSH)
	source .env.sh >/dev/null 2>&1; ssh -o BatchMode=yes -o ConnectTimeout=15 \
		-i "$${SSH_IDENTITY:-$$HOME/.ssh/id_fpga_local}" "$${FPGA_HOST}" \
		"cd ~/chisel_npu_py && .venv/bin/python -m pytest tests -m hw -v"

push:
	make push-image-${ARCH}

push-image-x86_64:
	make push-image-amd64

push-image-amd64:
	docker push fangruil/chisel-dev:amd64 
	docker push fangruil/chisel-dev:amd64-${VER}

push-image-arm64:
	docker push fangruil/chisel-dev:arm64 
	docker push fangruil/chisel-dev:arm64-${VER}

docs:
	pip3 install -r docs/requirements.txt 
	python3 -m mkdocs serve

clean:
	rm -rf project/target project/project/target target *.v *.anno.json $(VIVADO_LOGDIR)

clean-cache:
	rm -rf .cache
