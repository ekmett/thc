// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
#define _GNU_SOURCE
#define _DARWIN_C_SOURCE 1
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <sched.h>
#include <signal.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#if defined(__linux__)
#include <sys/eventfd.h>
#endif
#include <unistd.h>

/* This library is loaded as machine code, never as Sulong bitcode. The handler
 * cannot enter Java, allocate memory, acquire a lock or call guest code. */
_Static_assert(ATOMIC_INT_LOCK_FREE == 2, "signal atomics must be lock free");
_Static_assert(sizeof(siginfo_t) <= 512, "one signal record must fit PIPE_BUF");
static atomic_int signal_fd = -1, active_handlers, overflow;
static atomic_int owned;
static const int supported_signals[] = {SIGHUP, SIGINT, SIGQUIT, SIGUSR1, SIGUSR2, SIGTERM, SIGXCPU, SIGXFSZ};
#define SIGNAL_COUNT (sizeof(supported_signals) / sizeof(supported_signals[0]))
struct signal_session {
    int read_fd, write_fd, wake_fd, wake_write_fd;
    int action[SIGNAL_COUNT], installed[SIGNAL_COUNT];
    struct sigaction previous[SIGNAL_COUNT];
};

static void capture(int signal_number, siginfo_t *info, void *context) {
    (void)signal_number; (void)context;
    int saved_errno = errno;
    atomic_fetch_add_explicit(&active_handlers, 1, memory_order_seq_cst);
    int fd = atomic_load_explicit(&signal_fd, memory_order_seq_cst);
    if (fd >= 0 && write(fd, info, sizeof(*info)) != sizeof(*info))
        atomic_store_explicit(&overflow, 1, memory_order_seq_cst);
    atomic_fetch_sub_explicit(&active_handlers, 1, memory_order_seq_cst);
    errno = saved_errno;
}

int thc_signal_info_size(void) { return sizeof(siginfo_t); }
int thc_signal_number(const void *info) { return ((const siginfo_t *)info)->si_signo; }
int thc_signal_constant(int index) {
    if (index == 0) return NSIG;
    if (index >= 1 && index <= (int)SIGNAL_COUNT) return supported_signals[index - 1];
    if (index == (int)SIGNAL_COUNT + 1) return SIGBUS;
    if (index == (int)SIGNAL_COUNT + 2) return SIGSEGV;
    return -1;
}

static int vm_handler(const struct sigaction *action) {
    Dl_info image;
    void *handler = action->sa_flags & SA_SIGINFO ? (void *)action->sa_sigaction : (void *)action->sa_handler;
    if (action->sa_handler == SIG_DFL || action->sa_handler == SIG_IGN ||
        !dladdr(handler, &image) || !image.dli_fname)
        return 0;
    const char *name = strrchr(image.dli_fname, '/');
#if defined(__APPLE__) && defined(__MACH__)
    return strcmp(name ? name + 1 : image.dli_fname, "libjvm.dylib") == 0;
#else
    return strcmp(name ? name + 1 : image.dli_fname, "libjvm.so") == 0;
#endif
}

/* HotSpot's suspend handler does not chain. The standalone Linux launcher must
 * move it before JVM startup; environment text alone is not evidence that the
 * current VM released USR2. Signal 64 remains excluded from the guest set.
 * Interposers hiding the real VM dispositions conservatively fail this check. */
int thc_signal_usr2_available(void) {
#if defined(__linux__)
    const char *setting = getenv("_JAVA_SR_SIGNUM");
    struct sigaction reserved, guest;
    return setting && strcmp(setting, "64") == 0 &&
        sigaction(64, NULL, &reserved) == 0 && vm_handler(&reserved) &&
        sigaction(SIGUSR2, NULL, &guest) == 0 && !vm_handler(&guest);
#else
    /* No legal, verified HotSpot suspend-signal relocation is established here. */
    return 0;
#endif
}

