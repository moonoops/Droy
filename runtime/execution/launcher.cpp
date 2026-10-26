#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif
#include "protocol.hpp"
#include <arpa/inet.h>
#include <dirent.h>
#include <fcntl.h>
#include <grp.h>
#include <linux/audit.h>
#include <linux/capability.h>
#include <linux/filter.h>
#include <linux/mount.h>
#include <linux/magic.h>
#include <linux/seccomp.h>
#include <linux/securebits.h>
#include <net/if.h>
#include <poll.h>
#include <pwd.h>
#include <pty.h>
#include <sched.h>
#include <signal.h>
#include <sys/ioctl.h>
#include <sys/file.h>
#include <sys/mount.h>
#include <sys/prctl.h>
#include <sys/resource.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/sysmacros.h>
#include <sys/un.h>
#include <sys/vfs.h>
#include <sys/wait.h>
#include <termios.h>
#include <time.h>
#include <unistd.h>
#include <algorithm>
#include <array>
#include <cerrno>
#include <cstdio>
#include <cstring>
#include <deque>
#include <memory>
#include <set>

using namespace droy_exec;
namespace {
constexpr size_t kBufferLimit = 4 * 1024 * 1024;
constexpr uint16_t kProxyPort = 16880;
bool namespace_created = false, namespace_reaped = false;
struct Fd {
    int value = -1;
    explicit Fd(int n = -1) : value(n) {}
    Fd(Fd&& other) noexcept : value(other.release()) {}
    Fd& operator=(Fd&& other) noexcept { reset(other.release()); return *this; }
    Fd(const Fd&) = delete; Fd& operator=(const Fd&) = delete;
    ~Fd() { reset(); }
    int release() { int n = value; value = -1; return n; }
    void reset(int n = -1) { if (value >= 0) close(value); value = n; }
    operator int() const { return value; }
};
[[noreturn]] void system_fail(const char* stage) {
    throw Error(std::string(stage) + ":" + std::to_string(errno));
}
void check(int result, const char* stage) { if (result < 0) system_fail(stage); }
uint64_t now_ms() {
    timespec t{}; check(clock_gettime(CLOCK_MONOTONIC, &t), "clock");
    return uint64_t(t.tv_sec) * 1000 + t.tv_nsec / 1000000;
}
void nonblock(int fd) { check(fcntl(fd, F_SETFL, fcntl(fd, F_GETFL) | O_NONBLOCK), "nonblock"); }
std::string fd_path(int fd) { return "/proc/self/fd/" + std::to_string(fd); }
std::string link_at(int dir, const char* name) {
    std::array<char, 4096> buf{}; ssize_t n = readlinkat(dir, name, buf.data(), buf.size());
    if (n < 0) system_fail("readlink"); require(size_t(n) < buf.size(), "link_too_long");
    return std::string(buf.data(), size_t(n));
}
std::string canonical(const std::string& path) {
    std::unique_ptr<char, decltype(&free)> p(realpath(path.c_str(), nullptr), free);
    if (!p) system_fail("canonical_root"); return p.get();
}
Fd directory(const std::string& original) {
    // Canonicalization is only for host-owned ancestors such as /data/user/0.
    // The subsequent component walk never follows mutable symlinks.
    std::string path = canonical(original); Fd fd(open("/", O_RDONLY | O_DIRECTORY | O_CLOEXEC));
    check(fd, "open_root"); size_t from = 1;
    while (from < path.size()) {
        size_t end = path.find('/', from); if (end == std::string::npos) end = path.size();
        Fd next(openat(fd, path.substr(from, end - from).c_str(), O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW));
        check(next, "open_root_component"); fd = std::move(next); from = end + 1;
    }
    return fd;
}
Fd child_directory(int parent, const std::string& name, bool create, mode_t mode = 0700) {
    if (create && mkdirat(parent, name.c_str(), mode) < 0 && errno != EEXIST) system_fail("mkdir_child");
    Fd result(openat(parent, name.c_str(), O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW));
    check(result, "open_child_directory"); return result;
}
void owned_by(int fd, uid_t owner) {
    struct stat st{}; check(fstat(fd, &st), "stat_owner"); require(st.st_uid == owner, "unexpected_root_owner");
}
struct TreeBudget { uint64_t bytes = 0; size_t entries = 0; uint64_t deadline = 0; };
template<class Fn> void entries(int dir, Fn fn) {
    Fd copy(openat(dir, ".", O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW)); check(copy, "list_tree");
    DIR* raw = fdopendir(copy.release()); if (!raw) system_fail("fdopendir");
    std::unique_ptr<DIR, decltype(&closedir)> list(raw, closedir);
    errno = 0;
    while (dirent* ent = readdir(list.get())) {
        if (!strcmp(ent->d_name, ".") || !strcmp(ent->d_name, "..")) continue;
        fn(std::string(ent->d_name)); errno = 0;
    }
    if (errno) system_fail("readdir");
}
void tree_budget(TreeBudget& budget, int depth) {
    require(depth < 64 && ++budget.entries <= 100000 && budget.bytes <= 1024ull * 1024 * 1024, "workspace_limit");
    if (budget.deadline) require(now_ms() < budget.deadline, "preparation_timeout");
    pollfd host{0, 0, 0}; check(poll(&host, 1, 0), "preparation_host_check");
    require(!(host.revents & (POLLHUP | POLLERR | POLLNVAL)), "host_disconnected");
}
void prepare_permissions(int fd, const struct stat& st, gid_t group, mode_t mode) {
    bool group_changed = st.st_gid != group;
    if (group_changed) check(fchown(fd, -1, group), "workspace_group");
    // Even an unchanged chmod/chown updates ctime, which DSH includes in its
    // file version. A new isolated RPC must not invalidate a prior read guard.
    if (group_changed || (st.st_mode & 07777) != mode)
        check(fchmod(fd, mode), "workspace_permissions");
}
mode_t workspace_file_mode(mode_t original) {
    // A leased UID accesses host-owned or seeded scripts through the shared
    // group. Preserve owner execution for that group without granting others.
    return 0660 | (original & 0110) | ((original & 0100) ? 0010 : 0);
}
void prepare_tree(int dir, gid_t group, bool writable, TreeBudget& budget, int depth = 0) {
    tree_budget(budget, depth); struct stat root{}; check(fstat(dir, &root), "stat_tree");
    prepare_permissions(dir, root, group, writable ? 02770 : ((root.st_mode & 0777) | 0050));
    entries(dir, [&](const std::string& name) {
        struct stat st{}; check(fstatat(dir, name.c_str(), &st, AT_SYMLINK_NOFOLLOW), "stat_entry");
        if (S_ISLNK(st.st_mode)) return;
        if (S_ISDIR(st.st_mode)) {
            Fd sub = child_directory(dir, name, false); prepare_tree(sub, group, writable, budget, depth + 1); return;
        }
        require(S_ISREG(st.st_mode) && st.st_nlink == 1, "workspace_special_or_linked_file");
        tree_budget(budget, depth); Fd file(openat(dir, name.c_str(), O_RDONLY | O_CLOEXEC | O_NOFOLLOW)); check(file, "open_entry");
        struct stat actual{}; check(fstat(file, &actual), "stat_open_entry");
        require(S_ISREG(actual.st_mode) && actual.st_nlink == 1 && actual.st_ino == st.st_ino && actual.st_dev == st.st_dev, "workspace_changed");
        mode_t mode = writable ? workspace_file_mode(actual.st_mode) : ((actual.st_mode & 0777) | 0040 | ((actual.st_mode & 0100) ? 0010 : 0));
        prepare_permissions(file, actual, group, mode);
    });
}
void copy_tree(int source, int destination, gid_t group, TreeBudget& budget, int depth = 0) {
    tree_budget(budget, depth);
    entries(source, [&](const std::string& name) {
        struct stat st{}; check(fstatat(source, name.c_str(), &st, AT_SYMLINK_NOFOLLOW), "seed_stat");
        if (S_ISLNK(st.st_mode)) {
            auto target = link_at(source, name.c_str()); check(symlinkat(target.c_str(), destination, name.c_str()), "seed_symlink"); return;
        }
        if (S_ISDIR(st.st_mode)) {
            Fd from = child_directory(source, name, false); Fd to = child_directory(destination, name, true);
            copy_tree(from, to, group, budget, depth + 1); return;
        }
        require(S_ISREG(st.st_mode) && st.st_nlink == 1, "seed_special_or_linked_file");
        Fd from(openat(source, name.c_str(), O_RDONLY | O_NOFOLLOW | O_CLOEXEC)); check(from, "seed_open");
        struct stat actual{}; check(fstat(from, &actual), "seed_fstat");
        require(S_ISREG(actual.st_mode) && actual.st_nlink == 1 && actual.st_ino == st.st_ino && actual.st_dev == st.st_dev, "seed_changed");
        tree_budget(budget, depth); Fd to(openat(destination, name.c_str(), O_CREAT | O_EXCL | O_WRONLY | O_NOFOLLOW | O_CLOEXEC, 0600)); check(to, "seed_create");
        std::array<uint8_t, 65536> buffer{};
        for (;;) {
            if (budget.deadline) require(now_ms() < budget.deadline, "preparation_timeout");
            pollfd host{0, 0, 0}; check(poll(&host, 1, 0), "seed_host_check");
            require(!(host.revents & (POLLHUP | POLLERR | POLLNVAL)), "host_disconnected");
            ssize_t got = read(from, buffer.data(), buffer.size()); if (got < 0 && errno == EINTR) continue; check(got, "seed_read"); if (!got) break;
            budget.bytes += got; require(budget.bytes <= 1024ull * 1024 * 1024, "workspace_limit");
            size_t at = 0; while (at < size_t(got)) { ssize_t n = write(to, buffer.data() + at, got - at); if (n < 0 && errno == EINTR) continue; check(n, "seed_write"); at += n; }
        }
        check(fchown(to, kGuestUid, group), "seed_owner"); check(fchmod(to, workspace_file_mode(actual.st_mode)), "seed_mode");
    });
    check(fchown(destination, kGuestUid, group), "seed_dir_owner"); check(fchmod(destination, 02770), "seed_dir_mode");
}
void remove_tree(int dir) {
    entries(dir, [&](const std::string& name) {
        struct stat st{}; check(fstatat(dir, name.c_str(), &st, AT_SYMLINK_NOFOLLOW), "remove_stat");
        if (S_ISDIR(st.st_mode)) { Fd sub = child_directory(dir, name, false); remove_tree(sub); check(unlinkat(dir, name.c_str(), AT_REMOVEDIR), "remove_dir"); }
        else check(unlinkat(dir, name.c_str(), 0), "remove_file");
    });
}
Fd workspace(const Start& start, int files, uint64_t deadline) {
    Fd dsh_home;
    if (start.workspace_branch() == "default") dsh_home = child_directory(files, "dsh-home", true);
    Fd clean = child_directory(dsh_home >= 0 ? int(dsh_home) : files, "workspace", true); struct stat clean_st{}; check(fstat(clean, &clean_st), "workspace_owner");
    require(clean_st.st_uid == start.host_uid || clean_st.st_uid == kGuestUid || clean_st.st_uid == 0, "workspace_owner");
    if (start.workspace_id.empty()) return clean;
    Fd execution = child_directory(files, "droy-execution", true);
    Fd workspaces = child_directory(execution, "workspaces", true);
    for (int parent : {int(execution), int(workspaces)}) {
        struct stat st{}; check(fstat(parent, &st), "execution_parent_owner");
        require(st.st_uid == 0 || st.st_uid == start.host_uid, "execution_parent_owner");
        check(fchown(parent, start.host_uid, start.host_uid), "execution_parent_group"); check(fchmod(parent, 0700), "execution_parent_mode");
    }
    Fd session = child_directory(workspaces, start.workspace_id, true);
    check(fchown(session, start.host_uid, start.host_uid), "session_workspace_owner"); check(fchmod(session, 0700), "session_workspace_mode");
    std::string branch = start.workspace_branch();
    int existing = openat(session, branch.c_str(), O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
    if (existing >= 0) return Fd(existing);
    require(errno == ENOENT, "workspace_open_failed");
    std::string temporary = ".seed-" + std::to_string(getpid()) + "-" + std::to_string(now_ms());
    check(mkdirat(session, temporary.c_str(), 0700), "seed_mkdir");
    Fd staging = child_directory(session, temporary, false);
    try {
        TreeBudget budget; budget.deadline = deadline;
        // The reserved qualification workspace never copies the user's project.
        if (start.workspace_id != std::string(64, '0')) copy_tree(clean, staging, start.host_uid, budget);
        else { check(fchown(staging, kGuestUid, start.host_uid), "qualification_owner"); check(fchmod(staging, 02770), "qualification_permissions"); }
        int result = syscall(SYS_renameat2, int(session), temporary.c_str(), int(session), branch.c_str(), 1);
        if (result < 0 && errno != EEXIST) system_fail("seed_publish");
        if (result < 0) { remove_tree(staging); check(unlinkat(session, temporary.c_str(), AT_REMOVEDIR), "seed_cleanup"); }
    } catch (...) {
        try { remove_tree(staging); unlinkat(session, temporary.c_str(), AT_REMOVEDIR); } catch (...) { }
        throw;
    }
    return child_directory(session, branch, false);
}
void mkdirs(const std::string& path, mode_t mode = 0755) {
    size_t at = 1;
    while (at <= path.size()) {
        size_t end = path.find('/', at); if (end == std::string::npos) end = path.size();
        std::string part = path.substr(0, end);
        if (!part.empty() && mkdir(part.c_str(), mode) < 0 && errno != EEXIST) system_fail("guest_mkdir");
        at = end + 1;
    }
}
void ro_mount(int source, const std::string& target, bool directory_source = true) {
    if (directory_source) mkdirs(target);
    else { mkdirs(target.substr(0, target.find_last_of('/'))); Fd file(open(target.c_str(), O_CREAT | O_EXCL | O_WRONLY | O_CLOEXEC, 0644)); check(file, "mount_file"); }
    check(mount(fd_path(source).c_str(), target.c_str(), nullptr, MS_BIND | (directory_source ? MS_REC : 0), nullptr), "bind_readonly");
    struct mount_attr attr{}; attr.attr_set = MOUNT_ATTR_RDONLY | MOUNT_ATTR_NOSUID | MOUNT_ATTR_NODEV;
    check(syscall(SYS_mount_setattr, AT_FDCWD, target.c_str(), AT_RECURSIVE, &attr, sizeof(attr)), "recursive_readonly");
}
void send_packet(int socket, uint8_t type, const std::string& data = {}, int fd = -1) {
    Bytes payload{type}; payload.insert(payload.end(), data.begin(), data.end());
    iovec io{payload.data(), payload.size()}; msghdr msg{}; msg.msg_iov = &io; msg.msg_iovlen = 1;
    char ancillary[CMSG_SPACE(sizeof(int))]{};
    if (fd >= 0) {
        msg.msg_control = ancillary; msg.msg_controllen = sizeof(ancillary);
        auto* control = CMSG_FIRSTHDR(&msg); control->cmsg_level = SOL_SOCKET; control->cmsg_type = SCM_RIGHTS; control->cmsg_len = CMSG_LEN(sizeof(int));
        memcpy(CMSG_DATA(control), &fd, sizeof(fd));
    }
    if (sendmsg(socket, &msg, MSG_NOSIGNAL) != ssize_t(payload.size())) system_fail("status_send");
}
struct Packet { uint8_t type = 0; Bytes data; Fd fd; bool eof = false; };
Packet packet(int socket) {
    std::array<uint8_t, 8192> bytes{}; char ancillary[CMSG_SPACE(sizeof(int) * 2)]{};
    iovec io{bytes.data(), bytes.size()}; msghdr msg{}; msg.msg_iov = &io; msg.msg_iovlen = 1;
    msg.msg_control = ancillary; msg.msg_controllen = sizeof(ancillary);
    ssize_t n = recvmsg(socket, &msg, MSG_CMSG_CLOEXEC); check(n, "status_receive");
    Packet result; if (!n) { result.eof = true; return result; }
    require(!(msg.msg_flags & (MSG_TRUNC | MSG_CTRUNC)), "status_truncated");
    result.type = bytes[0]; result.data.assign(bytes.begin() + 1, bytes.begin() + n);
    for (auto* c = CMSG_FIRSTHDR(&msg); c; c = CMSG_NXTHDR(&msg, c)) {
        require(c->cmsg_level == SOL_SOCKET && c->cmsg_type == SCM_RIGHTS && c->cmsg_len == CMSG_LEN(sizeof(int)) && result.fd < 0, "invalid_status_descriptor");
        int fd; memcpy(&fd, CMSG_DATA(c), sizeof(fd)); result.fd.reset(fd);
    }
    return result;
}
void close_except(const std::set<int>& keep) {
    Fd directory_fd(open("/proc/self/fd", O_RDONLY | O_DIRECTORY | O_CLOEXEC)); check(directory_fd, "list_descriptors");
    std::vector<int> descriptors; entries(directory_fd, [&](const std::string& name) {
        char* end = nullptr; long fd = strtol(name.c_str(), &end, 10);
        if (end && !*end && fd >= 0 && fd != directory_fd && !keep.count(int(fd))) descriptors.push_back(fd);
    });
    for (int fd : descriptors) close(fd);
}
void drop_privileges(const Start& start) {
    check(setgroups(0, nullptr), "drop_groups");
    check(prctl(PR_SET_SECUREBITS, SECBIT_NOROOT | SECBIT_NOROOT_LOCKED | SECBIT_NO_SETUID_FIXUP | SECBIT_NO_SETUID_FIXUP_LOCKED, 0, 0, 0), "securebits");
    for (int cap = 0; cap < 64; ++cap) {
        if (prctl(PR_CAPBSET_DROP, cap, 0, 0, 0) < 0 && errno != EINVAL) system_fail("drop_bounding_cap");
    }
    check(setresgid(start.host_uid, start.host_uid, start.host_uid), "drop_gid");
    check(setresuid(start.guest_uid, start.guest_uid, start.guest_uid), "drop_uid");
    __user_cap_header_struct header{_LINUX_CAPABILITY_VERSION_3, 0};
    __user_cap_data_struct caps[2]{}; check(syscall(SYS_capset, &header, caps), "drop_capabilities");
    check(prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0), "no_new_privs");
    // init and its untrusted child share a UID. Never let the child inspect init's
    // retained host transport descriptors, irrespective of fs.suid_dumpable.
    check(prctl(PR_SET_DUMPABLE, 0, 0, 0, 0), "init_not_dumpable");
    uid_t r, e, s; check(getresuid(&r, &e, &s), "verify_uid");
    gid_t gr, ge, gs; check(getresgid(&gr, &ge, &gs), "verify_gid");
    require(r == start.guest_uid && e == r && s == r && gr == start.host_uid && ge == gr && gs == gr && getgroups(0, nullptr) == 0, "privilege_drop_not_verified");
    check(syscall(SYS_capget, &header, caps), "verify_capabilities");
    for (const auto& c : caps) require(c.effective == 0 && c.permitted == 0 && c.inheritable == 0, "capabilities_not_empty");
    require(prctl(PR_GET_NO_NEW_PRIVS, 0, 0, 0, 0) == 1, "no_new_privs_not_verified");
    require(prctl(PR_GET_DUMPABLE, 0, 0, 0, 0) == 0, "dumpable_not_cleared");
    require(setuid(0) == -1 && getuid() == start.guest_uid && geteuid() == start.guest_uid, "root_reacquired");
    check(prctl(PR_SET_PDEATHSIG, SIGKILL, 0, 0, 0), "parent_death_signal");
}
void syscall_filter() {
    std::vector<sock_filter> code;
    auto statement = [&](uint16_t op, uint32_t k) { code.push_back(sock_filter{op, 0, 0, k}); };
    auto jump = [&](uint32_t nr, uint8_t yes, uint8_t no) { code.push_back(sock_filter{uint16_t(BPF_JMP | BPF_JEQ | BPF_K), yes, no, nr}); };
    statement(BPF_LD | BPF_W | BPF_ABS, offsetof(seccomp_data, arch));
    jump(AUDIT_ARCH_AARCH64, 1, 0); statement(BPF_RET | BPF_K, SECCOMP_RET_KILL_PROCESS);
    statement(BPF_LD | BPF_W | BPF_ABS, offsetof(seccomp_data, nr));
    const int denied[] = {SYS_mount, SYS_umount2, SYS_pivot_root, SYS_chroot, SYS_setns, SYS_unshare,
        SYS_ptrace, SYS_process_vm_readv, SYS_process_vm_writev, SYS_open_by_handle_at,
        SYS_bpf, SYS_perf_event_open, SYS_reboot, SYS_kexec_load, SYS_init_module, SYS_finit_module,
        SYS_delete_module, SYS_swapon, SYS_swapoff, SYS_mknodat, SYS_userfaultfd,
        SYS_add_key, SYS_request_key, SYS_keyctl,
        SYS_io_uring_setup, SYS_io_uring_enter, SYS_io_uring_register,
        SYS_setuid, SYS_setreuid, SYS_setresuid, SYS_setfsuid,
        SYS_setgid, SYS_setregid, SYS_setresgid, SYS_setfsgid, SYS_setgroups,
        SYS_shmget, SYS_shmat, SYS_shmdt, SYS_shmctl, SYS_semget, SYS_semctl, SYS_semop, SYS_semtimedop,
        SYS_msgget, SYS_msgsnd, SYS_msgrcv, SYS_msgctl,
        SYS_mq_open, SYS_mq_unlink, SYS_mq_timedsend, SYS_mq_timedreceive, SYS_mq_notify, SYS_mq_getsetattr};
    for (int nr : denied) { jump(nr, 0, 1); statement(BPF_RET | BPF_K, SECCOMP_RET_ERRNO | EPERM); }
    jump(SYS_clone3, 0, 1); statement(BPF_RET | BPF_K, SECCOMP_RET_ERRNO | ENOSYS);
    jump(SYS_clone, 0, 3);
    statement(BPF_LD | BPF_W | BPF_ABS, offsetof(seccomp_data, args[0]));
    code.push_back(sock_filter{uint16_t(BPF_JMP | BPF_JSET | BPF_K), 0, 1,
        CLONE_NEWUSER | CLONE_NEWNS | CLONE_NEWNET | CLONE_NEWPID | CLONE_NEWIPC | CLONE_NEWUTS | CLONE_NEWCGROUP | CLONE_PARENT | CLONE_UNTRACED});
    statement(BPF_RET | BPF_K, SECCOMP_RET_ERRNO | EPERM);
    statement(BPF_LD | BPF_W | BPF_ABS, offsetof(seccomp_data, nr));
    jump(SYS_ioctl, 0, 5);
    statement(BPF_LD | BPF_W | BPF_ABS, offsetof(seccomp_data, args[1]));
    jump(TIOCSTI, 0, 1); statement(BPF_RET | BPF_K, SECCOMP_RET_ERRNO | EPERM);
    jump(TIOCLINUX, 0, 1); statement(BPF_RET | BPF_K, SECCOMP_RET_ERRNO | EPERM);
    // VSOCK and other non-IP transports are not covered by the network namespace.
    // Only local Unix, IP, and namespace-local route discovery are needed here.
    statement(BPF_LD | BPF_W | BPF_ABS, offsetof(seccomp_data, nr));
    jump(SYS_socket, 0, 12);
    statement(BPF_LD | BPF_W | BPF_ABS, offsetof(seccomp_data, args[0]));
    for (int family : {AF_UNIX, AF_INET, AF_INET6}) { jump(family, 0, 1); statement(BPF_RET | BPF_K, SECCOMP_RET_ALLOW); }
    jump(AF_NETLINK, 0, 3);
    statement(BPF_LD | BPF_W | BPF_ABS, offsetof(seccomp_data, args[2]));
    jump(0 /* NETLINK_ROUTE */, 0, 1); statement(BPF_RET | BPF_K, SECCOMP_RET_ALLOW);
    statement(BPF_RET | BPF_K, SECCOMP_RET_ERRNO | EPERM);
    statement(BPF_LD | BPF_W | BPF_ABS, offsetof(seccomp_data, nr));
    jump(SYS_socketpair, 0, 4);
    statement(BPF_LD | BPF_W | BPF_ABS, offsetof(seccomp_data, args[0]));
    jump(AF_UNIX, 0, 1); statement(BPF_RET | BPF_K, SECCOMP_RET_ALLOW);
    statement(BPF_RET | BPF_K, SECCOMP_RET_ERRNO | EPERM);
    statement(BPF_RET | BPF_K, SECCOMP_RET_ALLOW);
    sock_fprog program{static_cast<unsigned short>(code.size()), code.data()};
    check(prctl(PR_SET_SECCOMP, SECCOMP_MODE_FILTER, &program), "seccomp_install");
}
struct Context {
    Start start; std::string native, root_path; int root_fd = -1, runtime_fd = -1, native_fd = -1, cert_fd = -1;
    int status = -1, in = -1, out = -1, err = -1, control = -1;
    std::vector<std::pair<std::string, int>> system;
    std::vector<std::pair<std::string, int>> workspaces;
    std::array<ino_t, 3> original_ns{};
    uint64_t started = 0;
    pid_t supervisor_pid = -1;
};
std::string read_small(const std::string& path, size_t limit);
uid_t process_uid(pid_t pid);
uint64_t start_ticks(pid_t pid);
bool disappearing(const Error& error) {
    std::string code = error.what(); return code == "process_read:2" || code == "process_read:3";
}
std::vector<pid_t> uid_processes(uid_t uid) {
    Fd proc = directory("/proc"); std::vector<pid_t> result;
    entries(proc, [&](const std::string& name) {
        if (name.empty() || name.find_first_not_of("0123456789") != std::string::npos) return;
        pid_t pid = std::stol(name);
        try { if (process_uid(pid) == uid) result.push_back(pid); }
        catch (const Error& e) { if (!disappearing(e)) throw; }
    });
    return result;
}
void reap_uid(uid_t uid) {
    require(uid % 100000 >= 80000 && uid % 100000 < 80008, "invalid_lease_uid");
    uint64_t deadline = now_ms() + 5000;
    for (;;) {
        while (waitpid(-1, nullptr, WNOHANG) > 0) {}
        auto remaining = uid_processes(uid); if (remaining.empty()) return;
        require(now_ms() < deadline, "uid_cleanup_timeout");
        for (pid_t pid : remaining) {
            try {
                uint64_t ticks = start_ticks(pid); if (process_uid(pid) != uid) continue;
                Fd handle(syscall(SYS_pidfd_open, pid, 0));
                if (handle < 0 && errno == ESRCH) continue; check(handle, "uid_cleanup_pidfd");
                if (start_ticks(pid) != ticks || process_uid(pid) != uid) continue;
                if (syscall(SYS_pidfd_send_signal, int(handle), SIGKILL, nullptr, 0) < 0 && errno != ESRCH) system_fail("uid_cleanup_signal");
            } catch (const Error& e) { if (!disappearing(e)) throw; }
        }
        pollfd delay{-1, 0, 0}; (void)poll(&delay, 0, 10);
    }
}
struct UidLease { uid_t uid = 0; Fd lock_socket, record; };
void release_uid_lease(UidLease& lease) {
    require(!namespace_created || namespace_reaped, "uid_lease_still_active");
    check(ftruncate(lease.record, 0), "uid_lease_release");
    check(fsync(lease.record), "uid_lease_release_sync");
}
UidLease acquire_uid(const Start& start, int files) {
    struct stat host_net{}, current_net{};
    check(stat("/proc/1/ns/net", &host_net), "host_net_identity"); check(stat("/proc/self/ns/net", &current_net), "current_net_identity");
    require(host_net.st_ino == current_net.st_ino, "uid_lock_outside_host_network");
    Fd execution = child_directory(files, "droy-execution", true);
    Fd leases = child_directory(execution, "leases", true); owned_by(leases, 0); check(fchmod(leases, 0700), "lease_permissions");
    std::string boot = read_small("/proc/sys/kernel/random/boot_id", 80);
    require(boot.size() == 37 && boot.back() == '\n' && boot.find_first_not_of("0123456789abcdef-\n") == std::string::npos, "invalid_boot_identity");
    boot.pop_back();
    for (uid_t slot = 80000; slot < 80008; ++slot) {
        uid_t uid = (start.host_uid / 100000) * 100000 + slot;
        // Android's package, SDK sandbox, isolated and OEM IDs do not use this
        // app-id range. A platform-supplied passwd identity still vetoes it.
        if (getpwuid(uid) != nullptr) continue;
        Fd lock(socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0)); check(lock, "uid_lock_socket");
        sockaddr_un address{}; address.sun_family = AF_UNIX;
        std::string name = "droy.execution.uid." + std::to_string(uid);
        memcpy(address.sun_path + 1, name.data(), name.size());
        if (bind(lock, reinterpret_cast<sockaddr*>(&address), offsetof(sockaddr_un, sun_path) + 1 + name.size()) < 0) {
            if (errno == EADDRINUSE) continue; system_fail("uid_lock_bind");
        }
        Fd record(openat(leases, std::to_string(uid).c_str(), O_RDWR | O_CREAT | O_CLOEXEC | O_NOFOLLOW, 0600)); check(record, "uid_lease_open");
        struct stat st{}; check(fstat(record, &st), "uid_lease_stat");
        require(S_ISREG(st.st_mode) && st.st_uid == 0 && st.st_nlink == 1 && !(st.st_mode & 0077), "invalid_uid_lease");
        check(flock(record, LOCK_EX | LOCK_NB), "uid_lease_lock");
        require(st.st_size <= 160, "invalid_uid_lease_size");
        char old[161]{}; ssize_t count = pread(record, old, sizeof(old) - 1, 0); check(count, "uid_lease_read");
        std::string prefix = "DROY_UID_LEASE_V2 " + std::to_string(uid) + " " + std::to_string(start.host_uid) + " " + boot + " ";
        std::string expected = prefix + std::to_string(getpid()) + " " + std::to_string(start_ticks(getpid())) + "\n";
        if (!uid_processes(uid).empty()) {
            // A missing/replaced record or a prior boot never authorizes killing
            // an unidentified process. Do not reuse that identity.
            std::string previous(old, count);
            if (previous.compare(0, prefix.size(), prefix) != 0) continue;
            int owner = 0; unsigned long long owner_ticks = 0; char trailing = 0;
            require(sscanf(previous.c_str() + prefix.size(), "%d %llu %c", &owner, &owner_ticks, &trailing) == 2 && owner > 1 && owner_ticks > 0, "invalid_uid_lease_owner");
            bool owner_alive = false;
            try { owner_alive = start_ticks(owner) == owner_ticks; } catch (const Error& e) { if (!disappearing(e)) throw; }
            if (owner_alive) continue;
            reap_uid(uid);
        }
        check(ftruncate(record, 0), "uid_lease_truncate");
        require(pwrite(record, expected.data(), expected.size(), 0) == ssize_t(expected.size()), "uid_lease_write");
        check(fsync(record), "uid_lease_sync");
        UidLease result; result.uid = uid; result.lock_socket = std::move(lock); result.record = std::move(record); return result;
    }
    throw Error("execution_concurrency_or_uid_unavailable");
}
constexpr const char* kNamespaces[] = {"mnt", "net", "uts"};
std::array<ino_t, 3> namespaces() {
    std::array<ino_t, 3> result{};
    for (size_t i = 0; i < result.size(); ++i) {
        struct stat st{};
        if (stat((std::string("/proc/self/ns/") + kNamespaces[i]).c_str(), &st) < 0)
            system_fail((std::string("namespace_unavailable_") + kNamespaces[i]).c_str());
        result[i] = st.st_ino;
    }
    return result;
}
Fd current_namespace_source(int original, const std::string& fixed_path, bool is_directory = true) {
    Fd reopened;
    if (is_directory) reopened = directory(fixed_path);
    else {
        size_t slash = fixed_path.find_last_of('/'); Fd parent = directory(fixed_path.substr(0, slash));
        reopened.reset(openat(parent, fixed_path.substr(slash + 1).c_str(), O_RDONLY | O_NOFOLLOW | O_CLOEXEC)); check(reopened, "reopen_source_file");
    }
    struct stat before{}, after{}; check(fstat(original, &before), "original_source_stat"); check(fstat(reopened, &after), "reopened_source_stat");
    require(before.st_dev == after.st_dev && before.st_ino == after.st_ino && (before.st_mode & S_IFMT) == (after.st_mode & S_IFMT), "mount_source_replaced");
    return reopened;
}
void root_filesystem(Context& c) {
    check(mount(nullptr, "/", nullptr, MS_REC | MS_PRIVATE, nullptr), "private_mounts");
    // clone copied the mount namespace, but inherited FDs still reference mounts
    // in its parent. A mount target must be reopened in the new namespace.
    size_t slash = c.root_path.find_last_of('/');
    Fd root_parent = directory(c.root_path.substr(0, slash));
    std::string root_name = c.root_path.substr(slash + 1);
    Fd current_root = child_directory(root_parent, root_name, false);
    struct stat original{}, current{}; check(fstat(c.root_fd, &original), "original_root_stat"); check(fstat(current_root, &current), "current_root_stat");
    require(original.st_dev == current.st_dev && original.st_ino == current.st_ino, "sandbox_root_replaced");
    check(mount("tmpfs", fd_path(current_root).c_str(), "tmpfs", MS_NOSUID | MS_NODEV, "mode=0755,size=64m"), "root_tmpfs");
    Fd mounted_root = child_directory(root_parent, root_name, false);
    struct statfs mounted_type{}; check(fstatfs(mounted_root, &mounted_type), "mounted_root_type");
    require(mounted_type.f_type == TMPFS_MAGIC, "sandbox_root_not_tmpfs");
    // Keep subsequent setup anchored to the opened tmpfs, even if the host-side
    // parent directory is renamed while this private mount is being prepared.
    const std::string root = fd_path(mounted_root);
    for (const auto& source : c.system) {
        Fd current = current_namespace_source(source.second, source.first); ro_mount(current, root + source.first);
    }
    Fd native = current_namespace_source(c.native_fd, c.native);
    Fd runtime = current_namespace_source(c.runtime_fd, c.start.files + "/runtime");
    ro_mount(native, root + c.native); ro_mount(runtime, root + c.start.files + "/runtime");
    for (const auto& workspace : c.workspaces) {
        Start selected = c.start; selected.cwd = workspace.first;
        Fd current = current_namespace_source(workspace.second, selected.source_workspace());
        mkdirs(root + workspace.first);
        check(mount(fd_path(current).c_str(), (root + workspace.first).c_str(), nullptr, MS_BIND | MS_REC, nullptr), "workspace_bind");
        struct mount_attr workspace_attr{}; workspace_attr.attr_set = MOUNT_ATTR_NOSUID | MOUNT_ATTR_NODEV;
        check(syscall(SYS_mount_setattr, AT_FDCWD, (root + workspace.first).c_str(), AT_RECURSIVE, &workspace_attr, sizeof(workspace_attr)), "workspace_mount_flags");
    }
    if (c.cert_fd >= 0) {
        Fd current = current_namespace_source(c.cert_fd, c.start.files + "/ca-certificates.crt", false);
        ro_mount(current, root + c.start.files + "/ca-certificates.crt", false);
    }
    // Android's primary-user module realpath resolves through /data/data even
    // when Java supplies /data/user/0. Alias only this app inside the new root;
    // the target still contains only the explicitly mounted subtrees above.
    if (c.start.host_uid / 100000 == 0) {
        mkdirs(root + "/data/data");
        std::string app = c.start.files.substr(0, c.start.files.size() - 6);
        check(symlink(app.c_str(), (root + "/data/data/" + c.start.package()).c_str()), "app_data_alias");
    }
    mkdirs(root + "/tmp"); check(mount("tmpfs", (root + "/tmp").c_str(), "tmpfs", MS_NOSUID | MS_NODEV, "mode=1777,size=256m"), "tmp_mount");
    mkdirs(root + "/home"); check(mount("tmpfs", (root + "/home").c_str(), "tmpfs", MS_NOSUID | MS_NODEV, "mode=0755,size=64m"), "home_mount");
    mkdirs(root + "/home/droy/.dsh"); check(chown((root + "/home/droy").c_str(), c.start.guest_uid, c.start.host_uid), "home_owner");
    check(chown((root + "/home/droy/.dsh").c_str(), c.start.guest_uid, c.start.host_uid), "dsh_home_owner");
    mkdirs(root + "/dev"); check(mount("tmpfs", (root + "/dev").c_str(), "tmpfs", MS_NOSUID | MS_NOEXEC, "mode=0755,size=1m"), "dev_mount");
    for (const auto& node : std::vector<std::pair<const char*, unsigned>>{{"null", 3}, {"zero", 5}, {"random", 8}, {"urandom", 9}}) {
        check(mknod((root + "/dev/" + node.first).c_str(), S_IFCHR | 0666, makedev(1, node.second)), "dev_node");
        check(chmod((root + "/dev/" + node.first).c_str(), 0666), "dev_permissions");
    }
    mkdirs(root + "/dev/pts");
    std::string options = "newinstance,ptmxmode=0666,mode=0620,gid=" + std::to_string(c.start.host_uid);
    check(mount("devpts", (root + "/dev/pts").c_str(), "devpts", MS_NOSUID | MS_NOEXEC, options.c_str()), "devpts_mount");
    check(symlink("pts/ptmx", (root + "/dev/ptmx").c_str()), "ptmx_link");
    mkdirs(root + "/proc");
    struct stat host_proc{}, private_proc{}; check(stat("/proc", &host_proc), "host_proc_stat");
    check(mount("proc", (root + "/proc").c_str(), "proc", MS_NOSUID | MS_NODEV | MS_NOEXEC | MS_RDONLY, "hidepid=2,gid=0,subset=pid"), "proc_mount");
    check(stat((root + "/proc").c_str(), &private_proc), "private_proc_stat");
    require(host_proc.st_dev != private_proc.st_dev, "proc_instance_shared");
    require(access((root + "/proc/sys").c_str(), F_OK) == -1 && errno == ENOENT, "proc_globals_visible");
    check(symlink("/proc/self/fd", (root + "/dev/fd").c_str()), "fd_link");
    mkdirs(root + "/usr/bin"); mkdirs(root + c.start.files + "/bin");
    for (const char* name : {"node", "bash", "rg"}) {
        std::string source = c.native + "/libdroy_" + name + ".so";
        check(symlink(source.c_str(), (root + "/usr/bin/" + name).c_str()), "bin_link");
        check(symlink(source.c_str(), (root + c.start.files + "/bin/" + name).c_str()), "app_bin_link");
    }
    check(symlink(c.start.workspace().c_str(), (root + "/workspace").c_str()), "workspace_link");
    mkdirs(root + "/.old-root"); check(chdir(root.c_str()), "enter_root");
    check(syscall(SYS_pivot_root, ".", ".old-root"), "pivot_root"); check(chdir("/"), "root_cwd");
    check(umount2("/.old-root", MNT_DETACH), "detach_old_root"); check(rmdir("/.old-root"), "remove_old_root");
    check(mount(nullptr, "/", nullptr, MS_REMOUNT | MS_RDONLY | MS_NOSUID | MS_NODEV, nullptr), "freeze_root");
}
Fd proxy_listener() {
    Fd config(socket(AF_INET, SOCK_DGRAM | SOCK_CLOEXEC, 0)); check(config, "loopback_socket");
    ifreq req{}; strcpy(req.ifr_name, "lo"); check(ioctl(config, SIOCGIFFLAGS, &req), "loopback_flags");
    req.ifr_flags |= IFF_UP; check(ioctl(config, SIOCSIFFLAGS, &req), "loopback_up");
    Fd server(socket(AF_INET, SOCK_STREAM | SOCK_CLOEXEC | SOCK_NONBLOCK, 0)); check(server, "proxy_socket");
    sockaddr_in address{}; address.sin_family = AF_INET; address.sin_addr.s_addr = htonl(INADDR_LOOPBACK); address.sin_port = htons(kProxyPort);
    check(bind(server, reinterpret_cast<sockaddr*>(&address), sizeof(address)), "proxy_bind"); check(listen(server, 16), "proxy_listen"); return server;
}
std::string json_error(const std::string& code) {
    // Error codes are constants plus decimal errno; never include untrusted request strings.
    require(code.find_first_not_of("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_:-") == std::string::npos, "invalid_error_code");
    return "{\"code\":\"" + code + "\"}";
}
int init_child(void* opaque) {
    auto& c = *static_cast<Context*>(opaque);
    try {
        check(prctl(PR_SET_PDEATHSIG, SIGKILL, 0, 0, 0), "init_parent_death");
        require(getppid() == c.supervisor_pid, "supervisor_exited_before_init");
        check(setsid(), "init_private_session");
        root_filesystem(c);
        auto current = namespaces(); for (size_t i = 0; i < current.size(); ++i) require(current[i] != c.original_ns[i], "namespace_not_isolated");
        Fd listener;
        if (c.start.proxy_port) { listener = proxy_listener(); send_packet(c.status, 1, {}, listener); listener.reset(); }
        Fd master, slave;
        if (c.start.pty()) {
            int m, s; winsize size{}; size.ws_col = c.start.cols; size.ws_row = c.start.rows;
            check(openpty(&m, &s, nullptr, nullptr, &size), "open_private_pty"); master.reset(m); slave.reset(s);
            send_packet(c.status, 2, {}, master);
        }
        // Close inherited KSU driver descriptors and all host directory handles before dropping UID.
        // Reserve standard descriptor numbers until the workload replaces them;
        // otherwise the exec-status pipe could occupy stdout and be overwritten.
        std::set<int> keep{0, 1, 2, c.status, c.in, c.out, c.err}; if (c.control >= 0) keep.insert(c.control);
        if (master >= 0) keep.insert(master); if (slave >= 0) keep.insert(slave);
        close_except(keep);
        rlimit processes{128, 128}; check(setrlimit(RLIMIT_NPROC, &processes), "process_limit");
        drop_privileges(c.start); syscall_filter(); umask(0007);
        require(getppid() == c.supervisor_pid, "supervisor_exited_during_init");
        require(access("/proc/1/status", F_OK) == -1 && errno == ENOENT, "host_process_visible");
        for (const char* private_child : {"profiles", "storages", "sessions", "config.json", "memory"})
            require(access((c.start.files + "/dsh-home/" + private_child).c_str(), F_OK) != 0 && errno == ENOENT, "private_root_visible");
        int exec_pipe[2]; check(pipe2(exec_pipe, O_CLOEXEC), "exec_status_pipe"); Fd exec_read(exec_pipe[0]), exec_write(exec_pipe[1]);
        pid_t init_pid = getpid(), child = fork(); check(child, "fork_workload");
        if (child == 0) {
            exec_read.reset();
            try {
                check(prctl(PR_SET_PDEATHSIG, SIGKILL, 0, 0, 0), "workload_parent_death");
                require(getppid() == init_pid, "init_exited_before_workload");
                if (c.start.pty()) {
                    check(setsid(), "workload_session"); check(ioctl(slave, TIOCSCTTY, 0), "controlling_terminal");
                    check(dup2(slave, 0), "stdin_dup"); check(dup2(slave, 1), "stdout_dup"); check(dup2(slave, 2), "stderr_dup");
                } else {
                    check(setpgid(0, 0), "workload_group"); check(dup2(c.in, 0), "stdin_dup"); check(dup2(c.out, 1), "stdout_dup"); check(dup2(c.err, 2), "stderr_dup");
                }
                if (c.start.control()) check(dup2(c.control, 7), "control_dup");
                std::set<int> descriptors{0, 1, 2, int(exec_write)}; if (c.start.control()) descriptors.insert(7);
                close_except(descriptors); check(chdir(c.start.cwd.c_str()), "workload_cwd");
                clearenv(); for (const auto& item : c.start.env) { size_t eq = item.find('='); check(setenv(item.substr(0, eq).c_str(), item.substr(eq + 1).c_str(), 1), "environment"); }
                check(setenv("HOME", "/home/droy", 1), "home_env"); check(setenv("TMPDIR", "/tmp", 1), "tmp_env");
                check(setenv("DSH_HOME", "/home/droy/.dsh", 1), "dsh_home_env");
                // The packaged Android Node uses this same installed CA bundle
                // in RuntimeService; accept only the file mounted above.
                if (c.cert_fd >= 0) check(setenv("NODE_EXTRA_CA_CERTS", (c.start.files + "/ca-certificates.crt").c_str(), 1), "certificate_env");
                else check(unsetenv("NODE_EXTRA_CA_CERTS"), "certificate_env_clear");
                check(setenv("DROY_EXEC_INIT_PID", std::to_string(getppid()).c_str(), 1), "init_identity_env");
                if (c.start.control()) check(setenv("DSH_SUBPROCESS_CONTROL", "pipe", 1), "control_env");
                else check(unsetenv("DSH_SUBPROCESS_CONTROL"), "control_env_clear");
                if (!getenv("PATH")) check(setenv("PATH", ("/usr/bin:" + c.native + ":/system/bin").c_str(), 1), "path_env");
                std::vector<char*> args; for (auto& arg : c.start.argv) args.push_back(arg.data()); args.push_back(nullptr);
                execvp(c.start.executable.c_str(), args.data()); system_fail("exec_failed");
            } catch (const std::exception& error) {
                std::string message = error.what(); (void)write(exec_write, message.data(), message.size()); _exit(126);
            }
        }
        exec_write.reset(); slave.reset(); close(c.in); close(c.out); close(c.err); if (c.control >= 0) close(c.control);
        close(0); close(1); close(2);
        std::array<char, 256> failure{}; ssize_t n;
        do { n = read(exec_read, failure.data(), failure.size()); } while (n < 0 && errno == EINTR);
        check(n, "exec_receipt"); if (n) throw Error(std::string(failure.data(), n)); exec_read.reset();
        std::string ready = "{\"verified\":true,\"workloadPid\":" + std::to_string(child) + ",\"uid\":" + std::to_string(c.start.guest_uid) + ",\"gid\":" + std::to_string(c.start.host_uid) +
            ",\"mountNamespace\":true,\"pidNamespace\":false,\"ipcNamespace\":false,\"ipcSyscallsDenied\":true,\"processIsolation\":\"dedicated-uid\",\"procIsolation\":\"private-hidepid-subset\",\"uidLease\":true,\"descendantsManaged\":true,\"processLimit\":128,\"netNamespace\":true,\"utsNamespace\":true,\"rootFilesystemPrivate\":true,\"capabilitiesEmpty\":true,\"noNewPrivs\":true,\"initDumpable\":false,\"inheritedHostFdsClosed\":true,\"seccomp\":true,\"guestProxyPort\":" + std::to_string(c.start.proxy_port ? kProxyPort : 0) + "}";
        send_packet(c.status, 3, ready);
        int main_status = 0; bool main_done = false;
        while (!main_done) {
            int status = 0; pid_t got = waitpid(-1, &status, WNOHANG);
            if (got == child) { main_status = status; main_done = true; break; }
            if (got < 0 && errno != EINTR) system_fail("wait_workload");
            pollfd p{c.status, POLLIN, 0}; check(poll(&p, 1, 25), "init_poll");
            if (p.revents & (POLLHUP | POLLERR | POLLNVAL)) break;
            if (!(p.revents & POLLIN)) continue;
            Packet input = packet(c.status); if (input.eof) break;
            if (input.type == CANCEL) break;
            if (input.type == RESIZE) {
                require(input.data.size() == 8 && master >= 0, "invalid_resize"); winsize size{};
                size.ws_col = read32(input.data.data()); size.ws_row = read32(input.data.data() + 4);
                require(size.ws_col && size.ws_col <= 1000 && size.ws_row && size.ws_row <= 1000, "invalid_resize");
                check(ioctl(master, TIOCSWINSZ, &size), "resize"); continue;
            }
            require(input.type == SIGNAL || input.type == INSPECT_FOREGROUND, "invalid_init_control");
            require(input.data.size() == (input.type == SIGNAL ? 8 : 4), "invalid_foreground_request");
            uint32_t request = read32(input.data.data()); pid_t group = master >= 0 ? tcgetpgrp(master) : -1;
            std::string prefix = "{\"requestId\":" + std::to_string(request);
            if (input.type == INSPECT_FOREGROUND) {
                send_packet(c.status, FOREGROUND, prefix + ",\"found\":" + (group > 1 ? "true,\"groupId\":" + std::to_string(group) : "false") + ",\"scope\":\"host_pid_dedicated_uid\"}");
            } else {
                uint32_t signal = read32(input.data.data() + 4);
                require(signal == SIGHUP || signal == SIGINT || signal == SIGQUIT || signal == SIGTERM || signal == SIGTSTP || signal == SIGCONT || signal == SIGWINCH, "invalid_signal");
                bool ok = group > 1 && kill(-group, signal) == 0;
                send_packet(c.status, SIGNAL_RESULT, prefix + ",\"ok\":" + (ok ? "true" : "false") + (group > 1 ? ",\"groupId\":" + std::to_string(group) : ",\"code\":\"foreground_unavailable\"") + "}");
            }
        }
        // The Root subreaper outside the sandbox closes every remaining process
        // using the leased UID and pidfds. Never use kill(-1) in the host PID space.
        int code = main_done && WIFEXITED(main_status) ? WEXITSTATUS(main_status) : 128 + (main_done && WIFSIGNALED(main_status) ? WTERMSIG(main_status) : SIGKILL);
        int signal = main_done && WIFSIGNALED(main_status) ? WTERMSIG(main_status) : (main_done ? 0 : SIGKILL);
        send_packet(c.status, 4, "{\"exitCode\":" + std::to_string(code) + ",\"signal\":" + std::to_string(signal) + "}");
        return 0;
    } catch (const std::exception& error) {
        try { send_packet(c.status, ERROR, json_error(error.what())); } catch (...) { }
        return 125;
    }
}
struct Queue {
    std::deque<Bytes> chunks; size_t at = 0, size = 0;
    bool empty() const { return chunks.empty(); }
    void add(Bytes bytes) { require(size + bytes.size() <= kBufferLimit, "stream_buffer_limit"); size += bytes.size(); if (!bytes.empty()) chunks.push_back(std::move(bytes)); }
    bool flush(int fd) {
        if (empty()) return true;
        const Bytes& head = chunks.front(); ssize_t n = write(fd, head.data() + at, head.size() - at);
        if (n < 0 && (errno == EAGAIN || errno == EINTR)) return true;
        if (n <= 0) return false;
        at += n; size -= n; if (at == head.size()) { chunks.pop_front(); at = 0; } return true;
    }
};
struct Stream {
    Fd fd; Queue pending; bool close_after = false; bool read_open = true; Type type = STDOUT;
    explicit Stream(Fd value) : fd(std::move(value)) {}
};
struct Relay { Fd guest, host; Queue to_guest, to_host; bool connecting = true, guest_read = true, host_read = true; };
void write_receipt(const Bytes& bytes) {
    size_t at = 0; uint64_t deadline = now_ms() + 2000;
    while (at < bytes.size() && now_ms() < deadline) {
        pollfd p{STDOUT_FILENO, POLLOUT, 0}; if (poll(&p, 1, 50) < 0 && errno != EINTR) break;
        ssize_t n = write(STDOUT_FILENO, bytes.data() + at, bytes.size() - at);
        if (n > 0) at += n; else if (n < 0 && errno != EAGAIN && errno != EINTR) break;
    }
}
Bytes first_frame(Type expected_type = START) {
    Bytes data; uint64_t deadline = now_ms() + 15000; size_t expected = 4;
    while (data.size() < expected) {
        require(now_ms() < deadline, "start_timeout"); pollfd p{0, POLLIN, 0}; check(poll(&p, 1, 50), "start_poll");
        if (!(p.revents & POLLIN)) { require(!(p.revents & (POLLHUP | POLLERR)), "host_disconnected"); continue; }
        uint8_t buffer[4096]; ssize_t n = read(0, buffer, std::min(sizeof(buffer), expected - data.size()));
        if (n < 0 && (errno == EINTR || errno == EAGAIN)) continue; require(n > 0, "host_disconnected"); data.insert(data.end(), buffer, buffer + n);
        if (data.size() == 4 && expected == 4) { uint32_t size = read32(data.data()); require(size >= 1 && size <= kMaxFrame, "invalid_frame_length"); expected = size + 4; }
    }
    require(data[4] == expected_type, "unexpected_first_frame"); return Bytes(data.begin() + 5, data.end());
}
int supervise(Context& c, int status, pid_t child, Stream& input, Stream& output, Stream& errors, Stream& control) {
    Fd listener, master; Queue receipts, before_ready; Bytes buffered; std::vector<Relay> relays;
    bool cancelled = false, timed_out = false, child_done = false, have_exit = false, ready = false, status_open = true, native_error = false;
    std::string exit_json; int child_status = 0; uint64_t start = c.started, done_at = 0;
    nonblock(0); nonblock(1); nonblock(status);
    for (int fd : {int(input.fd), int(output.fd), int(errors.fd), int(control.fd)}) if (fd >= 0) nonblock(fd);
    auto emit = [&](Type type, const std::string& data) { receipts.add(frame(type, data)); };
    auto terminate = [&] { if (!cancelled && !child_done) { cancelled = true; (void)kill(child, SIGKILL); } };
    try {
        for (;;) {
            uint64_t now = now_ms(); if (!child_done && now - start > c.start.timeout_ms) { timed_out = true; terminate(); }
            pid_t waited = waitpid(child, &child_status, WNOHANG);
            if (waited == child) { child_done = true; reap_uid(c.start.guest_uid); namespace_reaped = true; done_at = now_ms(); input.fd.reset(); }
            if (child_done && !status_open && output.fd < 0 && errors.fd < 0 && control.fd < 0 && master < 0) break;
            if (child_done && now - done_at > 2000) { output.fd.reset(); errors.fd.reset(); control.fd.reset(); master.reset(); break; }
            struct Watch { pollfd p; int kind; size_t index; };
            std::vector<Watch> watches;
            auto watch = [&](int fd, short events, int kind, size_t index = 0) { if (fd >= 0) watches.push_back({{fd, events, 0}, kind, index}); };
            watch(0, POLLIN, 0); if (!receipts.empty()) watch(1, POLLOUT, 1);
            if (status_open) watch(status, POLLIN, 2);
            if (input.fd >= 0 && !input.pending.empty()) watch(input.fd, POLLOUT, 3);
            if (output.fd >= 0) watch(output.fd, POLLIN, 4);
            if (errors.fd >= 0) watch(errors.fd, POLLIN, 5);
            if (control.fd >= 0) watch(control.fd, (control.read_open ? POLLIN : 0) | (!control.pending.empty() ? POLLOUT : 0), 6);
            if (master >= 0) watch(master, POLLIN | (!input.pending.empty() ? POLLOUT : 0), 7);
            if (listener >= 0 && !child_done && relays.size() < 16) watch(listener, POLLIN, 8);
            for (size_t i = 0; i < relays.size(); ++i) {
                auto& r = relays[i];
                watch(r.guest, (r.guest_read && r.to_host.size < kBufferLimit / 4 ? POLLIN : 0) | (!r.to_guest.empty() ? POLLOUT : 0), 9, i);
                watch(r.host, (r.host_read && r.to_guest.size < kBufferLimit / 4 && !r.connecting ? POLLIN : 0) | (r.connecting || !r.to_host.empty() ? POLLOUT : 0), 10, i);
            }
            std::vector<pollfd> polls; for (const auto& w : watches) polls.push_back(w.p);
            int polled = poll(polls.data(), polls.size(), 25); if (polled < 0 && errno == EINTR) continue; check(polled, "supervisor_poll");
            for (size_t i = 0; i < watches.size(); ++i) {
                short events = polls[i].revents; if (!events) continue; auto& w = watches[i];
                if (w.kind == 0) {
                    if (events & POLLIN) {
                        uint8_t bytes[65536]; ssize_t n = read(0, bytes, sizeof(bytes));
                        if (n > 0) buffered.insert(buffered.end(), bytes, bytes + n); else if (!n) terminate(); else if (errno != EAGAIN && errno != EINTR) system_fail("host_read");
                        require(buffered.size() <= kMaxFrame + 65536, "input_buffer_limit");
                        while (buffered.size() >= 4) {
                            uint32_t length = read32(buffered.data()); require(length && length <= kMaxFrame, "invalid_frame_length"); if (buffered.size() < length + 4) break;
                            auto type = Type(buffered[4]); Bytes body(buffered.begin() + 5, buffered.begin() + 4 + length); buffered.erase(buffered.begin(), buffered.begin() + 4 + length);
                            if (type == CANCEL) { require(body.empty(), "invalid_cancel"); terminate(); }
                            else if (type == STDIN) { require(!input.close_after && !child_done, "stdin_closed"); input.pending.add(std::move(body)); }
                            else if (type == STDIN_EOF) { require(body.empty(), "invalid_eof"); input.close_after = true; if (c.start.pty()) input.pending.add(Bytes{4}); }
                            else if (type == CONTROL) { require(c.start.control() && !control.close_after && !child_done, "control_closed"); control.pending.add(std::move(body)); }
                            else if (type == CONTROL_EOF) { require(body.empty() && c.start.control(), "invalid_control_eof"); control.close_after = true; }
                            else if (type == RESIZE || type == SIGNAL || type == INSPECT_FOREGROUND) {
                                require(ready && !child_done, "workload_not_ready"); send_packet(status, type, std::string(body.begin(), body.end()));
                            } else throw Error("unknown_input_frame");
                        }
                    }
                    if (events & (POLLHUP | POLLERR | POLLNVAL)) terminate();
                } else if (w.kind == 1) { require(receipts.flush(1), "host_output_closed"); }
                else if (w.kind == 2) {
                    if (events & (POLLIN | POLLHUP)) {
                        Packet response = packet(status);
                        if (response.eof) status_open = false;
                        else {
                            std::string data(response.data.begin(), response.data.end());
                            if (response.type == 1) { require(c.start.proxy_port && response.fd >= 0 && listener < 0, "unexpected_proxy"); listener = std::move(response.fd); nonblock(listener); }
                            else if (response.type == 2) { require(c.start.pty() && response.fd >= 0 && master < 0, "unexpected_pty"); master = std::move(response.fd); nonblock(master); input.fd.reset(); output.fd.reset(); errors.fd.reset(); }
                            else if (response.type == 3) {
                                require(!ready && !data.empty() && data[0] == '{', "unexpected_ready"); ready = true;
                                data.insert(1, "\"pid\":" + std::to_string(child) + ",\"topLevelPid\":" + std::to_string(child) + ","); emit(READY, data);
                                while (!before_ready.chunks.empty()) { receipts.add(std::move(before_ready.chunks.front())); before_ready.chunks.pop_front(); }
                                before_ready.size = 0;
                            }
                            else if (response.type == 4) { have_exit = true; exit_json = data; }
                            else if (response.type == ERROR || response.type == FOREGROUND || response.type == SIGNAL_RESULT) {
                                if (response.type == ERROR) native_error = true;
                                emit(Type(response.type), data);
                            }
                            else throw Error("unknown_status_frame");
                        }
                    }
                } else if (w.kind >= 3 && w.kind <= 7) {
                    Stream* stream = w.kind == 3 ? &input : w.kind == 4 ? &output : w.kind == 5 ? &errors : w.kind == 6 ? &control : nullptr;
                    int fd = w.kind == 7 ? int(master) : int(stream->fd);
                    if (events & POLLOUT) {
                        Queue& queue = w.kind == 7 ? input.pending : stream->pending;
                        if (!queue.flush(fd)) { if (w.kind == 7) master.reset(); else stream->fd.reset(); }
                    }
                    if ((events & (POLLIN | POLLHUP | POLLERR)) && w.kind != 3 && fd >= 0) {
                        uint8_t bytes[65536]; ssize_t n = read(fd, bytes, sizeof(bytes));
                        if (n > 0) (ready ? receipts : before_ready).add(frame(w.kind == 5 ? STDERR : w.kind == 6 ? CONTROL_OUT : STDOUT, Bytes(bytes, bytes + n)));
                        else if (!n || (n < 0 && errno == EIO && w.kind == 7)) { if (w.kind == 7) master.reset(); else stream->fd.reset(); }
                        else if (errno != EAGAIN && errno != EINTR) { if (w.kind == 7) master.reset(); else stream->fd.reset(); }
                    }
                } else if (w.kind == 8) {
                    Fd guest(accept4(listener, nullptr, nullptr, SOCK_NONBLOCK | SOCK_CLOEXEC));
                    if (guest < 0) { if (errno == EAGAIN || errno == EINTR) continue; system_fail("proxy_accept"); }
                    Relay r; r.guest = std::move(guest); r.host.reset(socket(AF_INET, SOCK_STREAM | SOCK_NONBLOCK | SOCK_CLOEXEC, 0)); check(r.host, "proxy_host_socket");
                    sockaddr_in address{}; address.sin_family = AF_INET; address.sin_addr.s_addr = htonl(INADDR_LOOPBACK); address.sin_port = htons(c.start.proxy_port);
                    int connected = connect(r.host, reinterpret_cast<sockaddr*>(&address), sizeof(address));
                    if (connected < 0 && errno != EINPROGRESS) continue; r.connecting = connected != 0; relays.push_back(std::move(r));
                } else {
                    auto& r = relays[w.index]; bool guest = w.kind == 9; Fd& fd = guest ? r.guest : r.host;
                    Queue& outgoing = guest ? r.to_guest : r.to_host; Queue& incoming = guest ? r.to_host : r.to_guest;
                    bool& read_open = guest ? r.guest_read : r.host_read;
                    if (!guest && r.connecting && (events & POLLOUT)) {
                        int error = 0; socklen_t size = sizeof(error); if (getsockopt(fd, SOL_SOCKET, SO_ERROR, &error, &size) < 0 || error) { r.guest.reset(); r.host.reset(); continue; } r.connecting = false;
                    }
                    if (events & POLLOUT) { if (!outgoing.flush(fd)) { r.guest.reset(); r.host.reset(); continue; } }
                    if (read_open && (events & (POLLIN | POLLHUP | POLLERR))) {
                        uint8_t bytes[65536]; ssize_t n = read(fd, bytes, sizeof(bytes));
                        if (n > 0) incoming.add(Bytes(bytes, bytes + n));
                        else if (!n) read_open = false;
                        else if (errno != EINTR && errno != EAGAIN) { r.guest.reset(); r.host.reset(); }
                    }
                }
            }
            if (input.close_after && input.pending.empty() && !c.start.pty()) input.fd.reset();
            if (control.close_after && control.pending.empty() && control.fd >= 0) shutdown(control.fd, SHUT_WR);
            for (auto& r : relays) {
                if (!r.guest_read && r.to_host.empty() && r.host >= 0) shutdown(r.host, SHUT_WR);
                if (!r.host_read && r.to_guest.empty() && r.guest >= 0) shutdown(r.guest, SHUT_WR);
                if ((!r.host_read && !r.guest_read && r.to_host.empty() && r.to_guest.empty()) || child_done) { r.host.reset(); r.guest.reset(); }
            }
            relays.erase(std::remove_if(relays.begin(), relays.end(), [](const Relay& r) { return r.host < 0 || r.guest < 0; }), relays.end());
        }
        if (!child_done) { while (waitpid(child, &child_status, 0) < 0 && errno == EINTR) { } child_done = true; reap_uid(c.start.guest_uid); namespace_reaped = true; }
        require(have_exit || cancelled || native_error || !ready, "missing_exit_receipt");
        if (!have_exit) exit_json = "{\"exitCode\":" + std::to_string(WIFEXITED(child_status) ? WEXITSTATUS(child_status) : 128 + WTERMSIG(child_status)) + ",\"signal\":" + std::to_string(WIFSIGNALED(child_status) ? WTERMSIG(child_status) : 0) + "}";
        require(!exit_json.empty() && exit_json[0] == '{', "invalid_exit_receipt");
        exit_json.insert(1, std::string("\"namespaceCreated\":true,\"descendantsReaped\":true,\"cancelled\":") + (cancelled ? "true" : "false") + ",\"timedOut\":" + (timed_out ? "true" : "false") + ","); emit(EXIT, exit_json);
        uint64_t deadline = now_ms() + 2000;
        while (!receipts.empty() && now_ms() < deadline) { pollfd p{1, POLLOUT, 0}; (void)poll(&p, 1, 25); if (!receipts.flush(1)) break; }
        return ready ? 0 : 125;
    } catch (...) {
        if (!child_done) { (void)kill(child, SIGKILL); while (waitpid(child, nullptr, 0) < 0 && errno == EINTR) { } }
        reap_uid(c.start.guest_uid); namespace_reaped = true;
        throw;
    }
}
std::string installed_native_path() {
    std::string self = link_at(AT_FDCWD, "/proc/self/exe");
    require(self.size() > 17 && self.substr(self.find_last_of('/') + 1) == "libdroy_exec.so" && under(self, "/data/app"), "launcher_not_apk_installed");
    std::string native_path = self.substr(0, self.find_last_of('/')); Fd native = directory(native_path);
    struct stat native_stat{}; check(fstat(native, &native_stat), "native_directory_owner");
    require((native_stat.st_uid == 0 || native_stat.st_uid == 1000) && !(native_stat.st_mode & 0022), "native_directory_writable");
    return native_path;
}
int run() {
    require(getuid() == 0 && geteuid() == 0, "root_required");
    std::string native_path = installed_native_path(); Fd native = directory(native_path);
    Start start = Start::parse(first_frame());
    uint64_t started = now_ms(), deadline = started + start.timeout_ms;
    auto original_ns = namespaces();
    Fd files = directory(start.files); owned_by(files, start.host_uid);
    UidLease lease = acquire_uid(start, files); start.guest_uid = lease.uid;
    // Preparation can fail before clone (including host cancellation). Keep
    // lease cleanup outside that entire lifetime, while retaining the record
    // if a created process tree has not been confirmed reaped.
    auto run_task = [&]() {
        check(prctl(PR_SET_CHILD_SUBREAPER, 1, 0, 0, 0), "supervisor_subreaper");
        Fd runtime = child_directory(files, "runtime", false); owned_by(runtime, start.host_uid);
        Start startup = start, default_space = start;
        startup.cwd = start.files + "/workspace"; default_space.cwd = start.files + "/dsh-home/workspace";
        Fd startup_work = workspace(startup, files, deadline), default_work = workspace(default_space, files, deadline);
        TreeBudget workspace_budget; workspace_budget.deadline = deadline;
        prepare_tree(startup_work, start.host_uid, true, workspace_budget); prepare_tree(default_work, start.host_uid, true, workspace_budget);
        TreeBudget runtime_budget; runtime_budget.deadline = deadline; prepare_tree(runtime, start.host_uid, false, runtime_budget);
        Fd app = directory(start.files.substr(0, start.files.size() - 6)); Fd cache = child_directory(app, "cache", false);
        std::string root_name = "droy-exec-" + std::to_string(getpid()) + "-" + std::to_string(now_ms());
        check(mkdirat(cache, root_name.c_str(), 0700), "sandbox_root_create"); Fd root_fd = child_directory(cache, root_name, false);
        std::string root_path = canonical(start.files.substr(0, start.files.size() - 6) + "/cache/" + root_name);
        std::vector<Fd> system_fds; Context context; context.started = started; context.start = std::move(start); context.native = native_path; context.root_path = root_path;
        context.root_fd = root_fd; context.runtime_fd = runtime; context.native_fd = native; context.original_ns = original_ns; context.supervisor_pid = getpid();
        context.workspaces = {{context.start.files + "/workspace", int(startup_work)}, {context.start.files + "/dsh-home/workspace", int(default_work)}};
        for (const char* path : {"/system", "/apex", "/vendor", "/product", "/system_ext", "/linkerconfig"}) {
            if (access(path, F_OK) < 0 && errno == ENOENT) continue;
            system_fds.push_back(directory(path)); context.system.emplace_back(path, int(system_fds.back()));
        }
        Fd cert(openat(files, "ca-certificates.crt", O_RDONLY | O_CLOEXEC | O_NOFOLLOW));
        if (cert >= 0) {
            struct stat st{}; check(fstat(cert, &st), "certificate_stat"); require(S_ISREG(st.st_mode) && st.st_nlink == 1, "invalid_certificate_file");
            prepare_permissions(cert, st, context.start.host_uid, (st.st_mode & 0777) | 0040); context.cert_fd = cert;
        } else if (errno != ENOENT) system_fail("certificate_open");
        int si[2], so[2], se[2], sc[2], ss[2];
        check(pipe2(si, O_CLOEXEC), "stdin_pipe"); check(pipe2(so, O_CLOEXEC), "stdout_pipe"); check(pipe2(se, O_CLOEXEC), "stderr_pipe");
        check(socketpair(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0, sc), "control_socket");
        check(socketpair(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC, 0, ss), "status_socket");
        Stream input{Fd(si[1])}, output{Fd(so[0])}, errors{Fd(se[0])}, control{Fd(sc[0])};
        Fd child_in(si[0]), child_out(so[1]), child_err(se[1]), child_control(sc[1]), parent_status(ss[0]), child_status(ss[1]);
        context.in = child_in; context.out = child_out; context.err = child_err; context.control = context.start.control() ? int(child_control) : -1; context.status = child_status;
        std::vector<uint8_t> stack(1024 * 1024);
        pid_t child = clone(init_child, stack.data() + stack.size(), CLONE_NEWNS | CLONE_NEWNET | CLONE_NEWUTS | SIGCHLD, &context);
        if (child < 0) { int saved = errno; unlinkat(cache, root_name.c_str(), AT_REMOVEDIR); errno = saved; system_fail("namespace_clone"); }
        namespace_created = true;
        child_in.reset(); child_out.reset(); child_err.reset(); child_control.reset(); child_status.reset();
        if (!context.start.control()) control.fd.reset();
        int result;
        try { result = supervise(context, parent_status, child, input, output, errors, control); }
        catch (...) {
            unlinkat(cache, root_name.c_str(), AT_REMOVEDIR); throw;
        }
        check(unlinkat(cache, root_name.c_str(), AT_REMOVEDIR), "sandbox_root_remove"); return result;
    };
    int result;
    try { result = run_task(); }
    catch (...) {
        if (!namespace_created || namespace_reaped) release_uid_lease(lease);
        throw;
    }
    release_uid_lease(lease);
    return result;
}
std::string read_small(const std::string& path, size_t limit = 8192) {
    Fd fd(open(path.c_str(), O_RDONLY | O_CLOEXEC | O_NOFOLLOW)); if (fd < 0) system_fail("process_read");
    std::string output; char buffer[2048];
    for (;;) {
        ssize_t count = read(fd, buffer, sizeof(buffer)); if (count < 0 && errno == EINTR) continue; check(count, "process_read"); if (!count) break;
        require(output.size() + size_t(count) <= limit, "process_metadata_limit"); output.append(buffer, count);
    }
    return output;
}
uint64_t start_ticks(pid_t pid) {
    std::string stat = read_small("/proc/" + std::to_string(pid) + "/stat"); size_t end = stat.rfind(')');
    require(end != std::string::npos && end + 2 < stat.size(), "invalid_process_stat");
    size_t at = end + 2;
    for (int field = 3; field < 22; ++field) { at = stat.find(' ', at); require(at != std::string::npos, "invalid_process_stat"); ++at; }
    size_t stop = stat.find(' ', at); std::string value = stat.substr(at, stop - at);
    require(!value.empty() && value.find_first_not_of("0123456789") == std::string::npos, "invalid_process_start");
    return std::stoull(value);
}
uid_t process_uid(pid_t pid) {
    std::string status = read_small("/proc/" + std::to_string(pid) + "/status"); size_t at = status.find("\nUid:");
    require(at != std::string::npos, "process_uid_missing"); unsigned r, e, s, f;
    require(sscanf(status.c_str() + at, "\nUid:\t%u\t%u\t%u\t%u", &r, &e, &s, &f) == 4, "process_uid_invalid");
    return r;
}
std::string validate_main(uid_t uid, pid_t pid, uint64_t ticks) {
    require(process_uid(pid) == uid && start_ticks(pid) == ticks, "main_process_changed");
    std::string executable = link_at(AT_FDCWD, ("/proc/" + std::to_string(pid) + "/exe").c_str());
    auto name = executable.substr(executable.find_last_of('/') + 1);
    require(name == "app_process64" || name == "app_process", "main_not_android_java");
    std::string command = read_small("/proc/" + std::to_string(pid) + "/cmdline", 4096);
    std::string package = command.substr(0, command.find('\0'));
    require(supported_package(package), "main_package_unsupported");
    require(process_uid(pid) == uid && start_ticks(pid) == ticks, "main_process_changed");
    return package;
}
void validate_cleanup_transport(uid_t uid, pid_t main_pid, pid_t client_pid, uint64_t client_ticks) {
    require(client_pid > 1 && client_pid != main_pid && start_ticks(client_pid) == client_ticks, "cleanup_transport_changed");
    uid_t client_uid = process_uid(client_pid);
    require(client_uid == uid || client_uid == 0, "cleanup_transport_uid");
    std::string status = read_small("/proc/" + std::to_string(client_pid) + "/status");
    size_t at = status.find("\nPPid:"); unsigned parent = 0;
    require(at != std::string::npos && sscanf(status.c_str() + at, "\nPPid:\t%u", &parent) == 1 && parent == unsigned(main_pid), "cleanup_transport_parent");
    std::string executable = link_at(AT_FDCWD, ("/proc/" + std::to_string(client_pid) + "/exe").c_str());
    auto name = executable.substr(executable.find_last_of('/') + 1);
    require((client_pid == getpid() && executable == link_at(AT_FDCWD, "/proc/self/exe")) ||
        name == "su" || name == "magisk" || name == "magisk64" || name == "magisk32" || name == "ksud", "cleanup_transport_executable");
    struct stat executable_stat{}, host_pipe{}, client_pipe{};
    check(stat(executable.c_str(), &executable_stat), "cleanup_transport_executable_stat");
    require(S_ISREG(executable_stat.st_mode) && (executable_stat.st_uid == 0 || executable_stat.st_uid == 1000) && !(executable_stat.st_mode & 0022), "cleanup_transport_executable_owner");
    check(fstat(0, &host_pipe), "cleanup_transport_pipe");
    check(stat(("/proc/" + std::to_string(client_pid) + "/fd/0").c_str(), &client_pipe), "cleanup_transport_pipe");
    require(S_ISFIFO(host_pipe.st_mode) && S_ISFIFO(client_pipe.st_mode) && host_pipe.st_ino == client_pipe.st_ino && host_pipe.st_dev == client_pipe.st_dev, "cleanup_transport_pipe_mismatch");
}
int cleanup() {
    require(getuid() == 0 && geteuid() == 0, "root_required"); (void)installed_native_path();
    Bytes bytes = first_frame(CLEANUP); Reader input{bytes}; require(input.number() == 1, "unsupported_protocol");
    uid_t uid = input.number(); pid_t main_pid = input.number(); std::string ticks_text = input.text(20);
    pid_t client_pid = input.number(); std::string client_ticks_text = input.text(20); input.end();
    require(uid % 100000 >= 10000 && uid % 100000 <= 19999 && main_pid > 1 && !ticks_text.empty() && ticks_text.find_first_not_of("0123456789") == std::string::npos, "invalid_cleanup_identity");
    uint64_t ticks = std::stoull(ticks_text); require(ticks != 0, "invalid_cleanup_start");
    require(!client_ticks_text.empty() && client_ticks_text.find_first_not_of("0123456789") == std::string::npos, "invalid_cleanup_transport_start");
    uint64_t client_ticks = std::stoull(client_ticks_text); require(client_ticks != 0, "invalid_cleanup_transport_start");
    std::string package = validate_main(uid, main_pid, ticks);
    Fd files = directory(app_files_root(uid, package)); owned_by(files, uid);
    validate_cleanup_transport(uid, main_pid, client_pid, client_ticks);
    Fd proc = directory("/proc"); size_t terminated = 0; uint64_t deadline = now_ms() + 5000;
    for (;;) {
        validate_main(uid, main_pid, ticks); validate_cleanup_transport(uid, main_pid, client_pid, client_ticks);
        pollfd host{0, 0, 0}; check(poll(&host, 1, 0), "cleanup_host_check");
        require(!(host.revents & (POLLHUP | POLLERR | POLLNVAL)), "host_disconnected");
        size_t found = 0;
        entries(proc, [&](const std::string& name) {
            if (name.empty() || name.find_first_not_of("0123456789") != std::string::npos) return;
            long value = strtol(name.c_str(), nullptr, 10); if (value <= 1 || value > 2147483647 || value == main_pid || value == client_pid) return;
            pid_t pid = value;
            try {
                if (process_uid(pid) != uid) return;
                uint64_t pid_ticks = start_ticks(pid);
                Fd handle(syscall(SYS_pidfd_open, pid, 0));
                if (handle < 0 && errno == ESRCH) return; check(handle, "cleanup_pidfd");
                // Re-check the live UID after pidfd_open, never signal by a reusable PID.
                if (start_ticks(pid) != pid_ticks || process_uid(pid) != uid) return;
                ++found; require(now_ms() < deadline, "cleanup_timeout");
                validate_main(uid, main_pid, ticks);
                int result = syscall(SYS_pidfd_send_signal, int(handle), SIGKILL, nullptr, 0);
                if (result < 0 && errno == ESRCH) return; check(result, "cleanup_signal"); ++terminated;
            } catch (const Error& failure) {
                std::string code = failure.what();
                if (code == "process_read:2" || code == "process_read:3") return;
                throw;
            }
        });
        if (!found) break;
        require(now_ms() < deadline, "cleanup_timeout"); pollfd wait{0, 0, 0}; (void)poll(&wait, 1, 10);
    }
    validate_main(uid, main_pid, ticks); validate_cleanup_transport(uid, main_pid, client_pid, client_ticks); nonblock(1);
    std::string result = "{\"ok\":true,\"appUid\":" + std::to_string(uid) + ",\"mainPid\":" + std::to_string(main_pid) +
        ",\"mainStartTime\":\"" + ticks_text + "\",\"remainingAppUidProcesses\":0,\"remainingNonJava\":0,\"preservedOnlyMainJava\":true,\"terminated\":" + std::to_string(terminated) +
        ",\"preservedTransportPid\":" + std::to_string(client_pid) + ",\"preservedTransportStartTime\":\"" + client_ticks_text +
        "\",\"rootProcessCoverage\":\"host-registry-required\"}";
    write_receipt(frame(CLEANUP_RESULT, result));
    write_receipt(frame(EXIT, "{\"exitCode\":0,\"signal\":0,\"cancelled\":false,\"timedOut\":false,\"descendantsReaped\":true,\"namespaceCreated\":false}"));
    return 0;
}
int guest_check() {
    try {
        uid_t guest_uid = getuid();
        require(guest_uid % 100000 >= 80000 && guest_uid % 100000 < 80008 && geteuid() == guest_uid && prctl(PR_GET_NO_NEW_PRIVS, 0, 0, 0, 0) == 1 &&
                prctl(PR_GET_SECCOMP, 0, 0, 0, 0) == SECCOMP_MODE_FILTER, "guest_check_requires_sandbox");
        require(setuid(0) == -1 && getuid() == guest_uid && geteuid() == guest_uid, "setuid_escape");
        __user_cap_header_struct header{_LINUX_CAPABILITY_VERSION_3, 0}; __user_cap_data_struct caps[2]{};
        check(syscall(SYS_capget, &header, caps), "check_capabilities");
        for (const auto& cap : caps) require(!cap.effective && !cap.permitted && !cap.inheritable, "guest_capabilities");
        const char* init_text = getenv("DROY_EXEC_INIT_PID");
        require(init_text && *init_text && std::string(init_text).find_first_not_of("0123456789") == std::string::npos, "init_identity_missing");
        Fd init_fds(open(("/proc/" + std::string(init_text) + "/fd").c_str(), O_RDONLY | O_DIRECTORY | O_CLOEXEC));
        require(init_fds < 0 && (errno == ENOENT || errno == EACCES || errno == EPERM), "init_descriptors_exposed");
        Fd init_mem(open(("/proc/" + std::string(init_text) + "/mem").c_str(), O_RDONLY | O_CLOEXEC));
        require(init_mem < 0 && (errno == ENOENT || errno == EACCES || errno == EPERM), "init_memory_exposed");
        errno = 0; long result = syscall(SYS_add_key, "user", "droy-execution-synthetic", "x", size_t(1), -4);
        require(result == -1 && errno == EPERM, "user_keyring_add_allowed");
        errno = 0; result = syscall(SYS_request_key, "user", "droy-execution-synthetic", nullptr, -4);
        require(result == -1 && errno == EPERM, "user_keyring_request_allowed");
        errno = 0; result = syscall(SYS_keyctl, 10 /* KEYCTL_SEARCH */, -4, "user", "droy-execution-synthetic", 0);
        require(result == -1 && errno == EPERM, "user_keyring_search_allowed");
        errno = 0; Fd vsock(socket(40 /* AF_VSOCK */, SOCK_STREAM | SOCK_CLOEXEC, 0));
        require(vsock < 0 && errno == EPERM, "vsock_allowed");
        errno = 0; result = syscall(SYS_io_uring_setup, 1u, nullptr);
        require(result == -1 && errno == EPERM, "io_uring_allowed");
        // KernelSU's published reboot hook only installs a driver FD; these magic
        // values are not Linux reboot magic and cannot request a device reboot.
        int driver = -1; errno = 0;
        result = syscall(SYS_reboot, uint32_t(0xdeadbeef), uint32_t(0xcafebabe), 0, &driver);
        if (driver >= 0) { close(driver); throw Error("root_driver_installed"); }
        require(result == -1 && errno == EPERM, "root_driver_syscall_allowed");
        // Older KernelSU versions used a prctl command. Any escalation must stop
        // this synthetic helper immediately, before any further filesystem access.
        unsigned long legacy_result = 0;
        (void)prctl(0xdeadbeef, 0, 0, 0, &legacy_result);
        if (getuid() != guest_uid || geteuid() != guest_uid) {
            const char* failure = "{\"ok\":false,\"code\":\"legacy_root_reacquired\"}\n";
            (void)write(1, failure, strlen(failure)); _exit(126);
        }
        Fd self_fds = directory("/proc/self/fd");
        entries(self_fds, [&](const std::string& name) {
            auto target = link_at(self_fds, name.c_str());
            require(target.find("ksu_driver") == std::string::npos, "root_driver_inherited");
        });
        for (const char* path : {"/dev/binder", "/dev/hwbinder", "/dev/vndbinder", "/data/adb", "/proc/sys", "/proc/net", "/proc/driver", "/proc/1/status"}) {
            require(access(path, F_OK) == -1 && errno == ENOENT, "host_service_path_exposed");
        }
        printf("{\"ok\":true,\"uid\":%u,\"capabilitiesEmpty\":true,\"noNewPrivs\":true,\"initDescriptorsDenied\":true,\"initMemoryDenied\":true,\"keyringsDenied\":true,\"vsockDenied\":true,\"ioUringDenied\":true,\"rootDriverDenied\":true,\"rootReacquisitionDenied\":true,\"hostServicePathsHidden\":true,\"procSubsetPid\":true}\n", guest_uid);
        return 0;
    } catch (const std::exception& error) {
        try { printf("{\"ok\":false,\"code\":\"%s\"}\n", error.what()); } catch (...) { }
        return 126;
    }
}
}
int main(int argc, char** argv) {
    signal(SIGPIPE, SIG_IGN);
    signal(SIGCHLD, SIG_DFL);
    sigset_t unblocked; sigemptyset(&unblocked); (void)sigprocmask(SIG_SETMASK, &unblocked, nullptr);
    if (argc == 2 && !strcmp(argv[1], "--guest-check")) return guest_check();
    try {
        if (argc == 2 && !strcmp(argv[1], "--cleanup")) return cleanup();
        require(argc == 1, "no_command_line_arguments"); return run();
    }
    catch (const std::exception& error) {
        try {
            nonblock(1); write_receipt(frame(ERROR, json_error(error.what())));
            if (!namespace_created || namespace_reaped) write_receipt(frame(EXIT,
                std::string("{\"exitCode\":125,\"signal\":0,\"cancelled\":false,\"timedOut\":false,\"descendantsReaped\":true,\"namespaceCreated\":") + (namespace_created ? "true}" : "false}")));
        } catch (...) { }
        return 125;
    }
}
