#include <unistd.h>
#include <string.h>
#include <stdio.h>
#include <stdlib.h>
/* git — static ELF entry (files/bin/git): exec's the rootfs glibc loader
 * with real git. Scripts cannot be exec'd by loader-run children (bionic
 * shebang ENOENT), so this must be a binary. Build: NDK r27b
 * aarch64-linux-android35-clang -O2 -static -s. */
int main(int argc, char **argv) {
    const char *D = getenv("DX_FILES");
    if (!D || !*D) D = "/data/user/0/com.angussoftware.letta.env/files";
    char ld[600], lp[1200];
    snprintf(ld, sizeof(ld), "%s/rootfs/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1", D);
    snprintf(lp, sizeof(lp), "%s/rootfs/usr/lib/aarch64-linux-gnu:%s/rootfs/lib/aarch64-linux-gnu:%s/libs", D, D, D);
    char gcshim[600], execpath[1200], ca[600], resolv[600];
    snprintf(gcshim, sizeof(gcshim), "%s/gc-shim", D);
    snprintf(execpath, sizeof(execpath), "%s:%s/rootfs/usr/lib/git-core", gcshim, D);
    snprintf(ca, sizeof(ca), "%s/rootfs/etc/ssl/certs/ca-certificates.crt", D);
    snprintf(resolv, sizeof(resolv), "%s/rootfs/etc/resolv.conf", D);
    setenv("GIT_EXEC_PATH", execpath, 1);
    setenv("GIT_SSL_CAINFO", ca, 0);
    setenv("LD_LIBRARY_PATH", lp, 0);
    FILE *f = fopen(resolv, "r");
    if (!f) { FILE *w = fopen(resolv, "w"); if (w) { fputs("nameserver 1.1.1.1\nnameserver 8.8.8.8\n", w); fclose(w); } }
    else fclose(f);
    char gitpath[600];
    snprintf(gitpath, sizeof(gitpath), "%s/rootfs/usr/bin/git", D);
    char *nargv[argc + 8];
    int n = 0;
    nargv[n++] = ld;
    nargv[n++] = "--library-path";
    nargv[n++] = lp;
    nargv[n++] = "--preload";
    nargv[n++] = "/data/user/0/com.angussoftware.letta.env/files/libs/dnsport.so";
    nargv[n++] = gitpath;
    for (int i = 1; i < argc; i++) nargv[n++] = argv[i];
    nargv[n] = NULL;
    execv(ld, nargv);
    perror("git shim");
    return 127;
}
