#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <linux/audit.h>
#include <linux/filter.h>
#include <linux/seccomp.h>
#include <poll.h>
#include <signal.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/prctl.h>
#include <sys/socket.h>
#include <sys/syscall.h>
#include <sys/types.h>
#include <sys/uio.h>
#include <sys/wait.h>
#include <unistd.h>

/* Capability probes only. The ADDFD case deliberately uses a fixed fixture,
 * not a pathname policy, and must never be reused as a filesystem broker. */
static int fail(const char *stage) {
    printf("{\"probe\":\"primitive_failure\",\"stage\":\"%s\",\"errno\":%d}\n", stage, errno);
    return 1;
}

static int parent_death(pid_t expected) {
    return prctl(PR_SET_PDEATHSIG, SIGKILL, 0, 0, 0) == 0 && getppid() == expected;
}

static int read_remote(pid_t pid, const void *address) {
    char output[32] = {0};
    struct iovec local = {.iov_base = output, .iov_len = 19};
    struct iovec remote = {.iov_base = (void *)address, .iov_len = 19};
    errno = 0;
    ssize_t count = syscall(__NR_process_vm_readv, pid, &local, 1, &remote, 1, 0);
    printf("{\"probe\":\"process_vm_readv\",\"result\":%ld,\"errno\":%d}\n", (long)count, errno);
    return count == 19 && !memcmp(output, "synthetic-read-test", 19) ? 0 : 1;
}

static int read_self(void) { return read_remote(getpid(), "synthetic-read-test"); }

static int read_child(void) {
    int channels[2];
    if (pipe2(channels, O_CLOEXEC) < 0) return fail("read_child_pipe");
    pid_t parent = getpid(), child = fork();
    if (child < 0) return fail("read_child_fork");
    if (!child) {
        if (!parent_death(parent)) _exit(1);
        close(channels[0]);
        const char *value = "synthetic-read-test";
        uintptr_t address = (uintptr_t)value;
        if (write(channels[1], &address, sizeof(address)) != sizeof(address)) _exit(1);
        close(channels[1]);
        pause();
        _exit(1);
    }
    close(channels[1]);
    uintptr_t address;
    ssize_t received = read(channels[0], &address, sizeof(address));
    close(channels[0]);
    int result = received == sizeof(address) ? read_remote(child, (const void *)address) : fail("read_child_address");
    kill(child, SIGKILL);
    waitpid(child, NULL, 0);
    return result;
}

static int install_listener(void) {
    struct sock_filter instructions[] = {
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, arch)),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, AUDIT_ARCH_AARCH64, 1, 0),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_KILL_PROCESS),
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_openat, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_USER_NOTIF),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
    };
    struct sock_fprog program = {.len = sizeof(instructions) / sizeof(instructions[0]), .filter = instructions};
    if (prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) < 0) return -1;
    return syscall(__NR_seccomp, SECCOMP_SET_MODE_FILTER, SECCOMP_FILTER_FLAG_NEW_LISTENER, &program);
}

