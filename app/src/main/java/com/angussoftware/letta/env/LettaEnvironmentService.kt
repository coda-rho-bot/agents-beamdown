package com.angussoftware.letta.env

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Runs the letta server as a cloud execution environment on Android.
 *
 * Architecture (v2 — dynamic loader, no proot):
 *   proot's ptrace-based exec interception is blocked (EACCES) by Android 15 SELinux
 *   in untrusted_app/runas domains. Instead we invoke Debian's dynamic loader
 *   directly: it becomes the process image and loads node + all guest libraries
 *   from the extracted rootfs. Direct execve of app_data_file IS permitted.
 *
 * Android compatibility fixes applied here:
 *   1. letta.js ships PRE-PATCHED in the rootfs asset: link()/linkSync() lock-file
 *      creation replaced with writeFileSync(flag:"wx") — Android SELinux denies
 *      hardlink() on app_data_file.
 *   2. dns-shim.js preloaded via NODE_OPTIONS: Android has no /etc/resolv.conf,
 *      so glibc getaddrinfo fails; the shim swaps dns.lookup for c-ares UDP.
 *   3. files/bin/bash wrapper: the Bash tool needs `bash`; Android only ships
 *      /system/bin/sh. The wrapper chains: Android sh (shebang) → Debian loader
 *      → real Debian bash 5.x.
 */
class LettaEnvironmentService : Service() {

    companion object {
        private const val CHANNEL_ID = "letta-env"
        private const val NOTIFICATION_ID = 42
        @Volatile private var proc: Process? = null
        // Single-flight guard: prevents concurrent runEnvironment() calls when
        // multiple start requests arrive during the extraction phase (before the
        // process exists). Two concurrent extractions race on the same cache
        // file and corrupt the rootfs (observed: truncated gzip mid-stream).
        private val starting = java.util.concurrent.atomic.AtomicBoolean(false)
    }

    private var worker: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Starting..."))
        setStatus("starting")

        if (!starting.compareAndSet(false, true)) {
            log("start requested while already starting/running — ignoring")
            return START_STICKY
        }

        worker = Thread {
            try {
                runEnvironment()
            } catch (t: Throwable) {
                log("FATAL: ${t.message}")
                setStatus("failed: ${t.message}")
                updateNotification("Failed")
            } finally {
                starting.set(false)
            }
        }.also { it.start() }

