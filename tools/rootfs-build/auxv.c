#include <unistd.h>
#include <string.h>
#include <stdio.h>
int main() {
    char buf[4096]; FILE *f = fopen("/proc/self/auxv", "rb");
    if (!f) { puts("no auxv"); return 1; }
    unsigned long kv[2];
    int n;
    while ((n = fread(kv, 16, 1, f)) == 1) {
        if (kv[0] == 0) break;
        snprintf(buf, sizeof(buf), "auxv %lu = 0x%lx\n", kv[0], kv[1]);
        write(1, buf, strlen(buf));
    }
    fclose(f);
    return 0;
}
