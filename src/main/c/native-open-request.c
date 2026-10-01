// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

#define _GNU_SOURCE 1
#define _DARWIN_C_SOURCE 1
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <pthread.h>
#include <signal.h>
#include <sched.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/syscall.h>
#include <sys/stat.h>
#if defined(__linux__)
#include <sys/eventfd.h>
#elif defined(__APPLE__)
#include <mach/mach.h>
#include <mach/thread_info.h>
#if defined(__aarch64__)
#include <mach/arm/thread_status.h>
#else
#include <mach/i386/thread_status.h>
// The public SDK omits XNU syscall_sw.h. Its x86_64 UNIX trap class is
// SYSCALL_CLASS_UNIX (2) << SYSCALL_CLASS_SHIFT (24); SYS_openat comes from SDK.
#define DARWIN_UNIX_SYSCALL(number) ((2 << 24) | (number))
#endif
#endif
#if defined(__APPLE__)
#include <sys/ucontext.h>
#else
#include <ucontext.h>
#endif
#include <unistd.h>

#if defined(__linux__) && defined(__x86_64__)
_Static_assert(sizeof(int) == 4 && sizeof(mode_t) == 4 && sizeof(void *) == 8,
               "open request requires Linux LP64");
_Static_assert(SYS_openat == 257 && AT_FDCWD == -100 && EINTR == 4,
               "guard assembly must agree with the selected Linux ABI");
#define OPEN_SIGNAL SIGRTMIN
#elif defined(__APPLE__) && (defined(__x86_64__) || defined(__aarch64__))
_Static_assert(sizeof(int) == 4 && sizeof(mode_t) == 2 && sizeof(void *) == 8,
               "open request requires Darwin LP64");
#define OPEN_SIGNAL SIGUSR1
#else
#error "The owned open request requires Linux x86_64 or Darwin x86_64/arm64"
#endif
#define STRING_VALUE_(value) #value
#define STRING_VALUE(value) STRING_VALUE_(value)
_Static_assert(ATOMIC_INT_LOCK_FREE == 2, "signal cancellation flag must be lock free");
_Static_assert(sizeof(_Atomic int) == 4, "guard cmpl requires a four-byte cancellation flag");

// Only the owned native worker enters this function. The signal handler can
// cancel the check-to-syscall interval, including the check/entry race. The end
// label is IMMEDIATELY after syscall: the kernel resumes there when an operation has
// completed (including successful fd acquisition). Never discard that result.
// This is machine code, never Sulong code, and never runs on a JVM thread.
extern long thc_open_guard(const _Atomic int *, const char *, int, uint32_t, int);
extern char thc_open_guard_start[], thc_open_guard_end[], thc_open_guard_cancel[];
#if defined(__linux__)
__asm__(".text\n"
        ".hidden thc_open_guard\n.type thc_open_guard,@function\n"
        "thc_open_guard:\n"
        ".global thc_open_guard_start\n.hidden thc_open_guard_start\n"
        "thc_open_guard_start:\n"
        "cmpl $0,(%rdi)\n"
        "jne thc_open_guard_cancel\n"
        "mov %ecx,%r10d\n"
        "mov %r8d,%edi\n"
        "mov $257,%eax\n"
        "syscall\n"
        ".global thc_open_guard_end\n.hidden thc_open_guard_end\n"
        "thc_open_guard_end:\nret\n"
        ".global thc_open_guard_cancel\n.hidden thc_open_guard_cancel\n"
        "thc_open_guard_cancel:\nmov $-4,%rax\nret\n"
        ".size thc_open_guard,.-thc_open_guard\n");

