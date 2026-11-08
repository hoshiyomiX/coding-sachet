package id.codingsachet.mobile.runtime

import java.io.File

/**
 * Builds the proot command line that runs a program inside the Ubuntu
 * guest: fake root identity, a believable /proc, and binds for /dev,
 * /sys and (when reachable) the shared storage.
 *
 * [rootMode] (rooted phones, opt-in): the whole Android filesystem is
 * bound at /device inside the guest, and the command is launched through
 * `su -c` so the guest really runs as uid 0 — protected directories
 * (/data/data/…, /system/…) become readable in the file explorer.
 */
class ProotLauncher(
    private val paths: RuntimePaths,
    private val noSeccomp: Boolean,
    private val rootMode: Boolean = false,
) {

    companion object {
        private const val SHARED_STORAGE = "/storage/emulated/0"
        private const val PROC_LOADAVG = "0.10 0.05 0.01 1/128 512"
        private const val PROC_UPTIME = "512.16 2048.00"
        private const val PROC_VERSION =
            "Linux version 6.2.1-sachet (proot@android) (gcc 12.2.0) #1 SMP PREEMPT_DYNAMIC"

        /** POSIX single-quoting for `su -c` command strings. */
        fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"
    }

    /** Environment for the proot process itself (loader + tmp dir). */
    fun prootEnv(): Map<String, String> = buildMap {
        put("LD_LIBRARY_PATH", "${paths.nativeDir}:${paths.libDir.path}")
        put("PROOT_LOADER", paths.prootLoader.path)
        put("PROOT_TMP_DIR", paths.tmp.path)
        if (noSeccomp) put("PROOT_NO_SECCOMP", "1")
    }

    /**
     * The guest runs under `env -i` with a clean, predictable environment.
     * [guestArgv] is the command to start inside the rootfs.
     */
    fun command(guestArgv: List<String>, cwd: String = "/root", extraEnv: Map<String, String> = emptyMap()): List<String> {
        prepareFakeProc()
        val argv = mutableListOf(
            paths.proot.path,
            "--kernel-release=6.2.1-sachet",
            "--link2symlink",
            "--kill-on-exit",
            "--root-id",
            "--rootfs=${paths.rootfs.path}",
            "--cwd=$cwd",
            "--bind=/dev",
            "--bind=/dev/urandom:/dev/random",
            "--bind=/proc",
            "--bind=/proc/self/fd:/dev/fd",
            "--bind=/proc/self/fd/0:/dev/stdin",
            "--bind=/proc/self/fd/1:/dev/stdout",
            "--bind=/proc/self/fd/2:/dev/stderr",
            "--bind=/sys",
            "--bind=${paths.fake}/loadavg:/proc/loadavg",
            "--bind=${paths.fake}/stat:/proc/stat",
            "--bind=${paths.fake}/uptime:/proc/uptime",
            "--bind=${paths.fake}/version:/proc/version",
            "--bind=${File(paths.rootfs, "tmp")}:/dev/shm",
        )
        // Shared storage, bound under the same path and as /sdcard.
        if (File(SHARED_STORAGE).exists()) {
            argv += "--bind=$SHARED_STORAGE"
            argv += "--bind=$SHARED_STORAGE:/sdcard"
        }
        // Rooted devices: expose the whole Android filesystem at /device.
        if (rootMode) {
            File(paths.rootfs, "device").mkdirs()
            argv += "--bind=/:/device"
        }
        val guestEnv = linkedMapOf(
            "HOME" to "/root",
            "USER" to "root",
            "LOGNAME" to "root",
            "SHELL" to "/bin/bash",
            "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "TERM" to "xterm-256color",
            "LANG" to "C.UTF-8",
            "TMPDIR" to "/tmp",
            "SSL_CERT_FILE" to "/etc/ssl/certs/ca-certificates.crt",
            "NODE_EXTRA_CA_CERTS" to "/etc/ssl/certs/ca-certificates.crt",
            "DEBIAN_FRONTEND" to "noninteractive",
        ).apply { putAll(extraEnv) }
        argv += "/usr/bin/env"
        argv += "-i"
        guestEnv.forEach { (k, v) -> argv += "$k=$v" }
        argv += guestArgv
        return argv
    }

    fun processBuilder(guestArgv: List<String>, cwd: String = "/root", extraEnv: Map<String, String> = emptyMap()): ProcessBuilder {
        val argv = command(guestArgv, cwd, extraEnv)
        return if (rootMode) {
            // `su -c` takes ONE shell string; the proot environment must be
            // set inside it, so prefix the whole command with `env`.
            val envPrefix = prootEnv().entries.joinToString(" ") { (k, v) -> shellQuote("$k=$v") }
            val cmdLine = "env $envPrefix " + argv.joinToString(" ") { shellQuote(it) }
            ProcessBuilder("su", "-c", cmdLine).redirectErrorStream(true)
        } else {
            ProcessBuilder(argv)
                .also { it.environment().putAll(prootEnv()) }
                .redirectErrorStream(true)
        }
    }

    /**
     * Android hides parts of /proc from apps while Node reads them:
     * provide believable stand-ins through bind mounts.
     */
    private fun prepareFakeProc() {
        paths.fake.mkdirs()
        paths.tmp.mkdirs()
        File(paths.rootfs, "tmp").mkdirs()
        val cpus = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        write("loadavg", PROC_LOADAVG + "\n")
        write("uptime", PROC_UPTIME + "\n")
        write("version", PROC_VERSION + "\n")
        write("stat", buildString {
            append("cpu  ${980 * cpus} 0 ${1440 * cpus} ${46000 * cpus} 130 170 120 40 0 0\n")
            for (i in 0 until cpus) append("cpu$i 980 0 1440 46000 130 170 120 40 0 0\n")
            append("intr 0\nctxt 0\nbtime 1700000000\nprocesses 1\nprocs_running 1\nprocs_blocked 0\n")
        })
    }

    private fun write(name: String, text: String) {
        val f = File(paths.fake, name)
        if (!f.exists() || f.readText() != text) f.writeText(text)
    }
}
