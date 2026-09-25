// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

#define _GNU_SOURCE 1
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <pthread.h>
#include <signal.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/eventfd.h>
#include <sys/syscall.h>
#include <ucontext.h>
#include <unistd.h>

#if !defined(__linux__) || !defined(__x86_64__)
#error "The owned open request requires Linux x86_64"
#endif
_Static_assert(sizeof(int) == 4 && sizeof(mode_t) == 4 && sizeof(void *) == 8,
               "open request requires Linux LP64");
_Static_assert(SYS_openat == 257 && AT_FDCWD == -100 && EINTR == 4,
               "guard assembly must agree with the selected Linux ABI");
_Static_assert(ATOMIC_INT_LOCK_FREE == 2, "signal cancellation flag must be lock free");

// Only the owned native worker enters this function. The signal handler can
// cancel the check-to-syscall interval, including the check/entry race. The end
// label is IMMEDIATELY after syscall: Linux resumes there when an operation has
// completed (including successful fd acquisition). Never discard that result.
// This is machine code, never Sulong code, and never runs on a JVM thread.
extern long thc_open_guard(const _Atomic int *, const char *, int, uint32_t);
extern char thc_open_guard_start[], thc_open_guard_end[], thc_open_guard_cancel[];
__asm__(".text\n"
        ".hidden thc_open_guard\n.type thc_open_guard,@function\n"
        "thc_open_guard:\n"
        ".global thc_open_guard_start\n.hidden thc_open_guard_start\n"
        "thc_open_guard_start:\n"
        "cmpl $0,(%rdi)\n"
        "jne thc_open_guard_cancel\n"
        "mov %ecx,%r10d\n"
        "mov $-100,%edi\n"
        "mov $257,%eax\n"
        "syscall\n"
        ".global thc_open_guard_end\n.hidden thc_open_guard_end\n"
        "thc_open_guard_end:\nret\n"
        ".global thc_open_guard_cancel\n.hidden thc_open_guard_cancel\n"
        "thc_open_guard_cancel:\nmov $-4,%rax\nret\n"
        ".size thc_open_guard,.-thc_open_guard\n");

static pthread_mutex_t signal_lock = PTHREAD_MUTEX_INITIALIZER;
static int claimed_signal;

static void interrupt_open(int signal, siginfo_t *info, void *raw_context) {
  (void)signal;
  (void)info;
  ucontext_t *context = raw_context;
  uintptr_t pc = (uintptr_t)context->uc_mcontext.gregs[REG_RIP];
  if (pc >= (uintptr_t)thc_open_guard_start && pc < (uintptr_t)thc_open_guard_end)
    context->uc_mcontext.gregs[REG_RIP] = (greg_t)(uintptr_t)thc_open_guard_cancel;
}

// Claim only an unused application RT signal. Never replace a host handler, or
// reinstall ours if a host subsequently changes it. As with the process signal
// bridge, an embedding host must not concurrently mutate an owned disposition.
// Keep the handler mapped/installed for process lifetime: it references no
// request/TLS storage, so even a late signal cannot touch freed request memory.
static int own_signal(void) {
  int error = pthread_mutex_lock(&signal_lock);
  if (error) return error;
  int signal = SIGRTMIN;
  struct sigaction action;
  if (sigaction(signal, NULL, &action) < 0) error = errno;
  else if (claimed_signal) {
    if (!(action.sa_flags & SA_SIGINFO) || action.sa_sigaction != interrupt_open ||
        (action.sa_flags & SA_RESTART)) error = EBUSY;
  } else if (action.sa_handler != SIG_DFL) error = EBUSY;
  else {
    memset(&action, 0, sizeof(action));
    action.sa_sigaction = interrupt_open;
    action.sa_flags = SA_SIGINFO;
    sigemptyset(&action.sa_mask);
    if (sigaction(signal, &action, NULL) < 0) error = errno;
    else claimed_signal = signal;
  }
  pthread_mutex_unlock(&signal_lock);
  return error;
}

