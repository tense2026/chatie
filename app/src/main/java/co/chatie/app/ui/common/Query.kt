package co.chatie.app.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.chatie.app.data.ChatRepository

/**
 * Re-runs [query] whenever anything is written to the database.
 *
 * Reads are local SQLite against indexed columns, so re-querying a list on
 * change costs less than maintaining a subscription per screen — and it means
 * a screen can never show state the database does not actually hold.
 */
@Composable
fun <T> rememberQuery(repo: ChatRepository, vararg keys: Any?, query: () -> T): T {
    val revision by repo.revision.collectAsStateWithLifecycle()
    return remember(revision, *keys) { query() }
}
