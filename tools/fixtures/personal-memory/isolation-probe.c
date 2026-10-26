#define _GNU_SOURCE
#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/audit.h>
#include <linux/filter.h>
#include <linux/landlock.h>
#include <linux/seccomp.h>
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/prctl.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/types.h>
#include <sys/utsname.h>
#include <sys/wait.h>
#include <unistd.h>

static void landlock_capability(void) {
    errno = 0;
    long abi = syscall(__NR_landlock_create_ruleset, NULL, 0, LANDLOCK_CREATE_RULESET_VERSION);
    int error = errno;
    printf("{\"probe\":\"landlock_abi\",\"result\":%ld,\"errno\":%d}\n", abi, error);
}

static void no_new_privileges(void) {
    errno = 0;
    int result = prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0);
    int error = errno;
    int active = prctl(PR_GET_NO_NEW_PRIVS, 0, 0, 0, 0);
    printf("{\"probe\":\"no_new_privs\",\"result\":%d,\"errno\":%d,\"active\":%d}\n", result, error, active);
}

static void seccomp_loopback(void) {
    int listener = socket(AF_INET, SOCK_STREAM | SOCK_CLOEXEC, 0);
    struct sockaddr_in address = {.sin_family = AF_INET, .sin_addr.s_addr = htonl(INADDR_LOOPBACK)};
    socklen_t length = sizeof(address);
    if (listener < 0 || bind(listener, (struct sockaddr *)&address, length) < 0 ||
            listen(listener, 4) < 0 || getsockname(listener, (struct sockaddr *)&address, &length) < 0) {
        printf("{\"probe\":\"seccomp_network\",\"setup_errno\":%d}\n", errno);
        return;
    }
    int before = socket(AF_INET, SOCK_STREAM | SOCK_CLOEXEC, 0);
    errno = 0;
    int baseline = connect(before, (struct sockaddr *)&address, length);
    int baseline_error = errno;
    close(before);
    int prepared = socket(AF_INET, SOCK_STREAM | SOCK_CLOEXEC, 0);
    struct sock_filter instructions[] = {
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, arch)),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, AUDIT_ARCH_AARCH64, 1, 0),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_KILL_PROCESS),
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_connect, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ERRNO | EPERM),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_socket, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ERRNO | EPERM),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
    };
    struct sock_fprog program = {.len = sizeof(instructions) / sizeof(instructions[0]), .filter = instructions};
    int nnp = prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0);
    errno = 0;
    int installed = prctl(PR_SET_SECCOMP, SECCOMP_MODE_FILTER, &program);
    int install_error = errno;
    errno = 0;
    int connection = connect(prepared, (struct sockaddr *)&address, length);
    int connect_error = errno;
    errno = 0;
    int new_socket = socket(AF_INET, SOCK_STREAM | SOCK_CLOEXEC, 0);
    int socket_error = errno;
    printf("{\"probe\":\"seccomp_network\",\"no_new_privs\":%d,\"installed\":%d,\"install_errno\":%d,"
        "\"baseline_connect\":%d,\"baseline_errno\":%d,\"connect_after\":%d,\"connect_errno\":%d,"
        "\"socket_after\":%d,\"socket_errno\":%d,\"loopback_only\":true}\n",
        nnp, installed, install_error, baseline, baseline_error, connection, connect_error, new_socket, socket_error);
    if (new_socket >= 0) close(new_socket);
    close(prepared);
    close(listener);
}

static int readable(const char *path, int *error) {
    errno = 0;
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    *error = errno;
    if (fd < 0) return 0;
    char buffer[64];
    ssize_t size = read(fd, buffer, sizeof(buffer));
    close(fd);
    return size > 0;
}

