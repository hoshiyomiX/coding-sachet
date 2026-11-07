package id.codingsachet.mobile.runtime

import android.app.Application
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import id.codingsachet.mobile.BuildConfig
import id.codingsachet.mobile.api.AgentApi
import id.codingsachet.mobile.data.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/** Lifecycle of the local agent server: install → start → supervise → restart. */
sealed class RuntimeState {
    data object NotInstalled : RuntimeState()
    data class Installing(val step: String, val progress: Float?, val detail: String = "") : RuntimeState()
    data class InstallFailed(val message: String) : RuntimeState()
    data object Stopped : RuntimeState()
    data object Starting : RuntimeState()
    data class Running(val version: String) : RuntimeState()
    data class Crashed(val message: String) : RuntimeState()
}

/**
 * Installs the Ubuntu rootfs + Node.js + agent server, then keeps
 * `node /opt/sachet/server.js` alive inside proot.
 */
class RuntimeController(
    private val app: Application,
    val paths: RuntimePaths,
    private val prefs: Prefs,
    private val api: AgentApi,
) {
    companion object {
        // The environment ships inside the APK (assets/env), prepared by CI
        // with ultra compression (xz). Nothing is downloaded at first run.
        // Asset names are arch-neutral: each CI build pass fills them with the
        // matching rootfs/node for its ABI (arm64-v8a, armeabi-v7a, x86_64).
        const val ROOTFS_ASSET = "env/ubuntu-rootfs.tar.xz"
        const val NODE_ASSET = "env/node-runtime.tar.xz"

        const val NODE_GUEST = "/usr/local/bin/node"
        const val SERVER_JS_GUEST = "/opt/sachet/server.js"
        val SERVER_FILES = listOf("config.js", "agent.js", "tools.js", "server.js")

        // The pinned Node builds need libatomic.so.1, which the minimal
        // ubuntu-base rootfs does not ship (verified per-arch via readelf
        // against the extracted rootfs: every other NEEDED library is already
        // present). The per-arch library travels inside the APK and is copied
        // into the rootfs under its guest multiarch directory.
        private val GUEST_LIB_DIR = "/usr/lib/${BuildConfig.GUEST_TRIPLET}"
        private val RUNTIME_LIBS = listOf("libatomic.so.1")

        const val TAG = "Sachet"
    }

    val state = MutableStateFlow<RuntimeState>(
        if (prefs.runtimeReady && paths.nodeBin.exists()) RuntimeState.Stopped else RuntimeState.NotInstalled
    )
    val log = MutableStateFlow<List<String>>(emptyList())

    private var process: Process? = null
    @Volatile private var stopRequested = false
    @Volatile private var installing = false
    @Volatile var userStopped = false

    fun logLine(s: String) {
        Log.i(TAG, s)
        log.update { (it + s).takeLast(500) }
    }

    // ---------------------------------------------------------------- install

    suspend fun install() = withContext(Dispatchers.IO) {
        if (installing) return@withContext
        installing = true
        try {
            step("Bersiap", null)
            paths.libDir.mkdirs()
            File(paths.nativeDir, "libtalloc.so").copyTo(File(paths.libDir, "libtalloc.so.2"), overwrite = true)

            if (!paths.rootfsReady) {
                step("Membongkar Ubuntu 24.04 (dari APK)", 0f)
                paths.rootfs.deleteRecursively()
                val r = extractAsset(ROOTFS_ASSET, paths.rootfs) { d, t ->
                    step("Membongkar Ubuntu 24.04", frac(d, t), mb(d, t))
                }
                logLine("rootfs: ${r.files} file (${r.skipped} dilewati)")
                configureRootfs()
            }

            if (!paths.nodeBin.exists()) installNode()

            writeServerFiles()
            ensureRuntimeLibs()
            smokeTest()

            prefs.runtimeReady = true
            state.value = RuntimeState.Stopped
        } catch (e: Throwable) {
            logLine("Instalasi gagal: ${e.message}")
            state.value = RuntimeState.InstallFailed(e.message ?: e.javaClass.simpleName)
        } finally {
            installing = false
        }
    }

    /** Stream an env archive out of the APK assets and extract it. */
    private fun extractAsset(
        name: String,
        dest: File,
        onProgress: (Long, Long) -> Unit,
    ): TarExtract.Result {
        val len = runCatching { app.assets.openFd(name).length }.getOrDefault(-1L)
        return app.assets.open(name).use { input ->
            TarExtract.extractStream(input, len, dest, onProgress)
        }
    }

    private fun installNode() {
        step("Memasang Node.js ${BuildConfig.NODE_VERSION} (dari APK)", 0f)
        val staging = File(paths.tmp, "node-extract")
        staging.deleteRecursively()
        extractAsset(NODE_ASSET, staging) { d, t -> step("Memasang Node.js ${BuildConfig.NODE_VERSION}", frac(d, t), mb(d, t)) }
        // The tarball contains one top directory (node-vX…-linux-<arch>/);
        // its children become /usr/local/*.
        val top = staging.listFiles()?.firstOrNull { it.isDirectory }
            ?: error("Arsip Node tidak berisi direktori")
        val local = File(paths.rootfs, "usr/local")
        local.mkdirs()
        top.listFiles()?.forEach { entry ->
            val dest = File(local, entry.name)
            if (dest.exists()) dest.deleteRecursively()
            entry.copyRecursively(dest)
        }
        // Ensure the binary is executable.
        File(local, "bin/node").setExecutable(true, false)
        staging.deleteRecursively()
        logLine("Node.js ${BuildConfig.NODE_VERSION} terpasang")
    }

    /** Copy the agent-server JS from assets into the rootfs. */
    private fun writeServerFiles() {
        step("Memasang agent server", null)
        paths.serverDir.mkdirs()
        for (name in SERVER_FILES) {
            val text = app.assets.open("server/$name").bufferedReader().readText()
            File(paths.serverDir, name).writeText(text)
        }
        prefs.serverFilesVersion = BuildConfig.VERSION_CODE
    }

    /** Rewrite the JS files when the app was updated. */
    private fun ensureServerFilesCurrent() {
        if (prefs.serverFilesVersion != BuildConfig.VERSION_CODE) {
            runCatching { writeServerFiles() }
                .onFailure { logLine("Gagal memperbarui file server: ${it.message}") }
            // Heal rootfses created by older app versions (e.g. 1.0.6 without libatomic).
            runCatching { ensureRuntimeLibs() }
                .onFailure { logLine("Gagal memperbarui runtime libs: ${it.message}") }
        }
    }

    /** Copy the shared libraries bundled in the APK into the guest rootfs. */
    private fun ensureRuntimeLibs() {
        for (name in RUNTIME_LIBS) {
            val dest = paths.host("$GUEST_LIB_DIR/$name")
            dest.parentFile?.mkdirs()
            app.assets.open("runtime-libs/${runtimeLibAsset(name)}").use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
        }
    }

    /** The bundled copy of a runtime lib matching this build's guest arch. */
    private fun runtimeLibAsset(name: String): String = when (name) {
        "libatomic.so.1" -> when (BuildConfig.GUEST_TRIPLET) {
            "arm-linux-gnueabihf" -> "libatomic-armhf.so"
            "x86_64-linux-gnu" -> "libatomic-amd64.so"
            else -> "libatomic-arm64.so"
        }
        else -> name
    }

    private suspend fun smokeTest() {
        step("Menguji startup", null)
        suspend fun once(): Pair<Int, String> {
            val out = StringBuilder()
            val code = runGuest(listOf(NODE_GUEST, "--version"), timeoutSec = 120) { out.appendLine(it) }
            return code to out.toString()
        }
        var (code, out) = once()
        if (code != 0 || !Regex("v\\d+\\.\\d+\\.\\d+").containsMatchIn(out)) {
            logLine("Percobaan pertama gagal (kode $code), mencoba mode kompatibilitas")
            prefs.noSeccomp = true
            val second = once()
            code = second.first; out = second.second
            if (code != 0 || !Regex("v\\d+\\.\\d+\\.\\d+").containsMatchIn(out)) {
                prefs.noSeccomp = false
                error("Node tidak bisa berjalan (kode $code): ${out.trim().takeLast(300)}")
            }
        }
        logLine("Node: ${out.trim()}")
    }

    private fun configureRootfs() {
        fun write(guest: String, text: String) {
            val f = paths.host(guest)
            f.parentFile?.mkdirs()
            f.delete()
            f.writeText(text)
        }
        write("/etc/resolv.conf", "nameserver 1.1.1.1\nnameserver 8.8.8.8\n")
        write("/etc/hosts", "127.0.0.1 localhost\n::1 localhost ip6-localhost\n")
        write("/etc/hostname", "sachet\n")
        // apt inside proot cannot drop privileges to the _apt user.
        write("/etc/apt/apt.conf.d/99proot", "APT::Sandbox::User \"root\";\nAcquire::Retries \"3\";\n")
        val pem = app.assets.open("cacert.pem").use { it.readBytes() }
        for (p in listOf("/etc/ssl/certs/ca-certificates.crt", "/etc/ssl/cert.pem")) {
            val f = paths.host(p)
            f.parentFile?.mkdirs()
            f.delete()
            f.writeBytes(pem)
        }
        paths.workdir.mkdirs()
        paths.host("/tmp").mkdirs()
        File(paths.rootfs, ".sachet-configured").writeText("1")
    }

    private fun step(name: String, p: Float?, detail: String = "") {
        state.value = RuntimeState.Installing(name, p, detail)
    }

    private fun frac(d: Long, t: Long): Float = if (t > 0) (d.toFloat() / t).coerceIn(0f, 1f) else 0f

    private fun mb(d: Long, t: Long): String =
        if (t > 0) "%.0f / %.0f MB".format(d / 1e6, t / 1e6) else "%.0f MB".format(d / 1e6)

    // ---------------------------------------------------------------- run

    suspend fun supervise() = withContext(Dispatchers.IO) {
        stopRequested = false
        var failures = 0
        var windowStart = System.currentTimeMillis()
        while (isActive && !stopRequested) {
            state.value = RuntimeState.Starting
            killLeftoverServer()
            var waited = 0
            while (isActive && portBusy() && waited < 8_000) {
                delay(250); waited += 250
            }
            if (waited > 0) logLine("Menunggu port bebas selama ${waited}ms")

            ensureServerFilesCurrent()

            val pb = launcher().processBuilder(
                listOf(NODE_GUEST, SERVER_JS_GUEST),
                cwd = "/root",
                extraEnv = mapOf("SACHET_PORT" to prefs.serverPort.toString()),
            )
            val p = try {
                pb.start()
            } catch (e: Exception) {
                state.value = RuntimeState.Crashed("Tidak bisa memulai server: ${e.message}")
                return@withContext
            }
            process = p
            val reader = launch { runCatching { p.inputStream.bufferedReader().forEachLine { logLine(it) } } }
            try {
                var version: String? = null
                val deadline = System.currentTimeMillis() + 120_000
                while (isActive && p.isAlive && System.currentTimeMillis() < deadline && version == null) {
                    version = api.health()
                    if (version == null) delay(300)
                }
                if (version != null) {
                    state.value = RuntimeState.Running(version)
                    failures = 0
                }
                // Watchdog: process alive but not answering → restart it.
                val dog = launch {
                    var bad = 0
                    while (isActive && p.isAlive) {
                        delay(15_000)
                        val ok = runCatching {
                            kotlinx.coroutines.withTimeout(10_000) { api.health() }
                        }.isSuccess
                        bad = if (ok) 0 else bad + 1
                        if (bad >= 3) {
                            logLine("Server berhenti menjawab — memulai ulang")
                            p.destroy()
                            break
                        }
                    }
                }
                runInterruptible { p.waitFor() }
                dog.cancel()
            } finally {
                p.destroy()
                if (p.isAlive) {
                    runCatching { p.waitFor(3, TimeUnit.SECONDS) }
                    if (p.isAlive) p.destroyForcibly()
                }
                process = null
                reader.cancel()
            }
            if (stopRequested) break
            val code = runCatching { p.exitValue() }.getOrDefault(-1)
            logLine("Server keluar (kode $code)")
            val now = System.currentTimeMillis()
            if (now - windowStart > 120_000) {
                failures = 0
                windowStart = now
            }
            failures++
            state.value = RuntimeState.Crashed("Server berhenti (kode $code). Memulai ulang…")
            delay((1500L * failures).coerceAtMost(30_000))
        }
        state.value = RuntimeState.Stopped
    }

    private fun launcher() = ProotLauncher(paths, prefs.noSeccomp)

    fun portBusy(): Boolean {
        val port = prefs.serverPort
        return try {
            java.net.Socket().use { it.connect(java.net.InetSocketAddress("127.0.0.1", port), 300); true }
        } catch (_: Exception) {
            false
        }
    }

    /** Kill node processes left over inside the guest from an earlier run. */
    fun killLeftoverServer() {
        runCatching {
            val p = launcher().processBuilder(
                listOf("/bin/sh", "-c", "pkill -f '[s]achet/server.js'; sleep 0.4; pkill -9 -f '[s]achet/server.js'; exit 0"),
                cwd = "/root",
            ).start()
            if (!p.waitFor(8, TimeUnit.SECONDS)) p.destroyForcibly()
        }
    }

    fun requestStop() {
        stopRequested = true
        process?.destroy()
        Thread { killLeftoverServer() }.apply { isDaemon = true }.start()
    }

    fun startServer() {
        val s = state.value
        if (s is RuntimeState.NotInstalled || s is RuntimeState.Installing || s is RuntimeState.InstallFailed) return
        userStopped = false
        ContextCompat.startForegroundService(app, Intent(app, ServerService::class.java).setAction(ServerService.ACTION_START))
    }

    fun stopServer() {
        userStopped = true
        requestStop()
        app.stopService(Intent(app, ServerService::class.java))
        if (state.value !is RuntimeState.NotInstalled) state.value = RuntimeState.Stopped
    }

    fun restartServer() {
        stopServer()
        Thread {
            var waited = 0
            while (waited < 10_000 && (process != null || portBusy())) {
                Thread.sleep(200); waited += 200
            }
            killLeftoverServer()
            startServer()
        }.apply { isDaemon = true }.start()
    }

    /** Run a command inside the guest (used for apt tooling). */
    suspend fun runGuest(
        argv: List<String>,
        cwd: String = "/root",
        timeoutSec: Long = 900,
        onLine: (String) -> Unit = {},
    ): Int = withContext(Dispatchers.IO) {
        val p = launcher().processBuilder(argv, cwd).start()
        val reader = launch {
            runCatching { p.inputStream.bufferedReader().forEachLine { onLine(it); logLine(it) } }
        }
        val finished = try {
            runInterruptible { p.waitFor(timeoutSec, TimeUnit.SECONDS) }
        } finally {
            if (p.isAlive) p.destroyForcibly()
        }
        if (!finished) {
            reader.cancel()
            return@withContext -1
        }
        reader.join()
        p.exitValue()
    }

    /** Optional developer tools for the agent's bash tool. */
    suspend fun installDevTools(onLine: (String) -> Unit): Int {
        val script = "apt-get update && apt-get install -y --no-install-recommends git ripgrep curl ca-certificates unzip less nano python3"
        return runGuest(listOf("/bin/bash", "-lc", script), onLine = onLine)
    }

    fun resetRuntime() {
        stopServer()
        paths.rootfs.deleteRecursively()
        paths.tmp.deleteRecursively()
        paths.fake.deleteRecursively()
        prefs.runtimeReady = false
        prefs.serverFilesVersion = 0
        state.value = RuntimeState.NotInstalled
    }
}