static int listener_only(void) {
    errno = 0;
    int listener = install_listener();
    printf("{\"probe\":\"user_notif_listener\",\"result\":%d,\"errno\":%d}\n", listener, errno);
    if (listener < 0) return 1;
    close(listener);
    return 0;
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

static int addfd_send(void) {
    const char *content = "synthetic addfd";
    int source = open("addfd-fixture", O_CREAT | O_EXCL | O_RDWR | O_CLOEXEC, 0600);
    if (source < 0 || write(source, content, strlen(content)) != (ssize_t)strlen(content) || lseek(source, 0, SEEK_SET) != 0)
        return fail("addfd_fixture");
    int channels[2];
    if (socketpair(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC, 0, channels) < 0) return fail("addfd_socketpair");
    pid_t parent = getpid(), child = fork();
    if (child < 0) return fail("addfd_fork");
    if (!child) {
        if (!parent_death(parent)) _exit(1);
        close(source);
        close(channels[0]);
        int listener = install_listener();
        if (listener < 0 || transfer_fd(channels[1], listener) < 0) _exit(fail("addfd_listener_transfer"));
        close(listener);
        close(channels[1]);
        int fd = syscall(__NR_openat, AT_FDCWD, "fixed-synthetic-request", O_RDONLY | O_CLOEXEC, 0);
        char output[32] = {0};
        ssize_t count = fd < 0 ? -1 : read(fd, output, sizeof(output));
        int valid = count == (ssize_t)strlen(content) && !memcmp(content, output, (size_t)count);
        printf("{\"probe\":\"addfd_child_read\",\"passed\":%d}\n", valid);
        if (fd >= 0) close(fd);
        _exit(valid ? 0 : 1);
    }
    close(channels[1]);
    int listener = receive_fd(channels[0]);
    close(channels[0]);
    int result = 1;
    if (listener >= 0) {
        struct pollfd polling = {.fd = listener, .events = POLLIN};
        struct seccomp_notif request = {0};
        if (poll(&polling, 1, 3000) == 1 && (polling.revents & POLLIN) &&
            ioctl(listener, SECCOMP_IOCTL_NOTIF_RECV, &request) == 0 &&
            request.pid == (unsigned)child && request.data.nr == __NR_openat &&
            ioctl(listener, SECCOMP_IOCTL_NOTIF_ID_VALID, &request.id) == 0) {
            struct seccomp_notif_addfd addition = {.id = request.id, .flags = SECCOMP_ADDFD_FLAG_SEND,
                .srcfd = source, .newfd_flags = O_CLOEXEC};
            errno = 0;
            int injected = ioctl(listener, SECCOMP_IOCTL_NOTIF_ADDFD, &addition);
            printf("{\"probe\":\"addfd_atomic_send\",\"result\":%d,\"errno\":%d,\"continue_used\":false}\n", injected, errno);
            result = injected >= 0 ? 0 : 1;
        } else fail("addfd_receive");
        close(listener);
    } else fail("addfd_receive_listener");
    close(source);
    if (result) kill(child, SIGKILL);
    int status = 0;
    waitpid(child, &status, 0);
    return result || !WIFEXITED(status) || WEXITSTATUS(status) != 0;
}

static int isolated_case(const char *name, int (*test)(void)) {
    printf("{\"probe\":\"primitive_start\",\"case\":\"%s\"}\n", name);
    pid_t parent = getpid(), child = fork();
    if (child < 0) return fail("case_fork");
    if (!child) {
        if (!parent_death(parent)) _exit(1);
        alarm(8);
        _exit(test());
    }
    int status = 0;
    if (waitpid(child, &status, 0) != child) return fail("case_wait");
    int passed = WIFEXITED(status) && WEXITSTATUS(status) == 0;
    printf("{\"probe\":\"primitive_result\",\"case\":\"%s\",\"passed\":%d,\"exit\":%d,\"signal\":%d}\n",
        name, passed, WIFEXITED(status) ? WEXITSTATUS(status) : -1, WIFSIGNALED(status) ? WTERMSIG(status) : 0);
    return passed;
}

int main(void) {
    setbuf(stdout, NULL);
    printf("{\"probe\":\"primitives_runtime\",\"uid\":%u,\"initial_seccomp_mode\":%d}\n", getuid(), prctl(PR_GET_SECCOMP, 0, 0, 0, 0));
    int passed = 0;
    passed += isolated_case("process_vm_readv_self", read_self);
    passed += isolated_case("process_vm_readv_child", read_child);
    passed += isolated_case("user_notif_listener", listener_only);
    passed += isolated_case("addfd_atomic_send", addfd_send);
    printf("{\"probe\":\"primitives_summary\",\"passed\":%d,\"total\":4,\"production_sandbox_verified\":false}\n", passed);
    return passed == 4 ? 0 : 1;
}
