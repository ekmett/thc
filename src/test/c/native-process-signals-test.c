// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
/* Each control owns a child process. The test runner never handles signals. */
#include "../../main/c/native-process-signal-api.c"
#include <assert.h>
#include <stdio.h>
#include <sys/resource.h>
#include <sys/wait.h>

static int selected_signal = SIGINT;

static void capture_and_restore(void) {
    signal(selected_signal, SIG_IGN);
    void *session = thc_signal_open(); assert(session != NULL);
    assert(thc_signal_open() == NULL && errno == EBUSY);
    assert(thc_signal_install(session, selected_signal, -4) == -1);
    union sigval value = {.sival_int = 731};
    assert(sigqueue(getpid(), selected_signal, value) == 0);
    siginfo_t info;
    assert(thc_signal_take(session, &info) == 1);
    assert(thc_signal_number(&info) == selected_signal && info.si_code == SI_QUEUE);
    assert(info.si_pid == getpid() && info.si_uid == getuid() && info.si_value.sival_int == 731);
    assert(thc_signal_install(session, selected_signal, -2) == -4);
    assert(raise(selected_signal) == 0);
    assert(thc_signal_install(session, selected_signal, -5) == -2);
    assert(raise(selected_signal) == 0);
    assert(thc_signal_take(session, &info) == 1 && info.si_signo == selected_signal);
    struct sigaction current;
    assert(sigaction(selected_signal, NULL, &current) == 0 && current.sa_handler == SIG_DFL);
    assert(thc_signal_close(session) == 0);
    assert(sigaction(selected_signal, NULL, &current) == 0 && current.sa_handler == SIG_IGN);
    assert(raise(selected_signal) == 0);
    assert(thc_signal_open() == NULL && errno == EBUSY);
}
static void preserve_later_host_handler(void) {
    signal(selected_signal, SIG_DFL);
    void *session = thc_signal_open(); assert(session != NULL);
    assert(thc_signal_install(session, selected_signal, -4) == -1);
    signal(selected_signal, SIG_IGN);
    assert(thc_signal_close(session) == 0);
    struct sigaction current;
    assert(sigaction(selected_signal, NULL, &current) == 0 && current.sa_handler == SIG_IGN);
}
static void second_interrupt_terminates(void) {
    void *session = thc_signal_open(); assert(session != NULL);
    assert(thc_signal_install(session, selected_signal, -5) == -1);
    assert(raise(selected_signal) == 0);
    siginfo_t info; assert(thc_signal_take(session, &info) == 1);
    raise(selected_signal); _exit(91);
}
static void signal_exit(void) { signal(selected_signal, SIG_IGN); thc_signal_exit(selected_signal); }
static void independent_signals(void) {
    for (unsigned i = 0; i < SIGNAL_COUNT; ++i) signal(supported_signals[i], SIG_IGN);
    void *session = thc_signal_open(); assert(session != NULL);
    for (unsigned i = 0; i < SIGNAL_COUNT; ++i)
        assert(thc_signal_install(session, supported_signals[i], -4) == -1);
    for (unsigned i = 0; i < SIGNAL_COUNT; ++i) {
        union sigval value = {.sival_int = 900 + (int)i};
        assert(sigqueue(getpid(), supported_signals[i], value) == 0);
        siginfo_t info; assert(thc_signal_take(session, &info) == 1);
        assert(thc_signal_number(&info) == supported_signals[i]);
        assert(info.si_value.sival_int == 900 + (int)i);
    }
    for (unsigned i = 0; i < SIGNAL_COUNT; ++i)
        assert(thc_signal_install(session, supported_signals[i], -2) == -4);
    assert(thc_signal_close(session) == 0);
    for (unsigned i = 0; i < SIGNAL_COUNT; ++i) {
        struct sigaction current;
        assert(sigaction(supported_signals[i], NULL, &current) == 0 && current.sa_handler == SIG_IGN);
    }
}
static void unsupported_signal_rejected(void) {
    void *session = thc_signal_open(); assert(session != NULL);
    assert(thc_signal_install(session, SIGUSR1, -4) == -3 && errno == EINVAL);
    assert(thc_signal_install(session, SIGSEGV, -4) == -3 && errno == EINVAL);
    assert(thc_signal_install(session, SIGINT, 42) == -3 && errno == EINVAL);
    assert(thc_signal_close(session) == 0);
}
static void uninstalled_signal_is_not_restored(void) {
    signal(SIGHUP, SIG_DFL);
    void *session = thc_signal_open(); assert(session != NULL);
    signal(SIGHUP, SIG_IGN);
    assert(thc_signal_close(session) == 0);
    struct sigaction current;
    assert(sigaction(SIGHUP, NULL, &current) == 0 && current.sa_handler == SIG_IGN);
}
static void first_install_preserves_current_host_handler(void) {
    signal(SIGHUP, SIG_DFL);
    void *session = thc_signal_open(); assert(session != NULL);
    assert(thc_signal_install(session, SIGINT, -4) == -1);
    signal(SIGHUP, SIG_IGN);
    assert(thc_signal_install(session, SIGHUP, -4) == -1);
    assert(thc_signal_close(session) == 0);
    struct sigaction current;
    assert(sigaction(SIGHUP, NULL, &current) == 0 && current.sa_handler == SIG_IGN);
}
static int stop_or_continue_signal;
static void stop_or_continue_exit(void) { thc_signal_exit(stop_or_continue_signal); }
static void child(void (*body)(void), int expected_signal) {
    pid_t pid = fork(); assert(pid >= 0);
    if (pid == 0) {
        struct rlimit no_core = {0, 0};
        assert(setrlimit(RLIMIT_CORE, &no_core) == 0);
        alarm(5); body(); _exit(0);
    }
    int status; assert(waitpid(pid, &status, 0) == pid);
    if (expected_signal > 0) assert(WIFSIGNALED(status) && WTERMSIG(status) == expected_signal);
    else assert(WIFEXITED(status) && WEXITSTATUS(status) == -expected_signal);
}
int main(void) {
    for (unsigned i = 0; i < SIGNAL_COUNT; ++i) {
        selected_signal = supported_signals[i];
        child(capture_and_restore, 0);
        child(preserve_later_host_handler, 0);
        child(second_interrupt_terminates, selected_signal);
        child(signal_exit, selected_signal);
    }
    child(independent_signals, 0);
    child(unsupported_signal_rejected, 0);
    child(uninstalled_signal_is_not_restored, 0);
    child(first_install_preserves_current_host_handler, 0);
    const int stopping[] = {SIGSTOP, SIGTSTP, SIGTTIN, SIGTTOU, SIGCONT};
    for (unsigned i = 0; i < sizeof(stopping) / sizeof(stopping[0]); ++i) {
        stop_or_continue_signal = stopping[i];
        child(stop_or_continue_exit, -255);
    }
    puts("25 isolated native signal controls passed");
    return 0;
}
