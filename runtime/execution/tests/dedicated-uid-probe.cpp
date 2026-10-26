// Bounded synthetic device probe. This is not an activation certificate.
#define main droy_launcher_main
#include "../launcher.cpp"
#undef main
#include <pwd.h>

namespace {
bool uid_live(uid_t uid) {
    Fd proc = directory("/proc"); bool found = false;
    entries(proc, [&](const std::string& name) {
        if (name.empty() || name.find_first_not_of("0123456789") != std::string::npos) return;
        try { if (process_uid(std::stol(name)) == uid) found = true; }
        catch (const Error& e) { std::string code = e.what(); if (code != "process_read:2" && code != "process_read:3") throw; }
    });
    return found;
}
void probe_drop(uid_t uid) {
    check(setgroups(0, nullptr), "probe_groups");
    for (int cap = 0; cap < 64; ++cap) if (prctl(PR_CAPBSET_DROP, cap, 0, 0, 0) < 0 && errno != EINVAL) system_fail("probe_capbound");
    check(setresgid(uid, uid, uid), "probe_gid"); check(setresuid(uid, uid, uid), "probe_uid");
    __user_cap_header_struct header{_LINUX_CAPABILITY_VERSION_3, 0}; __user_cap_data_struct caps[2]{};
    check(syscall(SYS_capset, &header, caps), "probe_caps"); check(prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0), "probe_nnp");
    syscall_filter();
}
void blocked(const std::string& path) {
    Fd fd(open(path.c_str(), O_RDONLY | O_CLOEXEC)); require(fd < 0 && (errno == ENOENT || errno == EACCES || errno == EPERM), "probe_cross_process_open");
}
int namespace_probe(const std::string& root, pid_t host_pid) {
    check(prctl(PR_SET_PDEATHSIG, SIGKILL, 0, 0, 0), "probe_parent"); alarm(15);
    check(unshare(CLONE_NEWNS | CLONE_NEWNET | CLONE_NEWUTS), "probe_namespaces");
    check(mount(nullptr, "/", nullptr, MS_REC | MS_PRIVATE, nullptr), "probe_private_mounts");
    std::string tmp = root + "/tmpfs"; check(mkdir(tmp.c_str(), 0755), "probe_tmpfs_dir");
    Fd tmp_fd = directory(tmp);
    int tmp_result = mount("tmpfs", fd_path(tmp_fd).c_str(), "tmpfs", MS_NOSUID | MS_NODEV, "mode=0755,size=64m");
    dprintf(2, "tmpfs_fd_result:%d_errno:%d\n", tmp_result, tmp_result < 0 ? errno : 0);
    if (tmp_result < 0) {
        tmp_result = mount("tmpfs", tmp.c_str(), "tmpfs", MS_NOSUID | MS_NODEV, "mode=0755,size=64m");
        dprintf(2, "tmpfs_path_result:%d_errno:%d\n", tmp_result, tmp_result < 0 ? errno : 0);
    }
    check(tmp_result, "probe_tmpfs_mount"); check(umount2(tmp.c_str(), 0), "probe_tmpfs_unmount"); check(rmdir(tmp.c_str()), "probe_tmpfs_remove");
    std::string proc = root + "/proc";
    check(mount("proc", proc.c_str(), "proc", MS_RDONLY | MS_NOSUID | MS_NODEV | MS_NOEXEC, "hidepid=2,gid=0,subset=pid"), "probe_proc_mount");
    struct stat original{}, isolated{}; check(stat("/proc", &original), "probe_host_stat"); check(stat(proc.c_str(), &isolated), "probe_private_stat");
    require(original.st_dev != isolated.st_dev, "probe_proc_shared_superblock");
    require(access((proc + "/sys").c_str(), F_OK) == -1 && errno == ENOENT, "probe_proc_globals_visible");
    check(prctl(PR_SET_CHILD_SUBREAPER, 1, 0, 0, 0), "probe_subreaper");
    pid_t sibling = fork(); check(sibling, "probe_sibling");
    if (!sibling) { try { check(setsid(), "probe_sibling_session"); probe_drop(80001); alarm(10); pause(); _exit(0); } catch (...) { _exit(125); } }
    pid_t child = fork(); check(child, "probe_child");
    if (!child) {
        try {
            check(setsid(), "probe_child_session"); probe_drop(80000); alarm(8);
            Fd own(open((proc + "/self/status").c_str(), O_RDONLY | O_CLOEXEC)); check(own, "probe_own_proc");
            for (pid_t other : {host_pid, getppid(), sibling})
                for (const char* name : {"cmdline", "environ", "fd/0", "mem"}) blocked(proc + "/" + std::to_string(other) + "/" + name);
            require(kill(host_pid, 0) == -1 && errno == EPERM, "probe_host_signal_allowed");
            require(kill(sibling, 0) == -1 && errno == EPERM, "probe_sibling_signal_allowed");
            require(getsid(0) != getsid(host_pid) && getsid(0) != getsid(sibling), "probe_shared_session");
            require(kill(host_pid, SIGCONT) == -1 && errno == EPERM, "probe_host_sigcont_allowed");
            require(kill(sibling, SIGCONT) == -1 && errno == EPERM, "probe_sibling_sigcont_allowed");
            require(setuid(0) == -1 && getuid() == 80000, "probe_setuid_root");
            int driver = -1; errno = 0;
            require(syscall(SYS_reboot, 0xDEADBEEF, 0xCAFEBABE, 0, &driver) == -1 && errno == EPERM && driver == -1, "probe_ksu_driver");
            (void)prctl(0xdeadbeef, 0, 0, 0, 0); require(getuid() == 80000 && geteuid() == 80000, "probe_ksu_root");
            rlimit limit{8, 8}; check(setrlimit(RLIMIT_NPROC, &limit), "probe_nproc_limit");
            std::vector<pid_t> spawned; bool limited = false;
            for (int i = 0; i < 16; ++i) {
                pid_t worker = fork();
                if (worker < 0) { require(errno == EAGAIN, "probe_fork_error"); limited = true; break; }
                if (!worker) { alarm(3); pause(); _exit(0); }
                spawned.push_back(worker);
            }
            for (pid_t worker : spawned) { (void)kill(worker, SIGKILL); while (waitpid(worker, nullptr, 0) < 0 && errno == EINTR) {} }
            require(limited, "probe_nproc_not_enforced");
            // Detached descendants must be adopted by the trusted subreaper.
            pid_t detached = fork(); check(detached, "probe_detach_fork");
            if (!detached) { (void)setsid(); signal(SIGTERM, SIG_IGN); alarm(30); pause(); _exit(0); }
            _exit(0);
        } catch (const std::exception& e) { dprintf(2, "probe_child_error:%s\n", e.what()); _exit(125); }
    }
    int result = 0; while (waitpid(child, &result, 0) < 0 && errno == EINTR) {}
    require(WIFEXITED(result) && WEXITSTATUS(result) == 0, "probe_child_failed");
    require(uid_live(80000), "probe_detached_missing");
    reap_uid(80000); reap_uid(80001);
    require(!uid_live(80000) && !uid_live(80001), "probe_descendants_remain");
    check(umount2(proc.c_str(), 0), "probe_unmount");
    return 0;
}
}
int main(int argc, char** argv) {
    char path[] = "/data/local/tmp/droy-uid-probe-XXXXXX"; std::string root;
    try {
        require(getuid() == 0, "probe_root_required");
        if (argc == 3 && !strcmp(argv[1], "--hold-unleased-uid")) {
            uid_t uid = std::stoul(argv[2]); require(uid == 80007 && getpwuid(uid) == nullptr && !uid_live(uid), "probe_unleased_uid_busy");
            int status[2]; check(pipe2(status, O_CLOEXEC), "probe_unleased_pipe"); Fd read_end(status[0]), write_end(status[1]);
            pid_t parent = getpid(), child = fork(); check(child, "probe_unleased_fork");
            if (!child) {
                try {
                    read_end.reset(); probe_drop(uid);
                    check(prctl(PR_SET_PDEATHSIG, SIGKILL, 0, 0, 0), "probe_unleased_parent_death"); require(getppid() == parent, "probe_unleased_parent_lost");
                    alarm(20); require(write(write_end, "1", 1) == 1, "probe_unleased_ready"); write_end.reset(); pause(); _exit(0);
                } catch (...) { _exit(125); }
            }
            write_end.reset(); Fd handle(syscall(SYS_pidfd_open, child, 0)); check(handle, "probe_unleased_pidfd");
            pollfd ready{read_end, POLLIN, 0}; int polled = poll(&ready, 1, 3000); char receipt = 0;
            if (polled <= 0 || read(read_end, &receipt, 1) != 1 || receipt != '1') {
                (void)syscall(SYS_pidfd_send_signal, int(handle), SIGKILL, nullptr, 0); (void)waitpid(child, nullptr, 0); throw Error("probe_unleased_start_failed");
            }
            uint64_t ticks = start_ticks(child); require(process_uid(child) == uid, "probe_unleased_identity");
            printf("{\"uid\":%u,\"pid\":%d,\"startTicks\":%llu}\n", uid, child, static_cast<unsigned long long>(ticks)); fflush(stdout);
            pollfd caller{0, POLLIN, 0}; (void)poll(&caller, 1, 15000);
            int signalled = syscall(SYS_pidfd_send_signal, int(handle), SIGKILL, nullptr, 0);
            require(signalled == 0 || errno == ESRCH, "probe_unleased_stop");
            while (waitpid(child, nullptr, 0) < 0 && errno == EINTR) {}
            require(!uid_live(uid), "probe_unleased_not_reaped");
            puts("{\"released\":true,\"pidfdConfirmed\":true}"); return 0;
        }
        if (argc == 5 && !strcmp(argv[1], "--kill-owned-supervisor")) {
            pid_t pid = std::stoi(argv[2]); uint64_t ticks = std::stoull(argv[3]); std::string executable = argv[4];
            clean_absolute(executable);
            require(pid > 1 && ticks && under(executable, "/data/app") && executable.substr(executable.find_last_of('/') + 1) == "libdroy_exec.so", "probe_kill_identity");
            require(process_uid(pid) == 0 && start_ticks(pid) == ticks && link_at(AT_FDCWD, ("/proc/" + std::to_string(pid) + "/exe").c_str()) == executable, "probe_supervisor_changed");
            Fd handle(syscall(SYS_pidfd_open, pid, 0)); check(handle, "probe_supervisor_pidfd");
            require(process_uid(pid) == 0 && start_ticks(pid) == ticks && link_at(AT_FDCWD, ("/proc/" + std::to_string(pid) + "/exe").c_str()) == executable, "probe_supervisor_changed");
            check(syscall(SYS_pidfd_send_signal, int(handle), SIGKILL, nullptr, 0), "probe_supervisor_kill");
            puts("{\"ok\":true,\"syntheticSupervisorKilled\":true}"); return 0;
        }
        require(argc == 1, "probe_arguments");
        require(getpwuid(80000) == nullptr && getpwuid(80001) == nullptr, "probe_uid_reserved");
        require(!uid_live(80000) && !uid_live(80001), "probe_uid_in_use");
        std::string before = read_small("/proc/self/mountinfo", 1024 * 1024);
        require(mkdtemp(path) != nullptr, "probe_temp"); root = path; check(chmod(root.c_str(), 0755), "probe_dir_mode");
        check(mkdir((root + "/proc").c_str(), 0755), "probe_proc_dir");
        pid_t host = getpid(), child = fork(); check(child, "probe_namespace_child");
        if (!child) { try { _exit(namespace_probe(root, host)); } catch (const std::exception& e) { dprintf(2, "probe_namespace_error:%s\n", e.what()); _exit(125); } }
        int result; while (waitpid(child, &result, 0) < 0 && errno == EINTR) {}
        bool unchanged = before == read_small("/proc/self/mountinfo", 1024 * 1024);
        require(unchanged, "probe_host_mount_changed"); require(WIFEXITED(result) && WEXITSTATUS(result) == 0, "probe_namespace_failed");
        require(!uid_live(80000) && !uid_live(80001), "probe_live_uid_after_exit");
        check(rmdir((root + "/proc").c_str()), "probe_remove_proc"); check(rmdir(root.c_str()), "probe_remove_root");
        puts("{\"ok\":true,\"syntheticOnly\":true,\"privateProcSuperblock\":true,\"subsetPid\":true,\"hostMountsUnchanged\":true,\"crossProcessFilesDenied\":true,\"crossUidSignalsDenied\":true,\"crossUidSigcontDenied\":true,\"rootReacquisitionDenied\":true,\"nprocEnforced\":true,\"detachedChildKilledAndReaped\":true,\"remainingProbeProcesses\":0}"); return 0;
    } catch (const std::exception& e) {
        if (!root.empty()) { rmdir((root + "/proc").c_str()); rmdir(root.c_str()); }
        dprintf(2, "{\"ok\":false,\"code\":\"%s\"}\n", e.what()); return 125;
    }
}
