#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <linux/audit.h>
#include <linux/filter.h>
#include <linux/seccomp.h>
#include <poll.h>
#include <signal.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/prctl.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/mman.h>
#include <sys/syscall.h>
#include <sys/types.h>
#include <sys/uio.h>
#include <sys/utsname.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

static void failure(const char *stage) {
    printf("{\"probe\":\"failure\",\"stage\":\"%s\",\"errno\":%d}\n", stage, errno);
}

static int transfer_fd(int socket, int fd) {
    union { char bytes[CMSG_SPACE(sizeof(int))]; struct cmsghdr align; } control = {0};
    char marker = 'F';
    struct iovec vector = {.iov_base = &marker, .iov_len = 1};
    struct msghdr message = {.msg_iov = &vector, .msg_iovlen = 1,
        .msg_control = control.bytes, .msg_controllen = sizeof(control.bytes)};
    struct cmsghdr *header = CMSG_FIRSTHDR(&message);
    header->cmsg_level = SOL_SOCKET;
    header->cmsg_type = SCM_RIGHTS;
    header->cmsg_len = CMSG_LEN(sizeof(int));
    memcpy(CMSG_DATA(header), &fd, sizeof(fd));
    return sendmsg(socket, &message, MSG_NOSIGNAL) == 1 ? 0 : -1;
}

static int receive_fd(int socket) {
    union { char bytes[CMSG_SPACE(sizeof(int))]; struct cmsghdr align; } control = {0};
    char marker;
    struct iovec vector = {.iov_base = &marker, .iov_len = 1};
    struct msghdr message = {.msg_iov = &vector, .msg_iovlen = 1,
        .msg_control = control.bytes, .msg_controllen = sizeof(control.bytes)};
    if (recvmsg(socket, &message, MSG_CMSG_CLOEXEC) != 1 || marker != 'F') { errno = EPIPE; return -1; }
    struct cmsghdr *header = CMSG_FIRSTHDR(&message);
    if (!header || header->cmsg_level != SOL_SOCKET || header->cmsg_type != SCM_RIGHTS ||
        header->cmsg_len != CMSG_LEN(sizeof(int))) { errno = EPROTO; return -1; }
    int fd;
    memcpy(&fd, CMSG_DATA(header), sizeof(fd));
    return fd;
}

/* This experiment grants directory objects, not permanent pathname containment.
 * Fixtures have no private hardlinks/device nodes; trusted writers never import
 * private objects. This is deliberately not a general executable sandbox. */
static int install_listener(void) {
#define ALLOW(number) BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, number, 0, 1), BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW)
    struct sock_filter instructions[] = {
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, arch)),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, AUDIT_ARCH_AARCH64, 1, 0),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_KILL_PROCESS),
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_openat, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_USER_NOTIF),
        ALLOW(__NR_read), ALLOW(__NR_close), ALLOW(__NR_exit), ALLOW(__NR_exit_group),
        ALLOW(__NR_rt_sigreturn), ALLOW(__NR_sendmsg),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_write, 0, 4),
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, args[0])),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, STDOUT_FILENO, 1, 0),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, STDERR_FILENO, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ERRNO | EACCES),
    };
#undef ALLOW
    struct sock_fprog program = {.len = sizeof(instructions) / sizeof(instructions[0]), .filter = instructions};
    if (prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) < 0) return -1;
    return syscall(__NR_seccomp, SECCOMP_SET_MODE_FILTER, SECCOMP_FILTER_FLAG_NEW_LISTENER, &program);
}

static int restricted_open(int root, const char *copied) {
    size_t length = strnlen(copied, PATH_MAX);
    if (!length || length == PATH_MAX || copied[0] == '/' || copied[length - 1] == '/') { errno = EACCES; return -1; }
    char path[PATH_MAX];
    memcpy(path, copied, length + 1);
    int current = fcntl(root, F_DUPFD_CLOEXEC, 3);
    if (current < 0) return -1;
    char *component = path;
    while (1) {
        char *separator = strchr(component, '/');
        if (separator) *separator = 0;
        if (!component[0] || !strcmp(component, ".") || !strcmp(component, "..")) {
            close(current); errno = EACCES; return -1;
        }
        int flags = separator ? O_PATH | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC
            : O_RDONLY | O_NOFOLLOW | O_NONBLOCK | O_CLOEXEC;
        int next = openat(current, component, flags);
        close(current);
        if (next < 0) return -1;
        if (!separator) {
            struct stat metadata;
            if (fstat(next, &metadata) < 0 || !S_ISREG(metadata.st_mode)) {
                close(next); errno = EACCES; return -1;
            }
            return next;
        }
        current = next;
        component = separator + 1;
    }
}