#elif defined(__aarch64__)
// Darwin returns a positive errno with carry set. The SDK selects the syscall.
__asm__(".text\n.p2align 2\n.private_extern _thc_open_guard\n_thc_open_guard:\n"
        ".globl _thc_open_guard_start\n.private_extern _thc_open_guard_start\n_thc_open_guard_start:\n"
        "ldr w9,[x0]\ncbnz w9,Lthc_open_guard_cancel\nsxtw x0,w4\n"
        "mov x16,#" STRING_VALUE(SYS_openat) "\nsvc #0x80\n"
        ".globl _thc_open_guard_end\n.private_extern _thc_open_guard_end\n_thc_open_guard_end:\n"
        "b.cc 1f\nneg x0,x0\n1: ret\n"
        ".globl _thc_open_guard_cancel\n.private_extern _thc_open_guard_cancel\n_thc_open_guard_cancel:\nLthc_open_guard_cancel:\n"
        "mov x0,#-" STRING_VALUE(EINTR) "\nret\n");
#else
__asm__(".text\n.private_extern _thc_open_guard\n_thc_open_guard:\n"
        ".globl _thc_open_guard_start\n.private_extern _thc_open_guard_start\n_thc_open_guard_start:\n"
        "cmpl $0,(%rdi)\njne _thc_open_guard_cancel\nmov %ecx,%r10d\nmov %r8d,%edi\n"
        "mov $" STRING_VALUE(DARWIN_UNIX_SYSCALL(SYS_openat)) ",%eax\nsyscall\n"
        ".globl _thc_open_guard_end\n.private_extern _thc_open_guard_end\n_thc_open_guard_end:\n"
        "jnb 1f\nneg %rax\n1: ret\n"
        ".globl _thc_open_guard_cancel\n.private_extern _thc_open_guard_cancel\n_thc_open_guard_cancel:\n"
        "mov $-" STRING_VALUE(EINTR) ",%rax\nret\n");
#endif

static pthread_mutex_t signal_lock = PTHREAD_MUTEX_INITIALIZER;
static int claimed_signal;
static struct sigaction claimed_action;

static void interrupt_open(int signal, siginfo_t *info, void *raw_context) {
  (void)signal;
  (void)info;
  ucontext_t *context = raw_context;
#if defined(__linux__)
  uintptr_t pc = (uintptr_t)context->uc_mcontext.gregs[REG_RIP];
  if (pc >= (uintptr_t)thc_open_guard_start && pc < (uintptr_t)thc_open_guard_end)
    context->uc_mcontext.gregs[REG_RIP] = (greg_t)(uintptr_t)thc_open_guard_cancel;
#elif defined(__aarch64__)
  uintptr_t pc = arm_thread_state64_get_pc(context->uc_mcontext->__ss);
  if (pc >= (uintptr_t)thc_open_guard_start && pc < (uintptr_t)thc_open_guard_end)
    arm_thread_state64_set_pc_fptr(context->uc_mcontext->__ss, (void (*)(void))thc_open_guard_cancel);
#else
  uintptr_t pc = context->uc_mcontext->__ss.__rip;
  if (pc >= (uintptr_t)thc_open_guard_start && pc < (uintptr_t)thc_open_guard_end)
    context->uc_mcontext->__ss.__rip = (uintptr_t)thc_open_guard_cancel;
#endif
}

