#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif
#include <dirent.h>
#include <fcntl.h>
#include <poll.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>

#include <algorithm>
#include <array>
#include <cerrno>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <limits>
#include <map>
#include <stdexcept>
#include <string>
#include <utility>
#include <vector>

namespace {
constexpr size_t MAX_FRAME = 16 * 1024 * 1024;
constexpr size_t MAX_CANDIDATES = 20000;
constexpr uint64_t MAX_SCAN_BYTES = 128 * 1024 * 1024;
struct Error : std::runtime_error {
    std::string code;
    explicit Error(std::string c) : std::runtime_error(c), code(std::move(c)) {}
};
[[noreturn]] void fail(const char* code) { throw Error(code); }

// Deliberately small JSON implementation for a closed, bounded private protocol.
struct Json {
    enum Kind { NIL, BOOL, NUMBER, STRING, ARRAY, OBJECT } kind = NIL;
    bool boolean = false;
    double number = 0;
    std::string string;
    std::vector<Json> array;
    std::map<std::string, Json> object;
    Json() = default;
    Json(bool v) : kind(BOOL), boolean(v) {}
    Json(int v) : kind(NUMBER), number(v) {}
    Json(double v) : kind(NUMBER), number(v) {
        if (!std::isfinite(v)) fail("invalid_number");
    }
    Json(const char* v) : kind(STRING), string(v) {}
    Json(std::string v) : kind(STRING), string(std::move(v)) {}
    static Json obj() {
        Json v;
        v.kind = OBJECT;
        return v;
    }
    static Json list() {
        Json v;
        v.kind = ARRAY;
        return v;
    }
    Json& put(const std::string& key, Json value) {
        if (kind != OBJECT) fail("invalid_json");
        object[key] = std::move(value);
        return *this;
    }
    Json& add(Json value) {
        if (kind != ARRAY) fail("invalid_json");
        array.push_back(std::move(value));
        return *this;
    }
    const Json& get(const std::string& key) const {
        auto p = object.find(key);
        if (kind != OBJECT || p == object.end()) fail("missing_field");
        return p->second;
    }
    bool has(const std::string& key) const { return kind == OBJECT && object.find(key) != object.end(); }
    std::string text() const {
        if (kind != STRING) fail("invalid_field");
        return string;
    }
    std::string text(const std::string& key) const { return get(key).text(); }
    int integer() const {
        if (kind != NUMBER || number != std::floor(number) || number < 0 || number > 2147483647.0)
            fail("invalid_integer");
        return static_cast<int>(number);
    }
    int integer(const std::string& key) const { return get(key).integer(); }
    bool flag(const std::string& key) const {
        const auto& v = get(key);
        if (v.kind != BOOL) fail("invalid_field");
        return v.boolean;
    }
};

void append_utf8(std::string& out, unsigned c) {
    if (c <= 0x7f)
        out.push_back(static_cast<char>(c));
    else if (c <= 0x7ff) {
        out.push_back(static_cast<char>(0xc0 | (c >> 6)));
        out.push_back(static_cast<char>(0x80 | (c & 63)));
    } else if (c <= 0xffff) {
        out.push_back(static_cast<char>(0xe0 | (c >> 12)));
        out.push_back(static_cast<char>(0x80 | ((c >> 6) & 63)));
        out.push_back(static_cast<char>(0x80 | (c & 63)));
    } else {
        out.push_back(static_cast<char>(0xf0 | (c >> 18)));
        out.push_back(static_cast<char>(0x80 | ((c >> 12) & 63)));
        out.push_back(static_cast<char>(0x80 | ((c >> 6) & 63)));
        out.push_back(static_cast<char>(0x80 | (c & 63)));
    }
}
struct Parser {
    const std::string& source;
    size_t at = 0;
    explicit Parser(const std::string& s) : source(s) {}
    void space() {
        while (at < source.size() &&
               (source[at] == ' ' || source[at] == '\r' || source[at] == '\n' || source[at] == '\t'))
            ++at;
    }
    char take() {
        if (at >= source.size()) fail("invalid_json");
        return source[at++];
    }
    unsigned hex4() {
        unsigned v = 0;
        for (int i = 0; i < 4; ++i) {
            char c = take();
            v <<= 4;
            if (c >= '0' && c <= '9')
                v |= c - '0';
            else if (c >= 'a' && c <= 'f')
                v |= c - 'a' + 10;
            else if (c >= 'A' && c <= 'F')
                v |= c - 'A' + 10;
            else
                fail("invalid_json");
        }
        return v;
    }
    std::string text() {
        if (take() != '"') fail("invalid_json");
        std::string out;
        for (;;) {
            unsigned char c = static_cast<unsigned char>(take());
            if (c == '"') return out;
            if (c < 32) fail("invalid_json");
            if (c != '\\') {
                out.push_back(static_cast<char>(c));
                continue;
            }
            char e = take();
            switch (e) {
                case '"':
                    out.push_back('"');
                    break;
                case '\\':
                    out.push_back('\\');
                    break;
                case '/':
                    out.push_back('/');
                    break;
                case 'b':
                    out.push_back('\b');
                    break;
                case 'f':
                    out.push_back('\f');
                    break;
                case 'n':
                    out.push_back('\n');
                    break;
                case 'r':
                    out.push_back('\r');
                    break;
                case 't':
                    out.push_back('\t');
                    break;
                case 'u': {
                    unsigned v = hex4();
                    if (v >= 0xd800 && v <= 0xdbff) {
                        if (take() != '\\' || take() != 'u') fail("invalid_json");
                        unsigned l = hex4();
                        if (l < 0xdc00 || l > 0xdfff) fail("invalid_json");
                        v = 0x10000 + ((v - 0xd800) << 10) + l - 0xdc00;
                    } else if (v >= 0xdc00 && v <= 0xdfff)
                        fail("invalid_json");
                    append_utf8(out, v);
                    break;
                }
                default:
                    fail("invalid_json");
            }
        }
    }
    Json value(unsigned depth = 0) {
        if (depth > 24) fail("invalid_json");
        space();
        if (at >= source.size()) fail("invalid_json");
        char c = source[at];
        if (c == '"') return Json(text());
        if (c == '{') {
            ++at;
            Json out = Json::obj();
            space();
            if (at < source.size() && source[at] == '}') {
                ++at;
                return out;
            }
            for (;;) {
                space();
                std::string k = text();
                space();
                if (take() != ':' || out.has(k)) fail("invalid_json");
                out.put(k, value(depth + 1));
                space();
                char d = take();
                if (d == '}') return out;
                if (d != ',') fail("invalid_json");
            }
        }
        if (c == '[') {
            ++at;
            Json out = Json::list();
            space();
            if (at < source.size() && source[at] == ']') {
                ++at;
                return out;
            }
            for (;;) {
                out.add(value(depth + 1));
                space();
                char d = take();
                if (d == ']') return out;
                if (d != ',') fail("invalid_json");
            }
        }
        if (source.compare(at, 4, "true") == 0) {
            at += 4;
            return Json(true);
        }
        if (source.compare(at, 5, "false") == 0) {
            at += 5;
            return Json(false);
        }
        if (source.compare(at, 4, "null") == 0) {
            at += 4;
            return Json();
        }
        size_t start = at;
        if (source[at] == '-') ++at;
        if (at >= source.size()) fail("invalid_json");
        if (source[at] == '0')
            ++at;
        else {
            if (source[at] < '1' || source[at] > '9') fail("invalid_json");
            while (at < source.size() && source[at] >= '0' && source[at] <= '9') ++at;
        }
        if (at < source.size() && source[at] == '.') {
            ++at;
            size_t digit = at;
            while (at < source.size() && source[at] >= '0' && source[at] <= '9') ++at;
            if (at == digit) fail("invalid_json");
        }
        if (at < source.size() && (source[at] == 'e' || source[at] == 'E')) {
            ++at;
            if (at < source.size() && (source[at] == '+' || source[at] == '-')) ++at;
            size_t digit = at;
            while (at < source.size() && source[at] >= '0' && source[at] <= '9') ++at;
            if (at == digit) fail("invalid_json");
        }
        std::string token = source.substr(start, at - start);
        char* end = nullptr;
        double n = std::strtod(token.c_str(), &end);
        if (!end || *end || !std::isfinite(n)) fail("invalid_json");
        return Json(n);
    }
    Json parse() {
        Json out = value();
        space();
        if (at != source.size()) fail("invalid_json");
        return out;
    }
};
void quote(std::string& out, const std::string& s) {
    out.push_back('"');
    const char* h = "0123456789abcdef";
    for (unsigned char c : s) {
        if (c == '"' || c == '\\') {
            out.push_back('\\');
            out.push_back(static_cast<char>(c));
        } else if (c < 32) {
            out += "\\u00";
            out.push_back(h[c >> 4]);
            out.push_back(h[c & 15]);
        } else
            out.push_back(static_cast<char>(c));
    }
    out.push_back('"');
}
void encode(std::string& out, const Json& j) {
    switch (j.kind) {
        case Json::NIL:
            out += "null";
            break;
        case Json::BOOL:
            out += j.boolean ? "true" : "false";
            break;
        case Json::NUMBER: {
            char b[64];
            std::snprintf(b, sizeof(b), "%.17g", j.number);
            out += b;
            break;
        }
        case Json::STRING:
            quote(out, j.string);
            break;
        case Json::ARRAY: {
            out += '[';
            bool first = true;
            for (const auto& v : j.array) {
                if (!first) out += ',';
                first = false;
                encode(out, v);
            }
            out += ']';
            break;
        }
        case Json::OBJECT: {
            out += '{';
            bool first = true;
            for (const auto& v : j.object) {
                if (!first) out += ',';
                first = false;
                quote(out, v.first);
                out += ':';
                encode(out, v.second);
            }
            out += '}';
            break;
        }
    }
}
std::string encoded(const Json& j) {
    std::string out;
    encode(out, j);
    if (out.size() > MAX_FRAME) fail("memory_output_limit");
    return out;
}

struct Fd {
    int value = -1;
    explicit Fd(int v = -1) : value(v) {}
    ~Fd() {
        if (value >= 0) close(value);
    }
    Fd(const Fd&) = delete;
    Fd& operator=(const Fd&) = delete;
    Fd(Fd&& v) noexcept : value(v.value) { v.value = -1; }
    Fd& operator=(Fd&& v) noexcept {
        if (this != &v) {
            if (value >= 0) close(value);
            value = v.value;
            v.value = -1;
        }
        return *this;
    }
};
uint64_t now_ms() {
    timespec t{};
#ifdef CLOCK_BOOTTIME
    clock_gettime(CLOCK_BOOTTIME, &t);
#else
    clock_gettime(CLOCK_MONOTONIC, &t);
#endif
    return static_cast<uint64_t>(t.tv_sec) * 1000 + static_cast<uint64_t>(t.tv_nsec) / 1000000;
}
uint64_t deadline_ms = 0;
void check() {
    if (deadline_ms && now_ms() >= deadline_ms) fail("memory_deadline");
    pollfd p{STDIN_FILENO, POLLIN, 0};
    if (poll(&p, 1, 0) > 0 && (p.revents & (POLLHUP | POLLERR | POLLNVAL))) fail("memory_cancelled");
}
void exact_read(int fd, void* data, size_t n) {
    auto* p = static_cast<unsigned char*>(data);
    while (n) {
        check();
        pollfd f{fd, POLLIN, 0};
        int ready = poll(&f, 1, 50);
        if (ready < 0) {
            if (errno == EINTR) continue;
            fail("memory_protocol_error");
        }
        if (!ready) continue;
        ssize_t r = read(fd, p, n);
        if (r <= 0) {
            if (r < 0 && errno == EINTR) continue;
            fail("memory_cancelled");
        }
        p += r;
        n -= static_cast<size_t>(r);
    }
}
void exact_write(int fd, const void* data, size_t n) {
    const auto* p = static_cast<const unsigned char*>(data);
    while (n) {
        ssize_t r = write(fd, p, n);
        if (r <= 0) {
            if (r < 0 && errno == EINTR) continue;
            fail("memory_protocol_error");
        }
        p += r;
        n -= static_cast<size_t>(r);
    }
}
Json frame_read() {
    unsigned char b[4];
    exact_read(STDIN_FILENO, b, 4);
    size_t n = (static_cast<size_t>(b[0]) << 24) | (static_cast<size_t>(b[1]) << 16) |
               (static_cast<size_t>(b[2]) << 8) | b[3];
    if (n < 2 || n > MAX_FRAME) fail("memory_frame_limit");
    std::string s(n, '\0');
    exact_read(STDIN_FILENO, &s[0], n);
    return Parser(s).parse();
}
void frame_write(const Json& j) {
    std::string s = encoded(j);
    size_t n = s.size();
    unsigned char b[4] = {static_cast<unsigned char>(n >> 24), static_cast<unsigned char>(n >> 16),
                          static_cast<unsigned char>(n >> 8), static_cast<unsigned char>(n)};
    exact_write(STDOUT_FILENO, b, 4);
    exact_write(STDOUT_FILENO, s.data(), s.size());
}

std::string read_at(int dir, const char* name, size_t limit) {
    Fd f(openat(dir, name, O_RDONLY | O_CLOEXEC));
    if (f.value < 0) fail("memory_target_unavailable");
    std::string s;
    std::array<char, 4096> b{};
    for (;;) {
        ssize_t n = read(f.value, b.data(), b.size());
        if (n < 0) {
            if (errno == EINTR) continue;
            fail("memory_target_unavailable");
        }
        if (!n) return s;
        if (s.size() + static_cast<size_t>(n) > limit) fail("memory_metadata_limit");
        s.append(b.data(), static_cast<size_t>(n));
    }
}
uint64_t number(const std::string& s, int base = 10) {
    if (s.empty() || s[0] == '-' || s[0] == '+' || s.find_first_of(" \r\n\t") != std::string::npos)
        fail("invalid_integer");
    char* end = nullptr;
    errno = 0;
    unsigned long long n = std::strtoull(s.c_str(), &end, base);
    if (errno || !end || *end) fail("invalid_integer");
    return static_cast<uint64_t>(n);
}
uint64_t address(const Json& v) {
    std::string s = v.text();
    if (s.size() < 3 || s.compare(0, 2, "0x")) fail("invalid_address");
    return number(s.substr(2), 16);
}
std::string hex_address(uint64_t v) {
    char b[32];
    std::snprintf(b, sizeof(b), "0x%llx", static_cast<unsigned long long>(v));
    return b;
}
std::string hex_bytes(const unsigned char* p, size_t n) {
    const char* h = "0123456789abcdef";
    std::string s(n * 2, '0');
    for (size_t i = 0; i < n; ++i) {
        s[i * 2] = h[p[i] >> 4];
        s[i * 2 + 1] = h[p[i] & 15];
    }
    return s;
}
std::vector<unsigned char> unhex(const std::string& s, size_t max = 512) {
    if (s.empty() || s.size() % 2 || s.size() / 2 > max) fail("invalid_bytes");
    std::vector<unsigned char> b(s.size() / 2);
    auto digit = [](char c) -> unsigned {
        if (c >= '0' && c <= '9') return static_cast<unsigned>(c - '0');
        if (c >= 'a' && c <= 'f') return static_cast<unsigned>(c - 'a' + 10);
        if (c >= 'A' && c <= 'F') return static_cast<unsigned>(c - 'A' + 10);
        fail("invalid_bytes");
    };
    for (size_t i = 0; i < b.size(); ++i)
        b[i] = static_cast<unsigned char>((digit(s[i * 2]) << 4) | digit(s[i * 2 + 1]));
    return b;
}

struct Identity {
    int pid = 0, uid = 0, bits = 0;
    std::string start, name;
};
Identity identity(int dir, int pid) {
    Identity v;
    v.pid = pid;
    std::string status = read_at(dir, "status", 65536);
    size_t u = status.find("\nUid:");
    if (u == std::string::npos && status.compare(0, 4, "Uid:")) fail("memory_target_unavailable");
    u = (u == std::string::npos ? 0 : u + 1);
    unsigned real = 0, effective = 0, saved = 0, fs = 0;
    if (std::sscanf(status.c_str() + u, "Uid: %u %u %u %u", &real, &effective, &saved, &fs) != 4 ||
        real != effective || real != saved || real != fs || real > 2147483647u)
        fail("memory_uid_mismatch");
    v.uid = static_cast<int>(real);
    std::string stat = read_at(dir, "stat", 65536);
    size_t end = stat.rfind(')');
    if (end == std::string::npos) fail("memory_target_unavailable");
    size_t at = end + 1;
    for (int field = 3; field <= 22; ++field) {
        while (at < stat.size() && stat[at] == ' ') ++at;
        size_t first = at;
        while (at < stat.size() && stat[at] != ' ' && stat[at] != '\n') ++at;
        if (first == at) fail("memory_target_unavailable");
        if (field == 22) v.start = std::to_string(number(stat.substr(first, at - first)));
    }
    std::string cmd = read_at(dir, "cmdline", 65536);
    v.name = cmd.substr(0, cmd.find('\0'));
    if (v.name.size() > 256) v.name.resize(256);
    Fd exe(openat(dir, "exe", O_RDONLY | O_CLOEXEC));
    unsigned char elf[5]{};
    if (exe.value >= 0 && read(exe.value, elf, sizeof(elf)) == sizeof(elf) && elf[0] == 0x7f &&
        elf[1] == 'E' && elf[2] == 'L' && elf[3] == 'F')
        v.bits = elf[4] == 2 ? 64 : elf[4] == 1 ? 32 : 0;
    return v;
}
Json identity_json(const Identity& i) {
    return Json::obj()
        .put("pid", i.pid)
        .put("uid", i.uid)
        .put("startTime", i.start)
        .put("name", i.name)
        .put("bitness", i.bits);
}
std::string boot_id() {
    Fd d(open("/proc/sys/kernel/random", O_RDONLY | O_DIRECTORY | O_CLOEXEC));
    if (d.value < 0) fail("memory_target_unavailable");
    std::string s = read_at(d.value, "boot_id", 128);
    while (!s.empty() && (s.back() == '\n' || s.back() == '\r')) s.pop_back();
    return s;
}
int valid_uid(const Json& r) {
    int uid = r.integer("uid");
    int host_uid = r.integer("hostUid");
    int app = uid % 100000;
    if (app < 10000 || app >= 20000 || uid == host_uid || uid / 100000 != host_uid / 100000)
        fail("memory_target_not_allowed");
    return uid;
}
struct Region {
    uint64_t start = 0, end = 0, offset = 0;
    std::string perms, dev, inode, name, signature;
    bool readable() const { return perms.size() == 4 && perms[0] == 'r'; }
    bool data() const { return readable() && perms[2] != 'x' && perms[3] == 'p'; }
    bool writable() const { return data() && perms[1] == 'w'; }
};
Json region_json(const Region& r) {
    return Json::obj()
        .put("start", hex_address(r.start))
        .put("end", hex_address(r.end))
        .put("offset", hex_address(r.offset))
        .put("perms", r.perms)
        .put("dev", r.dev)
        .put("inode", r.inode)
        .put("name", r.name)
        .put("signature", r.signature);
}
std::vector<Region> maps(int dir) {
    std::string all = read_at(dir, "maps", 2 * 1024 * 1024);
    std::vector<Region> out;
    size_t at = 0;
    while (at < all.size()) {
        size_t end = all.find('\n', at);
        if (end == std::string::npos) end = all.size();
        std::string line = all.substr(at, end - at);
        at = end + 1;
        if (line.empty()) continue;
        unsigned long long start = 0, finish = 0, offset = 0, inode = 0;
        char perms[5]{}, dev[32]{};
        int n = 0;
        if (std::sscanf(line.c_str(), "%llx-%llx %4s %llx %31s %llu %n", &start, &finish, perms, &offset, dev,
                        &inode, &n) != 6 ||
            start >= finish)
            fail("memory_maps_invalid");
        Region r;
        r.start = start;
        r.end = finish;
        r.offset = offset;
        r.perms = perms;
        r.dev = dev;
        r.inode = std::to_string(inode);
        r.signature = line;
        std::string path = line.substr(static_cast<size_t>(n));
        size_t slash = path.rfind('/');
        r.name = path.empty() ? "[anonymous]" : slash == std::string::npos ? path : path.substr(slash + 1);
        if (r.name.size() > 256) r.name.resize(256);
        out.push_back(std::move(r));
        if (out.size() > 20000) fail("memory_metadata_limit");
    }
    return out;
}
const Region& find_region(const std::vector<Region>& current, const Json& expected) {
    uint64_t start = address(expected.get("start"));
    std::string signature = expected.text("signature");
    for (const auto& r : current)
        if (r.start == start) {
            if (r.signature != signature) fail("memory_region_changed");
            return r;
        }
    fail("memory_region_changed");
}
void range(const Region& r, uint64_t at, size_t length) {
    if (!length || at < r.start || at >= r.end || static_cast<uint64_t>(length) > r.end - at ||
        at > static_cast<uint64_t>(std::numeric_limits<off_t>::max()))
        fail("memory_range_invalid");
}
struct Target {
    Fd dir, mem;
    Identity info;
    std::vector<Region> regions;
    explicit Target(const Json& r, bool write = false) {
        int uid = valid_uid(r);
        int pid = r.integer("pid");
        if (pid <= 0 || pid == getpid()) fail("memory_target_not_allowed");
        std::string path = "/proc/" + std::to_string(pid);
        dir = Fd(open(path.c_str(), O_RDONLY | O_DIRECTORY | O_CLOEXEC));
        if (dir.value < 0) fail("memory_target_changed");
        try {
            info = identity(dir.value, pid);
        } catch (const Error&) {
            fail("memory_target_changed");
        }
        if (info.uid != uid) fail("memory_target_changed");
        if (info.start != r.text("startTime")) fail("memory_target_changed");
        if (info.bits != 64) fail("memory_target_bits_unsupported");
        mem = Fd(openat(dir.value, "mem", (write ? O_RDWR : O_RDONLY) | O_CLOEXEC));
        if (mem.value < 0)
            fail(errno == EACCES || errno == EPERM ? "memory_access_denied" : "memory_target_changed");
        regions = maps(dir.value);
    }
};
Json query(const Json& r) {
    int uid = valid_uid(r);
    DIR* d = opendir("/proc");
    if (!d) fail("memory_query_failed");
    Json found = Json::list();
    try {
        while (dirent* e = readdir(d)) {
            check();
            std::string n = e->d_name;
            if (n.empty() || n.find_first_not_of("0123456789") != std::string::npos) continue;
            int pid = static_cast<int>(number(n));
            Fd p(openat(dirfd(d), n.c_str(), O_RDONLY | O_DIRECTORY | O_CLOEXEC));
            if (p.value < 0) continue;
            try {
                Identity i = identity(p.value, pid);
                if (i.uid == uid && i.pid != getpid()) found.add(identity_json(i));
            } catch (const Error&) {
                continue;
            }
            if (found.array.size() > 128) fail("memory_process_limit");
        }
    } catch (...) {
        closedir(d);
        throw;
    }
    closedir(d);
    return Json::obj().put("processes", std::move(found));
}
Json inspect(const Json& r) {
    bool write = r.text("mode") == "read_write";
    if (!write && r.text("mode") != "read_only") fail("invalid_mode");
    Target t(r, write);
    Json out = identity_json(t.info);
    Json regions = Json::list();
    for (const auto& m : t.regions) regions.add(region_json(m));
    return out.put("pageSize", static_cast<int>(sysconf(_SC_PAGESIZE)))
        .put("readAccess", true)
        .put("writeEligibility", write ? "eligible_unverified" : "not_requested")
        .put("regions", std::move(regions));
}
Json regions(const Json& r) {
    Target t(r);
    Json a = Json::list();
    for (const auto& m : t.regions) a.add(region_json(m));
    return Json::obj().put("regions", std::move(a)).put("pageSize", static_cast<int>(sysconf(_SC_PAGESIZE)));
}
Json read_memory(const Json& r) {
    Target t(r);
    const Region& m = find_region(t.regions, r.get("region"));
    if (!m.readable()) fail("memory_region_not_readable");
    uint64_t at = address(r.get("address"));
    int length = r.integer("length");
    if (length < 1 || length > 512) fail("memory_read_limit");
    range(m, at, static_cast<size_t>(length));
    check();
    std::vector<unsigned char> b(static_cast<size_t>(length));
    ssize_t n = pread(t.mem.value, b.data(), b.size(), static_cast<off_t>(at));
    if (n < 0) fail("memory_read_failed");
    return Json::obj()
        .put("address", hex_address(at))
        .put("hex", hex_bytes(b.data(), static_cast<size_t>(n)))
        .put("bytesRead", static_cast<int>(n))
        .put("region", region_json(m));
}

size_t width(const std::string& t) {
    if (t == "int32" || t == "uint32" || t == "float32") return 4;
    if (t == "int64" || t == "uint64" || t == "float64") return 8;
    if (t == "bytes") return 0;
    fail("invalid_type");
}
template <class T>
T load(const unsigned char* p) {
    T v;
    std::memcpy(&v, p, sizeof(v));
    return v;
}
long double numeric(const std::string& t, const unsigned char* p) {
    if (t == "int32") return load<int32_t>(p);
    if (t == "uint32") return load<uint32_t>(p);
    if (t == "int64") return load<int64_t>(p);
    if (t == "uint64") return load<uint64_t>(p);
    if (t == "float32") return load<float>(p);
    if (t == "float64") return load<double>(p);
    fail("invalid_type");
}
bool match(const std::string& type, const std::string& comparison, const unsigned char* cur,
           const unsigned char* previous, const unsigned char* expected, size_t n, double tolerance) {
    if (comparison == "eq") {
        if (tolerance > 0 && (type == "float32" || type == "float64")) {
            long double a = numeric(type, cur), b = numeric(type, expected);
            return std::isfinite(a) && std::isfinite(b) && std::fabs(a - b) <= tolerance;
        }
        return std::memcmp(cur, expected, n) == 0;
    }
    if (!previous) fail("invalid_comparison");
    if (comparison == "changed" || comparison == "unchanged") {
        bool same = std::memcmp(cur, previous, n) == 0;
        if (tolerance > 0 && (type == "float32" || type == "float64")) {
            long double a = numeric(type, cur), b = numeric(type, previous);
            same = std::isfinite(a) && std::isfinite(b) && std::fabs(a - b) <= tolerance;
        }
        return comparison == "changed" ? !same : same;
    }
    if (type == "bytes") fail("invalid_comparison");
    if (comparison != "increased" && comparison != "decreased") fail("invalid_comparison");
    bool greater = comparison == "increased";
    if (type == "int32")
        return greater ? load<int32_t>(cur) > load<int32_t>(previous)
                       : load<int32_t>(cur) < load<int32_t>(previous);
    if (type == "uint32")
        return greater ? load<uint32_t>(cur) > load<uint32_t>(previous)
                       : load<uint32_t>(cur) < load<uint32_t>(previous);
    if (type == "int64")
        return greater ? load<int64_t>(cur) > load<int64_t>(previous)
                       : load<int64_t>(cur) < load<int64_t>(previous);
    if (type == "uint64")
        return greater ? load<uint64_t>(cur) > load<uint64_t>(previous)
                       : load<uint64_t>(cur) < load<uint64_t>(previous);
    long double a = numeric(type, cur), b = numeric(type, previous);
    if (!std::isfinite(a) || !std::isfinite(b)) return false;
    return greater ? a > b + tolerance : a < b - tolerance;
}
double tolerance(const Json& r) {
    if (!r.has("absoluteTolerance")) return 0;
    const auto& v = r.get("absoluteTolerance");
    if (v.kind != Json::NUMBER || v.number < 0) fail("invalid_tolerance");
    return v.number;
}
size_t candidate_limit(const Json& r) {
    int n = r.has("maxCandidates") ? r.integer("maxCandidates") : static_cast<int>(MAX_CANDIDATES);
    if (n < 1 || static_cast<size_t>(n) > MAX_CANDIDATES) fail("memory_candidate_limit");
    return static_cast<size_t>(n);
}
Json candidate(uint64_t at, const unsigned char* p, size_t size, int region) {
    return Json::obj()
        .put("address", hex_address(at))
        .put("hex", hex_bytes(p, size))
        .put("regionIndex", region);
}
struct SelectedRegion {
    Region snapshot;
    bool available = false;
};
std::vector<SelectedRegion> selected_regions(const std::vector<Region>& current_regions, const Json& r) {
    const auto& a = r.get("regions");
    if (a.kind != Json::ARRAY || a.array.empty() || a.array.size() > 20000) fail("invalid_regions");
    std::vector<SelectedRegion> out;
    uint64_t previous = 0;
    for (const auto& v : a.array) {
        SelectedRegion selected;
        Region& m = selected.snapshot;
        m.start = address(v.get("start"));
        m.end = address(v.get("end"));
        m.perms = v.text("perms");
        m.signature = v.text("signature");
        if (m.start >= m.end || !m.data() || (!out.empty() && m.start < previous))
            fail("memory_region_not_data");
        previous = m.end;
        for (const auto& current : current_regions) {
            if (current.start != m.start) continue;
            if (current.signature == m.signature && current.data()) {
                m = current;
                selected.available = true;
            }
            break;
        }
        // Keep every original slot. Candidate regionIndex and continuation cursors
        // refer to the caller's snapshot, including regions that have disappeared.
        out.push_back(std::move(selected));
    }
    return out;
}
Json changed_regions(const std::vector<SelectedRegion>& selected) {
    Json changed = Json::list();
    for (size_t index = 0; index < selected.size(); ++index)
        if (!selected[index].available) changed.add(static_cast<int>(index));
    return changed;
}
Json scan(const Json& r) {
    Target t(r);
    auto selected = selected_regions(t.regions, r);
    std::string type = r.text("type");
    size_t size = width(type);
    auto pattern = unhex(r.text("hex"), 256);
    if (size && pattern.size() != size) fail("invalid_bytes");
    if (!size) size = pattern.size();
    if (r.has("comparison") && r.text("comparison") != "eq") fail("invalid_comparison");
    size_t limit = candidate_limit(r);
    uint64_t max_bytes = r.has("maxBytes") ? static_cast<uint64_t>(r.integer("maxBytes")) : MAX_SCAN_BYTES;
    if (!max_bytes || max_bytes > MAX_SCAN_BYTES) fail("memory_scan_limit");
    size_t ri = 0;
    uint64_t at = selected[0].snapshot.start;
    if (r.has("cursor")) {
        const auto& cursor = r.get("cursor");
        ri = static_cast<size_t>(cursor.integer("regionIndex"));
        if (ri > selected.size()) fail("invalid_cursor");
        at = address(cursor.get("address"));
        if (ri < selected.size() && (at < selected[ri].snapshot.start || at > selected[ri].snapshot.end))
            fail("invalid_cursor");
    }
    Json found = Json::list();
    uint64_t scanned = 0, unreadable = 0, skipped = 0;
    bool overflow = false;
    uint64_t work_end = std::min(deadline_ms, now_ms() + 3000);
    size_t page = static_cast<size_t>(sysconf(_SC_PAGESIZE));
    if (page < 1024 || page > 1024 * 1024) fail("memory_page_size");
    std::vector<unsigned char> buffer(page + size);
    size_t alignment = type == "bytes" ? 1 : size;
    double tol = tolerance(r);
    while (ri < selected.size()) {
        const Region& m = selected[ri].snapshot;
        if (at >= m.end) {
            ++ri;
            if (ri < selected.size()) at = selected[ri].snapshot.start;
            continue;
        }
        check();
        if (scanned >= max_bytes || now_ms() >= work_end) break;
        if (!selected[ri].available) {
            skipped += m.end - at;
            at = m.end;
            continue;
        }
        uint64_t page_end = at - (at % page) + page;
        uint64_t primary_end = std::min(m.end, page_end);
        size_t primary = static_cast<size_t>(primary_end - at);
        if (primary > max_bytes - scanned) primary = static_cast<size_t>(max_bytes - scanned);
        if (!primary) break;
        size_t length = static_cast<size_t>(std::min<uint64_t>(m.end - at, primary + size - 1));
        ssize_t n = pread(t.mem.value, buffer.data(), length, static_cast<off_t>(at));
        if (n < 0 || static_cast<size_t>(n) < primary) {
            unreadable += primary;
        } else {
            uint64_t first = at;
            if (first % alignment) first += alignment - first % alignment;
            for (uint64_t pos = first;
                 pos < at + primary && pos + size <= m.end && pos - at + size <= static_cast<uint64_t>(n);
                 pos += alignment) {
                if (match(type, "eq", buffer.data() + static_cast<size_t>(pos - at), nullptr, pattern.data(),
                          size, tol)) {
                    if (found.array.size() >= limit) {
                        overflow = true;
                        at = pos;
                        goto done_scan;
                    }
                    found.add(candidate(pos, buffer.data() + static_cast<size_t>(pos - at), size,
                                        static_cast<int>(ri)));
                }
            }
        }
        at += primary;
        scanned += primary;
    }
done_scan:
    return Json::obj()
        .put("candidates", std::move(found))
        .put("cursor", Json::obj().put("regionIndex", static_cast<int>(ri)).put("address", hex_address(at)))
        .put("finished", ri >= selected.size())
        .put("scannedBytes", static_cast<double>(scanned))
        .put("unreadableBytes", static_cast<double>(unreadable))
        .put("changedRegionIndices", changed_regions(selected))
        .put("skippedBytes", static_cast<double>(skipped))
        .put("skippedCandidates", 0)
        .put("coverageIncomplete",
             unreadable != 0 || std::any_of(selected.begin(), selected.end(),
                                            [](const SelectedRegion& v) { return !v.available; }))
        .put("candidateOverflow", overflow);
}
Json refine(const Json& r) {
    Target t(r);
    auto selected = selected_regions(t.regions, r);
    const auto& input = r.get("candidates");
    if (input.kind != Json::ARRAY || input.array.size() > MAX_CANDIDATES) fail("memory_candidate_limit");
    std::string type = r.text("type"), comparison = r.text("comparison");
    size_t size = width(type);
    std::vector<unsigned char> expected;
    if (comparison == "eq") expected = unhex(r.text("hex"), 256);
    if (size && comparison == "eq" && expected.size() != size) fail("invalid_bytes");
    size_t index = r.has("cursorIndex") ? static_cast<size_t>(r.integer("cursorIndex")) : 0;
    if (index > input.array.size()) fail("invalid_cursor");
    Json found = Json::list();
    uint64_t unreadable = 0, skipped = 0;
    int skipped_candidates = 0;
    double tol = tolerance(r);
    uint64_t work_end = std::min(deadline_ms, now_ms() + 3000);
    for (; index < input.array.size(); ++index) {
        check();
        if (now_ms() >= work_end) break;
        const auto& c = input.array[index];
        int ri = c.integer("regionIndex");
        if (static_cast<size_t>(ri) >= selected.size()) fail("invalid_candidate");
        auto old = unhex(c.text("hex"), 256);
        size_t length = size ? size : old.size();
        if (old.size() != length || (comparison == "eq" && expected.size() != length))
            fail("invalid_candidate");
        uint64_t at = address(c.get("address"));
        const auto& region = selected[static_cast<size_t>(ri)];
        range(region.snapshot, at, length);
        if (!region.available) {
            skipped += length;
            ++skipped_candidates;
            continue;
        }
        std::vector<unsigned char> bytes(length);
        ssize_t n = pread(t.mem.value, bytes.data(), bytes.size(), static_cast<off_t>(at));
        if (n != static_cast<ssize_t>(length)) {
            unreadable += length;
            continue;
        }
        if (match(type, comparison, bytes.data(), old.data(), expected.empty() ? nullptr : expected.data(),
                  length, tol))
            found.add(candidate(at, bytes.data(), length, ri));
    }
    return Json::obj()
        .put("candidates", std::move(found))
        .put("nextIndex", static_cast<int>(index))
        .put("finished", index >= input.array.size())
        .put("unreadableBytes", static_cast<double>(unreadable))
        .put("changedRegionIndices", changed_regions(selected))
        .put("skippedBytes", static_cast<double>(skipped))
        .put("skippedCandidates", skipped_candidates)
        .put("coverageIncomplete",
             unreadable != 0 || std::any_of(selected.begin(), selected.end(),
                                            [](const SelectedRegion& v) { return !v.available; }))
        .put("candidateOverflow", false);
}
Json helper_status(const Json& r) {
    const auto& h = r.get("helper");
    if (h.text("bootId") != boot_id()) return Json::obj().put("alive", false);
    int pid = h.integer("pid");
    if (pid <= 0) fail("invalid_helper");
    std::string path = "/proc/" + std::to_string(pid);
    Fd d(open(path.c_str(), O_RDONLY | O_DIRECTORY | O_CLOEXEC));
    if (d.value < 0) {
        if (errno == ENOENT || errno == ESRCH) return Json::obj().put("alive", false);
        fail("memory_helper_status_unknown");
    }
    try {
        auto i = identity(d.value, pid);
        return Json::obj().put("alive", i.uid == 0 && i.start == h.text("startTime"));
    } catch (const Error&) {
        fail("memory_helper_status_unknown");
    }
}
Json self_identity() {
    Fd d(open("/proc/self", O_RDONLY | O_DIRECTORY | O_CLOEXEC));
    if (d.value < 0) fail("memory_target_unavailable");
    auto i = identity(d.value, getpid());
    return Json::obj().put("pid", i.pid).put("startTime", i.start).put("bootId", boot_id());
}
Json write_memory(const Json& r) {
    Target t(r, true);
    const Region& m = find_region(t.regions, r.get("region"));
    if (!m.writable()) fail("memory_region_not_writable");
    auto expected = unhex(r.text("expectedHex"), 8), value = unhex(r.text("valueHex"), 8);
    size_t n = value.size();
    if ((n != 4 && n != 8) || expected.size() != n) fail("memory_write_limit");
    uint64_t at = address(r.get("address"));
    range(m, at, n);
    size_t page = static_cast<size_t>(sysconf(_SC_PAGESIZE));
    if (!page || at % n || at / page != (at + n - 1) / page) fail("memory_write_alignment");
    check();
    std::array<unsigned char, 8> old{};
    ssize_t got = pread(t.mem.value, old.data(), n, static_cast<off_t>(at));
    if (got != static_cast<ssize_t>(n)) fail("memory_read_failed");
    if (std::memcmp(old.data(), expected.data(), n)) fail("memory_value_changed");
    if (!std::memcmp(old.data(), value.data(), n))
        return Json::obj()
            .put("status", "already_equal")
            .put("dispatched", false)
            .put("bytesWritten", 0)
            .put("writeResultKnown", true)
            .put("readback", Json::obj().put("status", "verified").put("hex", hex_bytes(old.data(), n)));
    frame_write(Json::obj().put("event", "prepared").put("helper", self_identity()));
    Json go = frame_read();
    if (!go.flag("go")) fail("memory_cancelled");
    check();
    // GO is deliberately followed by another identity, mapping and byte check.
    try {
        Identity current = identity(t.dir.value, t.info.pid);
        if (current.uid != t.info.uid || current.start != t.info.start) fail("memory_target_changed");
        auto current_maps = maps(t.dir.value);
        const Region& last = find_region(current_maps, r.get("region"));
        if (!last.writable()) fail("memory_region_not_writable");
        got = pread(t.mem.value, old.data(), n, static_cast<off_t>(at));
        if (got != static_cast<ssize_t>(n)) fail("memory_read_failed");
        if (std::memcmp(old.data(), expected.data(), n)) fail("memory_value_changed");
        check();
    } catch (const Error& e) {
        return Json::obj()
            .put("status", "not_dispatched")
            .put("dispatched", false)
            .put("writeResultKnown", true)
            .put("bytesWritten", 0)
            .put("reason", e.code)
            .put("readback", Json::obj().put("status", "not_attempted"));
    }
    ssize_t written = pwrite(t.mem.value, value.data(), n, static_cast<off_t>(at));
    int saved_errno = errno;
    Json result = Json::obj()
                      .put("dispatched", true)
                      .put("writeResultKnown", true)
                      .put("bytesWritten", written < 0 ? 0 : static_cast<int>(written));
    if (written < 0 || written == 0)
        return result.put("status", "write_failed")
            .put("nativeErrno", saved_errno)
            .put("readback", Json::obj().put("status", "not_attempted"));
    if (written != static_cast<ssize_t>(n))
        return result.put("status", "partial_write")
            .put("readback", Json::obj().put("status", "not_attempted"));
    std::array<unsigned char, 8> after{};
    ssize_t readback = pread(t.mem.value, after.data(), n, static_cast<off_t>(at));
    if (readback != static_cast<ssize_t>(n))
        return result.put("status", "written_unverified")
            .put("readback", Json::obj().put("status", "unavailable"));
    bool equal = !std::memcmp(after.data(), value.data(), n);
    return result.put("status", equal ? "written_verified" : "written_unverified")
        .put("readback", Json::obj()
                             .put("status", equal ? "verified" : "different")
                             .put("hex", hex_bytes(after.data(), n)));
}
Json dispatch(const Json& r) {
    std::string op = r.text("op");
    if (op == "query") return query(r);
    if (op == "inspect") return inspect(r);
    if (op == "regions") return regions(r);
    if (op == "read") return read_memory(r);
    if (op == "scan") return scan(r);
    if (op == "refine") return refine(r);
    if (op == "write") return write_memory(r);
    if (op == "helper_status") return helper_status(r);
    fail("invalid_operation");
}

int self_test() {
    Json j = Parser("{\"a\":[true,1,\"\\u4f60\\u597d\"],\"b\":null}").parse();
    if (j.get("a").array.size() != 3 || Parser(encoded(j)).parse().get("a").array[2].text() != "你好")
        return 1;
    for (const char* s : {"{\"a\":1,\"a\":2}", "[01]", "[1,]", "\"\\ud800\""}) {
        try {
            Parser(s).parse();
            return 2;
        } catch (const Error&) {
        }
    }
    auto bytes = unhex("ffffffff00000000");
    if (hex_bytes(bytes.data(), bytes.size()) != "ffffffff00000000") return 3;
    int64_t x = INT64_MIN, y = INT64_MAX;
    if (!match("int64", "increased", reinterpret_cast<unsigned char*>(&y),
               reinterpret_cast<unsigned char*>(&x), nullptr, 8, 0))
        return 4;
    uint64_t u = UINT64_MAX, v = UINT64_MAX - 1;
    if (!match("uint64", "increased", reinterpret_cast<unsigned char*>(&u),
               reinterpret_cast<unsigned char*>(&v), nullptr, 8, 0))
        return 5;
    float a = 1.25f, b = 1.2501f;
    if (!match("float32", "eq", reinterpret_cast<unsigned char*>(&a), nullptr,
               reinterpret_cast<unsigned char*>(&b), 4, 0.001))
        return 6;
    Region stale, stable;
    stale.start = 0x1000;
    stale.end = 0x2000;
    stale.perms = "rw-p";
    stale.signature = "old";
    stable.start = 0x3000;
    stable.end = 0x4000;
    stable.perms = "rw-p";
    stable.signature = "stable";
    Region changed = stale;
    changed.signature = "new";
    auto selection = selected_regions(
        {changed, stable},
        Json::obj().put("regions", Json::list().add(region_json(stale)).add(region_json(stable))));
    if (selection.size() != 2 || selection[0].available || !selection[1].available ||
        selection[1].snapshot.start != stable.start || changed_regions(selection).array[0].integer() != 0)
        return 7;
    auto disappeared = selected_regions(
        {stable}, Json::obj().put("regions", Json::list().add(region_json(stale)).add(region_json(stable))));
    if (disappeared.size() != 2 || disappeared[0].available || !disappeared[1].available) return 8;
    std::puts("memory native self-test passed");
    return 0;
}
}  // namespace

int main(int argc, char** argv) {
    if (argc == 2 && std::string(argv[1]) == "--self-test") return self_test();
    try {
        deadline_ms = now_ms() + 15000;
        if (geteuid() != 0) fail("memory_root_required");
        Json request = frame_read();
        if (request.integer("protocol") != 1) fail("memory_protocol_version");
        int budget = request.integer("budgetMs");
        if (budget < 1 || budget > 15000) fail("memory_deadline");
        deadline_ms = now_ms() + static_cast<uint64_t>(budget);
        Json result = dispatch(request);
        frame_write(Json::obj().put("ok", true).put("result", std::move(result)));
        return 0;
    } catch (const Error& e) {
        try {
            frame_write(Json::obj()
                            .put("ok", false)
                            .put("code", e.code)
                            .put("message",
                                 "The memory operation could not complete under its current target, "
                                 "permissions or limits"));
        } catch (...) {
        }
        return 1;
    } catch (...) {
        try {
            frame_write(Json::obj()
                            .put("ok", false)
                            .put("code", "memory_native_failed")
                            .put("message", "The fixed memory executor failed"));
        } catch (...) {
        }
        return 1;
    }
}
