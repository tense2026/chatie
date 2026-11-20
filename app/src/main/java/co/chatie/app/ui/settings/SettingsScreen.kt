package co.chatie.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import co.chatie.app.baze.Gateway
import co.chatie.app.baze.GatewayError
import co.chatie.app.context.ContextAssembler
import co.chatie.app.data.ApiKeyStore
import co.chatie.app.data.ChatRepository
import co.chatie.app.data.MemoryScope
import co.chatie.app.ui.common.MemoryList
import co.chatie.app.ui.common.rememberQuery
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    repo: ChatRepository,
    keyStore: ApiKeyStore,
    gateway: Gateway,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val globalMemories = rememberQuery(repo) { repo.listMemories(MemoryScope.GLOBAL) }

    // SecureTextField keeps the key in a TextFieldState the platform can mask
    // and keep out of autofill/clipboard heuristics.
    val keyState = rememberTextFieldState()
    var savedKeyHint by remember { mutableStateOf(keyStore.load()?.let(ApiKeyStore::hint)) }
    var connectionStatus by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }

    var baseInstructions by remember {
        mutableStateOf(repo.getSetting(ContextAssembler.SETTING_BASE_INSTRUCTIONS) ?: "")
    }
    val savedBaseInstructions =
        rememberQuery(repo) { repo.getSetting(ContextAssembler.SETTING_BASE_INSTRUCTIONS) ?: "" }
    val baseDirty = baseInstructions != savedBaseInstructions

    var budgetDraft by remember {
        mutableStateOf(
            (repo.getSetting(ContextAssembler.SETTING_HISTORY_CHAR_BUDGET)
                ?: ContextAssembler.DEFAULT_HISTORY_CHAR_BUDGET.toString()),
        )
    }
    val looseGlobal =
        rememberQuery(repo) {
            repo.getSetting(ContextAssembler.SETTING_LOOSE_USE_GLOBAL_MEMORY) != "0"
        }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("설정") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            SectionTitle("BAZE API 키")
            Text(
                text = "BAZE 웹에 로그인해 좌측 하단 'API Gateway'에서 키를 발급받아 붙여넣으세요. " +
                    "키는 이 기기의 키스토어에만 저장되고, 게이트웨이 외의 어디로도 전송되지 않습니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            savedKeyHint?.let {
                Text(
                    text = "저장된 키: $it",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            OutlinedSecureTextField(
                state = keyState,
                label = { Text("API 키") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        keyStore.save(keyState.text.toString())
                        savedKeyHint = keyStore.load()?.let(ApiKeyStore::hint)
                        keyState.clearText()
                        connectionStatus = null
                    },
                    enabled = keyState.text.isNotBlank(),
                ) { Text("저장") }

                OutlinedButton(
                    onClick = {
                        checking = true
                        connectionStatus = null
                        scope.launch {
                            connectionStatus = try {
                                val balance = gateway.credits()
                                val remaining = balance.total?.remaining
                                "연결 성공 · 남은 크레딧 ${remaining?.toInt() ?: "?"}"
                            } catch (e: GatewayError) {
                                "실패: ${e.userMessage}"
                            } catch (e: Throwable) {
                                "실패: ${e.message ?: "네트워크 오류"}"
                            }
                            checking = false
                        }
                    },
                    enabled = !checking && savedKeyHint != null,
                ) { Text(if (checking) "확인 중…" else "연결 확인") }

                if (savedKeyHint != null) {
                    TextButton(
                        onClick = {
                            keyStore.clear()
                            savedKeyHint = null
                            connectionStatus = null
                        },
                    ) { Text("삭제") }
                }
            }
            connectionStatus?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (it.startsWith("실패")) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
            }

            HorizontalDivider()

            SectionTitle("기본 지시문")
            Text(
                text = "모든 대화의 가장 앞에 들어갑니다. 프로젝트 지시문보다 먼저 적용됩니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = baseInstructions,
                onValueChange = {
                    if (it.length <= ContextAssembler.MAX_INSTRUCTIONS_CHARS) baseInstructions = it
                },
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(
                    onClick = { baseInstructions = savedBaseInstructions },
                    enabled = baseDirty,
                ) { Text("되돌리기") }
                TextButton(
                    onClick = {
                        repo.setSetting(
                            ContextAssembler.SETTING_BASE_INSTRUCTIONS,
                            baseInstructions,
                        )
                    },
                    enabled = baseDirty,
                ) { Text("저장") }
            }

            HorizontalDivider()

            SectionTitle("전역 메모리")
            Text(
                text = "프로젝트 없는 대화에 적용됩니다. 프로젝트 안에서는 그 프로젝트에서 켠 경우에만 들어갑니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            MemoryList(
                memories = globalMemories,
                emptyHint = "아직 전역 메모리가 없습니다.",
                onAdd = { repo.createMemory(MemoryScope.GLOBAL, it) },
                onToggle = { memory, enabled -> repo.updateMemory(memory.id, enabled = enabled) },
                onDelete = { repo.deleteMemory(it.id) },
            )

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "프로젝트 없는 대화에서 기본으로 사용",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = "각 대화에서 따로 켜거나 끌 수 있습니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = looseGlobal,
                    onCheckedChange = {
                        repo.setSetting(
                            ContextAssembler.SETTING_LOOSE_USE_GLOBAL_MEMORY,
                            if (it) "1" else "0",
                        )
                    },
                )
            }

            HorizontalDivider()

            SectionTitle("대화 기록 예산")
            Text(
                text = "한 요청에 담을 과거 메시지의 글자 수 상한입니다. 넘으면 오래된 메시지부터 생략되고, " +
                    "몇 개가 생략됐는지 대화 화면에 표시됩니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = budgetDraft,
                onValueChange = { budgetDraft = it.filter(Char::isDigit).take(7) },
                label = { Text("글자 수") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(
                    onClick = {
                        val value = budgetDraft.toIntOrNull()
                        if (value != null && value > 0) {
                            repo.setSetting(
                                ContextAssembler.SETTING_HISTORY_CHAR_BUDGET,
                                value.toString(),
                            )
                        }
                    },
                    enabled = budgetDraft.toIntOrNull()?.let { it > 0 } == true,
                ) { Text("저장") }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
}
