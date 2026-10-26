#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <linux/audit.h>
#include <linux/filter.h>
#include <linux/openat2.h>
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

static int install_listener(void) {
    struct sock_filter instructions[] = {
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, arch)),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, AUDIT_ARCH_AARCH64, 1, 0),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_KILL_PROCESS),
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_openat, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_USER_NOTIF),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_openat2, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ERRNO | EACCES),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
    };
    struct sock_fprog program = {.len = sizeof(instructions) / sizeof(instructions[0]), .filter = instructions};
    if (prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) < 0) return -1;
    return syscall(__NR_seccomp, SECCOMP_SET_MODE_FILTER, SECCOMP_FILTER_FLAG_NEW_LISTENER, &program);
}

static int read_tests(const char *phase) {
    const char *paths[] = {"allowed.txt", "../private/secret.txt", "escape.txt"};
    int passed = 1;
    for (int index = 0; index < 3; index++) {
        errno = 0;
        int fd = syscall(__NR_openat, AT_FDCWD, paths[index], O_RDONLY | O_CLOEXEC, 0);
        int error = errno;
        int readable = 0;
        if (fd >= 0) {
            char value[64] = {0};
            ssize_t size = read(fd, value, sizeof(value) - 1);
            readable = size > 0 && !strcmp(value, "synthetic allowed\n");
            close(fd);
        }
        int success = index == 0 ? readable : fd < 0 && error == EACCES;
        passed &= success;
        printf("{\"probe\":\"child_read\",\"phase\":\"%s\",\"case\":%d,\"fd\":%d,\"errno\":%d,\"allowed_content\":%d,\"passed\":%d}\n",
            phase, index, fd, error, readable, success);
    }
    return passed;
}

static void write_fixture(const char *path, const char *text) {
    int fd = open(path, O_CREAT | O_EXCL | O_WRONLY | O_CLOEXEC, 0600);
    if (fd < 0 || write(fd, text, strlen(text)) != (ssize_t)strlen(text)) { failure("fixture"); exit(1); }
    close(fd);
}

static int copy_path(pid_t target, uintptr_t remote, char result[PATH_MAX]) {
    // Copy into broker-owned memory. Never validate a target pointer then CONTINUE it.
    for (size_t offset = 0; offset < PATH_MAX; offset++) {
        struct iovec local = {.iov_base = result + offset, .iov_len = 1};
        struct iovec foreign = {.iov_base = (void *)(remote + offset), .iov_len = 1};
        if (syscall(__NR_process_vm_readv, target, &local, 1, &foreign, 1, 0) != 1) return -1;
        if (result[offset] == 0) return 0;
    }
    errno = ENAMETOOLONG;
    return -1;
}

static int deny(int listener, struct seccomp_notif *request, int error) {
    struct seccomp_notif_resp response = {.id = request->id, .error = -error};
    return ioctl(listener, SECCOMP_IOCTL_NOTIF_SEND, &response);
}

static int handle_notification(int listener, int root, pid_t target, struct seccomp_notif *request,
        int *allowed, int *denied, int *copies) {
    if (request->pid != (unsigned)target || request->data.nr != __NR_openat ||
        (int)request->data.args[0] != AT_FDCWD || request->data.args[2] != (O_RDONLY | O_CLOEXEC)) {
        return deny(listener, request, EACCES);
    }
    if (ioctl(listener, SECCOMP_IOCTL_NOTIF_ID_VALID, &request->id) < 0) return 0;
    char copied[PATH_MAX];
    if (copy_path(target, (uintptr_t)request->data.args[1], copied) < 0) {
        failure("process_vm_readv");
        return -1;
    }
    (*copies)++;
    if (ioctl(listener, SECCOMP_IOCTL_NOTIF_ID_VALID, &request->id) < 0) return 0;
    struct open_how how = {.flags = O_RDONLY | O_CLOEXEC,
        .resolve = RESOLVE_BENEATH | RESOLVE_NO_MAGICLINKS};
    int fd = syscall(__NR_openat2, root, copied, &how, sizeof(how));
    if (fd < 0) {
        printf("{\"probe\":\"broker_reject\",\"openat2_errno\":%d}\n", errno);
        (*denied)++;
        return deny(listener, request, EACCES);
    }
    struct seccomp_notif_addfd addition = {.id = request->id,
        .flags = SECCOMP_ADDFD_FLAG_SEND, .srcfd = fd, .newfd_flags = O_CLOEXEC};
    errno = 0;
    int injected = ioctl(listener, SECCOMP_IOCTL_NOTIF_ADDFD, &addition);
    int error = errno;
    close(fd);
    printf("{\"probe\":\"addfd\",\"result\":%d,\"errno\":%d,\"atomic_send\":true}\n", injected, error);
    if (injected < 0) { errno = error; failure("addfd"); return -1; }
    (*allowed)++;
    return 0;
}