struct open_request {
  pthread_t worker;
  _Atomic int cancelled;
  _Atomic int done;
  int event;
  int fd;
  int error;
  char *path;
  int flags;
  uint32_t mode;
};

static void wake_request(struct open_request *request) {
  uint64_t one = 1;
  ssize_t written;
  do { written = write(request->event, &one, sizeof(one)); } while (written < 0 && errno == EINTR);
  // EAGAIN already means readable. The fd stays owned through unregister/join.
}

static void *open_worker(void *argument) {
  struct open_request *request = argument;
  pthread_setname_np(pthread_self(), "thc-open");
  sigset_t mask;
  sigfillset(&mask);
  sigdelset(&mask, claimed_signal);
  int error = pthread_sigmask(SIG_SETMASK, &mask, NULL);
  long result = error ? -error : thc_open_guard(&request->cancelled, request->path, request->flags, request->mode);
  request->fd = result >= 0 ? (int)result : -1;
  request->error = result < 0 ? (int)-result : 0;
  atomic_store_explicit(&request->done, 1, memory_order_release);
  wake_request(request);
  return NULL;
}

void *thc_open_start(const char *path, int flags, uint32_t mode, int *error) {
  *error = own_signal();
  if (*error) return NULL;
  struct open_request *request = calloc(1, sizeof(*request));
  if (!request) { *error = ENOMEM; return NULL; }
  request->event = -1;
  request->fd = -1;
  request->path = strdup(path);
  if (!request->path) { *error = ENOMEM; goto failed; }
  request->event = eventfd(0, EFD_CLOEXEC | EFD_NONBLOCK);
  if (request->event < 0) { *error = errno; goto failed; }
  request->flags = flags;
  request->mode = mode;
  *error = pthread_create(&request->worker, NULL, open_worker, request);
  if (*error) goto failed;
  return request;
failed:
  if (request->event >= 0) close(request->event);
  free(request->path);
  free(request);
  return NULL;
}

int thc_open_done(struct open_request *request) {
  return atomic_load_explicit(&request->done, memory_order_acquire);
}

// Called only on owned requests. A successful syscall always wins: cancellation
// never closes its result or substitutes EINTR. The guest wrapper decides where
// its still-pending asynchronous exception may subsequently be delivered.
int thc_open_cancel(struct open_request *request) {
  if (thc_open_done(request)) return 0;
  int error = own_signal();
  if (error) return error;
  atomic_store_explicit(&request->cancelled, 1, memory_order_release);
  error = pthread_kill(request->worker, claimed_signal);
  return error && !thc_open_done(request) ? error : 0;
}

void thc_open_wake(struct open_request *request) { wake_request(request); }
void thc_open_reset(struct open_request *request) {
  uint64_t value;
  ssize_t count;
  do { count = read(request->event, &value, sizeof(value)); } while (count < 0 && errno == EINTR);
}

// Poll is the repeatable part, never acquisition. 1 = wake/completion, 0 = EINTR,
// negative = host errno. No native fd is exposed to the guest or Java caller.
int thc_open_wait(struct open_request *request) {
  struct pollfd pollfd = {request->event, POLLIN, 0};
  int result = poll(&pollfd, 1, -1);
  return result >= 0 ? 1 : errno == EINTR ? 0 : -errno;
}

// Caller has unregistered its Truffle interrupter. Join before freeing either
// the request or pathname. Passing no lease aborts and closes any acquired fd.
// A non-null lease receives ownership exactly once, after the native join.
int thc_open_finish(struct open_request *request, int *lease) {
  int error = pthread_join(request->worker, NULL);
  if (error) return -error; // Caller must retain the request on this contract violation.
  error = request->error;
  if (request->fd >= 0) {
    if (lease) *lease = request->fd;
    else close(request->fd);
  }
  close(request->event);
  free(request->path);
  free(request);
  return error;
}
