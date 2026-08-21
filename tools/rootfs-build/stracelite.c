// strace-lite v2: PTRACE_GETREGSET/NT_PRSTATUS (correct aarch64 API).
// Prints syscall nr + args + PC at syscall entry; the last line before death = killer.
#define _GNU_SOURCE
#include <sys/ptrace.h>
#include <sys/wait.h>
#include <sys/uio.h>
#include <sys/user.h>
#include <signal.h>
#include <unistd.h>
#include <stdio.h>
#include <string.h>
#include <errno.h>

int main(int argc, char **argv) {
    if (argc < 2) { dprintf(2, "usage: stracelite prog [args]\n"); return 2; }
    pid_t pid = fork();
    if (pid == 0) {
        ptrace(PTRACE_TRACEME, 0, 0, 0);
        raise(SIGSTOP);
        execv(argv[1], argv + 1);
        dprintf(2, "execv failed\n");
        _exit(127);
    }
    int status;
    waitpid(pid, &status, 0); // SIGSTOP
    ptrace(PTRACE_SETOPTIONS, pid, 0, PTRACE_O_EXITKILL | PTRACE_O_TRACESYSGOOD);
    ptrace(PTRACE_SYSCALL, pid, 0, 0);
    waitpid(pid, &status, 0);
    if (!WIFSTOPPED(status)) { dprintf(2, "died before exec\n"); return 1; }

    int entering = 1;
    unsigned long count = 0;
    while (1) {
        ptrace(PTRACE_SYSCALL, pid, 0, 0);
        if (waitpid(pid, &status, 0) < 0) break;
        if (WIFEXITED(status)) { dprintf(2, "EXITED code=%d after %lu syscalls\n", WEXITSTATUS(status), count); break; }
        if (WIFSIGNALED(status)) { dprintf(2, "SIGNALED sig=%d after %lu syscalls\n", WTERMSIG(status), count); break; }
        if (WIFSTOPPED(status)) {
            int sig = WSTOPSIG(status);
            if (sig == (SIGTRAP | 0x80)) {
                struct user_regs_struct r;
                struct iovec iov = { &r, sizeof(r) };
                if (ptrace(PTRACE_GETREGSET, pid, (void *)1 /*NT_PRSTATUS*/, &iov) == 0) {
                    if (entering) {
                        count++;
                        dprintf(2, "nr=%llu a0=%llx pc=%llx\n",
                                (unsigned long long)r.regs[8],
                                (unsigned long long)r.regs[0],
                                (unsigned long long)r.pc);
                        entering = 0;
                    } else {
                        entering = 1;
                    }
                } else {
                    dprintf(2, "getregset-failed\n");
                }
            } else if (sig == SIGTRAP) {
                // non-syscall trap (exec event etc.) — step over
            } else {
                struct user_regs_struct rr;
                struct iovec iovr = { &rr, sizeof(rr) };
                if (ptrace(PTRACE_GETREGSET, pid, (void *)1, &iovr) == 0) {
                    dprintf(2, "FATAL-SIGNAL sig=%d pc=%llx sp=%llx lr=%llx x0=%llx x1=%llx x2=%llx\n",
                            sig, (unsigned long long)rr.pc, (unsigned long long)rr.sp,
                            (unsigned long long)rr.regs[30], (unsigned long long)rr.regs[0],
                            (unsigned long long)rr.regs[1], (unsigned long long)rr.regs[2]);
                    // unwind x29 frame chain (caller pcs)
                    unsigned long fp = rr.regs[29]; // x29
                    dprintf(2, "=== STACK UNWIND ===\n");
                    for (int f = 0; f < 12 && fp; f++) {
                        errno = 0;
                        unsigned long next = ptrace(PTRACE_PEEKDATA, pid, (void *)fp, 0);
                        unsigned long lr = ptrace(PTRACE_PEEKDATA, pid, (void *)(fp + 8), 0);
                        if (errno) break;
                        dprintf(2, "FRAME%d pc=0x%lx\n", f, lr);
                        if (next <= fp) break;
                        fp = next;
                    }
                    dprintf(2, "=== END UNWIND ===\n");
                    char mp[64];
                    snprintf(mp, sizeof(mp), "/proc/%d/maps", pid);
                    FILE *mf = fopen(mp, "r");
                    if (mf) {
                        char line[512];
                        dprintf(2, "=== MAPS ===\n");
                        while (fgets(line, sizeof(line), mf)) {
                            dprintf(2, "M %s", line); // line includes newline
                        }
                        fclose(mf);
                        dprintf(2, "=== END MAPS ===\n");
                    } else {
                        dprintf(2, "maps-read-failed\n");
                    }
                } else {
                    dprintf(2, "STOPPED-by-signal sig=%d (delivering)\n", sig);
                }
                ptrace(PTRACE_SYSCALL, pid, 0, (void *)(long)sig);
                continue;
            }
        }
    }
    return 0;
}
