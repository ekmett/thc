// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

#define _GNU_SOURCE 1
#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <stddef.h>
#include <stdio.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/eventfd.h>
#include <sys/epoll.h>
#include <poll.h>
#include <termios.h>
#include <unistd.h>
#include <signal.h>
#include <sys/resource.h>
#include <sys/time.h>
#include <sys/times.h>
#include <sys/utsname.h>

/* NativeUnix's bounded caller-owned image transport uses this selected ABI. */
_Static_assert(sizeof(time_t) == 8 && sizeof(struct tms) == 32, "Unix time image ABI");
_Static_assert(sizeof(struct timeval) == 16 && sizeof(struct timespec) == 16, "Unix timestamp image ABI");
_Static_assert(sizeof(struct utsname) == 390 && sizeof(struct rlimit) == 16, "Unix query image ABI");
_Static_assert(sizeof(sigset_t) == 128 && sizeof(struct termios) == 60, "Unix signal/terminal image ABI");

// Private Linux provider transport. These are not original GHC FCall symbols.
// The fd slot belongs to a host lease allocated before acquisition. Once stored,
// host cleanup can close it without re-entering LLVM. Cancellation between libc
// acquisition and the slot store has not been proved safe by this checkpoint.
_Static_assert(sizeof(int) == 4 && sizeof(off_t) == 8 && sizeof(size_t) == 8 && sizeof(mode_t) == 4,
               "native files require the Linux LP64 ABI");
_Static_assert(sizeof(struct epoll_event) == 12 && offsetof(struct epoll_event, data) == 4 &&
               sizeof(struct pollfd) == 8 && offsetof(struct pollfd, revents) == 6,
               "native event images require the Linux x86_64 ABI");

int64_t thc_file_stat_size(void) { return sizeof(struct stat); }
int64_t thc_file_termios_size(void) { return sizeof(struct termios); }

// Acquired descriptors are published into host-owned lease slots before the
// call returns. Guest descriptor numbers are assigned separately by the context.
int64_t thc_file_eventfd(int *lease, uint32_t initial, int flags, int64_t *error) {
  int fd = eventfd(initial, flags);
  *error = fd < 0 ? errno : 0;
  if (fd >= 0) *lease = fd;
  return fd < 0 ? -1 : 0;
}

int64_t thc_file_epoll_create(int *lease, int size, int64_t *error) {
  int fd = epoll_create(size);
  *error = fd < 0 ? errno : 0;
  if (fd >= 0) *lease = fd;
  return fd < 0 ? -1 : 0;
}

int64_t thc_file_pipe(int *reader, int *writer, int64_t *error) {
  int descriptors[2];
  int result = pipe(descriptors);
  *error = result < 0 ? errno : 0;
  if (result == 0) { *reader = descriptors[0]; *writer = descriptors[1]; }
  return result;
}

int64_t thc_file_eventfd_write(const int *lease, uint64_t value, int64_t *error) {
  int result = eventfd_write(*lease, value);
  *error = result < 0 ? errno : 0;
  return result;
}

// The caller seeds the complete image. Preserve libc's actual writes (including
// unchanged padding and failure paths), rather than inventing an output image.
int64_t thc_file_tcgetattr(const int *lease, struct termios *image, int64_t *error) {
  int result = tcgetattr(*lease, image);
  *error = result < 0 ? errno : 0;
  return result;
}

int64_t thc_file_tcsetattr(const int *lease, int action, const struct termios *image, int64_t *error) {
  int result = tcsetattr(*lease, action, image);
  *error = result < 0 ? errno : 0;
  return result;
}

// Original unsafe open transport: no added flags, path decoding, type filter,
// retry, truncation deferral or RTS locking. The caller reserved its guest fd.
int64_t thc_file_open_raw(int *lease, const int *directory, const char *path, int flags, uint32_t mode, int64_t *error) {
  int fd = openat(*directory, path, flags, (mode_t)mode);
  *error = fd < 0 ? errno : 0;
  if (fd >= 0) *lease = fd;
  return fd < 0 ? -1 : 0;
}

int64_t thc_file_symlink(const int *directory, const char *target, const char *path, int64_t *error) {
  int result = symlinkat(target, *directory, path);
  *error = result < 0 ? errno : 0;
  return result;
}

