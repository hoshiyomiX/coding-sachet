package id.apinex.mobile.runtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import id.apinex.mobile.MainActivity
import id.apinex.mobile.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Foreground service hosting the agent-server process. */
class ServerService : Service() {

    companion object {
        const val ACTION_START = "id.apinex.mobile.START"
        const val ACTION_STOP = "id.apinex.mobile.STOP"
        private const val CHANNEL = "server"
        private const val NOTIF_ID = 1
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        startForeground(NOTIF_ID, notification("Menyiapkan server…"))
        if (intent?.action == ACTION_STOP) {
            GraphHolder.runtime.stopServer()
            stopSelf()
            return START_NOT_STICKY
        }
        if (GraphHolder.prefs.keepAwake && wake == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "apinex:server").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
        if (job == null) {
            job = scope.launch {
                launch {
                    GraphHolder.runtime.state.collect { s ->
                        val text = when (s) {
                            is RuntimeState.Running -> "Agent server aktif · 127.0.0.1:${GraphHolder.prefs.serverPort}"
                            is RuntimeState.Crashed -> "Server mati — memulai ulang…"
                            else -> "Menyiapkan server…"
                        }
                        runCatching {
                            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification(text))
                        }
                    }
                }
                GraphHolder.runtime.supervise()
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        GraphHolder.runtime.requestStop()
        scope.cancel()
        wake?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Agent server", NotificationManager.IMPORTANCE_LOW))
        }
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(
            this, 1, Intent(this, ServerService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("APInex")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }
}

/** Holds the app-wide singletons for the service (set in ApinexApp.onCreate). */
object GraphHolder {
    lateinit var runtime: RuntimeController
    lateinit var prefs: id.apinex.mobile.data.Prefs
}