static int copy_path(pid_t target, uintptr_t remote, char result[PATH_MAX]) {
    struct iovec local = {.iov_base = result, .iov_len = PATH_MAX};
    struct iovec foreign = {.iov_base = (void *)remote, .iov_len = PATH_MAX};
    ssize_t copied = syscall(__NR_process_vm_readv, target, &local, 1, &foreign, 1, 0);
    if (copied > 0 && memchr(result, 0, (size_t)copied)) return 0;
    errno = EACCES;
    return -1;
}

static int deny(int listener, struct seccomp_notif *request) {
    struct seccomp_notif_resp response = {.id = request->id, .error = -EACCES};
    return ioctl(listener, SECCOMP_IOCTL_NOTIF_SEND, &response);
}

struct race_stats { unsigned long swaps; };

static int handle_notification(int listener, int root, pid_t child, struct seccomp_notif *request,
        int *allowed, int *denied, int *copies, struct race_stats *stats,
        unsigned long *race_first, unsigned long *race_last, int *race_allowed, int *race_denied) {
    if (request->pid != (unsigned)child || request->data.nr != __NR_openat ||
        (int)request->data.args[0] != AT_FDCWD || request->data.args[2] != (O_RDONLY | O_CLOEXEC)) {
        (*denied)++;
        return deny(listener, request);
    }
    if (ioctl(listener, SECCOMP_IOCTL_NOTIF_ID_VALID, &request->id) < 0) return 0;
    char copied[PATH_MAX];
    if (copy_path(child, (uintptr_t)request->data.args[1], copied) < 0) {
        (*denied)++;
        return deny(listener, request);
    }
    (*copies)++;
    int race = !strcmp(copied, "file-swap") || !strcmp(copied, "dir-swap/allowed.txt");
    if (race) {
        unsigned long now = __atomic_load_n(&stats->swaps, __ATOMIC_RELAXED);
        if (*race_first == ULONG_MAX) *race_first = now;
        *race_last = now;
    }
    if (ioctl(listener, SECCOMP_IOCTL_NOTIF_ID_VALID, &request->id) < 0) return 0;
    int fd = restricted_open(root, copied);
    if (fd < 0) { (*denied)++; if (race) (*race_denied)++; return deny(listener, request); }
    struct seccomp_notif_addfd addition = {.id = request->id, .flags = SECCOMP_ADDFD_FLAG_SEND,
        .srcfd = fd, .newfd_flags = O_CLOEXEC};
    int injected = ioctl(listener, SECCOMP_IOCTL_NOTIF_ADDFD, &addition);
    close(fd);
    if (injected < 0) return -1;
    (*allowed)++;
    if (race) (*race_allowed)++;
    return 0;
}

static int read_case(const char *path, int expected, int noisy) {
    errno = 0;
    int fd = syscall(__NR_openat, AT_FDCWD, path, O_RDONLY | O_CLOEXEC, 0);
    int error = errno, content = 0;
    if (fd >= 0) {
        char value[64] = {0};
        ssize_t size = read(fd, value, sizeof(value) - 1);
        content = size == 18 && !memcmp(value, "synthetic allowed\n", 18);
        close(fd);
    }
    int passed = expected > 0 ? content : expected == 0 ? fd < 0 && error == EACCES : content || (fd < 0 && error == EACCES);
    if (noisy || !passed) printf("{\"probe\":\"fixed_root_read\",\"case\":\"%s\",\"passed\":%d,\"allowed_content\":%d,\"errno\":%d}\n", path, passed, content, error);
    return passed;
}

static int mutation_result(const char *name, long result) {
    int error = errno, passed = result == -1 && error == EACCES;
    printf("{\"probe\":\"mutation_denied\",\"case\":\"%s\",\"passed\":%d,\"errno\":%d}\n", name, passed, error);
    return passed;
}

