package id.aihubmix.mobile

import android.app.Application
import id.aihubmix.mobile.api.AihubmixApi
import id.aihubmix.mobile.data.ChatStore
import id.aihubmix.mobile.data.Prefs
import id.aihubmix.mobile.runtime.RuntimeController
import id.aihubmix.mobile.runtime.RuntimePaths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** App-wide singletons. */
object Graph {
    lateinit var prefs: Prefs
        private set
    lateinit var api: AihubmixApi
        private set
    lateinit var chats: ChatStore
        private set
    lateinit var runtime: RuntimeController
        private set
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun init(app: Application) {
        prefs = Prefs(app)
        api = AihubmixApi { prefs.serverPort }
        chats = ChatStore(app)
        val paths = RuntimePaths(app)
        runtime = RuntimeController(app, paths, prefs, api)
    }
}

class AihubmixApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Graph.init(this)
        id.aihubmix.mobile.runtime.GraphHolder.let {
            it.prefs = Graph.prefs
            it.runtime = Graph.runtime
        }
    }
}
