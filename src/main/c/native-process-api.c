// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <spawn.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <linux/wait.h>
#include <unistd.h>

/* Linux/glibc transport only. No JVM fork child executes user code. All fd
 * arguments are owned duplicates supplied by the context, never guest ints.
 * Result: pid, pidfd, parent stdin, parent stdout, parent stderr. */
_Static_assert(sizeof(pid_t) == sizeof(int32_t), "process pid ABI");
_Static_assert(sizeof(int) == sizeof(int32_t), "process status ABI");

static int high_fd(int fd) {
    if (fd < 0 || fd > 2) return fd;
    int copy = fcntl(fd, F_DUPFD_CLOEXEC, 3);
    int error = errno;
    close(fd);
    errno = error;
    return copy;
}

static int signal_fd(int pidfd, int signal) {
    return (int) syscall(SYS_pidfd_send_signal, pidfd, signal, NULL, 0);
}

/* posix_spawnp consults the parent's environ for PATH, even when the child
 * receives an environment override. Use the supplied parent-context PATH,
 * without modifying libc/JVM global environment state.
 * Relative PATH entries resolve after the child's directory actions. */
static int spawn_path(pid_t *pid, char *const argv[], char *const env[], const char *path,
                      posix_spawn_file_actions_t *actions, posix_spawnattr_t *attrs) {
    if (strchr(argv[0], '/'))
        return posix_spawn(pid, argv[0], actions, attrs, argv, env);
    char *default_path = NULL;
    if (!path) {
        size_t length = confstr(_CS_PATH, NULL, 0);
        if (!length) return errno ? errno : EINVAL;
        default_path = malloc(length);
        if (!default_path) return ENOMEM;
        confstr(_CS_PATH, default_path, length);
        path = default_path;
    }
    size_t name_length = strlen(argv[0]);
    size_t path_length = strlen(path);
    if (path_length > SIZE_MAX - name_length - 2) { free(default_path); return E2BIG; }
    char *candidate = malloc(path_length + name_length + 2);
    if (!candidate) { free(default_path); return ENOMEM; }
    int result = ENOENT, denied = 0;
    const char *start = path;
    for (;;) {
        const char *end = strchr(start, ':');
        size_t length = end ? (size_t)(end - start) : strlen(start);
        memcpy(candidate, start, length);
        if (length) candidate[length++] = '/';
        memcpy(candidate + length, argv[0], name_length + 1);
        result = posix_spawn(pid, candidate, actions, attrs, argv, env);
        if (!result) break;
        if (result == EACCES) denied = 1;
        else if (result != ENOENT && result != ENOTDIR) break;
        if (!end) { result = denied ? EACCES : ENOENT; break; }
        start = end + 1;
    }
    free(candidate);
    free(default_path);
    return result;
}