// Claim only an unused application signal (RT on Linux, SIGUSR1 on Darwin). Never replace a host handler, or
// reinstall ours if a host subsequently changes it. As with the process signal
// bridge, an embedding host must not concurrently mutate an owned disposition.
// Keep the handler mapped/installed for process lifetime: it references no
// request/TLS storage, so even a late signal cannot touch freed request memory.
static int own_signal(void) {
  int error = pthread_mutex_lock(&signal_lock);
  if (error) return error;
  int signal = OPEN_SIGNAL;
  struct sigaction action;
  if (sigaction(signal, NULL, &action) < 0) error = errno;
  else if (claimed_signal) {
    if (action.sa_sigaction != claimed_action.sa_sigaction || action.sa_flags != claimed_action.sa_flags) error = EBUSY;
#if defined(__linux__)
    if (action.sa_restorer != claimed_action.sa_restorer) error = EBUSY;
#endif
    for (int member = 1; member < NSIG && !error; ++member)
      if (sigismember(&action.sa_mask, member) != sigismember(&claimed_action.sa_mask, member)) error = EBUSY;
  } else if (action.sa_handler != SIG_DFL) error = EBUSY;
  else {
    memset(&action, 0, sizeof(action));
    action.sa_sigaction = interrupt_open;
    action.sa_flags = SA_SIGINFO;
    sigemptyset(&action.sa_mask);
    if (sigaction(signal, &action, NULL) < 0) error = errno;
    else {
      // Installation has already changed process state. Track the claim even
      // if readback fails; do not forget it or blindly restore over a host's
      // possible later change. A failed readback starts no worker and leaves
      // subsequent exact ownership checks conservative.
      claimed_signal = signal;
      claimed_action = action;
      if (sigaction(signal, NULL, &claimed_action) < 0) error = errno;
    }
  }
  pthread_mutex_unlock(&signal_lock);
  return error;
}

struct open_request {
  pthread_t worker;
  _Atomic int cancelled;
  _Atomic int done;
  int event;
  int event_write;
  int fd;
  int error;
  char *path;
  int flags;
  int directory;
  uint32_t mode;
};

#ifdef THC_OPEN_REQUEST_TEST
// Compiled only into the isolated native oracle, never the runtime library.
// Pause at the two ownership boundaries without changing syscall/handler code.
static _Atomic int test_pause;
static _Atomic int test_stage;
void thc_open_test_pause(int stage) {
  atomic_store(&test_stage, 0);
  atomic_store(&test_pause, stage);
}
int thc_open_test_stage(void) { return atomic_load(&test_stage); }
static void test_boundary(int stage) {
  if (atomic_load(&test_pause) == stage) {
    atomic_store(&test_stage, stage);
    while (atomic_load(&test_pause) == stage) sched_yield();
  }
}
void thc_open_test_disposition(int ignore) {
  struct sigaction action;
  memset(&action, 0, sizeof(action));
  action.sa_handler = ignore ? SIG_IGN : SIG_DFL;
  sigemptyset(&action.sa_mask);
  sigaction(OPEN_SIGNAL, &action, NULL);
}
#endif

static void wake_request(struct open_request *request) {
  uint64_t one = 1;
  ssize_t written;
  do { written = write(request->event_write, &one, sizeof(one)); } while (written < 0 && errno == EINTR);
  // EAGAIN already means readable. The fd stays owned through unregister/join.
}

static void *open_worker(void *argument) {
  struct open_request *request = argument;
#if defined(__APPLE__)
  pthread_setname_np("thc-open");
#else
  pthread_setname_np(pthread_self(), "thc-open");
#endif
  sigset_t mask;
  sigfillset(&mask);
  sigdelset(&mask, claimed_signal);
  int error = pthread_sigmask(SIG_SETMASK, &mask, NULL);
#ifdef THC_OPEN_REQUEST_TEST
  test_boundary(1);
#endif
  long result = error ? -error : thc_open_guard(&request->cancelled, request->path, request->flags, request->mode, request->directory);
#ifdef THC_OPEN_REQUEST_TEST
  test_boundary(2);
#endif
  request->fd = result >= 0 ? (int)result : -1;
  request->error = result < 0 ? (int)-result : 0;
  atomic_store_explicit(&request->done, 1, memory_order_release);
  wake_request(request);
  return NULL;
}