static int nonblocking_pipe(int descriptors[2]) {
#if defined(__linux__)
    return pipe2(descriptors, O_NONBLOCK | O_CLOEXEC);
#else
    if (pipe(descriptors)) return -1;
    for (int i = 0; i < 2; ++i) {
        int flags = fcntl(descriptors[i], F_GETFL);
        if (flags < 0 || fcntl(descriptors[i], F_SETFL, flags | O_NONBLOCK) < 0 ||
            fcntl(descriptors[i], F_SETFD, FD_CLOEXEC) < 0) {
            int error = errno;
            close(descriptors[0]); close(descriptors[1]);
            descriptors[0] = descriptors[1] = -1; errno = error; return -1;
        }
    }
    return 0;
#endif
}

void *thc_signal_open(void) {
    int expected = 0;
    if (!atomic_compare_exchange_strong(&owned, &expected, 1)) { errno = EBUSY; return NULL; }
    struct signal_session *s = calloc(1, sizeof(*s));
    int pipes[2] = {-1, -1};
    if (s == NULL) goto failed;
    s->wake_fd = s->wake_write_fd = -1;
    for (unsigned i = 0; i < SIGNAL_COUNT; ++i) s->action[i] = -1;
    if (nonblocking_pipe(pipes) != 0) goto failed;
#if defined(__linux__)
    s->wake_fd = eventfd(0, EFD_NONBLOCK | EFD_CLOEXEC);
    if (s->wake_fd < 0) goto failed;
    s->wake_write_fd = s->wake_fd;
#else
    int wake[2] = {-1, -1};
    if (nonblocking_pipe(wake) != 0) goto failed;
    s->wake_fd = wake[0]; s->wake_write_fd = wake[1];
#endif
    s->read_fd = pipes[0]; s->write_fd = pipes[1];
    atomic_store(&overflow, 0);
    atomic_store_explicit(&signal_fd, s->write_fd, memory_order_seq_cst);
    return s;
failed:;
    int error = errno;
    if (pipes[0] >= 0) close(pipes[0]);
    if (pipes[1] >= 0) close(pipes[1]);
    if (s != NULL && s->wake_fd >= 0) close(s->wake_fd);
    if (s != NULL && s->wake_write_fd >= 0 && s->wake_write_fd != s->wake_fd) close(s->wake_write_fd);
    free(s); atomic_store(&owned, 0); errno = error; return NULL;
}

/* Exact GHC STG_SIG_* constants. The logical old action initially is DFL,
 * independently of the embedding JVM disposition saved for restoration. */
int thc_signal_install(void *session, int signal_number, int action) {
    struct signal_session *s = session;
    unsigned slot = 0;
    while (slot < SIGNAL_COUNT && supported_signals[slot] != signal_number) ++slot;
    if (slot == SIGNAL_COUNT) { errno = EINVAL; return -3; }
#if defined(__APPLE__) && defined(__MACH__)
    /* Never overwrite Darwin HotSpot's active non-chaining/reserved dispositions.
       SIGINT is the explicit launcher-owned GHC interrupt contract. Linux keeps
       its established -Xrs/USR2 admission, including its replaceable XFSZ handler. */
    struct sigaction current;
    if (sigaction(signal_number, NULL, &current)) return -3;
    if (signal_number != SIGINT && vm_handler(&current)) { errno = EBUSY; return -3; }
#endif
    struct sigaction next = {0};
    sigemptyset(&next.sa_mask);
    switch (action) {
    case -1: next.sa_handler = SIG_DFL; break;
    case -2: next.sa_handler = SIG_IGN; break;
    case -4: next.sa_sigaction = capture; next.sa_flags = SA_SIGINFO; break;
    case -5: next.sa_sigaction = capture; next.sa_flags = SA_SIGINFO | SA_RESETHAND; break;
    default: errno = EINVAL; return -3;
    }
    /* Other signals may have changed since opening this session. Acquire the
     * immediately preceding disposition when first taking each signal. */
    if (sigaction(signal_number, &next, s->installed[slot] ? NULL : &s->previous[slot]) != 0)
        return -3;
    int old = s->action[slot];
    s->action[slot] = action; s->installed[slot] = 1; return old;
}

