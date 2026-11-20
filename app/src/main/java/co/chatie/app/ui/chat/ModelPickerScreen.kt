package co.chatie.app.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import co.chatie.app.baze.GatewayError
import co.chatie.app.baze.GatewayModel
import co.chatie.app.baze.ModelCatalog
import kotlinx.coroutines.launch

/**
 * The model list, painted from cache first.
 *
 * Switching model mid-conversation is allowed and recorded per message, so a
 * chat can start on a cheap model and finish on an expensive one with the
 * history intact.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelPickerScreen(
    catalog: ModelCatalog,
    currentModel: String,
    onPick: (String) -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var models by remember { mutableStateOf(catalog.cached()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var filter by remember { mutableStateOf("") }

    fun refresh() {
        if (loading) return
        loading = true
        error = null
        scope.launch {
            try {
                models = catalog.refresh()
            } catch (e: GatewayError) {
                error = e.userMessage
            } catch (e: Throwable) {
                error = e.message ?: "모델 목록을 받지 못했습니다."
            }
            loading = false
        }
    }

    // Cache paints instantly; a stale or empty cache refreshes behind it.
    LaunchedEffect(Unit) {
        if (models.isEmpty() || catalog.isStale()) refresh()
    }

    val shown = remember(models, filter) {
        if (filter.isBlank()) {
            models
        } else {
            models.filter {
                it.id.contains(filter, ignoreCase = true) ||
                    it.ownedBy?.contains(filter, ignoreCase = true) == true
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("모델 선택") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로")
                    }
                },
                actions = {
                    if (loading) {
                        CircularProgressIndicator(
                            Modifier.padding(end = 16.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        IconButton(onClick = { refresh() }) {
                            Icon(Icons.Default.Refresh, contentDescription = "새로 받기")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            OutlinedTextField(
                value = filter,
                onValueChange = { filter = it },
                label = { Text("검색") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )

            error?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }

            if (shown.isEmpty() && !loading) {
                Text(
                    text = if (models.isEmpty()) {
                        "모델 목록이 없습니다. API 키를 넣고 새로 받아보세요."
                    } else {
                        "검색 결과가 없습니다."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }

            LazyColumn {
                items(shown, key = GatewayModel::id) { model ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onPick(model.id) }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(
                            Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(model.id, style = MaterialTheme.typography.bodyLarge)
                            model.ownedBy?.let {
                                Text(
                                    text = it,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        if (model.id == currentModel) {
                            Icon(Icons.Default.Check, contentDescription = "선택됨")
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}
