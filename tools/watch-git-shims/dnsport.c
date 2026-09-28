/* dnsport.so — LD_PRELOAD DNS hook for glibc git on Android (Galaxy Watch).
 * glibc-internal resolver calls can't be interposed (no PLT for sendmmsg),
 * but libcurl calls getaddrinfo() cross-library -> interposable. Override
 * getaddrinfo: raw A/AAAA queries via direct syscalls to the local node
 * DNS forwarder on 127.0.0.1:15353 (files/dns-forwarder.js, auto-started
 * by launch-server.sh). Build: zig cc -target aarch64-linux-gnu.2.36
 * -shared -fPIC -O2 -ldl. NOTE: per-node malloc required — single-block
 * addrinfo alloc causes double free in freeaddrinfo (signal 6).
 * Deployed: files/libs/dnsport.so; all git shims pass
 * --preload .../libs/dnsport.so to the glibc loader. */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <netdb.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <string.h>
#include <stdlib.h>
#include <stdio.h>
#include <unistd.h>
#include <errno.h>
#include <sys/syscall.h>

#define FWD_PORT 15353

static int dns_query(const char *name, int qtype, unsigned char *ans, int anslen) {
    unsigned char q[512]; int qlen = 12;
    q[0]=0x43; q[1]=0x21; q[2]=0x01; q[3]=0x00;
    q[4]=0; q[5]=1; q[6]=0; q[7]=0; q[8]=0; q[9]=0; q[10]=0; q[11]=0;
    const char *p = name;
    while (*p) {
        const char *dot = strchr(p, '.');
        int len = dot ? (int)(dot - p) : (int)strlen(p);
        if (len > 63 || qlen + len + 5 >= (int)sizeof(q)) return -1;
        q[qlen++] = (unsigned char)len;
        memcpy(q + qlen, p, len); qlen += len;
        if (dot) p = dot + 1; else break;
    }
    q[qlen++] = 0; q[qlen++] = 0; q[qlen++] = (unsigned char)qtype; q[qlen++] = 0; q[qlen++] = 1;
    long s = syscall(SYS_socket, AF_INET, SOCK_DGRAM, 0);
    if (s < 0) return -1;
    struct sockaddr_in dst; memset(&dst, 0, sizeof(dst));
    dst.sin_family = AF_INET; dst.sin_port = htons(FWD_PORT);
    dst.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    struct timeval tv = { 4, 0 };
    syscall(SYS_setsockopt, s, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
    if (syscall(SYS_sendto, s, q, qlen, 0, (struct sockaddr*)&dst, sizeof(dst)) < 0) {
        syscall(SYS_close, s); return -1;
    }
    long n = syscall(SYS_recvfrom, s, ans, anslen, 0, NULL, NULL);
    syscall(SYS_close, s);
    if (n < 12 || (ans[3] & 0x0F) != 0) return -1;
    return (int)n;
}

/* skip a (possibly compressed) domain name; returns bytes consumed */
static int skip_name(const unsigned char *p, const unsigned char *end) {
    const unsigned char *q = p;
    while (q < end) {
        if (*q & 0xC0) return (q + 2 <= end) ? 2 : -1;
        if (*q == 0) return (int)(q - p) + 1;
        q += 1 + *q;
    }
    return -1;
}

static int parse_answers(const unsigned char *ans, int anlen, int want_type,
                         struct sockaddr_in6 *out, int max_out) {
    const unsigned char *p = ans + 12, *end = ans + anlen;
    int n = skip_name(p, end); if (n < 0) return 0; p += n + 4;
    int ancount = (ans[6] << 8) | ans[7], found = 0;
    for (int i = 0; i < ancount && p < end && found < max_out; i++) {
        n = skip_name(p, end); if (n < 0) break; p += n;
        if (p + 10 > end) break;
        int type = (p[0] << 8) | p[1]; p += 8;
        int rdlen = (p[0] << 8) | p[1]; p += 2;
        if (p + rdlen > end) break;
        if (type == want_type) {
            if (want_type == 1 && rdlen == 4) {
                memset(&out[found], 0, sizeof(out[found]));
                struct sockaddr_in *a = (struct sockaddr_in *)&out[found];
                a->sin_family = AF_INET;
                memcpy(&a->sin_addr, p, 4);
                found++;
            } else if (want_type == 28 && rdlen == 16) {
                memset(&out[found], 0, sizeof(out[found]));
                out[found].sin6_family = AF_INET6;
                memcpy(&out[found].sin6_addr, p, 16);
                found++;
            }
        }
        p += rdlen;
    }
    return found;
}

int getaddrinfo(const char *node, const char *service,
                const struct addrinfo *hints, struct addrinfo **res) {
    static int (*real)(const char *, const char *, const struct addrinfo *, struct addrinfo **);
    if (!real) real = dlsym(RTLD_NEXT, "getaddrinfo");
    if (!node) return real(node, service, hints, res);
    struct in6_addr tmp6;
    if (inet_pton(AF_INET, node, &((struct sockaddr_in){0}).sin_addr) > 0 ||
        inet_pton(AF_INET6, node, &tmp6) > 0 ||
        !strcmp(node, "localhost"))
        return real(node, service, hints, res);
    if (service && *service) {
        for (const char *c = service; *c; c++)
            if (*c < '0' || *c > '9') return real(node, service, hints, res);
    }
    int family = hints ? hints->ai_family : AF_UNSPEC;
    int socktype = hints ? hints->ai_socktype : 0;
    if (!socktype) socktype = SOCK_STREAM;
    unsigned char ans[1024];
    struct sockaddr_in6 addrs[8];
    int n4 = 0, n6 = 0;
    if (family == AF_UNSPEC || family == AF_INET) {
        int n = dns_query(node, 1, ans, sizeof(ans));
        if (n > 0) n4 = parse_answers(ans, n, 1, addrs, 8);
    }
    if (family == AF_UNSPEC || family == AF_INET6) {
        int n = dns_query(node, 28, ans, sizeof(ans));
        if (n > 0) n6 = parse_answers(ans, n, 28, addrs + n4, 8 - n4);
    }
    int total = n4 + n6;
    if (!total) return EAI_NONAME;
    int port = service && *service ? htons((unsigned short)atoi(service)) : 0;
    /* per-node allocations so freeaddrinfo can walk+free safely */
    struct addrinfo *head = NULL, *tail = NULL;
    for (int i = 0; i < total; i++) {
        int is4 = addrs[i].sin6_family == AF_INET;
        struct addrinfo *cur = calloc(1, sizeof(struct addrinfo));
        struct sockaddr_in6 *sa = calloc(1, sizeof(struct sockaddr_in6));
        if (!cur || !sa) return EAI_MEMORY;
        cur->ai_family = is4 ? AF_INET : AF_INET6;
        cur->ai_socktype = socktype;
        cur->ai_protocol = IPPROTO_TCP;
        cur->ai_addrlen = is4 ? sizeof(struct sockaddr_in) : sizeof(struct sockaddr_in6);
        cur->ai_addr = (struct sockaddr *)sa;
        memcpy(sa, &addrs[i], cur->ai_addrlen);
        if (is4) ((struct sockaddr_in *)cur->ai_addr)->sin_port = port;
        else ((struct sockaddr_in6 *)cur->ai_addr)->sin6_port = port;
        if (tail) tail->ai_next = cur; else head = cur;
        tail = cur;
    }
    *res = head;
    return 0;
}