static void filesystem_isolation(void) {
    int baseline_error = 0;
    int baseline_private = readable("private/secret.txt", &baseline_error);
    long abi = syscall(__NR_landlock_create_ruleset, NULL, 0, LANDLOCK_CREATE_RULESET_VERSION);
    if (abi < 1) {
        printf("{\"probe\":\"landlock_files\",\"available\":false,\"abi\":%ld,\"errno\":%d,\"baseline_private_readable\":%d}\n",
            abi, errno, baseline_private);
        return;
    }
    // This capability test covers read isolation only, not a production sandbox policy.
    const __u64 handled = LANDLOCK_ACCESS_FS_READ_FILE | LANDLOCK_ACCESS_FS_READ_DIR;
    int ruleset = syscall(__NR_landlock_create_ruleset, &handled, sizeof(handled), 0);
    int workspace = open("workspace", O_PATH | O_CLOEXEC);
    struct landlock_path_beneath_attr rule = {.allowed_access = handled, .parent_fd = workspace};
    int added = ruleset < 0 ? -1 : syscall(__NR_landlock_add_rule, ruleset, LANDLOCK_RULE_PATH_BENEATH, &rule, 0);
    int add_error = errno;
    int nnp = prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0);
    errno = 0;
    int restricted = added < 0 ? -1 : syscall(__NR_landlock_restrict_self, ruleset, 0);
    int restrict_error = errno;
    if (workspace >= 0) close(workspace);
    if (ruleset >= 0) close(ruleset);
    int private_error, public_error, symlink_error;
    int private_read = readable("private/secret.txt", &private_error);
    int public_read = readable("workspace/allowed.txt", &public_error);
    int symlink_read = readable("workspace/escape.txt", &symlink_error);
    printf("{\"probe\":\"landlock_files\",\"available\":true,\"abi\":%ld,\"baseline_private_readable\":%d,"
        "\"no_new_privs\":%d,\"add_result\":%d,\"add_errno\":%d,\"restrict_result\":%d,\"restrict_errno\":%d,"
        "\"private_read\":%d,\"private_errno\":%d,\"workspace_read\":%d,\"workspace_errno\":%d,"
        "\"symlink_escape_read\":%d,\"symlink_errno\":%d}\n", abi, baseline_private, nnp, added, add_error,
        restricted, restrict_error, private_read, private_error, public_read, public_error, symlink_read, symlink_error);
}

static void seccomp_file_denial(void) {
    int before_error;
    int before_private = readable("private/secret.txt", &before_error);
    int before_workspace = readable("workspace/allowed.txt", &before_error);
    struct sock_filter instructions[] = {
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, arch)),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, AUDIT_ARCH_AARCH64, 1, 0),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_KILL_PROCESS),
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_openat, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ERRNO | EACCES),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
    };
    struct sock_fprog program = {.len = sizeof(instructions) / sizeof(instructions[0]), .filter = instructions};
    int nnp = prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0);
    errno = 0;
    int installed = prctl(PR_SET_SECCOMP, SECCOMP_MODE_FILTER, &program);
    int install_error = errno;
    int private_error, workspace_error;
    int private_read = readable("private/secret.txt", &private_error);
    int workspace_read = readable("workspace/allowed.txt", &workspace_error);
    printf("{\"probe\":\"seccomp_openat_denial\",\"no_new_privs\":%d,\"installed\":%d,\"install_errno\":%d,"
        "\"before_private\":%d,\"before_workspace\":%d,\"private_read\":%d,\"private_errno\":%d,"
        "\"workspace_read\":%d,\"workspace_errno\":%d,\"preserves_workspace_access\":false}\n",
        nnp, installed, install_error, before_private, before_workspace, private_read, private_error,
        workspace_read, workspace_error);
}

static void run_child(const char *name, void (*probe)(void)) {
    pid_t pid = fork();
    if (pid == 0) { probe(); _exit(0); }
    if (pid < 0) { perror("fork"); exit(1); }
    int status;
    if (waitpid(pid, &status, 0) < 0) { perror("waitpid"); exit(1); }
    printf("{\"probe\":\"child_status\",\"name\":\"%s\",\"exit\":%d,\"signal\":%d}\n", name,
        WIFEXITED(status) ? WEXITSTATUS(status) : -1, WIFSIGNALED(status) ? WTERMSIG(status) : 0);
}

static void synthetic_file(const char *path) {
    int fd = open(path, O_WRONLY | O_CREAT | O_EXCL | O_CLOEXEC, 0600);
    const char content[] = "synthetic isolation fixture\n";
    if (fd < 0 || write(fd, content, sizeof(content) - 1) != sizeof(content) - 1) {
        perror("synthetic_file"); exit(1);
    }
    close(fd);
}

int main(int argc, char **argv) {
    setbuf(stdout, NULL);
    struct utsname kernel;
    if (uname(&kernel) < 0) return 1;
    printf("{\"probe\":\"runtime\",\"uid\":%u,\"kernel\":\"%s\",\"arch\":\"%s\",\"initial_seccomp_mode\":%d,\"initial_no_new_privs\":%d}\n",
        getuid(), kernel.release, kernel.machine, prctl(PR_GET_SECCOMP, 0, 0, 0, 0), prctl(PR_GET_NO_NEW_PRIVS, 0, 0, 0, 0));
    run_child("landlock_abi", landlock_capability);
    run_child("no_new_privs", no_new_privileges);
    run_child("seccomp_network", seccomp_loopback);
    if (argc == 2 && !strcmp(argv[1], "--files")) {
        if (mkdir("private", 0700) < 0 || mkdir("workspace", 0700) < 0) { perror("mkdir"); return 1; }
        synthetic_file("private/secret.txt");
        synthetic_file("workspace/allowed.txt");
        if (symlink("../private/secret.txt", "workspace/escape.txt") < 0) { perror("symlink"); return 1; }
        run_child("landlock_files", filesystem_isolation);
        run_child("seccomp_openat_denial", seccomp_file_denial);
    }
    return 0;
}
