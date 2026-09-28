#include <unistd.h>
#include <string.h>
#include <stdlib.h>
/* Multi-call static ELF shim: argv[0] basename selects the target git-core
 * helper. exec's the rootfs glibc loader — script shebangs CANNOT exec from
 * loader-chain processes (bionic loader skew, ENOENT). Deployed as
 * files/gc-shim/git-remote-{ftp,ftps,http,https} AND files/gc-shim/git
 * (git's run_command PATH-searches GIT_EXEC_PATH for `git remote-https`
 * spawns; raw glibc git-core/git ENOEXECs on Android, so this static shim
 * must be found first). Build: NDK r27b aarch64-linux-android35-clang
 * -O2 -static -s. See scenes/decisions/watch-git-https-dns-solved-sep27.md. */
int main(int argc, char **argv) {
    const char *D = "/data/user/0/com.angussoftware.letta.env/files";
    char path[512];
    const char *base = strrchr(argv[0], '/');
    base = base ? base + 1 : argv[0];
    snprintf(path, sizeof(path), "%s/rootfs/usr/lib/git-core/%s", D, base);
    char ld[512], lp[1024];
    snprintf(ld, sizeof(ld), "%s/rootfs/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1", D);
    snprintf(lp, sizeof(lp), "%s/rootfs/usr/lib/aarch64-linux-gnu:%s/rootfs/lib/aarch64-linux-gnu:%s/libs", D, D, D);
    char *nargv[argc + 8];
    int n = 0;
    nargv[n++] = ld;
    nargv[n++] = "--library-path";
    nargv[n++] = lp;
    nargv[n++] = "--preload";
    nargv[n++] = "/data/user/0/com.angussoftware.letta.env/files/libs/dnsport.so";
    nargv[n++] = path;
    for (int i = 1; i < argc; i++) nargv[n++] = argv[i];
    nargv[n] = NULL;
    setenv("GIT_EXEC_PATH", "/data/user/0/com.angussoftware.letta.env/files/rootfs/usr/lib/git-core", 0);
    execv(ld, nargv);
    _exit(127);
}
