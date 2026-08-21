package com.angussoftware.letta.env

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import java.io.File
import java.nio.file.Files
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Runs the letta server as a cloud execution environment on Android.
 *
 * Architecture (v3 — native lib dir, "everything exec-mapped lives in libdir"):
 *
 * Android's untrusted_app domain DENIES mmap(PROT_EXEC) of app_data_file
 * (files in filesDir), while execve of app_data IS allowed (static binaries
 * run) and the APK native lib dir (apk_data_file label) allows execute
 * mappings unconditionally. Therefore every file the dynamic loader maps —
 * the loader itself, libc & friends, node, guest bash, ripgrep, and all
 * .node native addons — ships in lib/arm64-v8a (injected post-build because
 * AGP filters non-lib*.so names) and is invoked as:
 *
 *   <libdir>/ld-linux-aarch64.so.1 --library-path <libdir> <libdir>/libnode.so <letta.js> server ...
 *
 * Additional Android fixes layered on top:
 *   - letta.js ships PRE-PATCHED: link()/linkSync() lock files replaced with
 *     writeFileSync(flag:"wx") — SELinux denies hardlinks on app_data_file.
 *   - ld-linux + libc patched: rseq syscall site returns -ENOSYS — the app
 *     seccomp filter (old targetSdk policy) TRAPs syscall 293 → SIGSYS.
 *   - dns-shim.js preloaded via NODE_OPTIONS: no /etc/resolv.conf on Android.
 *   - files/bin/bash wrapper: Bash tool → Debian bash via libdir chain.
 *   - rg wrapper script replaces the glibc rg binary (its PT_INTERP doesn't
 *     exist on the host fs); addons + libvips are symlinked into node_modules.
 */
class LettaEnvironmentService : Service() {

    companion object {
        private const val CHANNEL_ID = "letta-env"
        private const val NOTIFICATION_ID = 42
        @Volatile private var proc: Process? = null
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
        val libDir = File(applicationInfo.nativeLibraryDir)
        val libLoader = File(libDir, "ld-linux-aarch64.so.1")

        // Self-heal: corrupted earlier extraction leaves marker but incomplete rootfs.
        if (marker.exists() && !File(rootfsDir, "usr/local/bin/node").exists()) {
            log("marker exists but node missing — rootfs incomplete, re-extracting")
            marker.delete()
            rootfsDir.deleteRecursively()
        }

        if (!marker.exists()) {
            setStatus("extracting rootfs (first run)")
            log("Extracting rootfs...")
            rootfsDir.mkdirs()
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
                if (tarErrLines <= 20) log("tar: $it")
            }
            val tarExit = tar.waitFor()
            if (tarExit != 0) {
                // toybox tar skips /etc/alternatives absolute symlinks and exits 1 — tolerate
                log("tar exited $tarExit ($tarErrLines warning lines) — tolerating")
            }
            assetCopy.delete()
            if (!File(rootfsDir, "usr/local/bin/node").exists()) {
                rootfsDir.deleteRecursively()
                setStatus("failed: rootfs extraction incomplete")
                updateNotification("Extraction failed")
                log("Extraction verification failed — node missing after tar")
                return
            }
            marker.createNewFile()
            log("Rootfs extracted.")
        }

        if (!libLoader.exists()) {
            setStatus("failed: native lib dir missing loader (${libDir.absolutePath})")
            log("FATAL: $libLoader not found — APK injection missing?")
            return
        }

        installSupportFiles(rootfsDir, libDir)

        val libPath = libDir.absolutePath
        val lettaJs = File(rootfsDir, "usr/local/lib/node_modules/@letta-ai/letta-code/letta.js").absolutePath

        runDiagnostics(libDir, rootfsDir)

        setStatus("starting letta server")
        log("Launching letta server (env: ${BuildConfig.ENV_NAME}, libdir: $libPath)...")

        val dnsShim = File(filesDir, "dns-shim.js").absolutePath
        val bashDir = File(filesDir, "bin")

