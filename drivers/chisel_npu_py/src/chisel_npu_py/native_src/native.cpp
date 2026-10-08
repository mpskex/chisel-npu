/*
 * native.cpp — pybind11 boundary for the Chisel NPU XDMA driver (v0.2).
 *
 * This module is the ONLY place that handles file descriptors and raw DMA
 * transfers.  The DDR section map and the ctrl register offsets are supplied
 * by the Python NPUConfig at construction time (the config is the single
 * source of truth; native validates every transfer against it).  Python
 * only moves buffers and section-relative offsets — never absolute
 * addresses.
 *
 * Backing interface: the Xilinx xdma kernel driver device nodes
 *   /dev/xdma0_h2c_<ch>   host→card DMA (file offset = AXI address)
 *   /dev/xdma0_c2h_<ch>   card→host DMA (file offset = AXI address)
 *   /dev/xdma0_bypass     BAR2 AXI-Lite → ctrl_lite (mmap'd registers)
 */

#include <pybind11/numpy.h>
#include <pybind11/pybind11.h>
#include <pybind11/stl.h>

#include <cerrno>
#include <cstdint>
#include <cstring>
#include <fcntl.h>
#include <map>
#include <stdexcept>
#include <string>
#include <sys/mman.h>
#include <unistd.h>

namespace py = pybind11;

namespace {

constexpr uint64_t kDdrBase = 0x00000000ULL;
constexpr uint64_t kDdrSize = 0x100000000ULL;  // 4 GB unified C0/C1 DDR map
constexpr size_t kRegMapSize = 4096;           // one page of the bypass BAR

using SectionMap = std::map<std::string, std::pair<uint64_t, size_t>>;

[[noreturn]] void throw_errno(const char *what) {
    throw std::runtime_error(std::string(what) + ": " + std::strerror(errno));
}

void validate_chunk(uint64_t base, size_t window, size_t offset, size_t len) {
    if (len == 0)
        throw py::value_error("zero-length transfer");
    if ((offset & 3) != 0)
        throw py::value_error("offset must be 4-byte aligned");
    if ((len & 3) != 0)
        throw py::value_error("length must be a multiple of 4 bytes");
    if (offset > window || len > window - offset)
        throw py::value_error("chunk exceeds the section window");
    const uint64_t addr = base + offset;
    if (addr < kDdrBase || addr + len > kDdrBase + kDdrSize)
        throw py::value_error("address range outside the 4 GB DDR window "
                              "(0x00000000..0xFFFFFFFF)");
}

size_t do_pwrite(int fd, const void *buf, size_t len, uint64_t addr) {
    const char *p = static_cast<const char *>(buf);
    size_t done = 0;
    while (done < len) {
        ssize_t n = ::pwrite(fd, p + done, len - done, addr + done);
        if (n < 0) {
            if (errno == EINTR) continue;
            throw_errno("DMA write failed");
        }
        if (n == 0)
            throw std::runtime_error("DMA write: device returned 0 bytes");
        done += static_cast<size_t>(n);
    }
    return done;
}

size_t do_pread(int fd, void *buf, size_t len, uint64_t addr) {
    char *p = static_cast<char *>(buf);
    size_t done = 0;
    while (done < len) {
        ssize_t n = ::pread(fd, p + done, len - done, addr + done);
        if (n < 0) {
            if (errno == EINTR) continue;
            throw_errno("DMA read failed");
        }
        if (n == 0)
            throw std::runtime_error("DMA read: device returned 0 bytes");
        done += static_cast<size_t>(n);
    }
    return done;
}

// Convert any buffer-like object into a C-contiguous numpy array.  For
// outputs a copy is forbidden (a copied read-back would silently discard
// the data), so non-contiguous or read-only buffers are rejected.
py::array to_contiguous(py::handle obj, const char *argname, bool for_output = false) {
    py::array arr = py::array::ensure(obj, for_output ? 0 : py::array::c_style);
    if (!arr)
        throw py::type_error(std::string(argname) +
                             " must be a numpy array, bytes, bytearray or "
                             "memoryview");
    if (for_output) {
        if (!(arr.flags() & py::array::c_style))
            throw py::value_error(std::string(argname) +
                                  " must be a C-contiguous array (read-back "
                                  "must not copy)");
        if (!arr.writeable())
            throw py::value_error(std::string(argname) +
                                  " must be a writable buffer");
    }
    return arr;
}

}  // namespace

class NativeXDMA {
  public:
    NativeXDMA(const std::string &prefix, int h2c_ch, int c2h_ch,
               const SectionMap &sections, const std::map<std::string, uint64_t> &ctrl)
        : prefix_(prefix),
          h2c_path_(prefix + "_h2c_" + std::to_string(h2c_ch)),
          c2h_path_(prefix + "_c2h_" + std::to_string(c2h_ch)),
          bypass_path_(prefix + "_bypass"),
          sections_(sections),
          ctrl_offsets_(ctrl) {
        if (sections_.empty())
            throw py::value_error("sections map must not be empty");
        h2c_fd_ = ::open(h2c_path_.c_str(), O_RDWR);
        if (h2c_fd_ < 0) fail_open(h2c_path_);
        c2h_fd_ = ::open(c2h_path_.c_str(), O_RDWR);
        if (c2h_fd_ < 0) fail_open(c2h_path_);
        bypass_fd_ = ::open(bypass_path_.c_str(), O_RDWR | O_SYNC);
        if (bypass_fd_ < 0) fail_open(bypass_path_);
        reg_map_ = ::mmap(nullptr, kRegMapSize, PROT_READ | PROT_WRITE,
                          MAP_SHARED, bypass_fd_, 0);
        if (reg_map_ == MAP_FAILED) fail_open("mmap(" + bypass_path_ + ")");
    }

