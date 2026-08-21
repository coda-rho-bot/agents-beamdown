// SIGSYS catcher: prints the trapped syscall number from siginfo.
// seccomp RET_TRAP delivers SIGSYS with si_syscall = the syscall number.
#include <signal.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>

static void handler(int sig, siginfo_t *si, void *ctx) {
    char buf[128];
    int n = snprintf(buf, sizeof(buf), "SIGSYS_CAUGHT nr=%d addr=%p errno=%d\n",
                     si->si_syscall, si->si_call_addr, si->si_errno);
    write(2, buf, n);
    _exit(66); // distinctive exit: 66 = caught-and-reported
}

__attribute__((constructor(101)))
static void install(void) {
    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sa.sa_sigaction = handler;
    sa.sa_flags = SA_SIGINFO | SA_NODEFER;
    sigaction(SIGSYS, &sa, NULL);
    write(2, "sigsys_catch installed\n", 23);
}