int main(int argc, char **argv) {
    setbuf(stdout, NULL);
    if (argc == 2 && !strcmp(argv[1], "--exec-child")) {
        printf("{\"probe\":\"exec_inheritance\",\"seccomp_mode\":%d}\n", prctl(PR_GET_SECCOMP, 0, 0, 0, 0));
        return read_tests("after_exec") ? 0 : 1;
    }
    struct utsname kernel;
    uname(&kernel);
    printf("{\"probe\":\"runtime\",\"uid\":%u,\"kernel\":\"%s\",\"initial_seccomp_mode\":%d}\n",
        getuid(), kernel.release, prctl(PR_GET_SECCOMP, 0, 0, 0, 0));
    struct seccomp_notif_sizes sizes = {0};
    errno = 0;
    int query = syscall(__NR_seccomp, SECCOMP_GET_NOTIF_SIZES, 0, &sizes);
    printf("{\"probe\":\"notification_sizes\",\"result\":%d,\"errno\":%d,\"request\":%u,\"response\":%u}\n",
        query, errno, sizes.seccomp_notif, sizes.seccomp_notif_resp);
    if (query < 0 || sizes.seccomp_notif < sizeof(struct seccomp_notif)) return 1;
    char executable[PATH_MAX];
    if (!realpath(argv[0], executable)) { failure("executable_path"); return 1; }
    if (mkdir("workspace", 0700) < 0 || mkdir("private", 0700) < 0) { failure("mkdir"); return 1; }
    write_fixture("workspace/allowed.txt", "synthetic allowed\n");
    write_fixture("private/secret.txt", "synthetic private\n");
    if (symlink("../private/secret.txt", "workspace/escape.txt") < 0) { failure("symlink"); return 1; }
    int root = open("workspace", O_PATH | O_DIRECTORY | O_CLOEXEC);
    struct open_how how = {.flags = O_RDONLY | O_CLOEXEC, .resolve = RESOLVE_BENEATH | RESOLVE_NO_MAGICLINKS};
    errno = 0;
    int preflight = syscall(__NR_openat2, root, "allowed.txt", &how, sizeof(how));
    printf("{\"probe\":\"openat2_preflight\",\"result\":%d,\"errno\":%d}\n", preflight, errno);
    if (preflight < 0) return 1;
    close(preflight);
    int channels[2];
    if (socketpair(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC, 0, channels) < 0) { failure("socketpair"); return 1; }
    pid_t child = fork();
    if (child < 0) { failure("fork"); return 1; }
    if (child == 0) {
        close(channels[0]);
        if (fchdir(root) < 0) { failure("child_chdir"); _exit(1); }
        close(root);
        errno = 0;
        int listener = install_listener();
        printf("{\"probe\":\"listener\",\"result\":%d,\"errno\":%d}\n", listener, errno);
        if (listener < 0 || transfer_fd(channels[1], listener) < 0) { failure("listener_transfer"); _exit(1); }
        close(listener);
        close(channels[1]);
        if (!read_tests("before_exec")) _exit(1);
        execl(executable, executable, "--exec-child", NULL);
        failure("exec");
        _exit(1);
    }
    close(channels[1]);
    int listener = receive_fd(channels[0]);
    close(channels[0]);
    if (listener < 0) { failure("receive_listener"); kill(child, SIGKILL); waitpid(child, NULL, 0); return 1; }
    struct seccomp_notif *request = calloc(1, sizes.seccomp_notif);
    int allowed = 0, denied = 0, copies = 0, status = 0, completed = 0, failed = 0;
    time_t started = time(NULL);
    while (time(NULL) - started < 15) {
        pid_t result = waitpid(child, &status, WNOHANG);
        if (result == child) { completed = 1; break; }
        struct pollfd polling = {.fd = listener, .events = POLLIN};
        if (poll(&polling, 1, 100) < 0) { failure("poll"); failed = 1; break; }
        if (!(polling.revents & POLLIN)) continue;
        memset(request, 0, sizes.seccomp_notif);
        if (ioctl(listener, SECCOMP_IOCTL_NOTIF_RECV, request) < 0) {
            if (errno == EINTR || errno == ENOENT) continue;
            failure("receive_notification"); failed = 1; break;
        }
        if (handle_notification(listener, root, child, request, &allowed, &denied, &copies) < 0) { failed = 1; break; }
    }
    if (!completed) { kill(child, SIGKILL); waitpid(child, &status, 0); }
    free(request);
    close(listener);
    close(root);
    int passed = completed && !failed && WIFEXITED(status) && WEXITSTATUS(status) == 0 && allowed == 2 && denied == 4 && copies == 6;
    printf("{\"probe\":\"summary\",\"completed\":%d,\"allowed\":%d,\"denied\":%d,\"copied_paths\":%d,"
        "\"child_exit\":%d,\"child_signal\":%d,\"passed\":%d,\"continue_used\":false,\"production_sandbox_verified\":false}\n",
        completed, allowed, denied, copies, WIFEXITED(status) ? WEXITSTATUS(status) : -1,
        WIFSIGNALED(status) ? WTERMSIG(status) : 0, passed);
    return passed ? 0 : 1;
}
