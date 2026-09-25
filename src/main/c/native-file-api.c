// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

#define _GNU_SOURCE 1
#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

// Private Linux provider transport. These are not original GHC FCall symbols.
// The fd slot belongs to a host lease allocated before acquisition. Once stored,
// host cleanup can close it without re-entering LLVM. Cancellation between libc
// acquisition and the slot store has not been proved safe by this checkpoint.
_Static_assert(sizeof(int) == 4 && sizeof(off_t) == 8 && sizeof(size_t) == 8 && sizeof(mode_t) == 4,
               "native files require the Linux LP64 ABI");

int64_t thc_file_stat_size(void) { return sizeof(struct stat); }

// Original unsafe open transport: no added flags, path decoding, type filter,
// retry, truncation deferral or RTS locking. The caller reserved its guest fd.
int64_t thc_file_open_raw(int *lease, const char *path, int flags, uint32_t mode, int64_t *error) {
  int fd = open(path, flags, (mode_t)mode);
  *error = fd < 0 ? errno : 0;
  if (fd >= 0) *lease = fd;
  return fd < 0 ? -1 : 0;
}

int64_t thc_file_open(int *lease, const char *path, int mode, int64_t *error) {
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
  int fd = open(path, flags | O_CLOEXEC | O_NONBLOCK | O_NOCTTY, 0666);
  *error = fd < 0 ? errno : 0;
  if (fd < 0) return -1;
  *lease = fd;
  struct stat value;
  if (fstat(fd, &value) < 0) { *error = errno; return -1; }
  // -2 is explicit private-provider policy rejection, not a fabricated errno.
  // The host lease still owns cleanup of this successfully acquired resource.
  return S_ISREG(value.st_mode) ? 0 : -2;
}

int64_t thc_file_standard(int *lease, int endpoint, int64_t *error) {
  if (endpoint < 0 || endpoint > 2) { *error = EINVAL; return -1; }
  int fd = fcntl(endpoint, F_DUPFD_CLOEXEC, 3);
  *error = fd < 0 ? errno : 0;
  if (fd >= 0) *lease = fd;
  return fd < 0 ? -1 : 0;
}

int64_t thc_file_stat(const int *lease, void *destination, int64_t *error) {
  struct stat value;
  memset(&value, 0, sizeof(value));
  int result = fstat(*lease, &value);
  *error = result < 0 ? errno : 0;
  if (result == 0) memcpy(destination, &value, sizeof(value));
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
