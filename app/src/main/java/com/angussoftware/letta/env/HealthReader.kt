package com.angussoftware.letta.env

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.time.Duration
import java.time.Instant

/**
 * HealthReader — on-demand health telemetry for agentctl (docs/telemetry-spec.md §1).
 *
 * Phone path: Health Connect framework module (A14+) reading what Samsung
 * Health already synced — we never sample sensors ourselves. Watch path:
 * Health Connect does NOT exist on WearOS (Samsung FAQ); graceful fallback
 * (Health Services MeasureClient lands as a follow-up — see status output).
 *
 * HC client calls are suspend functions; agentctl commands run on the socket
 * thread where blocking is expected (up to the client's 15s window), so we
 * bridge with runBlocking — the socket pool has spare threads by design.
 *
 * Every successful read writes filesDir/health.json so the agent can `cat`
 * the last snapshot even if a later read fails.
 *
 * Permission model: the scoped HC grant IS the consent. No overlay. Missing
 * grants return actionable error JSON naming the Settings screen.
 */
object HealthReader {

    private const val CACHE = "health.json"

    val READ_PERMISSIONS = setOf(
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(SleepSessionRecord::class),
    )

    fun status(ctx: Context): JSONObject {
        val out = JSONObject()
        val sdk = HealthConnectClient.getSdkStatus(ctx)
        out.put("sdkAvailable", sdk == HealthConnectClient.SDK_AVAILABLE)
        if (sdk == HealthConnectClient.SDK_AVAILABLE) {
            val client = HealthConnectClient.getOrCreate(ctx)
            val granted = runCatching { runBlocking { client.permissionController.getGrantedPermissions() } }
                .getOrDefault(emptySet())
            out.put("path", "health-connect")
            out.put("granted", granted.sorted())
            out.put("missing", READ_PERMISSIONS.filter { it !in granted }.sorted())
            cacheAgeSec(ctx)?.let { out.put("cacheAgeSec", it) }
        } else {
            out.put("path", "unavailable")
            out.put("note", "Health Connect not present (WearOS). health hr falls back to a LIVE sensor measurement via Health Services MeasureClient.")
        }
        return out
    }

    /** Newest HR sample in the last 24h: {bpm, sampledAt, ageSec, source}.
     *  WearOS fallback (spec §1.3): no Health Connect on watches — when HC is
     *  unavailable, take a LIVE measurement via Health Services MeasureClient
     *  (register → first sample → unregister, 15s timeout). Direct sensor read;
     *  does not depend on Samsung Health sync at all. */
    fun heartRate(ctx: Context): JSONObject {
        connect(ctx) ?: return wearHeartRate(ctx)
        wearFallback = false
        return hcHeartRate(ctx)
    }

    private var wearFallback = false

    private fun wearHeartRate(ctx: Context): JSONObject {
        wearFallback = true
        return try {
            val measure = androidx.health.services.client.HealthServices
                .getClient(ctx).measureClient
            val latch = java.util.concurrent.CountDownLatch(1)
            var sampleJson: JSONObject? = null
            val cb = object : androidx.health.services.client.MeasureCallback {
                override fun onAvailabilityChanged(
                    t: androidx.health.services.client.data.DeltaDataType<*, *>,
                    a: androidx.health.services.client.data.Availability
                ) { /* wait for data */ }
                override fun onDataReceived(d: androidx.health.services.client.data.DataPointContainer) {
                    val dp = d.getData(
                        androidx.health.services.client.data.DataType.HEART_RATE_BPM).firstOrNull()
                    if (dp != null) {
                        sampleJson = JSONObject()
                            .put("ok", true)
                            .put("bpm", dp.value.toInt())
                            .put("sampledAt", System.currentTimeMillis())
                            .put("ageSec", 0)
                            .put("source", "wear-measure-live")
                        latch.countDown()
                    }
                }
            }
            measure.registerMeasureCallback(
                androidx.health.services.client.data.DataType.HEART_RATE_BPM, cb)
            if (!latch.await(15, java.util.concurrent.TimeUnit.SECONDS)) {
                // Best-effort unregister (ListenableFuture via reflection-free
                // listener — avoids guava dep): the callback object going
                // unreferenced stops delivery; sensor registration is dropped
                // by the service when the client process disconnects.
                return JSONObject().put("ok", false)
                    .put("error", "no HR sample within 15s — is the watch worn and the sensor touching skin?")
            }
            val result = sampleJson!!
            cache(ctx, result)
            result
        } catch (e: Exception) {
            JSONObject().put("ok", false)
                .put("error", "wear HR measure failed: ${e.message}")
        }
    }

