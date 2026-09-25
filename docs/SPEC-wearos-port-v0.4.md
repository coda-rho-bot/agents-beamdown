# SPEC: WearOS Port — "Adaptive Watch Agent" (v0.4 track)

Status: DRAFT — pending owner review
Date: Sep 25, 2026
Method: 17-agent adversarial review (4 researchers → 3 competing architects → 9 adversarial reviewers → synthesis), all glm-5.3. Evidence-backed; every "verified" claim below traces to a probe, prior art, or measured arithmetic.

## Target

Samsung Galaxy Watch 8 — WearOS 6.0 (Android 16 base), Exynos W1000 (5-core: 1× A78 @1.6GHz + 4× A55 @1.5GHz), 2GB LPDDR5, 32GB+ storage, 425mAh-class battery, BT-tethered to the ZFold 7 by default. Distribution: adb wireless sideload (no Play Store).

Goal (Harry, verbatim): run the FULL letta server on the watch — "same control as the Android app." The watch is a **cloud execution environment**: it dials OUT to Letta Cloud and receives dispatched agent turns, exactly like the phone environment. It is not a LAN server.

## Owner decisions (Sep 25, 2026)

1. **Full-day untethered required** — not charger-only.
2. **Standalone reachability required** — watch must be usable without the phone nearby.
3. **Wake model: same as the phone app** — the environment receives dispatched turns; no watch-initiated scheduling needed.
4. **LAN/WiFi mode: build it** — with auto-timeout.

## Architecture

### Networking: cloud-native outbound, watch never listens

- The letta server on the watch connects outbound to api.letta.com over the parent-app bridge (ClawWatch pattern — Samsung watches give native child processes no network; all sockets live in the Kotlin parent, bridged via pipes). Proven prior art on Watch 7/8.
- **Phone-tethered (default):** outbound HTTPS rides the BT tether (outbound proxy — allowed). Continuous connection, dispatch latency ≈ phone-class.
- **Standalone (no phone):** watch connects outbound over WiFi (or LTE on Ultra models). The connection duty-cycles: poll every 2–5 min, hold long enough to drain dispatches, disconnect. Dispatch latency: minutes, not seconds — acceptable for agent work. This is NOT the inbound-WiFi battery trap (40–90mW was for holding an inbound listener + association); outbound duty-cycled polling is a fraction of that. P2 measures the real number.
- **LAN mode (opt-in):** tile deep-link (setWifiEnabled is system-app-only since API 29 — we cannot toggle WiFi programmatically), 10-minute hard auto-timeout, surfaces the watch on the LAN like the phone environment. Debug + standalone-bridge path.
- RFCOMM inbound-while-tethered: demoted to optional optimization. Never load-bearing.

### Lifecycle: adaptive persistent process

- Wake-on-event: tile tap, charger connection, or a dispatch arriving via the outbound tunnel.
- Process starts and STAYS alive while active; parks to low-power long-poll after idle timeout; relaunchable via tile after swipe-dismiss.
- Charger-connected → always-on.
- No exact alarms (SCHEDULE_EXACT_ALARM denied by default on WearOS; Samsung force-stop cancels pending alarms). The outbound long-poll IS the wake channel.
- Continuous-by-default and 55-min-latency duty-cycle were both killed in adversarial review; this is the survivor.

### Maintenance prerequisite (BLOCKING — before any port code)

Single shared patch-pattern artifact: one declarative pattern file (JSON) consumed by (a) tools/rootfs-build/patch-binaries.py, (b) the phone Kotlin service, (c) the watch Kotlin service. A 0-site match becomes a HARD BOOT FAILURE with a surfaced error — never the silent log line that caused the Sep 25 incident (0.33.2 renamed link3→link4; mirror matched 0 sites; server EACCES-died on every start until hot-patched on-device).

Watch rootfs generated FROM the phone rootfs by a prune script (no independent prune list to rot; CI diff-checks dep creep).

npm-upgrade-over-bridge: validate in week 1 or the watch is declared upgrade-frozen (upgrades flow phone-first, watch follows via re-staged rootfs).

## Phases (go/no-go gates, calibrated per adversarial review)

**P0 — Recon + real-APK probe (Days 1–3)**
- WearOS 6 = A16 → `--bypass-low-target-sdk-block` exists. Sideload the actual phone APK unmodified.
- Check `getconf PAGESIZE` (16KB risk on newest devices → rootfs ELF rebuild branch).
- Probe: exec trampoline under the real loader chain; glibc userspace boots; proot child spawn executes (git, bash — the things MemFS needs).
- Companion grant via Galaxy Wearable phone app; disable Sleeping-apps per-app.
- GO: trampoline + child spawn work. KILL: SELinux denial on either → DEAD (no watch port possible).

**P1 — Bridge + FGS endurance (Days 3–6)**
- Replicate ClawWatch parent-bridge; verify outbound socket from the Node child through the bridge over BT tether.
- FGS `specialUse` endurance: 4h+ soak (30 min is exactly the companion-grant kill latency — a coin flip proves nothing), with swipe-dismiss and BT→WiFi handover injected mid-soak.
- GO: bridge passes data; FGS alive at 4h. KILL: FGS dies <2h despite grants → DEAD.

**P2 — Power + RAM soak (Days 6–10)**
- `dumpsys batterystats` over 4–6h with idle baseline (battery-% ticks are noise: 1 tick = 16mWh). Measure the TARGET lifecycle (adaptive + standalone duty-cycle), not continuous Node.
- RAM/LMK under real 250–400MB load; kill-recovery via supervisor.
- GO: ≤10%/day adaptive; LMK kills recoverable ≤1/day. KILL: >20%/day in target lifecycle, or unrecoverable LMK thrash → charger-only scope or abandon.

**P3 — Port (Weeks 2–3)**
- Wear flavor (targetSdk 35, specialUse FGS, FOREGROUND_SERVICE_SPECIAL_USE + subtype declaration), no a11y, no /sdcard bind.
- NetBridge.kt (parent-bridge), adaptive supervisor, tile UI, pruned rootfs, shared patch artifact wired in.
- Upgrade path: validated or frozen (decision from week 1).

**P4 — 72h soak + handoff (Week 3)**
- Full-day untethered test (Harry's requirement #1 is the acceptance bar): watch survives a full day off charger with the adaptive lifecycle, standalone mode included.

## Honest cost (from the review's arithmetic)

- Adaptive lifecycle: ~4.5–7%/day typical; standalone WiFi duty-cycle adds ~10–20%/day worst case (P2 measures).
- Thermal: non-issue (<0.5°C at these draws).
- RAM: 250–400MB of ~1GB free — feasible, fragile; supervisor + pidfile recovery for LMK kills.
- Cold start 2–5s per wake.
- Full-day untethered: viable per the model (28–31h projected); P4 proves it.

## Confidence

~65% overall. High: exec/bridge mechanics (Termux-on-Watch-4, ClawWatch prior art). Medium: FGS endurance, power model. Low (hence demoted/probed): RFCOMM inbound, sideload edge cases.

## Open items

- Watch 8 vs 8 Ultra: LTE availability (affects standalone mode quality, not architecture).
- The four Angus PR-#21 follow-up findings (dead notify, uid-gate, gesture deadlock, JSON quoting) — file as issues when a write:issue-scoped token is available; they ride the phone app, not this spec.