        val pb = ProcessBuilder(
            libLoader.absolutePath, "--library-path", libPath,
            File(libDir, "libnode.so").absolutePath,
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
            put("PATH", "${bashDir.absolutePath}:/system/bin:/system/xbin")
            put("TERM", "dumb")
            put("LD_LIBRARY_PATH", libPath)
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
     * Minimal staged verification that the libdir loader chain works.
     *   libdir-true  → loader + libc + guest binary, all from libdir
     *   libdir-node  → full node startup via the production invocation
     */
    private fun runDiagnostics(libDir: File, rootfsDir: File) {
        val loader = File(libDir, "ld-linux-aarch64.so.1").absolutePath
        val libPath = libDir.absolutePath
        data class T(val name: String, val cmd: List<String>)
        val tests = listOf(
            T("libdir-true", listOf(loader, "--library-path", libPath, File(libDir, "libtrue.so").absolutePath)),
            T("libdir-node", listOf(loader, "--library-path", libPath, File(libDir, "libnode.so").absolutePath, "--version"))
        )
        for (t in tests) {
            try {
                val p = ProcessBuilder(t.cmd).redirectErrorStream(true).start()
                val out = StringBuilder()
                val reader = Thread { p.inputStream.bufferedReader().forEachLine { out.appendLine(it) } }
                reader.isDaemon = true
                reader.start()
                val finished = p.waitFor(10, TimeUnit.SECONDS)
                if (!finished) {
                    p.destroyForcibly()
                    log("DIAG ${t.name}: TIMEOUT (killed)")
                    continue
                }
                log("DIAG ${t.name}: exit=${p.exitValue()} tail='${out.toString().trim().takeLast(150)}'")
            } catch (e: Exception) {
                log("DIAG ${t.name}: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    /**
     * Runtime wiring that depends on the per-install libdir path:
     *  - files/bin/bash      → wrapper chaining into libdir Debian bash
     *  - node_modules rg     → wrapper script delegating to libdir/librg.so
     *  - native addons       → symlinks from node_modules into libdir
     *  - files/dns-shim.js   → copied from assets
     */
    private fun installSupportFiles(rootfsDir: File, libDir: File) {
        val libPath = libDir.absolutePath
        val loaderFlag = "${File(libDir, "ld-linux-aarch64.so.1").absolutePath} --library-path $libPath"

        val shim = File(filesDir, "dns-shim.js")
        if (!shim.exists()) {
            assets.open("dns-shim.js").use { input ->
                shim.outputStream().use { output -> input.copyTo(output) }
            }
            log("dns-shim.js installed")
        }

        val binDir = File(filesDir, "bin")
        binDir.mkdirs()
        val bash = File(binDir, "bash")
        bash.writeText("#!/system/bin/sh\nexec $loaderFlag ${File(libDir, "libguestbash.so").absolutePath} \"$@\"\n")
        bash.setExecutable(true, false)
        log("bash wrapper installed")

        // ripgrep: replace the glibc binary with a delegating script
        val nmRoot = File(rootfsDir, "usr/local/lib/node_modules/@letta-ai/letta-code/node_modules")
        val rg = File(nmRoot, "@vscode/vscode-ripgrep-linux-arm64/bin/rg")
        val librg = File(libDir, "librg.so")
        if (rg.exists() && librg.exists()) {
            rg.writeText("#!/system/bin/sh\nexec $loaderFlag ${librg.absolutePath} \"$@\"\n")
            rg.setExecutable(true, false)
            log("rg wrapper installed")
        }

        // native addons: symlink into libdir (dlopen'd with PROT_EXEC)
        val linkTargets = listOf(
            File(nmRoot, "node-pty/build/Release/pty.node"),
            File(nmRoot, "@img/sharp-linux-arm64/lib/sharp-linux-arm64.node"),
            File(nmRoot, "@img/sharp-libvips-linux-arm64/lib/libvips-cpp.so.8.17.3")
        )
        for (t in linkTargets) {
            try {
                val libCopy = File(libDir, t.name)
                if (libCopy.exists() && t.exists() && !Files.isSymbolicLink(t.toPath())) {
                    t.delete()
                    Files.createSymbolicLink(t.toPath(), libCopy.toPath())
                    log("linked ${t.name} -> libdir")
                }
            } catch (e: Exception) {
                log("WARN: link failed for ${t.name}: ${e.message}")
            }
        }
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