int64_t thc_file_rename(const int *directory, const char *source, const char *destination, int64_t *error) {
  int result = renameat(*directory, source, *directory, destination);
  *error = result < 0 ? errno : 0;
  return result;
}

int64_t thc_file_readlink(const int *directory, const char *path, void *output, uint64_t capacity, int64_t *error) {
  ssize_t result = readlinkat(*directory, path, output, (size_t)capacity);
  *error = result < 0 ? errno : 0;
  // The genuine pinned Unix declaration returns CInt; validated capacity fits it.
  return (int32_t)result;
}

// The authenticated lease owns the host descriptor; no guest integer reaches libc.
int64_t thc_file_unlinkat(const int *lease, const char *path, int flags, int64_t *error) {
  int result = unlinkat(*lease, path, flags);
  *error = result < 0 ? errno : 0;
  return result;
}

// Preserve filename/flag error ordering for a missing guest descriptor. The
// constant -1 cannot name a host file; the caller guarantees a relative path.
int64_t thc_file_unlinkat_invalid(const char *path, int flags, int64_t *error) {
  int result = unlinkat(-1, path, flags);
  *error = result < 0 ? errno : 0;
  return result;
}


int64_t thc_file_access(const int *directory, const char *path, int mode, int64_t *error) {
  int result = faccessat(*directory, path, mode, 0);
  *error = result < 0 ? errno : 0;
  return result;
}

int64_t thc_file_mkdir(const int *directory, const char *path, uint32_t mode, int64_t *error) {
  int result = mkdirat(*directory, path, (mode_t)mode);
  *error = result < 0 ? errno : 0;
  return result;
}

int64_t thc_file_chmod(const int *directory, const char *path, uint32_t mode, int64_t *error) {
  int result = fchmodat(*directory, path, (mode_t)mode, 0);
  *error = result < 0 ? errno : 0;
  return result;
}

int64_t thc_file_unlink(const int *directory, const char *path, int64_t *error) {
  int result = unlinkat(*directory, path, 0);
  *error = result < 0 ? errno : 0;
  return result;
}

int64_t thc_file_open(int *lease, const int *directory, const char *path, int mode, int64_t *error) {
  int flags;
  switch (mode) {
    case 0: flags = O_RDONLY; break;
    case 1: flags = O_WRONLY | O_CREAT; break;
    case 2: flags = O_WRONLY | O_CREAT | O_APPEND; break;
    case 3: flags = O_RDWR | O_CREAT; break;
    default: *error = EINVAL; return -1;
  }
  // Do not hang opening a FIFO or acquire a controlling terminal before type
  // validation. O_NONBLOCK has no effect on the only admitted type: regular.
  int fd = openat(*directory, path, flags | O_CLOEXEC | O_NONBLOCK | O_NOCTTY, 0666);
  *error = fd < 0 ? errno : 0;
  if (fd < 0) return -1;
  *lease = fd;
  struct stat value;
  if (fstat(fd, &value) < 0) { *error = errno; return -1; }
  // -2 is explicit private-provider policy rejection, not a fabricated errno.
  // The host lease still owns cleanup of this successfully acquired resource.
  return S_ISREG(value.st_mode) ? 0 : -2;
}

// The caller holds the resource lease across both observation and acquisition.
int64_t thc_file_duplicate_inheritable(const int *lease, int64_t *error) {
  int flags = fcntl(*lease, F_GETFD);
  if (flags < 0) { *error = errno; return -1; }
  if (flags & FD_CLOEXEC) { *error = 0; return -1; }
  int fd = fcntl(*lease, F_DUPFD_CLOEXEC, 0);
  *error = fd < 0 ? errno : 0;
  return fd;
}

int64_t thc_file_standard(int *lease, int endpoint, int64_t *error) {
  if (endpoint < 0 || endpoint > 2) { *error = EINVAL; return -1; }
  int fd = fcntl(endpoint, F_DUPFD_CLOEXEC, 3);
  *error = fd < 0 ? errno : 0;
  if (fd >= 0) *lease = fd;
  return fd < 0 ? -1 : 0;
}

