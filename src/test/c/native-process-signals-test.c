// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
/* Each control owns a child process. The test runner never handles SIGINT. */
#include "../../main/c/native-process-signal-api.c"
#include <assert.h>
#include <stdio.h>
#include <sys/wait.h>

static void capture_and_restore(void) {
    signal(SIGINT, SIG_IGN);
    void *session = thc_signal_open(); assert(session != NULL);
    assert(thc_signal_open() == NULL && errno == EBUSY);
    assert(thc_signal_install(session, -4) == -1);
    union sigval value = {.sival_int = 731};
    assert(sigqueue(getpid(), SIGINT, value) == 0);
    siginfo_t info;
    assert(thc_signal_take(session, &info) == 1);
    assert(info.si_signo == SIGINT && info.si_code == SI_QUEUE);
    assert(info.si_pid == getpid() && info.si_uid == getuid() && info.si_value.sival_int == 731);
    assert(thc_signal_install(session, -2) == -4);
    assert(raise(SIGINT) == 0);
    assert(thc_signal_install(session, -5) == -2);
    assert(raise(SIGINT) == 0);
    assert(thc_signal_take(session, &info) == 1 && info.si_signo == SIGINT);
    struct sigaction current;
    assert(sigaction(SIGINT, NULL, &current) == 0 && current.sa_handler == SIG_DFL);
    assert(thc_signal_close(session) == 0);
    assert(sigaction(SIGINT, NULL, &current) == 0 && current.sa_handler == SIG_IGN);
    assert(raise(SIGINT) == 0);
    assert(thc_signal_open() == NULL && errno == EBUSY);
}
static void preserve_later_host_handler(void) {
    signal(SIGINT, SIG_DFL);
    void *session = thc_signal_open(); assert(session != NULL);
    assert(thc_signal_install(session, -4) == -1);
    signal(SIGINT, SIG_IGN);
    assert(thc_signal_close(session) == 0);
    struct sigaction current;
    assert(sigaction(SIGINT, NULL, &current) == 0 && current.sa_handler == SIG_IGN);
}
static void second_interrupt_terminates(void) {
    void *session = thc_signal_open(); assert(session != NULL);
    assert(thc_signal_install(session, -5) == -1);
    assert(raise(SIGINT) == 0);
    siginfo_t info; assert(thc_signal_take(session, &info) == 1);
    raise(SIGINT); _exit(91);
}
static void signal_exit(void) { signal(SIGINT, SIG_IGN); thc_signal_exit(SIGINT); }
static int stop_or_continue_signal;
static void stop_or_continue_exit(void) { thc_signal_exit(stop_or_continue_signal); }
static void child(void (*body)(void), int expected_signal) {
    pid_t pid = fork(); assert(pid >= 0);
    if (pid == 0) { alarm(5); body(); _exit(0); }
    int status; assert(waitpid(pid, &status, 0) == pid);
    if (expected_signal > 0) assert(WIFSIGNALED(status) && WTERMSIG(status) == expected_signal);
    else assert(WIFEXITED(status) && WEXITSTATUS(status) == -expected_signal);
}
int main(void) {
    child(capture_and_restore, 0);
    child(preserve_later_host_handler, 0);
    child(second_interrupt_terminates, SIGINT);
    child(signal_exit, SIGINT);
    const int stopping[] = {SIGSTOP, SIGTSTP, SIGTTIN, SIGTTOU, SIGCONT};
    for (unsigned i = 0; i < sizeof(stopping) / sizeof(stopping[0]); ++i) {
        stop_or_continue_signal = stopping[i];
        child(stop_or_continue_exit, -255);
    }
    puts("9 isolated native signal controls passed");
    return 0;
}
