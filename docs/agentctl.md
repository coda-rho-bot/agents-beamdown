# agentctl — the agent control protocol

`agentctl` is the client agents use to operate an Agents Beamdown device
(phone or watch) over the accessibility channel. This document is for
developers building agent-side tooling and for anyone auditing what the
channel can do.

**The live truth always wins:** `agentctl access` prints the complete,
probed manifest for THIS device. This page describes the design; that
command measures reality. `agentctl commands` prints the current command
index with consent classes.

## Transport

- Unix-domain socket at `files/agentctl.sock` (mode 600), reachable only
  from the app's own uid — filesystem permissions block other apps at
  connect() time, and peer credentials are re-verified on accept. The agent
  shell (same uid) talks to it via `files/bin/agentctl` (`toybox nc -U`).
- Line protocol: one JSON request per line → one JSON response per line.
- Request shape: `{"cmd":"<name>", ...args}`. Response always carries
  `ok` (bool); failures add `error`.

## Consent model

Four classes, declared per command in the code-level `REGISTRY`
(`AgentAccessibilityService.kt`). The gate enforces from the same table
that generates all documentation — they cannot disagree.

1. **UNGATED** — introspection (`ping`, `commands`, `access`,
   `capabilities`, `status`), consent flow itself (`session`),
   grant-state-only reads (`notifstatus`), the app's own diagnostic log
   (`logtail`).
2. **GRANT_IS_CONSENT** — scoped telemetry where the Android permission
   grant is the consent: `health` (Health Connect), `location`.
3. **SESSION_GATED** — all UI-level device control: `screen`, `tree`,
   `click`, `clickId`, `text`, `tap`, `longPress`, `swipe`, `key`,
   `launch`, `notiflist`, `hcrequest`, navigation (`back`, `home`,
   `notifications` — audit R7-6: back can dismiss security dialogs), and
   the shell passthroughs `am`, `pm`, `keyevent` (audit R7-2: same
   capabilities as their gated named twins). Requires an approved session.
4. **CLIENT_LOCAL** — implemented in the client script itself (agent
   shell): `notify`. Never crosses the gated socket.

   The session-gated passthroughs (`am`, `pm`, `keyevent`) are gated
   client-side: agentctl queries the live service session state before
   running them and refuses without an approved session (fail-closed).

While a consent prompt is on screen, all session-gated actions are paused
(the service refuses them) — an active session cannot keep acting while
the user is deciding, and cannot approve its own renewal prompt.

Sessions: `agentctl session "description" [seconds]` raises a
full-screen overlay on the device. Default TTL 300s, max 3600s. Prompt
timeout 120s; timeout or decline = deny (fail-closed). Check state with
`agentctl status`.

## Client authoring guide

- Run `agentctl access` first. Feature-detect via `manifestVersion` and
  the `commands` array — never assume a command exists.
- Call `agentctl session` before any session-gated command; treat
  denial as final for that action, not a retry-loop.
- Prefer `click "label"` over coordinate taps (OEM gesture filters).
- Respect `gesturesBlocked` in the index — when true, injected gestures
  are dead on this OEM build.
- `am_start` denials (Samsung) have the `launch` workaround.
- Health `hr` returns the newest *synced* sample — it can lag the
  sensor by minutes; it is not a live heart-rate monitor.

## Versioning

- `manifestVersion` 1: current shape (device / filesystem / consentModel
  / commands / shellContext).
- Minor (1.x): additive fields/commands. Clients must tolerate unknown
  fields.
- Major (2): consent-semantics changes (class moves, session rule
  changes). Breaking; gate on `manifestVersion` before using gated
  commands.

## Extension rules

- Every new socket command MUST be added to `REGISTRY` with a consent
  class. CI (`CommandRegistryTest`) fails otherwise — and also fails if
  the shipped `agentctl.sh` sends a command the registry doesn't know.
- Renaming a wire command is a breaking change; prefer adding an alias
  (`clientAlias`) — see `screen` ↔ `screenshot-text`.
- Docs changes that touch consent classes need an ACCESS.md update in
  the same PR.
