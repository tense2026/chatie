package co.chatie.app

import android.app.Application
import co.chatie.app.baze.Gateway
import co.chatie.app.context.ContextAssembler
import co.chatie.app.data.ApiKeyStore
import co.chatie.app.data.ChatRepository
import co.chatie.app.data.Db

class ChatieApp : Application() {

    /** Null when start-up failed; [startupError] then holds the reason. */
    var container: AppContainer? = null
        private set

    var startupError: String? = null
        private set

    override fun onCreate() {
        super.onCreate()
        // First, so a crash anywhere after this point is recoverable as text.
        CrashLog.install(this)
        try {
            container = AppContainer(this)
        } catch (e: Throwable) {
            startupError = CrashLog.describe(Thread.currentThread(), e)
            CrashLog.write(this, startupError!!)
        }
    }
}

/** Hand-rolled container: four objects, no DI framework, no startup cost. */
class AppContainer(app: Application) {
    val db = Db(app)
    val repo = ChatRepository(db)
    val keyStore = ApiKeyStore(app)
    val assembler = ContextAssembler(repo)
    val gateway = Gateway(apiKey = { keyStore.load() })
}
