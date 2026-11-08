package id.codingsachet.mobile

import android.app.Application
import id.codingsachet.mobile.api.AgentApi
import id.codingsachet.mobile.data.ChatStore
import id.codingsachet.mobile.data.Prefs
import id.codingsachet.mobile.runtime.RuntimeController
import id.codingsachet.mobile.runtime.RuntimePaths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** App-wide singletons. */
object Graph {
    lateinit var prefs: Prefs
        private set
    lateinit var api: AgentApi
        private set
    lateinit var chats: ChatStore
        private set
    lateinit var runtime: RuntimeController
        private set
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun init(app: Application) {
        prefs = Prefs(app)
        api = AgentApi { prefs.serverPort }
        chats = ChatStore(app)
        val paths = RuntimePaths(app)
        runtime = RuntimeController(app, paths, prefs, api)
    }
}

class SachetApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Graph.init(this)
        id.codingsachet.mobile.runtime.GraphHolder.let {
            it.prefs = Graph.prefs
            it.runtime = Graph.runtime
        }
        Logx.i("app", "boot Coding Sachet ${BuildConfig.VERSION_NAME} (vCode ${BuildConfig.VERSION_CODE})")
        // VPN-aware DNS: keep the guest's resolver in sync with the active
        // network (VPN on/off, wifi↔cellular) for as long as the app lives.
        Graph.runtime.startNetworkWatch()
    }
}