int thc_process_spawn(char *const argv[], char *const env[], int directory,
                      const char *cwd, const int streams[3], int flags,
                      const char *search_path, int result[5]) {
    for (int i = 0; i < 5; ++i) result[i] = -1;
    if (!argv || !argv[0] || !argv[0][0] || !env || directory < 0) return EINVAL;
    /* process-1.6 flags: close_fds, create_group, new_session, reset INT/QUIT.
     * Windows console flags and credential changes are not silently ignored. */
    if (flags & ~(0x1 | 0x2 | 0x8 | 0x20)) return ENOTSUP;
    /* posix_spawn -> pidfd_open requires an unreaped child to reserve its PID.
     * Reject automatic reaping before creating pipes or a child. This observes
     * host policy; it cannot synchronize with an external reaper or concurrent
     * sigaction changes. The embedding host must exclude both for our children. */
    struct sigaction child_action;
    if (sigaction(SIGCHLD, NULL, &child_action)) return errno;
    if (child_action.sa_handler == SIG_IGN || (child_action.sa_flags & SA_NOCLDWAIT))
        return ENOTSUP;
    int probe = (int) syscall(SYS_pidfd_open, getpid(), 0);
    if (probe < 0) return errno;
    siginfo_t probe_info;
    int probe_error = waitid(P_PIDFD, (id_t)probe, &probe_info, WEXITED | WNOHANG) ? errno : 0;
    close(probe);
    if (probe_error != ECHILD) return probe_error ? probe_error : EIO;
    int parent[3] = {-1, -1, -1}, child[3] = {-1, -1, -1};
    int error = 0;
    posix_spawn_file_actions_t actions;
    posix_spawnattr_t attrs;
    error = posix_spawn_file_actions_init(&actions);
    if (error) return error;
    error = posix_spawnattr_init(&attrs);
    if (error) { posix_spawn_file_actions_destroy(&actions); return error; }
#define ACTION(call) do { error = (call); if (error) goto done; } while (0)
    ACTION(posix_spawn_file_actions_addfchdir_np(&actions, directory));
    if (cwd) ACTION(posix_spawn_file_actions_addchdir_np(&actions, cwd));
    for (int i = 0; i < 3; ++i) {
        if (streams[i] == -1) {
            int pipefd[2];
            if (pipe2(pipefd, O_CLOEXEC)) { error = errno; goto done; }
            pipefd[0] = high_fd(pipefd[0]);
            if (pipefd[0] < 0) { error = errno; close(pipefd[1]); goto done; }
            pipefd[1] = high_fd(pipefd[1]);
            if (pipefd[1] < 0) { error = errno; close(pipefd[0]); goto done; }
            parent[i] = pipefd[i == 0 ? 1 : 0];
            child[i] = pipefd[i == 0 ? 0 : 1];
            ACTION(posix_spawn_file_actions_adddup2(&actions, child[i], i));
        } else if (streams[i] == -2) {
            /* addclose on an already closed endpoint is permitted by glibc. */
            ACTION(posix_spawn_file_actions_addclose(&actions, i));
        } else if (streams[i] >= 0) {
            child[i] = fcntl(streams[i], F_DUPFD_CLOEXEC, 3);
            if (child[i] < 0) { error = errno; goto done; }
            ACTION(posix_spawn_file_actions_adddup2(&actions, child[i], i));
        } else { error = EBADF; goto done; }
    }
    /* Unregistered JVM descriptors are never inherited, even with close_fds
     * unset. Supplied authenticated endpoints have already been duplicated. */
    ACTION(posix_spawn_file_actions_addclosefrom_np(&actions, 3));
    short spawn_flags = POSIX_SPAWN_SETSIGMASK;
    sigset_t empty;
    sigemptyset(&empty);
    ACTION(posix_spawnattr_setsigmask(&attrs, &empty));
    if (flags & 0x2) {
        spawn_flags |= POSIX_SPAWN_SETPGROUP;
        ACTION(posix_spawnattr_setpgroup(&attrs, 0));
    }
    if (flags & 0x8) spawn_flags |= POSIX_SPAWN_SETSID;
    if (flags & 0x20) {
        sigset_t defaults;
        sigemptyset(&defaults); sigaddset(&defaults, SIGINT); sigaddset(&defaults, SIGQUIT);
        ACTION(posix_spawnattr_setsigdefault(&attrs, &defaults));
        spawn_flags |= POSIX_SPAWN_SETSIGDEF;
    }
    ACTION(posix_spawnattr_setflags(&attrs, spawn_flags));
    pid_t pid;
    ACTION(spawn_path(&pid, argv, env, search_path, &actions, &attrs));
    int pidfd = (int) syscall(SYS_pidfd_open, pid, 0);
    if (pidfd < 0) {
        error = errno;
        /* Under the stable host policy/no-external-reaper contract above, the
         * unreaped child still owns this PID. This is the only pre-pidfd cleanup
         * and never receives a guest supplied PID. */
        kill(pid, SIGKILL);
        while (waitpid(pid, NULL, 0) < 0 && errno == EINTR) {}
        goto done;
    }
    result[0] = pid; result[1] = pidfd;
    for (int i = 0; i < 3; ++i) { result[i + 2] = parent[i]; parent[i] = -1; }
done:
    for (int i = 0; i < 3; ++i) {
        if (parent[i] >= 0) close(parent[i]);
        if (child[i] >= 0) close(child[i]);
    }
    posix_spawnattr_destroy(&attrs);
    posix_spawn_file_actions_destroy(&actions);
    return error;
#undef ACTION
}

/* Return captured errno separately from the original C result. Only reaping
 * runs under the owning Kotlin child lock. pidfd avoids PID reuse races. */
int thc_process_poll(int pidfd, int result[2]) {
    siginfo_t info = {0};
    result[0] = 0; result[1] = 0;
    if (waitid(P_PIDFD, (id_t)pidfd, &info, WEXITED | WNOHANG)) return errno;
    if (!info.si_pid) return 0;
    if (info.si_code != CLD_EXITED && info.si_code != CLD_KILLED && info.si_code != CLD_DUMPED)
        return EIO;
    result[0] = 1;
    result[1] = info.si_code == CLD_EXITED ? info.si_status : -info.si_status;
    return 0;
}

int thc_process_terminate(int pidfd, int *error) {
    int result = signal_fd(pidfd, SIGTERM);
    *error = result ? errno : 0;
    return result == 0; /* Original terminateProcess's exact Boolean C ABI. */
}

int thc_process_dispose(int pidfd) {
    int error = 0;
    if (signal_fd(pidfd, SIGKILL) && errno != ESRCH) error = errno;
    if (error) return error;
    siginfo_t info;
    while (waitid(P_PIDFD, (id_t)pidfd, &info, WEXITED)) {
        if (errno == EINTR) continue;
        if (errno != ECHILD) error = errno;
        break;
    }
    return error;
}
