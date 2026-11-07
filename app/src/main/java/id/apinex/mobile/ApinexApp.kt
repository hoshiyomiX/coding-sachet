package id.apinex.mobile

import android.app.Application
import id.apinex.mobile.api.ApinexApi
import id.apinex.mobile.data.ChatStore
import id.apinex.mobile.data.Prefs
import id.apinex.mobile.runtime.RuntimeController
import id.apinex.mobile.runtime.RuntimePaths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** App-wide singletons. */
object Graph {
    lateinit var prefs: Prefs
        private set
    lateinit var api: ApinexApi
        private set
    lateinit var chats: ChatStore
        private set
    lateinit var runtime: RuntimeController
        private set
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun init(app: Application) {
        prefs = Prefs(app)
        api = ApinexApi { prefs.serverPort }
        chats = ChatStore(app)
        val paths = RuntimePaths(app)
        runtime = RuntimeController(app, paths, prefs, api)
    }
}

class ApinexApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Graph.init(this)
        id.apinex.mobile.runtime.GraphHolder.let {
            it.prefs = Graph.prefs
            it.runtime = Graph.runtime
        }
    }
}
