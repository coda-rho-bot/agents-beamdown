# Access Surface — Agents Beamdown

What this app can touch on a device, and what stops it. For the live,
per-device truth run `agentctl access` on the environment — this file is
the design; that command is the measurement.

## The two doors

Agents reach a device running Agents Beamdown through two paths with
different trust models:

**1. The shell door (broad, trusted by design).** Agents dispatched to the
environment get a full Linux shell (Debian rootfs via proot) running as the
app's unprivileged uid. File-level access: the app's own storage and
`/sdcard` (shared storage — legacy-storage targetSdk 28), both read and
write. No per-action consent — this is the autonomy model. A `README.txt`
in the shell home restates this at every server start.

**2. The agentctl door (consent-gated, per session).** UI-level access —
reading the screen, tapping, typing, launching intents, reading
notification content — goes through `agentctl`
(`AgentAccessibilityService`, local unix socket `files/agentctl.sock` mode 600, same-uid only — enforced by filesystem permissions plus peer-credential verification).
Privacy-bearing commands require the device owner to approve a full-screen
overlay. Session TTL 300s (max 3600s); prompt timeout 120s; timeout or
decline = deny, fail-closed.

## OS boundaries (both doors)

- No root. Shell and service run as a standard app uid.
- Android sandbox: other apps' private data is invisible.
- `/system` is read-only.
- OEM quirks are probed live (`agentctl capabilities`): e.g. Samsung
  denies some shell `am start` targets; One UI 7 can cancel injected
  gestures (the service learns and reports `gesturesBlocked`).

## Consent classes (single source of truth)

Every agentctl command declares one of four classes in a code-level
registry (`REGISTRY` in `AgentAccessibilityService.kt`). The consent
gate enforces from it; the `commands` index, `help`, and the `access`
manifest render from it. Docs cannot drift from enforcement — they are
the same table.

| Class | Meaning | Commands |
|---|---|---|
| `ungated` | introspection, app-own diagnostics | ping, commands, access, capabilities, status, session, notifstatus, logtail |
| `grant-is-consent` | scoped telemetry — the Android permission grant IS the consent | health, location |
| `session-gated` | all UI-level device control — requires approved overlay session (audit R7-6/R7-2) | screen, tree, click, clickId, text, tap, longPress, swipe, key, launch, notiflist, hcrequest, back, home, notifications, am, pm, keyevent |
| `client-local` | runs in the agent shell, never crosses the gated socket | notify |

`am`/`pm`/`keyevent` are session-gated **client-side**: agentctl checks the
live service session state before running them (fail-closed — no service
response means refusal). Their named twins `launch`/`key` are gated
server-side; the passthroughs expose the same capabilities, so they carry
the same class (Oct 8 audit R7-2).

Adding a command: add it to `REGISTRY` with a consent class (CI fails
without one — see `CommandRegistryTest`). Client/server drift is also a
test failure: the test parses the shipped `agentctl.sh` and asserts every
command it can send exists in the registry.

## Known asymmetries (by design)

- **Phone** shows a battery-optimization banner (One UI kills
  unexempted apps). **Watch** (One UI Watch) has no per-app battery
  controls at all — the foreground-service exempted bucket is the only
  protection, so no banner is shown (a check with no fix path would be a
  dead UI).
- Both devices share one APK; form factor is detected at runtime
  (`isScreenRound`).