static int64_t fstatat_image(int fd, const char *path, void *destination, int flags, int64_t *error) {
  struct stat value;
  memset(&value, 0, sizeof(value));
  int result = fstatat(fd, path, &value, flags);
  *error = result < 0 ? errno : 0;
  if (result == 0) memcpy(destination, &value, sizeof(value));
  return result;
}

int64_t thc_file_fstatat(const int *lease, const char *path, void *destination, int flags, int64_t *error) {
  return fstatat_image(*lease, path, destination, flags, error);
}


// Only a relative name can reach this fixed-invalid-descriptor failure path.
int64_t thc_file_fstatat_invalid(const char *path, void *destination, int flags, int64_t *error) {
  return fstatat_image(-1, path, destination, flags, error);
}

int64_t thc_file_path_stat(const int *directory, const char *path, int follow, void *destination, int64_t *error) {
  struct stat value;
  memset(&value, 0, sizeof(value));
  int result = fstatat(*directory, path, &value, follow ? 0 : AT_SYMLINK_NOFOLLOW);
  *error = result < 0 ? errno : 0;
  if (result == 0) memcpy(destination, &value, sizeof(value));
  return result;
}

int64_t thc_file_stat(const int *lease, void *destination, int64_t *error) {
  struct stat value;
  memset(&value, 0, sizeof(value));
  int result = fstat(*lease, &value);
  *error = result < 0 ? errno : 0;
  if (result == 0) memcpy(destination, &value, sizeof(value));
  return result;
}

int64_t thc_file_isatty(const int *lease, int64_t *error) {
  errno = 0;
  int terminal = isatty(*lease);
  *error = terminal ? 0 : (errno ? errno : ENOTTY);
  return terminal ? 1 : -1;
}

// The original CAPI write wrapper passes CLong to fcntl's variadic argument.
// The lease supplies the real descriptor; libc owns command and argument semantics.
_Static_assert(sizeof(long) == 8, "original fcntl requires LP64 CLong");
int64_t thc_file_fcntl(const int *lease, int command, int64_t argument, int has_argument, int64_t *error) {
  errno = 0; // F_GETOWN can return a negative process group on success.
  int result = has_argument ? fcntl(*lease, command, (long)argument) : fcntl(*lease, command);
  *error = result == -1 ? errno : 0;
  return result;
}

// Descriptor results must be published into owned leases, not returned as guest integers.
int64_t thc_file_fcntl_duplicate_command(int command, int64_t *error) {
  *error = 0;
  return command == F_DUPFD || command == F_DUPFD_CLOEXEC;
}

int64_t thc_file_fcntl_duplicate(int *target, const int *source, int command, int64_t minimum, int64_t *error) {
  int fd = fcntl(*source, command, (long)minimum);
  *error = fd < 0 ? errno : 0;
  if (fd >= 0) *target = fd;
  return fd < 0 ? -1 : 0;
}

int64_t thc_file_getfl(const int *lease, int64_t *error) {
  int result = fcntl(*lease, F_GETFL);
  *error = result < 0 ? errno : 0;
  return result;
}

int64_t thc_file_read(const int *lease, void *bytes, int64_t count, int64_t *error) {
  if (count < 0) { *error = EINVAL; return -1; }
  ssize_t result = read(*lease, bytes, (size_t)count);
  *error = result < 0 ? errno : 0;
  return result;
}

int64_t thc_file_write(const int *lease, const void *bytes, int64_t count, int64_t *error) {
  if (count < 0) { *error = EINVAL; return -1; }
  ssize_t result = write(*lease, bytes, (size_t)count);
  *error = result < 0 ? errno : 0;
  return result;
}

int64_t thc_file_seek(const int *lease, int64_t offset, int mode, int64_t *error) {
  int whence;
  switch (mode) {
    case 0: whence = SEEK_SET; break;
    case 1: whence = SEEK_CUR; break;
    case 2: whence = SEEK_END; break;
    default: *error = EINVAL; return -1;
  }
  off_t result = lseek(*lease, offset, whence);
  *error = result < 0 ? errno : 0;
  return result;
}

int64_t thc_file_truncate(const int *lease, int64_t length, int64_t *error) {
  int result = ftruncate(*lease, length);
  *error = result < 0 ? errno : 0;
  return result;
}
