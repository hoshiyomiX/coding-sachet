package id.codingsachet.mobile

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import id.codingsachet.mobile.ui.AppRoot
import id.codingsachet.mobile.ui.rememberAppState

class MainActivity : ComponentActivity() {

    private val storagePerms =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            // The grant lands before the onboarding finishes (the server only
            // starts once an API key is set), so the next server start already
            // resolves the /sdcard workdir. A late grant applies after the
            // next app restart — the server falls back to /root/work meanwhile.
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestStorageIfNeeded()
        setContent {
            AppRoot(rememberAppState())
        }
    }

    /**
     * The agent's default project folder is /sdcard/coding-sachet (shared
     * storage, bound into proot). targetSdk 28 keeps the legacy storage
     * model on every Android version, but the write grant must still be
     * requested at runtime — without it the server silently falls back to
     * the private /root/work.
     */
    private fun requestStorageIfNeeded() {
        val wanted = arrayOf(
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
        ).filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()
        if (wanted.isNotEmpty()) storagePerms.launch(wanted)
    }
}
