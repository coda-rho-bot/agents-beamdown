# Security Policy — Agents Beamdown

## Trust model (honest scope)

This app runs an agent environment (Letta Code) on a personal Android
device, on purpose. The design goal is **scoping with owner consent**,
not confinement of untrusted code:

- **The shell door is trusted by design.** Whatever the registered Letta
  account dispatches executes with the app uid's full reach: app storage
  and /sdcard read/write. If that account or the Letta Cloud linkage is
  compromised, this device's shared storage is reachable. That is the
  accepted autonomy trade-off — the mitigation is account security, not
  this app.
- **The consent overlay defends user attention, not the kernel.** It
  guarantees privacy-bearing UI actions (screen read, taps, typing,
  launches, notification content) surface a full-screen prompt the
  device owner must approve. It is fail-closed (120s timeout = deny).
  It does not (and cannot) constrain the shell door.
- **No privilege anywhere.** No root, no system writes, app-sandbox
  confinement, /system read-only. The app requests the minimum for its
  function: accessibility service (UI channel), Health Connect +
  location (telemetry), notification listener (notiflist), foreground
  service.

## What the consent gate does NOT defend

- The agent shell (file-level access described above).
- Same-uid processes on the device (Android already confines other
  apps; the agentctl socket additionally rejects non-same-uid peers).
- Physical access — the device owner is presumed present (they approve
  overlays).

## Reporting

Open an issue at git.angussoftware.dev/coda/agents-beamdown (private)
or the public mirror github.com/coda-rho-bot/agents-beamdown. Include
`agentctl access` output (redact nothing — it carries no secrets) and
the app version.
