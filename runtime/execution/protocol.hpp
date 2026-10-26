#pragma once
#include <cstdint>
#include <stdexcept>
#include <string>
#include <vector>

namespace droy_exec {
using Bytes = std::vector<uint8_t>;
constexpr uint32_t kProtocol = 1;
constexpr size_t kMaxFrame = 1024 * 1024;
constexpr uint32_t kGuestUid = 65534;
enum Type : uint8_t {
    START = 1, STDIN = 2, STDIN_EOF = 3, CANCEL = 4, RESIZE = 5,
    CONTROL = 6, CONTROL_EOF = 7, SIGNAL = 8, INSPECT_FOREGROUND = 9,
    READY = 16, STDOUT = 17, STDERR = 18, CONTROL_OUT = 19, EXIT = 20,
    ERROR = 21, FOREGROUND = 22, SIGNAL_RESULT = 23, CLEANUP_RESULT = 24,
    CLEANUP = 32,
};
struct Error : std::runtime_error {
    explicit Error(const std::string& code) : std::runtime_error(code) {}
};
inline void require(bool value, const char* code) { if (!value) throw Error(code); }
inline bool supported_package(const std::string& package) {
    return package == "io.droyagent.app" || package == "io.droyagent.app.dev";
}
inline std::string app_files_root(uint32_t uid, const std::string& package) {
    require(supported_package(package), "unsupported_package");
    return "/data/user/" + std::to_string(uid / 100000) + "/" + package + "/files";
}
inline void put32(Bytes& out, uint32_t value) {
    out.push_back(value >> 24); out.push_back(value >> 16);
    out.push_back(value >> 8); out.push_back(value);
}
inline uint32_t read32(const uint8_t* p) {
    return (uint32_t(p[0]) << 24) | (uint32_t(p[1]) << 16) |
           (uint32_t(p[2]) << 8) | uint32_t(p[3]);
}
inline bool utf8(const std::string& value) {
    size_t i = 0;
    while (i < value.size()) {
        uint32_t c = static_cast<unsigned char>(value[i++]);
        if (c < 0x80) continue;
        size_t count; uint32_t minimum;
        if (c >= 0xc2 && c <= 0xdf) { c &= 31; count = 1; minimum = 0x80; }
        else if (c >= 0xe0 && c <= 0xef) { c &= 15; count = 2; minimum = 0x800; }
        else if (c >= 0xf0 && c <= 0xf4) { c &= 7; count = 3; minimum = 0x10000; }
        else return false;
        if (i + count > value.size()) return false;
        while (count--) { auto n = static_cast<unsigned char>(value[i++]); if ((n & 0xc0) != 0x80) return false; c = (c << 6) | (n & 63); }
        if (c < minimum || c > 0x10ffff || (c >= 0xd800 && c <= 0xdfff)) return false;
    }
    return true;
}
inline Bytes frame(Type type, const Bytes& data) {
    require(data.size() < kMaxFrame, "frame_too_large");
    Bytes out; out.reserve(data.size() + 5); put32(out, data.size() + 1);
    out.push_back(type); out.insert(out.end(), data.begin(), data.end()); return out;
}
inline Bytes frame(Type type, const std::string& data) {
    return frame(type, Bytes(data.begin(), data.end()));
}
struct Reader {
    const Bytes& bytes; size_t at = 0;
    uint32_t number() {
        require(at + 4 <= bytes.size(), "truncated_request");
        uint32_t result = read32(bytes.data() + at); at += 4; return result;
    }
    std::string text(size_t maximum = 65536) {
        size_t size = number(); require(size <= maximum && size <= bytes.size() - at, "invalid_string_length");
        std::string result(bytes.begin() + at, bytes.begin() + at + size); at += size;
        require(result.find('\0') == std::string::npos, "nul_in_string");
        require(utf8(result), "invalid_utf8"); return result;
    }
    std::vector<std::string> strings(size_t maximum) {
        size_t count = number(); require(count <= maximum, "too_many_arguments");
        std::vector<std::string> result; result.reserve(count);
        while (count--) result.push_back(text()); return result;
    }
    void end() const { require(at == bytes.size(), "trailing_request_data"); }
};
inline bool under(const std::string& path, const std::string& root) {
    return path == root || (path.size() > root.size() && path.compare(0, root.size(), root) == 0 && path[root.size()] == '/');
}
inline void clean_absolute(const std::string& path) {
    require(!path.empty() && path[0] == '/' && path.size() < 4096, "invalid_absolute_path");
    require(path.find("//") == std::string::npos && path.find("/../") == std::string::npos &&
            path.find("/./") == std::string::npos && path != "/.." && path != "/." &&
            !(path.size() >= 3 && path.compare(path.size() - 3, 3, "/..") == 0) &&
            !(path.size() >= 2 && path.compare(path.size() - 2, 2, "/.") == 0), "invalid_absolute_path");
}
struct Start {
    uint32_t host_uid, timeout_ms, flags, cols, rows, proxy_port;
    uint32_t guest_uid = kGuestUid; // Native lease allocation; never accepted from the wire.
    std::string files, workspace_id, cwd, executable;
    std::vector<std::string> argv, env;
    bool pty() const { return (flags & 1) != 0; }
    bool control() const { return (flags & 2) != 0; }
    std::string package() const {
        return files == app_files_root(host_uid, "io.droyagent.app.dev") ? "io.droyagent.app.dev" : "io.droyagent.app";
    }
    std::string workspace() const {
        return under(cwd, files + "/dsh-home/workspace") ? files + "/dsh-home/workspace" : files + "/workspace";
    }
    std::string workspace_branch() const { return workspace() == files + "/workspace" ? "startup" : "default"; }
    std::string source_workspace() const {
        return workspace_id.empty() ? workspace() : files + "/droy-execution/workspaces/" + workspace_id + "/" + workspace_branch();
    }
    static Start parse(const Bytes& body) {
        Reader in{body}; require(in.number() == kProtocol, "unsupported_protocol");
        Start s; s.host_uid = in.number(); s.timeout_ms = in.number(); s.flags = in.number();
        s.cols = in.number(); s.rows = in.number(); s.proxy_port = in.number();
        s.files = in.text(4096); s.workspace_id = in.text(64); s.cwd = in.text(4096); s.executable = in.text(4096);
        s.argv = in.strings(256); s.env = in.strings(256); in.end();
        require(s.host_uid % 100000 >= 10000 && s.host_uid % 100000 <= 19999, "invalid_host_uid");
        require(s.files == app_files_root(s.host_uid, "io.droyagent.app") ||
                s.files == app_files_root(s.host_uid, "io.droyagent.app.dev"), "invalid_files_root");
        require(s.workspace_id.empty() || (s.workspace_id.size() == 64 &&
            s.workspace_id.find_first_not_of("0123456789abcdef") == std::string::npos), "invalid_workspace_id");
        require(s.timeout_ms >= 100 && s.timeout_ms <= 86400000, "invalid_timeout");
        require((s.flags & ~3u) == 0 && s.cols > 0 && s.cols <= 1000 && s.rows > 0 && s.rows <= 1000, "invalid_terminal_options");
        require(s.proxy_port <= 65535, "invalid_proxy_port");
        clean_absolute(s.cwd); require(under(s.cwd, s.files + "/workspace") || under(s.cwd, s.files + "/dsh-home/workspace"), "cwd_outside_workspace");
        require(!s.executable.empty() && !s.argv.empty() && s.argv.size() <= 256, "invalid_argv");
        if (s.executable.find('/') != std::string::npos) clean_absolute(s.executable);
        std::vector<std::string> keys;
        for (const auto& e : s.env) {
            size_t eq = e.find('='); require(eq != std::string::npos && eq > 0, "invalid_environment");
            std::string name = e.substr(0, eq);
            require(name.find_first_not_of("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_") == std::string::npos &&
                    !(name[0] >= '0' && name[0] <= '9'), "invalid_environment");
            for (const auto& previous : keys) require(previous != name, "duplicate_environment");
            keys.push_back(name);
            require(name != "DROY_BRIDGE_TOKEN" && name != "DROY_WEB_TOKEN" && name != "DSH_HOME", "private_environment");
        }
        return s;
    }
};
}
