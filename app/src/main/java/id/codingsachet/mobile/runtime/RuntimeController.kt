package id.codingsachet.mobile.runtime

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkRequest
import androidx.core.content.ContextCompat
import id.codingsachet.mobile.BuildConfig
import id.codingsachet.mobile.Logx
import id.codingsachet.mobile.api.AgentApi
import id.codingsachet.mobile.data.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
        // EVERY file the server requires at boot must be listed here: the
        // updater only copies these into the rootfs. T-ML11 incident: net.js
        // shipped inside the APK but was missing from this list, so the
        // 1.0.6 update crashed the server with MODULE_NOT_FOUND.
        val SERVER_FILES = listOf("config.js", "agent.js", "tools.js", "server.js", "net.js", "ratelimit.js", "log.js")

        // The pinned Node builds need libatomic.so.1, which the minimal
        // ubuntu-base rootfs does not ship (verified per-arch via readelf
        // against the extracted rootfs: every other NEEDED library is already
        // present). The per-arch library travels inside the APK and is copied
        // into the rootfs under its guest multiarch directory.
        private val GUEST_LIB_DIR = "/usr/lib/${BuildConfig.GUEST_TRIPLET}"
        private val RUNTIME_LIBS = listOf("libatomic.so.1")

        // Common su binary locations (file-existence only — never exec su
        // just to probe: that would pop the superuser grant dialog).
        private val SU_PATHS = listOf(
            "/system/bin/su", "/system/xbin/su", "/sbin/su", "/system/sbin/su",
            "/vendor/bin/su", "/su/bin/su", "/system/app/Superuser.apk",
        )
    }

    val state = MutableStateFlow<RuntimeState>(
        if (prefs.runtimeReady && paths.nodeBin.exists()) RuntimeState.Stopped else RuntimeState.NotInstalled
    )
    val log = MutableStateFlow<List<String>>(emptyList())

    private var process: Process? = null
    @Volatile private var stopRequested = false
    @Volatile private var installing = false
    @Volatile var userStopped = false
    // Set when the server's stdout shows proot reporting a child killed by a
    // signal ("proot info: vpid 1: terminated with signal 9", T-ML30 field
    // evidence). proot then exits 0 — without this flag the log line "Server
    // keluar (kode 0)" reads as a normal stop and misleads the diagnosis
    // (T-ML31 P1-5).
    @Volatile private var sawSignalKill = false

    // ---- VPN-aware DNS push ----
    // The guest resolves gateway hostnames with its own resolv.conf; public
    // resolvers are unreachable through many VPNs, which surfaced as
    // "Koneksi ke gateway gagal: fetch failed". The Android system's DNS
    // list (VPN-aware) is pushed to the server, which rewrites resolv.conf.
    private val dnsScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var lastDnsPush = 0L

    fun logLine(s: String) {
        Logx.raw(s)
        log.update { (it + s).takeLast(500) }
    }

    // ---------------------------------------------------------------- root

    /** A su binary is present (rooted phone or Magisk). */
    fun hasSu(): Boolean = SU_PATHS.any { File(it).exists() }

    /** Root mode is requested AND possible. */
    fun rootMode(): Boolean = prefs.rootAccess && hasSu()

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
            Logx.i("rt", "instalasi runtime selesai (rootfs + node ${BuildConfig.NODE_VERSION} + ${SERVER_FILES.size} file server)")
        } catch (e: Throwable) {
            logLine("Instalasi gagal: ${e.message}")
            Logx.e("rt", "instalasi runtime gagal", e)
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
        // The MODULE_NOT_FOUND class of failure must never ship silently
        // again: verify what the boot will actually require on disk.
        val missing = SERVER_FILES.filterNot { File(paths.serverDir, it).length() > 0L }
        if (missing.isNotEmpty()) logLine("PERINGATAN file server kosong: ${missing.joinToString()}")
        prefs.serverFilesVersion = BuildConfig.VERSION_CODE
    }

    /** Rewrite the JS files when the app was updated. Heals missing files
     *  even when the version matches (a file deleted or never written). */
    private fun ensureServerFilesCurrent() {
        val missing = SERVER_FILES.filterNot { File(paths.serverDir, it).exists() }
        if (prefs.serverFilesVersion != BuildConfig.VERSION_CODE || missing.isNotEmpty()) {
            if (missing.isNotEmpty()) logLine("File server hilang, menulis ulang: ${missing.joinToString()}")
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
            sawSignalKill = false
            // Fast-path restart (T-ML27 P2): the old order booted a whole
            // proot just to run pkill BEFORE checking whether anything was
            // even listening — seconds of latency (and up to the 8 s pkill
            // timeout when it hung) on every restart. The port answers the
            // question cheaper: free port = no leftover server to kill.
            if (portBusy()) {
                killLeftoverServer()
                var waited = 0
                while (isActive && portBusy() && waited < 8_000) {
                    delay(250); waited += 250
                }
                if (waited > 0) logLine("Menunggu port bebas selama ${waited}ms")
            }

            ensureServerFilesCurrent()

            val rootMode = rootMode()
            if (rootMode) logLine("Mode root aktif: server berjalan sebagai root, /device terpasang")
            val pb = launcher().processBuilder(
                listOf(NODE_GUEST, SERVER_JS_GUEST),
                cwd = "/root",
                extraEnv = buildMap {
                    put("SACHET_PORT", prefs.serverPort.toString())
                    if (rootMode) put("SACHET_EXTRA_ROOTS", "/,/device")
                },
            )
            val p = try {
                pb.start()
            } catch (e: Exception) {
                state.value = RuntimeState.Crashed("Tidak bisa memulai server: ${e.message}")
                return@withContext
            }
            process = p
            val startedAt = System.currentTimeMillis()
            val reader = launch {
                runCatching {
                    p.inputStream.bufferedReader().forEachLine {
                        if (it.contains("terminated with signal")) sawSignalKill = true
                        logLine(it)
                    }
                }
            }
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
                    pushSystemDns()
                    // Cache the server's resolved workdir (T-ML28): the chat
                    // builds per-session project-folder paths from it without
                    // a round trip (/sdcard/coding-sachet or /root/work).
                    runCatching { api.workdir() }.getOrNull()
                        ?.takeIf { it.isNotBlank() }?.let { prefs.workdirHint = it }
                    Logx.i("rt", "server aktif (v$version, node di atas proot)")
                } else {
                    Logx.w("rt", "health check gagal — proses ${if (p.isAlive) "hidup tapi tidak menjawab" else "sudah mati"}")
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
                // Bounded drain before cancelling: let the stdout reader
                // finish the process's final lines — proot's "terminated with
                // signal" evidence — but never hang the restart loop on a
                // pipe held open by a leaked grandchild (T-ML31 P1-5).
                withTimeoutOrNull(250) { reader.join() }
                reader.cancel()
            }
            if (stopRequested) break
            val code = runCatching { p.exitValue() }.getOrDefault(-1)
            val lived = System.currentTimeMillis() - startedAt
            val signalKillNote = if (sawSignalKill && code == 0)
                " — node dibunuh paksa sinyal 9 (tekanan memori?) walau proot keluar 0"
            else ""
            logLine("Server keluar (kode $code${exitCodeNote(code)}$signalKillNote)")
            // A run that lived a while is not a crash loop: an external kill
            // (T-ML27 evidence: kode 137 while apt-get ran in the guest —
            // kernel OOM killer / OEM memory manager) deserves an immediate
            // restart, not a growing backoff (incident 00:12 waited 28 s).
            if (lived > 60_000) failures = 0
            val now = System.currentTimeMillis()
            if (now - windowStart > 120_000) {
                failures = 0
                windowStart = now
            }
            failures++
            state.value = RuntimeState.Crashed("Server berhenti (kode $code${exitCodeNote(code)}$signalKillNote). Memulai ulang…")
            delay((1500L * failures).coerceAtMost(30_000))
        }
        state.value = RuntimeState.Stopped
    }

    private fun launcher() = ProotLauncher(paths, prefs.noSeccomp, rootMode())

    /** Human-readable note for the exit codes the field actually produced
     *  (T-ML27 P2): 137 = SIGKILL from outside the app — kernel OOM killer
     *  or the OEM's memory manager, both observed striking while the agent
     *  ran apt-get in the guest; 143 = SIGTERM. */
    private fun exitCodeNote(code: Int): String = when (code) {
        137 -> " — dimatikan paksa oleh sistem (tekanan memori?)"
        143 -> " — dihentikan oleh sinyal berhenti"
        else -> ""
    }

    // ---------------------------------------------------------------- dns

    /** The Android system's current DNS servers (follows the active
     *  network — including a VPN's own resolvers). */
    fun systemDnsServers(): List<String> = runCatching {
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net: Network = cm.activeNetwork ?: return emptyList()
        val lp: LinkProperties = cm.getLinkProperties(net) ?: return emptyList()
        lp.dnsServers.mapNotNull { it.hostAddress }.filter { !it.contains('%') }
    }.getOrDefault(emptyList())

    /** Push the system DNS list into the guest's resolv.conf via /api/dns. */
    fun pushSystemDns() {
        val servers = systemDnsServers()
        if (servers.isEmpty()) return
        lastDnsPush = System.currentTimeMillis()
        dnsScope.launch {
            val ok = api.pushDns(servers)
            logLine(
                if (ok) "DNS sistem diteruskan ke guest: ${servers.joinToString(", ")}"
                else "Gagal meneruskan DNS sistem (guest memakai resolver cadangan)"
            )
        }
    }

    /** Network-change hook: re-push DNS at most once per 10 s, only while
     *  the server runs. Covers VPN toggling on/off and wifi↔cellular moves. */
    fun maybePushDns() {
        if (state.value !is RuntimeState.Running) return
        val now = System.currentTimeMillis()
        if (now - lastDnsPush < 10_000) return
        pushSystemDns()
    }

    /** Register the app-lifetime network watcher (idempotent). */
    fun startNetworkWatch() {
        if (netCallback != null) return
        runCatching {
            val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { maybePushDns() }
                override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) { maybePushDns() }
            }
            cm.registerNetworkCallback(NetworkRequest.Builder().build(), cb)
            netCallback = cb
        }.onFailure { logLine("Network watcher gagal: ${it.message}") }
    }

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
        Logx.i("rt", "start server (diminta UI)")
        userStopped = false
        ContextCompat.startForegroundService(app, Intent(app, ServerService::class.java).setAction(ServerService.ACTION_START))
    }

    fun stopServer() {
        Logx.i("rt", "stop server (diminta UI)")
        userStopped = true
        requestStop()
        app.stopService(Intent(app, ServerService::class.java))
        if (state.value !is RuntimeState.NotInstalled) state.value = RuntimeState.Stopped
    }

    fun restartServer() {
        Logx.i("rt", "restart server (diminta UI)")
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

    /** Optional developer tools for the agent's bash tool: a complete
     *  toolbox for real coding work (VCS, search, JSON, archives, Python
     *  with pip + venv, C/C++ toolchain, sqlite). */
    suspend fun installDevTools(onLine: (String) -> Unit): Int {
        val script = (
            "apt-get update && " +
                "apt-get install -y --no-install-recommends " +
                "git ripgrep curl wget ca-certificates unzip zip xz-utils " +
                "less nano jq tree htop " +
                "python3 python3-pip python3-venv " +
                "build-essential sqlite3 pkg-config"
        )
        return runGuest(listOf("/bin/bash", "-lc", script), onLine = onLine)
    }

    fun resetRuntime() {
        stopServer()
        // Under root mode the guest's files are owned by real root — the
        // app uid cannot delete them, so clean up through su first.
        if (rootMode()) {
            runCatching {
                val dirs = listOf(paths.rootfs, paths.tmp, paths.fake)
                    .joinToString(" ") { ProotLauncher.shellQuote(it.path) }
                val p = ProcessBuilder("su", "-c", "rm -rf $dirs").start()
                p.waitFor(20, TimeUnit.SECONDS)
            }.onFailure { logLine("Pembersihan via su gagal: ${it.message}") }
        }
        paths.rootfs.deleteRecursively()
        paths.tmp.deleteRecursively()
        paths.fake.deleteRecursively()
        prefs.runtimeReady = false
        prefs.serverFilesVersion = 0
        state.value = RuntimeState.NotInstalled
    }
}