    ~NativeXDMA() {
        if (reg_map_ != MAP_FAILED) ::munmap(reg_map_, kRegMapSize);
        if (bypass_fd_ >= 0) ::close(bypass_fd_);
        if (c2h_fd_ >= 0) ::close(c2h_fd_);
        if (h2c_fd_ >= 0) ::close(h2c_fd_);
    }

    NativeXDMA(const NativeXDMA &) = delete;
    NativeXDMA &operator=(const NativeXDMA &) = delete;

    std::string prefix() const { return prefix_; }

    // ── Named sections (bases from the NPUConfig; offsets cross the API) ───
    size_t write_staged(const std::string &name, py::handle data, size_t offset) {
        const auto &slot = section_slot(name);
        py::array arr = to_contiguous(data, name.c_str());
        const size_t nbytes = static_cast<size_t>(arr.nbytes());
        validate_chunk(slot.first, slot.second, offset, nbytes);
        return do_pwrite(h2c_fd_, arr.data(), nbytes, slot.first + offset);
    }

    size_t read_staged(const std::string &name, py::handle out, size_t offset) {
        const auto &slot = section_slot(name);
        py::array arr = to_contiguous(out, name.c_str(), true);
        const size_t nbytes = static_cast<size_t>(arr.nbytes());
        validate_chunk(slot.first, slot.second, offset, nbytes);
        return do_pread(c2h_fd_, arr.mutable_data(), nbytes, slot.first + offset);
    }

    size_t section_size(const std::string &name) const {
        return section_slot(name).second;
    }

    // ── ctrl_lite register map (mmap'd bypass BAR; offsets from NPUConfig) ──
    uint32_t ctrl_read_reg(uint64_t offset) {
        return read_reg(offset);
    }

    void ctrl_write_reg(uint64_t offset, uint32_t value) {
        write_reg(offset, value);
    }

    // Convenience aliases for the CTRL word (offset 0).
    uint32_t ctrl_read() { return read_reg(0); }
    void ctrl_write(uint32_t value) { write_reg(0, value); }

  private:
    const std::pair<uint64_t, size_t> &section_slot(const std::string &name) const {
        auto it = sections_.find(name);
        if (it == sections_.end())
            throw py::value_error("unknown section '" + name + "'");
        return it->second;
    }

    uint32_t read_reg(uint64_t offset) const {
        check_reg_offset(offset);
        return *reinterpret_cast<volatile uint32_t *>(
            static_cast<char *>(reg_map_) + offset);
    }

    void write_reg(uint64_t offset, uint32_t value) const {
        check_reg_offset(offset);
        *reinterpret_cast<volatile uint32_t *>(static_cast<char *>(reg_map_) +
                                               offset) = value;
    }

    void check_reg_offset(uint64_t offset) const {
        if ((offset & 3) != 0 || offset + 4 > kRegMapSize)
            throw py::value_error("register offset outside the mapped BAR "
                                  "window (0.." +
                                  std::to_string(kRegMapSize - 4) + ")");
    }

    [[noreturn]] void fail_open(const std::string &what) const {
        std::string msg = "cannot open '" + what + "': " +
                          std::strerror(errno) +
                          ". Is the xdma driver loaded and are the device "
                          "nodes accessible (udev rule 99-xdma.rules)?";
        throw std::runtime_error(msg);
    }

    std::string prefix_, h2c_path_, c2h_path_, bypass_path_;
    SectionMap sections_;
    std::map<std::string, uint64_t> ctrl_offsets_;
    int h2c_fd_ = -1, c2h_fd_ = -1, bypass_fd_ = -1;
    void *reg_map_ = MAP_FAILED;
};

PYBIND11_MODULE(_native, m) {
    m.doc() = "pybind11 boundary of chisel_npu_py: owns XDMA fds, DMA "
              "transfers and the ctrl register map.  The NPUConfig (sections "
              "+ register offsets) is supplied from Python; native validates "
              "every transfer.  Python only moves buffers and section "
              "offsets.";
    m.attr("__version__") = "0.2.0";

    py::class_<NativeXDMA>(m, "NativeXDMA")
        .def(py::init([](const std::string &prefix, int h2c_ch, int c2h_ch,
                         const SectionMap &sections,
                         const std::map<std::string, uint64_t> &ctrl) {
                 return new NativeXDMA(prefix, h2c_ch, c2h_ch, sections, ctrl);
             }),
             py::arg("prefix") = std::string("/dev/xdma0"), py::arg("h2c_ch") = 0,
             py::arg("c2h_ch") = 0, py::arg("sections") = SectionMap(),
             py::arg("ctrl") = std::map<std::string, uint64_t>())
        .def_property_readonly("prefix", &NativeXDMA::prefix)
        .def("write_staged", &NativeXDMA::write_staged, py::arg("name"),
             py::arg("data"), py::arg("offset") = 0)
        .def("read_staged", &NativeXDMA::read_staged, py::arg("name"),
             py::arg("out"), py::arg("offset") = 0)
        .def("section_size", &NativeXDMA::section_size, py::arg("name"))
        .def("ctrl_read_reg", &NativeXDMA::ctrl_read_reg, py::arg("offset"))
        .def("ctrl_write_reg", &NativeXDMA::ctrl_write_reg, py::arg("offset"),
             py::arg("value"))
        .def("ctrl_read", &NativeXDMA::ctrl_read)
        .def("ctrl_write", &NativeXDMA::ctrl_write, py::arg("value"));
}
