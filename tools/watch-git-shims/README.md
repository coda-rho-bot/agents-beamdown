# Watch git HTTPS shims (Sep 27 2026)

Full glibc git over HTTPS on the Galaxy Watch 7 (WearOS, API 36), inside the
Letta Environment app sandbox. No root, no proot. Verified e2e: ls-remote,
full clone, push --dry-run of fleet-shared against api.letta.com.

## Components
- `gitmain.c` -> `files/bin/git` — static bionic ELF (NDK r27b, API 35)
  exec'ing the glibc loader with real git. Scripts cannot exec from
  loader-chain processes (bionic shebang ENOENT).
- `gcshim.c` -> `files/gc-shim/git-remote-{ftp,ftps,http,https}` AND
  `files/gc-shim/git` — multi-call static ELF shim. The `git` entry is
  REQUIRED: git's run_command PATH-searches GIT_EXEC_PATH for
  `git remote-https` spawns; raw glibc git-core/git ENOEXECs on Android.
- `dnsport.c` -> `files/libs/dnsport.so` — zig cc
  `-target aarch64-linux-gnu.2.36` LD_PRELOAD overriding `getaddrinfo()`
  (glibc-internal sendmmsg is NOT interposable — no PLT). Raw A/AAAA via
  direct syscalls to 127.0.0.1:15353. Per-node malloc mandatory
  (single-block alloc -> double free in freeaddrinfo).
- `dns-forwarder.js` -> `files/dns-forwarder.js` — node UDP forwarder
  15353 -> 1.1.1.1/8.8.8.8. Must launch via the APK libldlnx.so/libnode.so
  path (uv__close assert otherwise) with setsid (dies with adb shell
  otherwise). Auto-started by launch-server.sh (netstat port check).

## Builds
    NDK=/path/to/ndk/toolchains/llvm/prebuilt/linux-x86_64/bin
    $NDK/aarch64-linux-android35-clang -O2 -static -s -o git gitmain.c
    $NDK/aarch64-linux-android35-clang -O2 -static -s -o git-remote-https gcshim.c
    zig cc -target aarch64-linux-gnu.2.36 -shared -fPIC -O2 -o dnsport.so dnsport.c -ldl

Auth: `http.https://api.letta.com/v1/git/.extraheader` Basic `letta:at-...`
(same pattern as desktop MemFS config).