        return START_STICKY
    }

    private fun runEnvironment() {
        val rootfsDir = File(filesDir, "rootfs")
        val marker = File(filesDir, ".rootfs-extracted")
        val loaderFile = File(rootfsDir, "lib/aarch64-linux-gnu/ld-linux-aarch64.so.1")

        // Self-heal: a previously corrupted extraction (e.g. from the concurrent
        // race, or interrupted) leaves the marker but an incomplete rootfs.
        // Detect via the one file we absolutely need and re-extract cleanly.
        if (marker.exists() && !loaderFile.exists()) {
            log("marker exists but loader missing — rootfs incomplete, re-extracting")
            marker.delete()
            rootfsDir.deleteRecursively()
        }

        if (!marker.exists()) {
            setStatus("extracting rootfs (first run)")
            log("Extracting rootfs...")
            rootfsDir.mkdirs()
            // Copy asset to cache then extract with toybox tar (available on Android 10+)
            val assetCopy = File(cacheDir, "rootfs.tgz")
            assets.open("rootfs.tar").use { input ->
                assetCopy.outputStream().use { output -> input.copyTo(output, 1 shl 20) }
            }
            val tar = ProcessBuilder("tar", "-xzf", assetCopy.absolutePath, "-C", rootfsDir.absolutePath)
                .redirectErrorStream(true)
                .start()
            var tarErrLines = 0
            tar.inputStream.bufferedReader().forEachLine {
                tarErrLines++
                if (tarErrLines <= 20) log("tar: $it") // cap log noise
            }
            val tarExit = tar.waitFor()
            if (tarExit != 0) {
                // Android toybox tar skips ~650 absolute-path symlinks under
                // /etc/alternatives (man pages etc.) and exits 1. Non-critical
                // for node/letta — tolerate and continue.
                log("tar exited $tarExit ($tarErrLines warning lines) — tolerating, absolute symlinks skipped")
            }
            assetCopy.delete()
            if (!loaderFile.exists()) {
                // tar "succeeded" but the critical file is absent — extraction
                // was corrupt, not merely missing symlinks. Fail loudly instead
                // of writing the marker over a broken rootfs.
                rootfsDir.deleteRecursively()
                setStatus("failed: rootfs extraction incomplete (loader missing)")
                updateNotification("Extraction failed")
                log("Extraction verification failed — loader missing after tar")
                return
            }
            marker.createNewFile()
            log("Rootfs extracted.")
        }

        installSupportFiles()

        val loader = File(rootfsDir, "lib/aarch64-linux-gnu/ld-linux-aarch64.so.1").absolutePath
        val node = File(rootfsDir, "usr/local/bin/node").absolutePath
        val lettaJs = File(rootfsDir, "usr/local/lib/node_modules/@letta-ai/letta-code/letta.js").absolutePath
        val libPath = listOf(
            File(rootfsDir, "lib/aarch64-linux-gnu"),
            File(rootfsDir, "usr/lib/aarch64-linux-gnu"),
            File(rootfsDir, "usr/local/lib")
        ).joinToString(":") { it.absolutePath }

        setStatus("starting letta server")
        log("Launching letta server via dynamic loader (env: ${BuildConfig.ENV_NAME})...")

        val dnsShim = File(filesDir, "dns-shim.js").absolutePath
        val bashDir = File(filesDir, "bin")

        val pb = ProcessBuilder(
            loader, "--library-path", libPath,
            node,
            lettaJs,
            "server",
            "--env-name", BuildConfig.ENV_NAME,
            "--debug"
        ).redirectErrorStream(true)

        pb.directory(filesDir)

        pb.environment().apply {
            put("LETTA_API_KEY", BuildConfig.LETTA_API_KEY)
            put("HOME", File(rootfsDir, "root").absolutePath)
            put("TMPDIR", File(rootfsDir, "tmp").absolutePath)
            // files/bin (bash wrapper) first, then Android system tools.
            // Rootfs bin dirs must NOT be on PATH: guest binaries can't execve
            // directly (missing ELF interpreter) — they need the loader wrapper.
            put("PATH", "${bashDir.absolutePath}:/system/bin:/system/xbin")
            put("TERM", "dumb")
            put("LD_LIBRARY_PATH", libPath)
            // Android has no /etc/resolv.conf — preload the c-ares DNS shim
            put("NODE_OPTIONS", "--require $dnsShim")
        }

        val p = pb.start()
        proc = p
        log("Process started, streaming output...")

        p.inputStream.bufferedReader().forEachLine { line ->
            log(line)
            when {
                line.contains("Registering with") -> {
                    setStatus("registering with Letta Cloud")
                    updateNotification("Registering...")
                }
                line.contains("Registered successfully") -> {
                    setStatus("registered with Letta Cloud")
                    updateNotification("Registered — online")
                }
                line.contains("[Listen V2]") -> {
                    setStatus("online — listener active")
                    updateNotification("Online — ${BuildConfig.ENV_NAME}")
                }
            }
        }

        val code = p.waitFor()
        log("letta server exited: $code")
        setStatus("exited: $code")
        updateNotification("Stopped (exit $code)")
    }

    /**
     * Runtime support files that depend on absolute paths:
     * - files/dns-shim.js  (from assets; pure JS, path-independent content)
     * - files/bin/bash     (wrapper chaining Android sh → Debian loader → bash)
     */
    private fun installSupportFiles() {
        val shim = File(filesDir, "dns-shim.js")
        if (!shim.exists()) {
            assets.open("dns-shim.js").use { input ->
                shim.outputStream().use { output -> input.copyTo(output) }
            }
            log("dns-shim.js installed")
        }

        val rootfs = File(filesDir, "rootfs")
        val binDir = File(filesDir, "bin")
        binDir.mkdirs()
        val bash = File(binDir, "bash")
        val loader = File(rootfs, "lib/aarch64-linux-gnu/ld-linux-aarch64.so.1").absolutePath
        val libPath = listOf(
            File(rootfs, "lib/aarch64-linux-gnu"),
            File(rootfs, "usr/lib/aarch64-linux-gnu"),
            File(rootfs, "usr/local/lib")
        ).joinToString(":") { it.absolutePath }
        val guestBash = File(rootfs, "usr/bin/bash").absolutePath
        bash.writeText("#!/system/bin/sh\nexec $loader --library-path $libPath $guestBash \"$@\"\n")
        bash.setExecutable(true, false)
        log("bash wrapper installed at ${bash.absolutePath}")
    }

    private fun log(msg: String) {
        val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        File(filesDir, "server.log").appendText("[$ts] $msg\n")
    }

    private fun setStatus(s: String) {
        File(filesDir, "status.txt").writeText("env=${BuildConfig.ENV_NAME}\nstate=$s\n")
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Letta Environment", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Letta Environment")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setOngoing(true)
            .build()

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    override fun onDestroy() {
        proc?.destroy()
        worker?.interrupt()
        super.onDestroy()
    }
}
