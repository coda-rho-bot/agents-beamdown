# SPEC: Health Connect + NotificationListener + Location telemetry

Status: DRAFT — pending owner review · Date: Sep 28, 2026
Adds three read-only telemetry channels to agentctl, permission-gated in-app, never silent.

## Answers to the blocking questions (from docs, verified Sep 28 2026)

**Q: Does WearOS API 36 (Watch 8, WearOS 6) have Health Connect on-device?**
**No.** Samsung's developer FAQ is explicit: "The Health Connect application can be installed on
Android mobile devices. It does not support Wear OS devices." WearOS exposes **Health Services**
(`androidx.health:health-services-client`, stable since May 2025) instead. This app is one APK for
phone + watch (`MainActivity.isWatch()`), so the health reader must branch:
- Phone (ZFold 7, A14+): Health Connect is a framework module → `HealthConnectClient.getSdkStatus()`
  returns `SDK_AVAILABLE`, no provider install needed.
- Watch: `SDK_UNAVAILABLE` → fall back to Health Services (`MeasureClient` on-demand HR sample).
Bonus convergence: WearOS 6 / Android 16 deprecates `BODY_SENSORS` in favor of the *same*
`android.permission.health.READ_HEART_RATE` / `READ_HEALTH_DATA_IN_BACKGROUND` names, so the
manifest block below is correct for both paths. Apps targeting ≤35 get compat auto-request on W6.

**Q: `READ_HEALTH_DATA_IN_BACKGROUND` or foreground read?**
Both, declared together. Our reader runs from a foreground *service* with no visible activity —
the risky case. Docs: foreground reads are normal (a foreground service may carry a read started
while foreground), but long-lived service reads should hold
`android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND`, requested via the **same**
`PermissionController.createRequestPermissionResultContract()` used for per-type grants, and
feature-gated on `HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND`. Sideloaded APK
(no Play Store) → the Play-Console health declaration requirement does not apply.
Also note: Health Connect can read only ~30 days back without `PERMISSION_READ_HEALTH_DATA_HISTORY`
— skip that perm; we only want recent readings.

**Q: Minimum viable?** Yes — one HR reading on demand (see §1.3).

**Q: SkinTemperature?** Type exists (`SkinTemperatureRecord`, deltas vs optional baseline) but is
feature-gated (`FEATURE_SKIN_TEMPERATURE`, needs recent Health Connect) and Samsung Health is the
only realistic writer today. Include the read path but degrade gracefully; don't block on it.

## 1. Health Connect (priority)

### 1.1 Manifest (`app/src/main/AndroidManifest.xml`)
```xml
<uses-permission android:name="android.permission.health.READ_HEART_RATE" />
<uses-permission android:name="android.permission.health.READ_STEPS" />
<uses-permission android:name="android.permission.health.READ_SLEEP" />
<uses-permission android:name="android.permission.health.READ_SKIN_TEMPERATURE" />
<uses-permission android:name="android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND" />
<!-- A14+ framework-module onboarding route -->
<activity-alias android:name="UAndAboveOnboardingActivity" android:exported="true"
    android:targetActivity=".MainActivity"
    android:permission="android.permission.health.START_ONBOARDING">
  <intent-filter><action android:name="android.health.connect.action.SHOW_ONBOARDING" /></intent-filter>
</activity-alias>
```

### 1.2 Gradle (`app/build.gradle.kts` — deps today: only `androidx.core:core-ktx:1.13.1`)
```kotlin
implementation("androidx.health.connect:connect-client:1.2.0")      // phone path
implementation("androidx.health:health-services-client:1.1.0")      // watch path
```
minSdk 26 is fine (HC SDK floor is 26; HC itself needs device API 28+ — phone is A14+).

### 1.3 Reader — new `HealthReader.kt` (com.angussoftware.letta.env)
- `HealthConnectClient.getSdkStatus()`: unavailable → if `isWatch()`, Health Services
  `MeasureClient` one-shot (register → first `HeartRateSample` → unregister, ~5–15s timeout);
  else error JSON with remediation text.
