/*
 * liblink2copy.so — SELinux hardlink-denial shim for the Android rootfs.
 *
 * The app sandbox (untrusted_app / runas_app context) denies hardlink
 * creation in the app data dir on One UI 7 (link -> EACCES). Debian
 * tooling hardlinks: dpkg's status-old backup, dpkg-deb extraction of
 * packages containing hardlinked files.
 *
 * Shim strategy: try the real link first; on failure fall back to a byte
 * copy with preserved mode/ownership. Copy is semantically close enough
 * for package-manager use (dpkg only needs content preserved; it never
 * assumes inode identity for status-old; extracted hardlinked files
 * become independent copies).
 *
 * Build: zig cc -target aarch64-linux-gnu -shared -fPIC -O2 \
 *            -o liblink2copy.so link2copy.c -ldl
 */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <stdarg.h>
#include <stddef.h>
#include <stdio.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>

static int copy_file(const char *src, const char *dst) {
    int in = open(src, O_RDONLY | O_CLOEXEC);
    if (in < 0) return -1;
    struct stat st;
    if (fstat(in, &st) < 0) { close(in); return -1; }
    int out = open(dst, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, st.st_mode & 07777);
    if (out < 0) { close(in); return -1; }
    char buf[65536];
    ssize_t n;
    while ((n = read(in, buf, sizeof buf)) > 0) {
        ssize_t off = 0;
        while (off < n) {
            ssize_t w = write(out, buf + off, (size_t)(n - off));
            if (w < 0) { close(in); close(out); return -1; }
            off += w;
        }
    }
    int r = (n < 0) ? -1 : 0;
    if (r == 0) {
        fchown(out, st.st_uid, st.st_gid); /* best-effort */
        fsync(out);                        /* dpkg-grade durability */
    }
    close(in);
    close(out);
    return r;
}

typedef int (*link_fn)(const char *, const char *);
typedef int (*linkat_fn)(int, const char *, int, const char *, int);

int link(const char *oldpath, const char *newpath) {
    static link_fn real;
    if (!real) real = (link_fn)dlsym(RTLD_NEXT, "link");
    if (real && real(oldpath, newpath) == 0) return 0;
    if (copy_file(oldpath, newpath) == 0) return 0;
    return -1;
}

/* Build a /proc/self/fd/N-based absolute path for dirfd-relative lookups. */
static int fd_path(int dirfd, const char *path, char *out, size_t outsz) {
    if (path[0] == '/') {
        if (snprintf(out, outsz, "%s", path) >= (int)outsz) return -1;
        return 0;
    }
    char dir[PATH_MAX];
    if (dirfd == AT_FDCWD) {
        if (!getcwd(dir, sizeof dir)) return -1;
    } else {
        char procpath[64];
        if (snprintf(procpath, sizeof procpath, "/proc/self/fd/%d", dirfd) >= (int)sizeof procpath) return -1;
        ssize_t len = readlink(procpath, dir, sizeof dir - 1);
        if (len < 0) return -1;
        dir[len] = '\0';
    }
    if (snprintf(out, outsz, "%s/%s", dir, path) >= (int)outsz) return -1;
    return 0;
}

int linkat(int olddirfd, const char *oldpath, int newdirfd, const char *newpath, int flags) {
    static linkat_fn real;
    if (!real) real = (linkat_fn)dlsym(RTLD_NEXT, "linkat");
    if (real && real(olddirfd, oldpath, newdirfd, newpath, flags) == 0) return 0;
    char opath[PATH_MAX], npath[PATH_MAX];
    if (fd_path(olddirfd, oldpath, opath, sizeof opath) < 0) { errno = EACCES; return -1; }
    if (fd_path(newdirfd, newpath, npath, sizeof npath) < 0) { errno = EACCES; return -1; }
    if (copy_file(opath, npath) == 0) return 0;
    errno = EACCES;
    return -1;
}