void *thc_open_start_at(const char *path, int flags, uint32_t mode, int directory, int *error) {
  *error = own_signal();
  if (*error) return NULL;
  struct open_request *request = calloc(1, sizeof(*request));
  if (!request) { *error = ENOMEM; return NULL; }
  request->event = request->event_write = -1;
  request->fd = -1;
  atomic_init(&request->cancelled, 0);
  atomic_init(&request->done, 0);
  request->path = strdup(path);
  if (!request->path) { *error = ENOMEM; goto failed; }
#if defined(__linux__)
  request->event = eventfd(0, EFD_CLOEXEC | EFD_NONBLOCK);
  request->event_write = request->event;
  if (request->event < 0) { *error = errno; goto failed; }
#else
  int wake[2];
  if (pipe(wake)) { *error = errno; goto failed; }
  request->event = wake[0]; request->event_write = wake[1];
  for (int i = 0; i < 2; ++i)
    if (fcntl(wake[i], F_SETFD, FD_CLOEXEC) < 0 ||
        fcntl(wake[i], F_SETFL, O_NONBLOCK) < 0) { *error = errno; goto failed; }
#endif
  request->flags = flags;
  request->directory = directory;
  request->mode = mode;
  *error = pthread_create(&request->worker, NULL, open_worker, request);
  if (*error) goto failed;
  return request;
failed:
  if (request->event_write >= 0 && request->event_write != request->event) close(request->event_write);
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
#if defined(__APPLE__)
  while (count > 0) {
    do { count = read(request->event, &value, sizeof(value)); } while (count < 0 && errno == EINTR);
  }
#endif
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
  if (request->event_write != request->event) close(request->event_write);
  close(request->event);
  free(request->path);
  free(request);
  return error;
}

// Existing isolated native oracle entry; runtime supplies an owned directory.
void *thc_open_start(const char *path, int flags, uint32_t mode, int *error) {
  return thc_open_start_at(path, flags, mode, AT_FDCWD, error);
}

// Read-only physical observations. These inspect kernel threads/descriptors,
// never a request registry or a self-reported completion counter.
long thc_open_observe(int kind, const char *path) {
#if defined(__APPLE__)
  if (kind == 0 || kind == 1) {
    thread_act_array_t threads; mach_msg_type_number_t count;
    if (task_threads(mach_task_self(), &threads, &count) != KERN_SUCCESS) return -EIO;
    long found = 0;
    for (mach_msg_type_number_t i = 0; i < count; ++i) {
      thread_extended_info_data_t info; mach_msg_type_number_t size = THREAD_EXTENDED_INFO_COUNT;
      if (thread_info(threads[i], THREAD_EXTENDED_INFO, (thread_info_t)&info, &size) == KERN_SUCCESS &&
          !strcmp(info.pth_name, "thc-open")) {
        if (kind == 0) ++found;
        else if (info.pth_run_state == TH_STATE_WAITING) {
#if defined(__aarch64__)
          arm_thread_state64_t state; size = ARM_THREAD_STATE64_COUNT;
          kern_return_t result = thread_get_state(threads[i], ARM_THREAD_STATE64, (thread_state_t)&state, &size);
          uintptr_t pc = result == KERN_SUCCESS ? arm_thread_state64_get_pc(state) : 0;
#else
          x86_thread_state64_t state; size = x86_THREAD_STATE64_COUNT;
          kern_return_t result = thread_get_state(threads[i], x86_THREAD_STATE64, (thread_state_t)&state, &size);
          uintptr_t pc = result == KERN_SUCCESS ? state.__rip : 0;
#endif
          if (pc >= (uintptr_t)thc_open_guard_start && pc <= (uintptr_t)thc_open_guard_end) ++found;
        }
      }
      mach_port_deallocate(mach_task_self(), threads[i]);
    }
    vm_deallocate(mach_task_self(), (vm_address_t)threads, count * sizeof(*threads));
    return found;
  }
#else
  if (kind == 0 || kind == 1) return -ENOTSUP;
#endif
  struct stat wanted;
  if (path && stat(path, &wanted)) return -errno;
  long found = 0;
  for (int fd = 0, limit = getdtablesize(); fd < limit; ++fd) {
    struct stat actual;
    if (!fstat(fd, &actual) && (!path || (actual.st_dev == wanted.st_dev && actual.st_ino == wanted.st_ino))) ++found;
  }
  return found;
}
