# export ARCH=`uname -m`
# Currently the image does not support arm64, 
# We have to hard code the arch into amd64 as a workaround
export ARCH=amd64
export VER=0.1

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
	if [ ${ARCH} = "arm64" ]; then docker run --rm -it -v ${PWD}:/workspace/ fangruil/chisel-dev:arm64 bash; else docker run --rm -it -v ${PWD}:/workspace/ fangruil/chisel-dev:amd64 bash; fi

test:
	if [ ${ARCH} = "arm64" ]; then docker run --rm -it -v ${PWD}:/workspace/ fangruil/chisel-dev:arm64 sbt test; else docker run --rm -it -v ${PWD}:/workspace/ fangruil/chisel-dev:amd64 sbt test; fi

build:
	if [ ${ARCH} = "arm64" ]; then docker run --rm -it -v ${PWD}:/workspace/ fangruil/chisel-dev:arm64 sbt run; else docker run --rm -it -v ${PWD}:/workspace/ fangruil/chisel-dev:amd64 sbt run; fi

push:
	make push-image-${ARCH}

push-x86_64:
	make push-image-amd64

push-image-amd64:
	docker push fangruil/chisel-dev:amd64 
	docker push fangruil/chisel-dev:amd64-${VER}

push-image-arm64:
	docker push fangruil/chisel-dev:arm64 
	docker push fangruil/chisel-dev:arm64-${VER}