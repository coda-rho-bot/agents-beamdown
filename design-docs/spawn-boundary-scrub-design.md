# Design: Spawn-Boundary API-Key Scrub (beamdown ambient-key fix)

Todo: 1c786d4e8f8a ([WILL HURT] LETTA_API_KEY ambient in every agent-shell command env)
Author: Coda (design); implementer: Angus. Empirical data: `~/envkey-0.34.4-vs-0.34.25.md` (probe report, Nov 7 — referenced throughout, not restated).
Status: DRAFT for Uncia review. Lands in the 0.4.35 batch.

## 1. Problem

`LettaEnvironmentService` starts the node server via ProcessBuilder with the
master `LETTA_API_KEY` in its environment (service-internal — correct for the
server's own use). Node passes the full environ to every spawned child: any
Bash-tool command, npm lifecycle script, or build step the agent runs inherits
Harry's master Letta credential in its own env. Exposure = exfiltration/
accidental-echo surface on the master account credential.

The original prescription (delete `process.env.LETTA_API_KEY` at server start)
was INVALIDATED Oct 27: a 129-site classification found the redactor consumes
the ambient key (scrubbing = disabling log redaction = security regression),
and a boot-time snapshot doesn't exist (the server does per-call env reads —
no single point to capture before deletion). Prescription reverted inert
(d7ee8b2).

## 2. Fix shape: scrub at the spawn boundary only

**Rule: children spawned by the Letta server get NOTHING; the server env
stays intact.**

Implementation: at every ProcessBuilder launch site in
`LettaEnvironmentService` (and any other server-side spawn path), `env()`
mutates the child environment to REMOVE `LETTA_API_KEY` after the server's
own needs are copied. The server process env — which the redactor and every
per-call read site consume — is untouched, so log redaction and all 129
classified read sites behave identically before and after the change.

Single choke point preferred: if the service funnels all spawns through one
helper, add the scrub there (one diff site); enumerate launch sites in the
implementation PR and assert coverage in a test (see §5).

A site census (~10 ProcessBuilder spawn sites across LettaEnvironmentService
+ AgentAccessibilityService) shows only the main server launch passes
`LETTA_API_KEY` — the scrub is a no-op at the other sites, enumerated for
coverage proof by the implementation census test (§5).

## 3. Empirical preconditions (answered — the design is data-backed)

### 3.1 Boundary enumeration + subagent key-needs — **NONE**

Probe swept the entire 0.34.4-pinned letta-code runtime (35,470 files):
**zero code reads `LETTA_API_KEY` by name.** The only mentions are skill
documentation (agent-facing export instructions) and `.d.ts` type
declarations — no runtime consumption. Consequences:

- The scrub breaks nothing on the current pin: no child type needs the key,
  so the "whitelist-if-needed" surface is empty by evidence, not assumption.
- The question is ANSWERED for 0.34.4; it must be RE-ANSWERED on any version
  bump (see §4 version table).

### 3.2 Redaction safety — **asserted explicitly**

The log redactor consumes the ambient key (that finding invalidated the
original fix). The spawn-boundary scrub does not touch it: the scrub mutates
**child** ProcessBuilder environments; the **server process env** — where the
redactor reads — stays fully populated. Mechanically: `env().remove(...)` on
the child builder never reaches `System.getenv()`. Redaction behavior is
identical pre/post change, and assertion E2E-4 (§5) proves it rather than
assuming it.

### 3.3 Key-needing child failure mode — **visible auth error, not silent**

Zero classified children need the key today, so the whitelist is empty. If a
FUTURE child type needs Letta auth, the failure mode is a **visible auth
error** (HTTP 401/403 from the API surface it touches) — never silent
degradation, because API auth failures are loud by construction. The fix adds
no silent-fallback path; a future key-needing child requires an explicit
design amendment naming the child and its whitelist entry.

### 3.4 E2E assertion — **logs still redact post-change**

The existing redaction assertions in the contract suite continue to pass
unchanged (they exercise the server's env, untouched). One new E2E assertion
accompanies the implementation: a spawned child's environ contains NO
`LETTA_API_KEY` (verified via `/proc/<pid>/environ` or `env` inside the
child), proving the scrub at the boundary.

## 4. Version transfer table + the ordering trap

| Version | env reads of LETTA_API_KEY | Scrub safe? | Notes |
|---|---|---|---|
| 0.34.4 (current pin) | **0** runtime reads | **YES** — this design | Probe: 35,470-file sweep, zero hits |
| 0.34.25+ (future) | **4** in `dist/letta.js` | **CONDITIONAL** | SDK auth default; keychain migration; explicit scrub |

**The ordering trap (upgrade checklist item, mandatory):** 0.34.25 adds an
active keychain-migration path — it reads `settings.env?.LETTA_API_KEY`,
moves the key to OS secure storage, and DELETES it from settings. That
migration runs inside the app process. If the spawn-boundary scrub is
misread as "scrub the app env too" and applied before migration executes,
the migration silently loses its source: the key never reaches the keychain
AND stays ambient anyway (worst case: regression + no progress).

Rule for upgrades: **keychain migration runs before any env scrub is
evaluated** — added to the version-bump checklist alongside the existing
compileSdk/toolchain re-verification items. The scrub's server-env-intact
guarantee is unaffected by this trap; the trap is only for anyone
reconsidering a wider scrub on a future version.

## 5. Rollback + test plan

**Rollback:** single-commit revert of the scrub changes (launch-site edits +
the new E2E assertion). No schema, no data, no external state — revert
restores spawn behavior exactly.

**What proves it (assertions):**
1. Child environ scrubbed: spawned child's `/proc/<pid>/environ` (or `env`)
   contains no `LETTA_API_KEY` — new E2E assertion.
2. Server redaction intact: existing log-redaction contract tests pass
   unchanged.
3. Server functionality intact: full boot + Bash-tool round-trip unchanged
   (existing boot assertions).
4. Launch-site coverage: implementation PR enumerates all ProcessBuilder
   sites and demonstrates the scrub helper covers each (test-asserted via a
   site census, not hand-waved).

**Release-gate linkage:** lands in the 0.4.35 batch, gated by the SAME
connected-run evidence as the rest of the release (todo b19a3916657e chain —
one boot exercises both the rootfs-marker migration and this scrub).

## 6. Sequencing

Design (this doc) → Uncia review → Angus implements → lands in the 0.4.35
batch. Related: repo-reconciliation of angus-bot/woodpecker-ci (separate
PR, pending) — no interaction with this change.
