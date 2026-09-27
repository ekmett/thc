// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
/* Private context-directory transport. No process chdir or fchdir. */
#define _GNU_SOURCE
#include <sys/stat.h>
#include <sys/syscall.h>
#include <stdint.h>
#include <fcntl.h>
#include <dirent.h>
#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

static int identity(int fd, struct statx *st) {
    if (statx(fd, "", AT_EMPTY_PATH, STATX_BASIC_STATS | STATX_MNT_ID, st)) return -1;
    if (!(st->stx_mask & STATX_MNT_ID)) { errno = ENOTSUP; return -1; }
    if (!S_ISDIR(st->stx_mode)) { errno = ENOTDIR; return -1; }
    return 0;
}
static int same(const struct statx *a, const struct statx *b) {
    return a->stx_ino == b->stx_ino && a->stx_dev_major == b->stx_dev_major &&
        a->stx_dev_minor == b->stx_dev_minor && a->stx_mnt_id == b->stx_mnt_id;
}
/* Verify components without PATH_MAX, UTF-8 conversion, or following symlinks.
   Each descriptor is owned here. This can fail EACCES where kernel getcwd works. */
static int verify(int root, const char *path, const struct statx *wanted) {
    if (*path != '/') { errno = ENOENT; return -1; }
    char *copy = strdup(path);
    if (!copy) return -1;
    int fd = fcntl(root, F_DUPFD_CLOEXEC, 3), saved = 0;
    if (fd < 0) { free(copy); return -1; }
    char *save = NULL;
    for (char *part = strtok_r(copy, "/", &save); part; part = strtok_r(NULL, "/", &save)) {
        if (!strcmp(part,".") || !strcmp(part,"..")) { saved = ENOENT; break; }
        int next = openat(fd, part, O_PATH | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
        if (next < 0) { saved = errno; break; }
        close(fd); fd = next;
    }
    struct statx actual;
    if (!saved && identity(fd, &actual)) saved = errno;
    if (!saved && !same(&actual, wanted)) saved = ENOENT;
    close(fd); free(copy); errno = saved;
    return saved ? -1 : 0;
}

/* Stage and validate a physical name before publishing any byte. Walking may
   conservatively fail EACCES where kernel getcwd can still name the directory.
   This is explicit; never trust an unverified proc string or strip its suffix. */
static int64_t physical_name(int root, int fd, char *output, size_t capacity) {
    if (!capacity) return -EINVAL;
    struct statx wanted, rootid, currentid;
    if (identity(fd, &wanted) || identity(root, &rootid)) return -errno;
    if (wanted.stx_nlink == 0) return -ENOENT;
    {
        char link[64], candidate[4097];
        int formatted = snprintf(link, sizeof link, "/proc/self/fd/%d", fd);
        if (formatted < 0 || (size_t)formatted >= sizeof link) return -EOVERFLOW;
        ssize_t size = readlink(link, candidate, sizeof candidate - 1);
        if (size >= 0) {
            candidate[size] = 0;
            if (!verify(root, candidate, &wanted)) {
                if ((size_t)size >= capacity) return -ERANGE;
                memcpy(output, candidate, (size_t)size + 1); return (int)size;
            }
        }
    }
    char *path = malloc(capacity);
    if (!path) return -ENOMEM;
    size_t used = 0;
    path[capacity - 1] = 0;
    int current = fcntl(fd, F_DUPFD_CLOEXEC, 3), error = 0;
    if (current < 0) { free(path); return -errno; }
    currentid = wanted;
    while (!same(&currentid, &rootid)) {
        int parent = openat(current, "..", O_RDONLY | O_DIRECTORY | O_CLOEXEC);
        if (parent < 0) { error = errno; break; }
        struct statx parentid;
        if (identity(parent, &parentid)) { error = errno; close(parent); break; }
        if (same(&parentid, &currentid)) { error = ENOENT; close(parent); break; }
        DIR *entries = fdopendir(parent);
        if (!entries) { error = errno; close(parent); break; }
        char *name = NULL;
        for (;;) {
            errno = 0;
            struct dirent *entry = readdir(entries);
            if (!entry) { error = errno ? errno : ENOENT; break; }
            if (!strcmp(entry->d_name,".") || !strcmp(entry->d_name,"..")) continue;
            int child = openat(parent, entry->d_name, O_PATH | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
            if (child < 0) continue;
            struct statx childid;
            int matches = !identity(child, &childid) && same(&childid, &currentid);
            close(child);
            if (matches) { name = strdup(entry->d_name); if (!name) error = ENOMEM; break; }
        }
        /* Catch a rename between parent acquisition and directory enumeration. */
        int check = name ? openat(current, "..", O_PATH | O_DIRECTORY | O_CLOEXEC) : -1;
        struct statx checkid;
        if (name && (check < 0 || identity(check, &checkid) || !same(&checkid, &parentid))) error = ENOENT;
        if (check >= 0) close(check);
        int next = !error ? fcntl(parent, F_DUPFD_CLOEXEC, 3) : -1;
        if (!error && next < 0) error = errno;
        closedir(entries);
        if (error) { free(name); break; }
        size_t length = strlen(name);
        if (length + 1 >= capacity - used) { free(name); close(next); error = ERANGE; break; }
        used += length;
        memcpy(path + capacity - 1 - used, name, length);
        path[capacity - 2 - used] = '/'; ++used;
        free(name); close(current); current = next; currentid = parentid;
    }
    close(current);
    if (!error && !used) {
        if (capacity < 2) error = ERANGE;
        else { path[capacity - 2] = '/'; used = 1; }
    }
    char *answer = path + capacity - 1 - used;
    if (!error && verify(root, answer, &wanted)) error = errno;
    if (!error) memcpy(output, answer, used + 1);
    free(path);
    return error ? -error : (int)used;
}

/* O_PATH alone does not check final-directory search permission. Validate the
   acquired identity using effective credentials/ACLs before publication. */
int thc_directory_open(int at, const char *path, int *slot) {
    int fd = openat(at, path, O_PATH | O_DIRECTORY | O_CLOEXEC);
    if (fd < 0) return errno;
    if (syscall(SYS_faccessat2, fd, "", X_OK, AT_EMPTY_PATH | AT_EACCESS) < 0) {
        int error = errno; close(fd); return error;
    }
    *slot = fd;
    return 0;
}

int64_t thc_directory_name(int fd, char *output, uint64_t capacity) {
    if (!capacity) return -EINVAL;
    int root = open("/", O_PATH | O_DIRECTORY | O_CLOEXEC);
    if (root < 0) return -errno;
    int64_t result = physical_name(root, fd, output, (size_t)capacity);
    close(root);
    return result;
}

/* The admitted Unix 2.8.8.0 helpers select readdir (and a no-op free_dirent)
   on glibc >= 2.23. Keep the same EOF/errno protocol, not readdir_r semantics. */
#if !defined(__GLIBC__) || (__GLIBC__ == 2 && __GLIBC_MINOR__ < 23)
#error "Directory streams require the verified glibc readdir contract"
#endif

int thc_directory_stream_open(int at, const char *name, DIR **slot) {
    int fd = openat(at, name, O_RDONLY | O_DIRECTORY | O_NONBLOCK | O_CLOEXEC);
    if (fd < 0) return errno;
    DIR *stream = fdopendir(fd);
    if (!stream) { int error = errno; close(fd); return error; }
    *slot = stream;
    return 0;
}

/* Only a privately owned duplicate enters here. Failure leaves it with its
   caller; success moves it into DIR and clears the lease before returning. */
int thc_directory_stream_fdopen(int *lease, DIR **slot) {
    DIR *stream = fdopendir(*lease);
    if (!stream) return errno;
    *lease = -1;
    *slot = stream;
    return 0;
}

int thc_directory_stream_read(DIR *stream, char **name, uint64_t *length, int *error) {
    errno = *error;
    struct dirent *entry = readdir(stream);
    *error = errno;
    *name = entry ? entry->d_name : NULL;
    *length = entry ? strlen(entry->d_name) : 0;
    return entry ? 0 : -1;
}

int thc_directory_stream_close(DIR **slot) {
    DIR *stream = *slot;
    *slot = NULL;
    if (!stream) return 0;
    return closedir(stream) ? errno : 0;
}