void thc_signal_wake(void *session) {
    struct signal_session *s = session;
    uint64_t one = 1;
    int saved_errno = errno;
    while (write(s->wake_write_fd, &one, sizeof(one)) < 0 && errno == EINTR) {}
    errno = saved_errno;
}
void thc_signal_reset_wake(void *session) {
    struct signal_session *s = session;
    uint64_t value;
    ssize_t count;
    do { count = read(s->wake_fd, &value, sizeof(value)); }
    while (count == sizeof(value) || (count < 0 && errno == EINTR));
}

/* 1: real siginfo, 0: wake/interrupted, -1: errno, -2: overflow. */
int thc_signal_take(void *session, void *info) {
    struct signal_session *s = session;
    if (atomic_load(&overflow)) return -2;
    struct pollfd descriptors[2] = {{s->read_fd, POLLIN, 0}, {s->wake_fd, POLLIN, 0}};
    int ready = poll(descriptors, 2, -1);
    if (ready < 0) return errno == EINTR ? 0 : -1;
    if (descriptors[1].revents) return 0;
    if (atomic_load(&overflow)) return -2;
    ssize_t count = read(s->read_fd, info, sizeof(siginfo_t));
    if (count == sizeof(siginfo_t)) return 1;
    if (count < 0 && (errno == EAGAIN || errno == EINTR)) return 0;
    errno = EIO; return -1;
}

/* Called after the reader has stopped. A handler only observes static storage:
 * even a previously scheduled handler that starts late cannot touch freed fds. */
int thc_signal_close(void *session) {
    struct signal_session *s = session;
    atomic_store_explicit(&signal_fd, -1, memory_order_seq_cst);
    int result = 0, error = 0;
    for (unsigned i = 0; i < SIGNAL_COUNT; ++i) {
        if (!s->installed[i]) continue;
        struct sigaction current;
        if (sigaction(supported_signals[i], NULL, &current) != 0) {
            result = -1; error = errno; continue;
        }
        int ours = (current.sa_flags & SA_SIGINFO) && current.sa_sigaction == capture;
        ours |= s->action[i] == -1 && current.sa_handler == SIG_DFL;
        ours |= s->action[i] == -2 && current.sa_handler == SIG_IGN;
        ours |= s->action[i] == -5 && current.sa_handler == SIG_DFL; /* consumed one-shot */
        if (ours && sigaction(supported_signals[i], &s->previous[i], NULL) != 0) {
            result = -1; error = errno;
        }
    }
    while (atomic_load_explicit(&active_handlers, memory_order_seq_cst) != 0) sched_yield();
    close(s->read_fd); close(s->write_fd); close(s->wake_fd);
    if (s->wake_write_fd != s->wake_fd) close(s->wake_write_fd);
    free(s);
    /* A launcher has one process lifetime. Never reuse the global handler
     * slot: a pending old handler could start after this close completed. */
    atomic_store(&owned, 2); errno = error; return result;
}

/* Launcher only, after its Truffle context has closed and restored handlers. */
_Noreturn void thc_signal_exit(int signal_number) {
    /* GHC shutdownSignal must terminate, including for stop/continue signals. */
    if (signal_number == SIGSTOP || signal_number == SIGTSTP || signal_number == SIGTTIN ||
        signal_number == SIGTTOU || signal_number == SIGCONT) _exit(255);
    struct sigaction action = {0};
    action.sa_handler = SIG_DFL;
    sigemptyset(&action.sa_mask);
    if (sigaction(signal_number, &action, NULL) == 0) {
        sigset_t mask; sigemptyset(&mask); sigaddset(&mask, signal_number);
        if (sigprocmask(SIG_UNBLOCK, &mask, NULL) == 0) raise(signal_number);
    }
    _exit(255);
}
