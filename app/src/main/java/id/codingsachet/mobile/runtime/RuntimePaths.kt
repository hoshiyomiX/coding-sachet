package id.codingsachet.mobile.runtime

import android.content.Context
import java.io.File

/** Where everything lives: proot from jniLibs, the rootfs and temp files in filesDir. */
class RuntimePaths(ctx: Context) {
    val nativeDir: String = ctx.applicationInfo.nativeLibraryDir
    val proot = File(nativeDir, "libproot.so")
    val prootLoader = File(nativeDir, "libproot-loader.so")

    private val base: File = ctx.filesDir
    val rootfs = File(base, "rootfs")

    /** A copy of libtalloc.so named libtalloc.so.2: jniLibs cannot hold such names. */
    val libDir = File(base, "lib")
    val tmp = File(base, "tmp")
    val fake = File(base, "fake")

    val nodeBin = File(rootfs, "usr/local/bin/node")
    val serverDir = File(rootfs, "opt/sachet")
    val workdir = File(rootfs, "root/work")

    val rootfsReady: Boolean
        get() = File(rootfs, "etc/os-release").exists() && File(rootfs, ".sachet-configured").exists()

    /** Map a guest path to the same file on the host. */
    fun host(guestPath: String): File = File(rootfs, guestPath.trimStart('/'))
}
