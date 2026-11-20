package co.chatie.app.baze

import co.chatie.app.data.ChatRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * The model list, cached in SQLite.
 *
 * Opening the model picker must never wait on the network: the cached list
 * paints immediately and a refresh runs behind it.
 */
class ModelCatalog(
    private val repo: ChatRepository,
    private val gateway: Gateway,
) {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    /** Cached models, or an empty list when nothing has been fetched yet. */
    fun cached(): List<GatewayModel> {
        val raw = repo.getSetting(CACHE_KEY) ?: return emptyList()
        return runCatching { json.decodeFromString<List<GatewayModel>>(raw) }.getOrDefault(emptyList())
    }

    fun cacheAgeMillis(): Long? =
        repo.getSetting(CACHE_AT_KEY)?.toLongOrNull()?.let { System.currentTimeMillis() - it }

    /** Fetch from the gateway and replace the cache. Throws [GatewayError]. */
    suspend fun refresh(): List<GatewayModel> {
        val models = gateway.listChatModels()
        withContext(Dispatchers.IO) {
            repo.setSetting(CACHE_KEY, json.encodeToString(models))
            repo.setSetting(CACHE_AT_KEY, System.currentTimeMillis().toString())
        }
        return models
    }

    /** True when the cache is old enough to be worth refreshing in the background. */
    fun isStale(): Boolean = (cacheAgeMillis() ?: Long.MAX_VALUE) > STALE_AFTER_MS

    companion object {
        private const val CACHE_KEY = "model_cache_llm"
        private const val CACHE_AT_KEY = "model_cache_llm_at"
        private const val STALE_AFTER_MS = 6 * 60 * 60 * 1000L
    }
}
