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
 *   <libdir>/libldlnx.so --library-path <libdir> <libdir>/libnode.so <letta.js> server ...
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
        const val PREFS = "letta_env"
        const val PREF_KEY = "api_key"
        const val PREF_ENV = "env_name"
        const val DEFAULT_ENV = "android"
    }

    private fun apiKey(): String =
        getSharedPreferences(PREFS, MODE_PRIVATE).getString(PREF_KEY, "") ?: ""

    private fun envName(): String =
        getSharedPreferences(PREFS, MODE_PRIVATE).getString(PREF_ENV, DEFAULT_ENV) ?: DEFAULT_ENV

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
        if (apiKey().isBlank()) {
            setStatus("no api key — open the app to configure")
            updateNotification("Needs API key")
            log("No API key configured; launch blocked until onboarding completes.")
            return
        }
        val rootfsDir = File(filesDir, "rootfs")
        val marker = File(filesDir, ".rootfs-extracted")
        val libDir = File(applicationInfo.nativeLibraryDir)
        val libLoader = File(libDir, "libldlnx.so")

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
        log("Launching letta server (env: ${envName()}, libdir: $libPath)...")

        val dnsShim = File(filesDir, "dns-shim.js").absolutePath
        val bashDir = File(filesDir, "bin")

        val filesLibs = File(filesDir, "libs")
        val combinedPath = libPath + ":" + filesLibs.absolutePath
        // Launch via a script that mirrors the PROVEN adb test invocation:
        // shell-redirected stdio (> log 2>&1 < /dev/null) avoids node's
        // uv__close(fd<=2) assertion with Java ProcessBuilder fd plumbing.
        val launchScript = File(filesDir, "launch-server.sh")
        launchScript.writeText(
            "#!/system/bin/sh\n" +
            "cd ${filesDir.absolutePath}\n" +
            "export HOME=${File(rootfsDir, "root").absolutePath}\n" +
            "export TMPDIR=${File(rootfsDir, "tmp").absolutePath}\n" +
            "export PATH=${bashDir.absolutePath}:/system/bin:/system/xbin\n" +
            "export TERM=dumb\n" +
            "export LETTA_API_KEY=${apiKey()}\n" +
            "export LD_LIBRARY_PATH=$combinedPath\n" +
            "export NODE_OPTIONS=\"--require ${File(filesDir, "dns-shim.js").absolutePath}\"\n" +
            "export UV_USE_IO_URING=0\n" +
            "exec ${libLoader.absolutePath} --library-path $combinedPath ${File(libDir, "libnode.so").absolutePath} $lettaJs server --env-name ${envName()} --debug > ${File(filesDir, "server-stdout.log").absolutePath} 2>&1 < /dev/null\n"
        )
        val stdinFile = File(filesDir, "stdin.txt")
        if (!stdinFile.exists()) stdinFile.writeText("")
        launchScript.setExecutable(true, false)

        val pb = ProcessBuilder(launchScript.absolutePath)

        val p = pb.start()
        proc = p
        log("Process started, streaming output (via server-stdout.log)...")

        // Output goes to a file (valid fd for the child); tail it for status.
        val outLogFile = java.io.File(filesDir, "server-stdout.log")
        val tailer = Thread {
            var pos = 0L
            while (proc?.isAlive != false) {
                try {
                    if (outLogFile.length() > pos) {
                        java.io.RandomAccessFile(outLogFile, "r").use { raf ->
                            raf.seek(pos)
                            var line = raf.readLine()
                            while (line != null) {
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
                                        updateNotification("Online — ${envName()}")
                                    }
                                }
                                pos = raf.filePointer
                                line = raf.readLine()
                            }
                        }
                    }
                } catch (_: Exception) {}
                Thread.sleep(500)
            }
        }
        tailer.isDaemon = true
        tailer.start()

        val code = p.waitFor()
        log("letta server exited: $code")
        setStatus("exited: $code")
        updateNotification("Stopped (exit $code)")
    }

    /** In-process mmap-exec matrix: can the ART runtime dlopen exec-mapped files? */
    private fun inProcessMapTests(libDir: File) {
        try {
            System.load(File(libDir, "libzerodep.so").absolutePath)
            log("DIAG sysload-libdir: OK (in-process dlopen of apk lib works)")
        } catch (t: Throwable) {
            log("DIAG sysload-libdir: FAIL ${t.message}")
        }
        try {
            val dataCopy = File(filesDir, "libzerodep-data.so")
            if (!dataCopy.exists()) {
                File(libDir, "libzerodep.so").inputStream().use { input ->
                    dataCopy.outputStream().use { input.copyTo(it) }
                }
            }
            System.load(dataCopy.absolutePath)
            log("DIAG sysload-appdata: OK (in-process dlopen of app_data works)")
        } catch (t: Throwable) {
            log("DIAG sysload-appdata: FAIL ${t.message}")
        }
    }

    /**
     * Staged verification + forensics:
     *   sig-sys / sig-segv  → verify Java exit-value encoding is 128+signal
     *   libdir-true/node    → loader chain sanity
     *   *-catch variants    → LD_PRELOAD SIGSYS catcher prints the trapped
     *                         syscall number (si_syscall) if death occurs
     *                         after preload constructors run
     */
    private fun runDiagnostics(libDir: File, rootfsDir: File) {
        val loader = File(libDir, "libldlnx.so").absolutePath
        val libPath = libDir.absolutePath + ":" + File(filesDir, "libs").absolutePath
        val sigsys = File(libDir, "libsigsys.so").absolutePath
        inProcessMapTests(libDir)
        // auxv dumper: what does the kernel tell this process about its CPU?
        try {
            val auxvBin = File(filesDir, "auxv")
            if (!auxvBin.exists()) {
                assets.open("diag-auxv").use { input -> auxvBin.outputStream().use { input.copyTo(it) } }
                auxvBin.setExecutable(true, false)
            }
            val pa = ProcessBuilder(auxvBin.absolutePath).redirectErrorStream(true).start()
            val ao = pa.inputStream.bufferedReader().readText()
            pa.waitFor()
            log("DIAG auxv: ${ao.replace("\n", " | ").take(400)}")
        } catch (e: Exception) { log("DIAG auxv: FAIL ${e.message}") }
        // strace-lite: ptrace the dying chain, capture every syscall nr
        try {
            val stl = File(filesDir, "stracelite")
            if (!stl.exists()) {
                assets.open("diag-stracelite").use { input -> stl.outputStream().use { input.copyTo(it) } }
                stl.setExecutable(true, false)
            }
            val rl = File(rootfsDir, "lib/aarch64-linux-gnu/ld-linux-aarch64.so.1").absolutePath
            val lp = File(rootfsDir, "lib/aarch64-linux-gnu").absolutePath + ":" + File(rootfsDir, "usr/lib/aarch64-linux-gnu").absolutePath
            val tb = File(rootfsDir, "usr/bin/true").absolutePath
            val lettaJsP = File(rootfsDir, "usr/local/lib/node_modules/@letta-ai/letta-code/letta.js").absolutePath
            val pb3 = ProcessBuilder(stl.absolutePath, File(libDir, "libldlnx.so").absolutePath, "--library-path", libDir.absolutePath + ":" + File(filesDir, "libs").absolutePath, File(libDir, "libnode.so").absolutePath, lettaJsP, "server", "--env-name", envName()).redirectErrorStream(true)
            pb3.environment()["NODE_OPTIONS"] = "--require " + File(filesDir, "dns-shim.js").absolutePath
            pb3.environment()["LETTA_API_KEY"] = apiKey()
            pb3.environment()["HOME"] = File(rootfsDir, "root").absolutePath
            pb3.environment()["TMPDIR"] = File(rootfsDir, "tmp").absolutePath
            pb3.environment()["PATH"] = File(filesDir, "bin").absolutePath + ":/system/bin:/system/xbin"
            pb3.environment().clear()
            val p3 = pb3.start()
            val o3 = StringBuilder()
            val r3 = Thread { try { p3.inputStream.bufferedReader().forEachLine { o3.appendLine(it) } } catch (_: Exception) {} }
            r3.isDaemon = true; r3.start()
            val fin3 = p3.waitFor(15, TimeUnit.SECONDS)
            if (!fin3) { p3.destroyForcibly(); log("DIAG stracelite: TIMEOUT") }
            else {
                val f3 = o3.toString()
                File(filesDir, "diag-stracelite.txt").writeText(f3)
                log("DIAG stracelite: exit=${p3.exitValue()} lines=${f3.lines().size} last10='${f3.trim().lines().takeLast(10).joinToString(" | ")}'")
            }
        } catch (e: Exception) { log("DIAG stracelite: FAIL ${e.message}") }
        // env-clean glibc chain: does clearing the inherited Zygote env fix it?
        try {
            val pb2 = ProcessBuilder(File(rootfsDir, "lib/aarch64-linux-gnu/ld-linux-aarch64.so.1").absolutePath, "--library-path", File(rootfsDir, "lib/aarch64-linux-gnu").absolutePath + ":" + File(rootfsDir, "usr/lib/aarch64-linux-gnu").absolutePath, File(rootfsDir, "usr/bin/true").absolutePath).redirectErrorStream(true)
            pb2.environment().clear()
            pb2.environment()["LD_DEBUG"] = "all"
            val p2 = pb2.start()
            val o2 = StringBuilder()
            val r2 = Thread { try { p2.inputStream.bufferedReader().forEachLine { o2.appendLine(it) } } catch (_: Exception) {} }
            r2.isDaemon = true; r2.start()
            val fin = p2.waitFor(10, TimeUnit.SECONDS)
            if (!fin) { p2.destroyForcibly(); log("DIAG envclean-true: TIMEOUT") }
            else {
                val f2 = o2.toString()
                File(filesDir, "diag-envclean.txt").writeText(f2)
                log("DIAG envclean-true: exit=${p2.exitValue()} len=${f2.length} tail='${f2.trim().takeLast(150)}'")
            }
        } catch (e: Exception) { log("DIAG envclean-true: FAIL ${e.message}") }
        data class T(val name: String, val cmd: List<String>, val env: Map<String, String> = emptyMap())
        val tests = listOf(
            T("sig-sys", listOf("/system/bin/sh", "-c", "kill -SYS \$\$")),
            T("sig-segv", listOf("/system/bin/sh", "-c", "kill -SEGV \$\$")),
            T("exit159", listOf("/system/bin/sh", "-c", "exit 159")),
            T("libdir-true", listOf(loader, "--library-path", libPath, File(libDir, "libtrue.so").absolutePath)),
            T("libdir-true-catch", listOf(loader, "--library-path", libPath, File(libDir, "libtrue.so").absolutePath), mapOf("LD_PRELOAD" to sigsys)),
            T("libdir-true-debug", listOf(loader, "--library-path", libPath, File(libDir, "libtrue.so").absolutePath), mapOf("LD_DEBUG" to "all")),
            T("rootfs-orig-debug", listOf(File(rootfsDir, "lib/aarch64-linux-gnu/ld-linux-aarch64.so.1").absolutePath, "--library-path", File(rootfsDir, "lib/aarch64-linux-gnu").absolutePath + ":" + File(rootfsDir, "usr/lib/aarch64-linux-gnu").absolutePath, File(rootfsDir, "usr/bin/true").absolutePath), mapOf("LD_DEBUG" to "all")),
            T("libdir-node-catch", listOf(loader, "--library-path", libPath, File(libDir, "libnode.so").absolutePath, "--version"), mapOf("LD_PRELOAD" to sigsys))
        )
        for (t in tests) {
            try {
                val pb = ProcessBuilder(t.cmd).redirectErrorStream(true)
                pb.environment().putAll(t.env)
                val p = pb.start()
                val out = StringBuilder()
                val reader = Thread { try { p.inputStream.bufferedReader().forEachLine { out.appendLine(it) } } catch (_: Exception) {} }
                reader.isDaemon = true
                reader.start()
                val finished = p.waitFor(10, TimeUnit.SECONDS)
                if (!finished) {
                    p.destroyForcibly()
                    log("DIAG ${t.name}: TIMEOUT (killed)")
                    continue
                }
                val full = out.toString()
                if (t.name.endsWith("debug")) {
                    File(filesDir, "diag-" + t.name + ".txt").writeText(full)
                }
                log("DIAG ${t.name}: exit=${p.exitValue()} len=${full.length} tail='${full.trim().takeLast(200)}'")
            } catch (e: Exception) {
                log("DIAG ${t.name}: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    /**
     * Runtime wiring that depends on the per-install libdir path:
     *  - files/bin/bash      → wrapper chaining into libdir Debian bash
     *  - native addons       → symlinks from node_modules into libdir
     *  - files/dns-shim.js   → copied from assets
     */
    private fun installSupportFiles(rootfsDir: File, libDir: File) {
        val filesLibs = File(filesDir, "libs")
        val libPath = libDir.absolutePath + ":" + filesLibs.absolutePath
        val loaderFlag = "${File(libDir, "libldlnx.so").absolutePath} --library-path $libPath"

        // original-name glibc libs: copied from rootfs (byte-patched, never patchelf'd).
        // The loader maps app_data files fine (proven); sonames resolve by original names.
        val libSources = listOf(
            File(rootfsDir, "lib/aarch64-linux-gnu") to listOf("libc.so.6", "libm.so.6", "libdl.so.2"),
            File(rootfsDir, "usr/lib/aarch64-linux-gnu") to listOf("libpthread.so.0", "libgcc_s.so.1", "libstdc++.so.6", "libtinfo.so.6", "libresolv.so.2")
        )
        filesLibs.mkdirs()
        for ((dir, names) in libSources) {
            for (n in names) {
                val dst = File(filesLibs, n)
                val src = File(dir, n)
                if (src.exists() && !dst.exists()) {
                    src.copyTo(dst)
                }
            }
        }
        // native addons into files/libs too (original names)
        val nmRoot0 = File(rootfsDir, "usr/local/lib/node_modules/@letta-ai/letta-code/node_modules")
        val addons = listOf(
            File(nmRoot0, "node-pty/build/Release/pty.node"),
            File(nmRoot0, "@img/sharp-linux-arm64/lib/sharp-linux-arm64.node"),
            File(nmRoot0, "@img/sharp-libvips-linux-arm64/lib/libvips-cpp.so.8.17.3")
        )
        for (a in addons) {
            val dst = File(filesLibs, a.name)
            if (a.exists() && !dst.exists()) a.copyTo(dst)
        }
        log("files/libs populated: ${filesLibs.listFiles()?.size} files")

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

        // ripgrep is statically linked (musl) — execve from app_data works as-is.

        // native addons: symlink into libdir (dlopen'd with PROT_EXEC)
        val nmRoot = File(rootfsDir, "usr/local/lib/node_modules/@letta-ai/letta-code/node_modules")
        val linkTargets = listOf(
            File(nmRoot, "node-pty/build/Release/pty.node"),
            File(nmRoot, "@img/sharp-linux-arm64/lib/sharp-linux-arm64.node"),
            File(nmRoot, "@img/sharp-libvips-linux-arm64/lib/libvips-cpp.so.8.17.3")
        )
        for (t in linkTargets) {
            try {
                val libCopy = File(File(filesDir, "libs"), t.name)
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
        File(filesDir, "status.txt").writeText("env=${envName()}\nstate=$s\n")
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
