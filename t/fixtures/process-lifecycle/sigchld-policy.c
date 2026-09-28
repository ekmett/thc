// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <signal.h>
#include <spawn.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/wait.h>
#include <unistd.h>

int thc_process_spawn(char *const[], char *const[], int, const char *, const int[3], int,
                      const char *, int[6]);
int thc_process_poll(int, int[2]);
int thc_process_dispose(int);

/* Count the actual libc launch boundary, always forwarding to the real call.
 * This detects a forbidden launch even if subsequent cleanup hides its child. */
static int spawns;
int __real_posix_spawn(pid_t *, const char *, const posix_spawn_file_actions_t *,
                       const posix_spawnattr_t *, char *const[], char *const[]);
int __wrap_posix_spawn(pid_t *pid, const char *path, const posix_spawn_file_actions_t *actions,
                       const posix_spawnattr_t *attrs, char *const argv[], char *const env[]) {
    ++spawns;
    return __real_posix_spawn(pid, path, actions, attrs, argv, env);
}

static void require(int condition, const char *message) {
    if (!condition) { fprintf(stderr, "%s (errno=%d)\n", message, errno); exit(1); }
}

static void child_notification(int signal) { (void)signal; }

int main(int argc, char **argv) {
    require(argc == 2, "expected signal-policy mode");
    int supported = !strcmp(argv[1], "default");
    struct sigaction action = {0};
    action.sa_handler = SIG_DFL;
    sigemptyset(&action.sa_mask);
    if (!strcmp(argv[1], "ignore")) action.sa_handler = SIG_IGN;
    else if (!strcmp(argv[1], "no-cld-wait")) action.sa_flags = SA_NOCLDWAIT;
    else if (!strcmp(argv[1], "handler-no-cld-wait")) {
        action.sa_handler = child_notification;
        action.sa_flags = SA_NOCLDWAIT;
    } else require(supported, "unknown signal-policy mode");
    require(sigaction(SIGCHLD, &action, NULL) == 0, "install subprocess policy");

    /* Independent kernel/libc control: these exact dispositions really do
     * discard native child status. No signal policy in the JVM is touched. */
    char *command[] = {"/bin/sh", "-c", "exit 23", NULL};
    char *environment[] = {NULL};
    pid_t pid;
    require(posix_spawn(&pid, command[0], NULL, NULL, command, environment) == 0, "native launch");
    siginfo_t info = {0};
    int waited;
    do { waited = waitid(P_PID, (id_t)pid, &info, WEXITED | WNOWAIT); } while (waited && errno == EINTR);
    if (supported) {
        require(waited == 0 && info.si_code == CLD_EXITED && info.si_status == 23, "native retained exit");
        pid_t reaped;
        do { reaped = waitpid(pid, NULL, 0); } while (reaped < 0 && errno == EINTR);
        require(reaped == pid, "native reap");
    } else require(waited == -1 && errno == ECHILD, "native automatic reaping");

    int directory = open(".", O_RDONLY | O_DIRECTORY | O_CLOEXEC);
    require(directory >= 0, "open directory");
    int streams[3] = {-2, -2, -2}, result[6];
    int before = spawns;
    int error = thc_process_spawn(command, environment, directory, NULL, streams, 0, NULL, result);
    close(directory);
    if (!supported) {
        if (!error) { thc_process_dispose(result[1]); close(result[1]); }
        require(error == ENOTSUP, "transport rejects automatic reaping");
        require(spawns == before, "rejection precedes actual libc launch");
        for (int i = 0; i < 5; ++i) require(result[i] == -1, "rejected launch publishes nothing");
        require(result[5] == 2, "rejected launch identifies SIGCHLD policy stage");
        puts(!strcmp(argv[1], "ignore") ? "ignore baseline=ECHILD transport=ENOTSUP spawns=0" :
             !strcmp(argv[1], "no-cld-wait") ? "no-cld-wait baseline=ECHILD transport=ENOTSUP spawns=0" :
             "handler-no-cld-wait baseline=ECHILD transport=ENOTSUP spawns=0");
    } else {
        require(error == 0 && result[5] == 0 && spawns == before + 1, "transport launches under default policy");
        struct pollfd ready = {.fd = result[1], .events = POLLIN};
        int polled;
        do { polled = poll(&ready, 1, 5000); } while (polled < 0 && errno == EINTR);
        if (polled != 1) { thc_process_dispose(result[1]); close(result[1]); }
        require(polled == 1, "child readiness");
        int status[2];
        error = thc_process_poll(result[1], status);
        close(result[1]);
        require(error == 0 && status[0] == 1 && status[1] == 23, "transport retained exit");
        puts("default baseline=23 transport=0 spawns=1 exit=23");
    }
    return 0;
}