static int worker_tests(void) {
    const char *paths[] = {"allowed.txt", "nested/allowed.txt", "../private/secret.txt", "nested/../allowed.txt",
        "./allowed.txt", "/proc/self/status", "escape.txt", "escape-dir/secret.txt", "magic-link",
        "nested//allowed.txt", "nested/", "", "fifo", "nested"};
    int passed = 1, static_passes = 0;
    for (unsigned index = 0; index < sizeof(paths) / sizeof(paths[0]); index++)
        static_passes += read_case(paths[index], index < 2 ? 1 : 0, 1);
    passed &= static_passes == 14;
    int mutations = 0;
    mutations += mutation_result("write_open", syscall(__NR_openat, AT_FDCWD, "allowed.txt", O_WRONLY | O_CLOEXEC, 0));
    mutations += mutation_result("rename", syscall(__NR_renameat, AT_FDCWD, "allowed.txt", AT_FDCWD, "changed.txt"));
    mutations += mutation_result("unlink", syscall(__NR_unlinkat, AT_FDCWD, "allowed.txt", 0));
    mutations += mutation_result("symlink", syscall(__NR_symlinkat, "allowed.txt", AT_FDCWD, "created-link"));
    mutations += mutation_result("hardlink", syscall(__NR_linkat, AT_FDCWD, "allowed.txt", AT_FDCWD, "created-hardlink", 0));
    mutations += mutation_result("mkdir", syscall(__NR_mkdirat, AT_FDCWD, "created-dir", 0700));
    mutations += mutation_result("chdir", syscall(__NR_chdir, "nested"));
    int read_only = syscall(__NR_openat, AT_FDCWD, "allowed.txt", O_RDONLY | O_CLOEXEC, 0);
    if (read_only < 0) return 0;
    mutations += mutation_result("file_write", syscall(__NR_write, read_only, "x", 1));
    mutations += mutation_result("truncate", syscall(__NR_ftruncate, read_only, 0));
    close(read_only);
    passed &= mutations == 9;
    int races = 0;
    for (int index = 0; index < 128; index++) {
        races += read_case("file-swap", -1, 0);
        races += read_case("dir-swap/allowed.txt", -1, 0);
    }
    passed &= races == 256;
    printf("{\"probe\":\"fixed_root_worker\",\"static_cases\":%d,\"mutation_denials\":%d,\"race_reads\":%d,\"passed\":%d}\n",
        static_passes, mutations, races, passed);
    return passed;
}

static int fixture(const char *path, const char *content) {
    int fd = open(path, O_CREAT | O_EXCL | O_WRONLY | O_CLOEXEC, 0600);
    if (fd < 0) return -1;
    ssize_t count = write(fd, content, strlen(content));
    close(fd);
    return count == (ssize_t)strlen(content) ? 0 : -1;
}

static void mutate_links(pid_t owner, struct race_stats *stats) {
    if (prctl(PR_SET_PDEATHSIG, SIGKILL, 0, 0, 0) < 0 || getppid() != owner) _exit(1);
    alarm(15);
    for (;;) {
        if (symlink("../private/secret.txt", "workspace/file-next") < 0 ||
            rename("workspace/file-next", "workspace/file-swap") < 0 ||
            rename("workspace/dir-swap", "workspace/dir-held") < 0 ||
            symlink("../private", "workspace/dir-swap") < 0) _exit(1);
        __atomic_add_fetch(&stats->swaps, 1, __ATOMIC_RELAXED);
        usleep(100);
        if (fixture("workspace/file-next", "synthetic allowed\n") < 0 ||
            rename("workspace/file-next", "workspace/file-swap") < 0 ||
            unlink("workspace/dir-swap") < 0 ||
            rename("workspace/dir-held", "workspace/dir-swap") < 0) _exit(1);
        __atomic_add_fetch(&stats->swaps, 1, __ATOMIC_RELAXED);
        usleep(100);
    }
}

