package co.chatie.app

import android.app.Application
import co.chatie.app.baze.Gateway
import co.chatie.app.context.ContextAssembler
import co.chatie.app.data.ApiKeyStore
import co.chatie.app.data.ChatRepository
import co.chatie.app.data.Db

class ChatieApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
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