    private fun hcHeartRate(ctx: Context): JSONObject {
        val client = connect(ctx) ?: return errUnavailable()
        val granted = grantedOf(client)
        val hrPerm = HealthPermission.getReadPermission(HeartRateRecord::class)
        if (hrPerm !in granted) return errPermission("READ_HEART_RATE")

        val now = Instant.now()
        val resp = runBlocking {
            client.readRecords(ReadRecordsRequest(
                HeartRateRecord::class,
                TimeRangeFilter.between(now.minus(Duration.ofHours(24)), now)
            ))
        }
        val sample = resp.records
            .flatMap { r -> r.samples.map { s -> Triple(s.beatsPerMinute, s.time, r.metadata.dataOrigin.packageName) } }
            .maxByOrNull { it.second }
            ?: return JSONObject().put("ok", false)
                .put("error", "no heart-rate samples in the last 24h — " +
                    "is Samsung Health syncing watch data to the phone?")

        val out = JSONObject()
            .put("ok", true)
            .put("bpm", sample.first.toInt())
            .put("sampledAt", sample.second.toEpochMilli())
            .put("ageSec", Duration.between(sample.second, now).seconds)
            .put("source", sample.third)
        cache(ctx, out)
        return out
    }

    /** Step count summed over the last N hours (default 24). */
    fun steps(ctx: Context, hours: Long): JSONObject {
        val client = connect(ctx) ?: return errUnavailable()
        val granted = grantedOf(client)
        if (HealthPermission.getReadPermission(StepsRecord::class) !in granted)
            return errPermission("READ_STEPS")
        val now = Instant.now()
        val resp = runBlocking {
            client.readRecords(ReadRecordsRequest(
                StepsRecord::class,
                TimeRangeFilter.between(now.minus(Duration.ofHours(hours)), now)
            ))
        }
        val total = resp.records.sumOf { it.count }
        return JSONObject().put("ok", true).put("steps", total).put("windowHours", hours)
    }

    /** Latest sleep session summary (from the last N days, default 2). */
    fun sleep(ctx: Context, days: Long): JSONObject {
        val client = connect(ctx) ?: return errUnavailable()
        val granted = grantedOf(client)
        if (HealthPermission.getReadPermission(SleepSessionRecord::class) !in granted)
            return errPermission("READ_SLEEP")
        val now = Instant.now()
        val resp = runBlocking {
            client.readRecords(ReadRecordsRequest(
                SleepSessionRecord::class,
                TimeRangeFilter.between(now.minus(Duration.ofDays(days)), now)
            ))
        }
        val session = resp.records.maxByOrNull { it.startTime }
            ?: return JSONObject().put("ok", false)
                .put("error", "no sleep sessions in the last ${days}d")
        val stages = session.stages.groupingBy { it.stage }.eachCount()
        return JSONObject().put("ok", true)
            .put("start", session.startTime.toEpochMilli())
            .put("end", session.endTime.toEpochMilli())
            .put("durationMin", Duration.between(session.startTime, session.endTime).toMinutes())
            .put("stages", JSONObject(stages.mapKeys { it.key.toString() }))
    }

    // ---- helpers -----------------------------------------------------------------

    private fun connect(ctx: Context): HealthConnectClient? =
        if (HealthConnectClient.getSdkStatus(ctx) == HealthConnectClient.SDK_AVAILABLE)
            HealthConnectClient.getOrCreate(ctx) else null

    private fun grantedOf(client: HealthConnectClient): Set<String> =
        runCatching { runBlocking { client.permissionController.getGrantedPermissions() } }
            .getOrDefault(emptySet())

    private fun errUnavailable() = JSONObject().put("ok", false)
        .put("error", "Health Connect unavailable on this device; " +
            "watch fallback not yet implemented (docs/telemetry-spec.md)")

    private fun errPermission(name: String) = JSONObject().put("ok", false)
        .put("error", "$name not granted — open the Letta Environment app > Telemetry " +
            "and grant health access, then retry")

    private fun cache(ctx: Context, data: JSONObject) {
        runCatching {
            JSONObject().put("at", System.currentTimeMillis()).put("data", data)
                .let { java.io.File(ctx.filesDir, CACHE).writeText(it.toString()) }
        }
    }

    private fun cacheAgeSec(ctx: Context): Long? =
        runCatching {
            val f = java.io.File(ctx.filesDir, CACHE)
            if (f.exists()) (System.currentTimeMillis() - f.lastModified()) / 1000 else null
        }.getOrNull()
}