int main(void) {
    setbuf(stdout, NULL);
    printf("{\"probe\":\"fixed_root_contract\",\"uid\":%u,\"initial_seccomp_mode\":%d,\"contract\":\"authorized_directory_objects_no_private_import\",\"current_path_beneath\":false,\"production_sandbox_verified\":false}\n", getuid(), prctl(PR_GET_SECCOMP, 0, 0, 0, 0));
    if (mkdir("workspace", 0700) < 0 || mkdir("private", 0700) < 0 || mkdir("workspace/nested", 0700) < 0 ||
        mkdir("workspace/dir-swap", 0700) < 0 || fixture("workspace/allowed.txt", "synthetic allowed\n") < 0 ||
        fixture("workspace/nested/allowed.txt", "synthetic allowed\n") < 0 ||
        fixture("workspace/dir-swap/allowed.txt", "synthetic allowed\n") < 0 ||
        fixture("workspace/file-swap", "synthetic allowed\n") < 0 ||
        fixture("private/secret.txt", "synthetic private\n") < 0 ||
        fixture("private/allowed.txt", "synthetic private\n") < 0 ||
        symlink("../private/secret.txt", "workspace/escape.txt") < 0 ||
        symlink("../private", "workspace/escape-dir") < 0 ||
        symlink("/proc/self/fd/1", "workspace/magic-link") < 0 || mkfifo("workspace/fifo", 0600) < 0) { failure("fixtures"); return 1; }
    int root = open("workspace", O_PATH | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
    if (root < 0) { failure("fixed_root"); return 1; }
    int channels[2];
    if (socketpair(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC, 0, channels) < 0) { failure("socketpair"); return 1; }
    pid_t owner = getpid(), child = fork();
    if (child < 0) { failure("fork"); return 1; }
    if (!child) {
        if (prctl(PR_SET_PDEATHSIG, SIGKILL, 0, 0, 0) < 0 || getppid() != owner) _exit(1);
        close(channels[0]);
        if (fchdir(root) < 0) _exit(1);
        close(root);
        int listener = install_listener();
        if (listener < 0 || transfer_fd(channels[1], listener) < 0) _exit(1);
        close(listener); close(channels[1]);
        _exit(worker_tests() ? 0 : 1);
    }
    close(channels[1]);
    int listener = receive_fd(channels[0]);
    close(channels[0]);
    if (listener < 0) { failure("receive_listener"); kill(child, SIGKILL); waitpid(child, NULL, 0); return 1; }
    struct race_stats *stats = mmap(NULL, sizeof(*stats), PROT_READ | PROT_WRITE, MAP_SHARED | MAP_ANONYMOUS, -1, 0);
    if (stats == MAP_FAILED) { failure("race_stats"); kill(child, SIGKILL); waitpid(child, NULL, 0); return 1; }
    stats->swaps = 0;
    pid_t mutator = fork();
    if (mutator < 0) { failure("mutator"); kill(child, SIGKILL); waitpid(child, NULL, 0); return 1; }
    if (!mutator) { close(listener); close(root); mutate_links(owner, stats); _exit(1); }
    int allowed = 0, denied = 0, copies = 0, status = 0, completed = 0, failed = 0;
    int race_allowed = 0, race_denied = 0;
    unsigned long race_first = ULONG_MAX, race_last = 0;
    time_t started = time(NULL);
    while (time(NULL) - started < 15) {
        if (waitpid(child, &status, WNOHANG) == child) { completed = 1; break; }
        struct pollfd polling = {.fd = listener, .events = POLLIN};
        if (poll(&polling, 1, 100) < 0) { failed = 1; break; }
        if (!(polling.revents & POLLIN)) continue;
        struct seccomp_notif request = {0};
        if (ioctl(listener, SECCOMP_IOCTL_NOTIF_RECV, &request) < 0) {
            if (errno == EINTR || errno == ENOENT) continue;
            failed = 1; break;
        }
        if (handle_notification(listener, root, child, &request, &allowed, &denied, &copies,
            stats, &race_first, &race_last, &race_allowed, &race_denied) < 0) { failed = 1; break; }
    }
    if (!completed) { kill(child, SIGKILL); waitpid(child, &status, 0); }
    kill(mutator, SIGTERM);
    int mutator_status = 0;
    waitpid(mutator, &mutator_status, 0);
    unsigned long swaps = __atomic_load_n(&stats->swaps, __ATOMIC_RELAXED);
    int mutator_live = WIFSIGNALED(mutator_status) && WTERMSIG(mutator_status) == SIGTERM;
    munmap(stats, sizeof(*stats));
    close(listener); close(root);
    int passed = completed && !failed && WIFEXITED(status) && WEXITSTATUS(status) == 0 &&
        allowed >= 3 && denied >= 13 && copies == 271 && swaps > 0 && mutator_live &&
        race_allowed + race_denied == 256 && race_first != ULONG_MAX && race_last > race_first;
    printf("{\"probe\":\"fixed_root_summary\",\"passed\":%d,\"completed\":%d,\"allowed\":%d,\"denied\":%d,\"copied_paths\":%d,\"concurrent_swaps\":%lu,\"race_swaps_first\":%lu,\"race_swaps_last\":%lu,\"race_allowed\":%d,\"race_denied\":%d,\"mutator_live_until_stop\":%d,\"child_exit\":%d,\"continue_used\":false,\"openat2_used\":false,\"production_sandbox_verified\":false}\n",
        passed, completed, allowed, denied, copies, swaps, race_first, race_last, race_allowed, race_denied,
        mutator_live, WIFEXITED(status) ? WEXITSTATUS(status) : -1);
    return passed ? 0 : 1;
}

