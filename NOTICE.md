# Agents Beamdown — Third-Party Notices

The Agents Beamdown app code is licensed Apache-2.0 (see LICENSE). The
distributed APK additionally bundles the third-party components below; each
retains its own license. Full license texts are in [`licenses/`](licenses/).

| Component | License | Source |
|---|---|---|
| Debian GNU/Linux 12 "bookworm" base rootfs (arm64) | GPL-2.0, GPL-3.0, LGPL-2.1, LGPL-3.0, and per-package licenses (Debian `copyright` files upstream) | [Debian docker hub](https://hub.docker.com/_/debian), [snapshot.debian.org](https://snapshot.debian.org) for package sources |
| Node.js 22 (linux-arm64) | MIT (includes bundled ICU, zlib, OpenSSL components under their licenses) | [nodejs.org](https://nodejs.org/en/download) |
| git (arm64 build) | GPL-2.0 | [git-scm.com](https://git-scm.com) |
| proot + libtalloc (arm64) | GPL-2.0 (proot), LGPL-3.0 (libtalloc) | [proot.gitlab.io](https://proot-me.github.io) |
| `@letta-ai/letta-code` 0.30.27 | Apache-2.0 | [npm](https://www.npmjs.com/package/@letta-ai/letta-code) |
| ld-linux loader, glibc (Debian) | LGPL-2.1+ | Debian |

## Modifications

Bundled binaries are modified by `tools/rootfs-build/patch-binaries.py`
(published in this repository — the source form of these modifications).
Patch 4 modifies `letta.js` from `@letta-ai/letta-code` (Apache-2.0): the
`link()` call is replaced with `writeFileSync(..., wx)` for hard-link-free
filesystems. Per Apache-2.0 §4(b), this file is modified when built; the
build applies a header comment marking the modification.

## Source availability

The complete build recipe (rootfs assembly, binary patches, APK assembly)
is in this repository under `tools/rootfs-build/` and `.woodpecker/`.
Corresponding source for Debian packages: http://snapshot.debian.org.

## Offer

Written offers for the complete corresponding source of the GPL/LGPL
components are available on request via GitHub issues at
https://github.com/coda-rho-bot/agents-beamdown.
