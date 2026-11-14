# Vivado 2025.2 runtime on Ubuntu 22.04 (glibc 2.35).
#
# The host is Ubuntu 26.04 / glibc 2.43. Vivado 2025.2's file-utils thread
# aborts on that glibc (`HUTFileUtils::checkRegistry_real`), so run Vivado in
# this 22.04 container instead. The Vivado install, license directory and
# project worktrees are bind-mounted from the host; nothing Vivado-specific is
# baked into the image.
#
# Build:
#   docker build -f docker/vivado-22.04.dockerfile -t vivado-runtime:22.04 .
#
# Run (batch):
#   docker run --rm --ipc=host --net=host -u 1000:1000 \
#     -e HOME=/home/mpsk -e XILINX_LOCAL_USER_DATA=no \
#     -v /home/mpsk/Vivado/2025.2:/home/mpsk/Vivado/2025.2 \
#     -v /home/mpsk/.Xilinx:/home/mpsk/.Xilinx \
#     -v /home/mpsk/chisel-npu-nov2:/home/mpsk/chisel-npu-nov2 \
#     -w /home/mpsk/chisel-npu-nov2 vivado-runtime:22.04 \
#     /home/mpsk/Vivado/2025.2/Vivado/bin/vivado -mode batch -source ...

FROM ubuntu:22.04

ENV DEBIAN_FRONTEND=noninteractive

# Use the same Tsinghua mirror as the host/chisel image instead of
# archive.ubuntu.com (which is slow/retrying from this network).
# HTTP is used for the bootstrap because the minimal ubuntu:22.04 image has no
# ca-certificates yet; apt still verifies the signed package lists/packages.
RUN printf '%s\n' \
        'deb http://mirrors.tuna.tsinghua.edu.cn/ubuntu/ jammy main restricted universe multiverse' \
        'deb http://mirrors.tuna.tsinghua.edu.cn/ubuntu/ jammy-updates main restricted universe multiverse' \
        'deb http://mirrors.tuna.tsinghua.edu.cn/ubuntu/ jammy-backports main restricted universe multiverse' \
        'deb http://mirrors.tuna.tsinghua.edu.cn/ubuntu/ jammy-security main restricted universe multiverse' \
        > /etc/apt/sources.list

RUN apt-get update && apt-get install -y --no-install-recommends \
        build-essential \
        ca-certificates \
        curl \
        git \
        sudo \
        language-pack-en \
        locales \
        libncurses5 \
        libncursesw5 \
        libtinfo5 \
        libtinfo6 \
        libgtk-3-0 \
        libxrender1 \
        libxtst6 \
        libxi6 \
        libglib2.0-0 \
        libusb-1.0-0 \
        libx11-6 \
        libxext6 \
        libxft2 \
        libfontconfig1 \
        libfreetype6 \
        libsm6 \
        libice6 \
        libpng16-16 \
        zlib1g \
        libudev1 \
    && locale-gen en_US.UTF-8 \
    && rm -rf /var/lib/apt/lists/*

ENV LANG=en_US.UTF-8
ENV LC_ALL=en_US.UTF-8
ENV LANGUAGE=en_US:en

CMD ["/bin/bash"]