- Available → `getOrCreate()`, check `permissionController.getGrantedPermissions()`.
- **Minimum viable:** `hr` = readRecords(`HeartRateRecord`, last 24h window), take the newest
  sample, return `{bpm, sampledAt, ageSec, source}`. Zero sampling of our own; reads what Samsung
  Health already synced. Steps = aggregate over today; sleep = newest `SleepSessionRecord` stage
  summary; skin = newest record's `baseline`+last delta, only if `FEATURE_SKIN_TEMPERATURE`.
- Writes a `filesDir/health.json` snapshot on each successful read (the "cached/recent" pattern —
  agent can `cat` it even if a read later fails).
- Samsung reality (their FAQ): watch→phone Samsung Health→HC sync follows Samsung's battery-driven
  policy, so "recent HR" may lag minutes; include `ageSec` and never claim it's live.

### 1.4 Command channel wiring
Handlers live in `AgentAccessibilityService.handle()` (the 127.0.0.1:8765 channel `agentctl`
speaks), delegating to `HealthReader`. **Run telemetry on the socket thread** — add `"health"`,
`"notifications"`, `"location"` to the session-style bypass in `execute()` (they touch no a11y
APIs; the 15s main-handler latch is unnecessary and suspend calls shouldn't hold the main thread).
Register in `commandsIndex()` so `agentctl help` stays self-describing.

```
agentctl health status          -> availability, granted perms, watch/phone path, cache age
agentctl health hr              -> single most-recent HR reading (MVP)
agentctl health steps [hours]   -> aggregate step count
agentctl health sleep [days]    -> latest sleep session summary
agentctl health skin            -> latest skin-temp (feature-gated)
```

## 2. NotificationListenerService

### 2.1 Manifest
```xml
<service android:name=".AgentNotificationListener" android:exported="false"
    android:label="@string/notif_listener_label"
    android:permission="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE">
  <intent-filter><action android:name="android.service.notification.NotificationListenerService" /></intent-filter>
</service>
```
No runtime grant exists — this is a **user special-access toggle**:
`Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS` (on One UI: Settings → Notifications → Advanced
settings → Notification history and access). Expose a "Grant" button in the new Settings section,
plus the existing guide-poll pattern from the a11y flow (`MainActivity` polls while user is away).

### 2.2 Listener — new `AgentNotificationListener.kt`
- `onListenerConnected` → snapshot `getActiveNotifications()` into a companion-object ring
  (last 50, refreshed on posted/removed). `agentctl notifications [n]` reads the snapshot via the
  socket. Not connected → error JSON naming the settings screen.
- `exported=false` + binding permission; ignore `extras` beyond package/title/text; truncate text
  at 200 chars (parity with `visibleText()`), drop our own package's notifications.
- **Samsung quirks (known, mostly dev-time):** app occasionally vanishes from the Notification
  access list after reinstall/toggle (SO #65708992, seen on Samsung) — reboot or re-toggle fixes;
  `onListenerConnected` can fire before the grant is real after reinstall (SO #71657831) — so
  treat `enabled_notification_listeners` Secure setting as the truth for status display, not the
  callback; if unbound but granted, call `NotificationListenerService.requestRebind(ComponentName)`
  (API 24+, works on One UI). Ship-time risk is low; probe on the ZFold during P1.

## 3. Location

**No new dependency.** The app depends only on `core-ktx`; adding `play-services-location` for one
subcommand isn't justified (and the sideloaded/rootfs build minimizes deps). Use framework
`LocationManager`:
- Manifest: `ACCESS_FINE_LOCATION` + `ACCESS_COARSE_LOCATION` (fine requires declaring coarse on
  API 31+ even though we target 28 — declare both, request FINE).
- Runtime request from `MainActivity` (extend `ensureRuntimePermissions()`).
- `location`: `getLastKnownLocation()` across GPS/NETWORK/PASSIVE providers → newest, return
  `{lat, lon, accuracy, ageSec}`; null everywhere → on API 30+ try `getCurrentLocation()` once with
  a 15s timeout. Document that last-known may be stale (age in response).
- Watch: same code path works (watch has its own GPS).
- Open risk to probe: on A14+, background-service location reads are meant to ride an FGS of type
  `location` for apps targeting 34+; we target 28 (compat carve-outs apply, and the a11y service
  hosts the read). Probe on the ZFold; if reads throw, fall back to `getCurrentLocation` from a
  short-lived `location`-typed FGS in `LettaEnvironmentService`.

## 4. Permission UX — one place, never silent

New "Telemetry" section in `MainActivity` (phone layout; a compact pill row on the watch layout),
following the existing a11y-status card pattern (live dot + label + action, re-evaluated on the 2s
tick). Each row shows grant state and the exact next action:

| Capability | Grant mechanism | Where consented |
|---|---|---|
| Health (HR/steps/sleep/skin) | `PermissionController` contract from MainActivity | HC onboarding UI (per-type toggles) |
| Health background read | same contract, feature-gated | HC onboarding UI |
| Notifications read | special-access settings toggle | system Notification-access screen |
| Location | standard runtime dialog | system permission dialog |

Gating philosophy (deterministic, in-service, like the session gate):
- `health`/`location`: the scoped permission grant IS the consent — check grants, return
  actionable error JSON (`"error":"READ_HEART_RATE not granted — open the app > Telemetry"`), no
  session overlay.
- `notifications`: reading message content is peer to `screen` → **session-gated** like
  screen/tree/click (add to the gated set in `handle()`, remove `notifications`' current global
  free pass once it becomes a read — the current `notifications` cmd pulls the shade; keep that
  one ungated, name the new read `notiflist` on the wire to avoid the collision).
- Nothing is requested at install or on boot; only from the Settings section or on first use the
  agent gets an error pointing at it.

## 5. Files touched

- `app/src/main/AndroidManifest.xml` — perms + activity-alias + listener service.
- `app/build.gradle.kts` — two health deps.
- `app/src/main/assets/agentctl.sh` — new `health`/`notiflist`/`location` case arms + header docs.
  **Keep `tools/on-device/agentctl.sh` in sync** (established mirror policy).
- `app/src/main/java/com/angussoftware/letta/env/AgentAccessibilityService.kt` — `execute()`
  socket-thread bypass, `handle()` handlers, `commandsIndex()` entries, gate-set change.
- New: `HealthReader.kt`, `AgentNotificationListener.kt` (snapshot + grant-check helpers).
- `app/src/main/java/com/angussoftware/letta/env/MainActivity.kt` — Telemetry section +
  `ensureRuntimePermissions()` extension. Note `LettaEnvironmentService.installAssets()` rewrites
  agentctl from the asset on every install (line ~1059) — no service change needed for rollout.

## 6. Rollout order

1. **Plumbing first** (½ day): wire cmds end-to-end with stub JSON, Settings section with
   state-only rows, agentctl verbs + help sync. De-risks everything after.
2. **Health HR** (owner priority, MVP scope): manifest + deps + `HealthReader` phone path +
   watch MeasureClient fallback. `health status` before the other verbs.
3. **Notifications**: listener + snapshot + special-access grant row; probe the Samsung quirks.
4. **Location**: perms + LocationManager; probe the A14 FGS-typing question.
5. **Health expansion**: steps/sleep, then skin-temp behind its feature flag.
   Rationale: 2 honors priority; 3 and 4 are framework-only (no new deps); 5 lands once the HC
   grant surface is proven on-device. If the connect-client dependency fights the
   minSdk-26/target-28 build, swap 2↔3 (notifications is pure framework) and unblock health next.
