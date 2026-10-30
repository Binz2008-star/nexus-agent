package com.nexus.agent.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nexus.agent.R
import com.nexus.agent.data.local.NexusDatabase
import com.nexus.agent.data.network.DeepSeekClient
import com.nexus.agent.data.remote.KnowledgeApiClient
import com.nexus.agent.data.remote.WebSearchApiClient
import com.nexus.agent.data.repository.ApiKeyReadResult
import com.nexus.agent.data.repository.AppPreferencesRepository
import com.nexus.agent.data.repository.ChatRepository
import com.nexus.agent.data.repository.KnowledgeRepository
import com.nexus.agent.data.repository.SecretStorageRepository
import com.nexus.agent.data.repository.WebSearchRepository
import com.nexus.agent.domain.model.ChatMessage
import com.nexus.agent.domain.model.KnowledgeResult
import com.nexus.agent.domain.model.WebSearchResult
import com.nexus.agent.domain.tool.KnowledgeSearchResult
import com.nexus.agent.domain.tool.KnowledgeSearchTool
import com.nexus.agent.domain.tool.TaskStatus
import com.nexus.agent.domain.tool.ToolOrchestrator
import com.nexus.agent.domain.tool.ToolSource
import com.nexus.agent.domain.tool.WebSearchOutcome
import com.nexus.agent.security.AndroidKeystoreKeyProvider
import com.nexus.agent.security.CryptoEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val crypto = remember { CryptoEngine(AndroidKeystoreKeyProvider()) }
    val secrets = remember { SecretStorageRepository.create(context, crypto) }
    val preferences = remember { AppPreferencesRepository.create(context) }
    val db = remember { NexusDatabase.getInstance(context) }
    val client = remember {
        DeepSeekClient(apiKeyProvider = {
            when (val r = secrets.readApiKey()) {
                is ApiKeyReadResult.Present -> r.value
                else -> null
            }
        })
    }
    val repository = remember {
        ChatRepository(
            messageDao = db.messageDao(),
            conversationDao = db.conversationDao(),
            client = client,
            apiKeyProvider = {
                when (val r = secrets.readApiKey()) {
                    is ApiKeyReadResult.Present -> r.value
                    else -> null
                }
            },
            systemPromptProvider = { preferences.systemPrompt.first() }
        )
    }

    val knowledgeTool = remember {
        val api = KnowledgeApiClient(baseUrlProvider = { preferences.knowledgeBaseUrl.first() })
        KnowledgeRepository(searchApi = { q, k -> api.search(q, k) })
    }
    val webSearchTool = remember {
        val api = WebSearchApiClient(apiKeyProvider = {
            when (val r = secrets.readApiKey()) {
                is ApiKeyReadResult.Present -> r.value
                else -> null
            }
        })
        WebSearchRepository(searchApi = { q, c -> api.search(q, c) })
    }

    val knowledgeSearchTool = remember { KnowledgeSearchTool(knowledgeTool) }
    val toolOrchestrator = remember {
        ToolOrchestrator(mapOf(knowledgeSearchTool.name to knowledgeSearchTool))
    }

    val viewModel: ChatViewModel = viewModel(
        factory = ChatViewModel.Factory(
            repository, preferences, knowledgeTool, webSearchTool, toolOrchestrator
        )
    )

    val conversations by viewModel.conversations.collectAsState()
    val currentConversationId by viewModel.currentConversationId.collectAsState()
    var showConversationList by remember { mutableStateOf(false) }

    val messages by viewModel.messages.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val selectedModel by viewModel.selectedModel.collectAsState()
    val streamingText by viewModel.streamingText.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val isSearching by viewModel.isSearching.collectAsState()
    val searchResults by viewModel.searchResults.collectAsState()
    var showSearch by remember { mutableStateOf(false) }
    val webQuery by viewModel.webQuery.collectAsState()
    val isWebSearching by viewModel.isWebSearching.collectAsState()
    val webResults by viewModel.webResults.collectAsState()
    var showWebSearch by remember { mutableStateOf(false) }

    val taskStatus by viewModel.taskStatus.collectAsState()

    var status by remember { mutableStateOf("") }
    var showSettings by remember { mutableStateOf(false) }
    var keyInput by remember { mutableStateOf("") }
    var systemPromptInput by remember { mutableStateOf("") }
    var knowledgeBaseUrlInput by remember { mutableStateOf("") }
    var input by remember { mutableStateOf("") }

    var elapsedSeconds by remember { mutableStateOf(0) }
    LaunchedEffect(taskStatus) {
        val running = taskStatus as? TaskStatus.Running
        if (running != null) {
            while (true) {
                elapsedSeconds = ((System.currentTimeMillis() - running.startedAtMs) / 1000).toInt()
                delay(1000)
            }
        } else {
            elapsedSeconds = 0
        }
    }

    LaunchedEffect(selectedModel) {
        val result = withContext(Dispatchers.IO) { secrets.readApiKey() }
        status = when (result) {
            is ApiKeyReadResult.Present -> context.getString(R.string.status_key_saved) + " · $selectedModel"
            is ApiKeyReadResult.Missing -> context.getString(R.string.status_no_key)
            is ApiKeyReadResult.Corrupted -> context.getString(R.string.status_corrupted)
            is ApiKeyReadResult.KeyUnavailable -> context.getString(R.string.status_unavailable)
        }
    }

    LaunchedEffect(showSettings) {
        if (showSettings) {
            systemPromptInput = withContext(Dispatchers.IO) { preferences.systemPrompt.first() }
            knowledgeBaseUrlInput = withContext(Dispatchers.IO) { preferences.knowledgeBaseUrl.first() }
        }
    }

    val listState = rememberLazyListState()
    LaunchedEffect(messages.size, streamingText) {
        if (messages.isNotEmpty() || streamingText.isNotEmpty()) {
            listState.animateScrollToItem(messages.size)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleMedium)
                        Text(
                            status,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showConversationList = true }) {
                        Icon(Icons.Default.Menu, contentDescription = stringResource(R.string.conversations))
                    }
                    TextButton(onClick = { viewModel.createNewConversation() }) {
                        Text(stringResource(R.string.new_chat))
                    }
                    TextButton(onClick = {
                        val next = if (selectedModel == DeepSeekClient.DEFAULT_MODEL)
                            DeepSeekClient.PRO_MODEL else DeepSeekClient.DEFAULT_MODEL
                        viewModel.setModel(next)
                    }) {
                        Text(
                            if (selectedModel == DeepSeekClient.DEFAULT_MODEL)
                                stringResource(R.string.model_flash)
                            else stringResource(R.string.model_pro)
                        )
                    }
                    IconButton(onClick = { showWebSearch = true }) {
                        Icon(Icons.Default.Public, contentDescription = stringResource(R.string.web_search))
                    }
                    IconButton(onClick = { showSearch = true }) {
                        Icon(Icons.Default.Search, contentDescription = stringResource(R.string.search))
                    }
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.settings))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            AnimatedVisibility(visible = errorMessage != null, enter = fadeIn(), exit = fadeOut()) {
                errorMessage?.let { code ->
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = mapErrorCode(code),
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall
                            )
                            TextButton(onClick = { viewModel.retryLast() }) {
                                Text(stringResource(R.string.retry))
                            }
                        }
                    }
                }
            }

            if (messages.isEmpty() && !isLoading && streamingText.isEmpty()) {
                EmptyState(modifier = Modifier.weight(1f))
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(messages, key = { it.id }) { message ->
                        MessageBubble(message)
                    }
                    if (streamingText.isNotEmpty()) {
                        item(key = "streaming") {
                            MessageBubble(
                                ChatMessage(ChatMessage.Role.ASSISTANT, streamingText),
                                isStreaming = true
                            )
                        }
                    } else if (isLoading) {
                        item(key = "thinking") {
                            ThinkingIndicator()
                        }
                    }
                }
            }

            TaskStatusBar(
                status = taskStatus,
                elapsedSeconds = elapsedSeconds,
                onCancel = viewModel::cancelTask
            )

            (taskStatus as? TaskStatus.Completed)?.let { completed ->
                if (completed.sources.isNotEmpty()) {
                    SourcesCard(completed.sources)
                }
            }

            Composer(
                value = input,
                onValueChange = { input = it },
                enabled = !isLoading,
                sendEnabled = !isLoading && taskStatus !is TaskStatus.Running,
                onSend = {
                    viewModel.sendMessageWithAgent(input)
                    input = ""
                }
            )
        }
    }

    if (showConversationList) {
        AlertDialog(
            onDismissRequest = { showConversationList = false },
            title = { Text(stringResource(R.string.conversations)) },
            text = {
                LazyColumn {
                    items(conversations) { conv ->
                        TextButton(
                            onClick = {
                                viewModel.selectConversation(conv.id)
                                showConversationList = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                conv.title,
                                color = if (conv.id == currentConversationId)
                                    MaterialTheme.colorScheme.primary
                                else
                                    MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showConversationList = false }) { Text(stringResource(R.string.close)) }
            }
        )
    }

    if (showSearch) {
        SearchDialog(
            query = searchQuery,
            onQueryChange = viewModel::setSearchQuery,
            isSearching = isSearching,
            results = searchResults,
            onSearch = viewModel::searchKnowledge,
            onDismiss = {
                showSearch = false
                viewModel.clearSearch()
            }
        )
    }

    if (showWebSearch) {
        WebSearchDialog(
            query = webQuery,
            onQueryChange = viewModel::setWebQuery,
            isSearching = isWebSearching,
            results = webResults,
            onSearch = viewModel::searchWeb,
            onDismiss = {
                showWebSearch = false
                viewModel.clearWebSearch()
            }
        )
    }

    if (showSettings) {
        SettingsDialog(
            keyInput = keyInput,
            onKeyChange = { keyInput = it },
            systemPrompt = systemPromptInput,
            onSystemPromptChange = { systemPromptInput = it },
            knowledgeBaseUrl = knowledgeBaseUrlInput,
            onKnowledgeBaseUrlChange = { knowledgeBaseUrlInput = it },
            onDismiss = { showSettings = false },
            onSave = {
                scope.launch {
                    val key = keyInput.trim()
                    if (key.isNotEmpty()) {
                        withContext(Dispatchers.IO) { secrets.saveApiKey(key) }
                        status = context.getString(R.string.status_key_saved) + " · $selectedModel"
                    }
                    withContext(Dispatchers.IO) { preferences.setSystemPrompt(systemPromptInput) }
                    withContext(Dispatchers.IO) { preferences.setKnowledgeBaseUrl(knowledgeBaseUrlInput) }
                    keyInput = ""
                    showSettings = false
                }
            }
        )
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Outlined.AutoAwesome,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.empty_chat),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun MessageBubble(message: ChatMessage, isStreaming: Boolean = false) {
    val isUser = message.role == ChatMessage.Role.USER
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(0.82f),
            shape = RoundedCornerShape(
                topStart = 18.dp,
                topEnd = 18.dp,
                bottomStart = if (isUser) 18.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 18.dp
            ),
            colors = CardDefaults.cardColors(
                containerColor = if (isUser)
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text(
                    text = message.text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (isUser)
                        MaterialTheme.colorScheme.onPrimary
                    else
                        MaterialTheme.colorScheme.onSurface
                )
                if (isStreaming) {
                    Text(
                        "…",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun ThinkingIndicator() {
    Row(
        modifier = Modifier.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant
        ) {
            Text(
                stringResource(R.string.thinking),
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun TaskStatusBar(status: TaskStatus, elapsedSeconds: Int, onCancel: () -> Unit) {
    if (status !is TaskStatus.Running) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.secondaryContainer
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.working_for, elapsedSeconds),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stageLabel(status.stage),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onCancel) {
            Text(stringResource(R.string.cancel))
        }
    }
}

@Composable
private fun SourcesCard(sources: List<ToolSource>) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = stringResource(R.string.sources),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(4.dp))
            sources.take(3).forEach { source ->
                Text(
                    text = source.title.ifBlank { source.file }.ifBlank { source.project },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (source.snippet.isNotBlank()) {
                    Text(
                        text = source.snippet,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2
                    )
                }
                Spacer(Modifier.height(6.dp))
            }
        }
    }
}

@Composable
private fun stageLabel(stage: String): String = when (stage) {
    "searching" -> stringResource(R.string.stage_searching)
    "thinking" -> stringResource(R.string.thinking)
    else -> stage
}

@Composable
private fun Composer(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    sendEnabled: Boolean,
    onSend: () -> Unit
) {
    Surface(
        tonalElevation = 3.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text(stringResource(R.string.type_message)) },
                enabled = enabled,
                maxLines = 5,
                shape = RoundedCornerShape(24.dp)
            )
            Spacer(Modifier.width(8.dp))
            IconButton(
                onClick = onSend,
                enabled = value.isNotBlank() && sendEnabled,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    Icons.Default.Send,
                    contentDescription = stringResource(R.string.send),
                    tint = if (value.isNotBlank() && sendEnabled)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SettingsDialog(
    keyInput: String,
    onKeyChange: (String) -> Unit,
    systemPrompt: String,
    onSystemPromptChange: (String) -> Unit,
    knowledgeBaseUrl: String,
    onKnowledgeBaseUrlChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onSave: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings)) },
        text = {
            Column {
                OutlinedTextField(
                    value = keyInput,
                    onValueChange = onKeyChange,
                    label = { Text(stringResource(R.string.api_key_title)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = systemPrompt,
                    onValueChange = onSystemPromptChange,
                    label = { Text(stringResource(R.string.system_prompt)) },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 5,
                    shape = RoundedCornerShape(12.dp)
                )
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = knowledgeBaseUrl,
                    onValueChange = onKnowledgeBaseUrlChange,
                    label = { Text(stringResource(R.string.knowledge_base_url)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )
            }
        },
        confirmButton = {
            Button(onClick = onSave) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

@Composable
private fun mapErrorCode(code: String): String {
    return when {
        code == "auth" -> stringResource(R.string.error_auth)
        code == "quota" -> stringResource(R.string.error_quota)
        code == "rate" -> stringResource(R.string.error_rate)
        code == "no_key" -> stringResource(R.string.error_no_key)
        code == "empty" -> stringResource(R.string.error_empty)
        code == "nothing_to_retry" -> stringResource(R.string.error_nothing_to_retry)
        code == "empty_response" -> "Empty response from server"
        code == "timeout" -> "Request timed out"
        code == "network" -> "Network error. Check your connection."
        code.startsWith("api_") -> "API error ${code.removePrefix("api_")}"
        code.startsWith("generic:") -> "Request failed (${code.removePrefix("generic:")})"
        else -> stringResource(R.string.error_generic)
    }
}

@Composable
private fun SearchDialog(
    query: String,
    onQueryChange: (String) -> Unit,
    isSearching: Boolean,
    results: KnowledgeSearchResult?,
    onSearch: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.search)) },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    label = { Text(stringResource(R.string.search_placeholder)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                when (val r = results) {
                    is KnowledgeSearchResult.Failure ->
                        Text(mapSearchError(r.errorCode), color = MaterialTheme.colorScheme.error)
                    is KnowledgeSearchResult.Success ->
                        if (r.results.isEmpty()) {
                            Text(stringResource(R.string.search_no_results))
                        } else {
                            LazyColumn(modifier = Modifier.heightIn(max = 280.dp)) {
                                items(r.results) { item -> SearchResultItem(item) }
                            }
                        }
                    null -> if (isSearching) Text(stringResource(R.string.thinking))
                }
            }
        },
        confirmButton = {
            Button(onClick = onSearch, enabled = query.isNotBlank() && !isSearching) {
                Text(stringResource(R.string.search))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
        }
    )
}

@Composable
private fun SearchResultItem(result: KnowledgeResult) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(
            text = listOfNotNull(result.file, result.chunk).joinToString(" · "),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = result.content,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 3
        )
        Text(
            text = "${result.project} · ${"%.2f".format(result.score)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun mapSearchError(code: String): String = when (code) {
    "empty" -> stringResource(R.string.search_empty)
    "no_url" -> stringResource(R.string.search_no_url)
    "network" -> stringResource(R.string.search_network)
    "timeout" -> stringResource(R.string.search_timeout)
    "server" -> stringResource(R.string.search_server)
    "malformed" -> stringResource(R.string.search_malformed)
    else -> stringResource(R.string.search_error)
}

@Composable
private fun WebSearchDialog(
    query: String,
    onQueryChange: (String) -> Unit,
    isSearching: Boolean,
    results: WebSearchOutcome?,
    onSearch: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.web_search)) },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    label = { Text(stringResource(R.string.web_search_placeholder)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                when (val r = results) {
                    is WebSearchOutcome.Failure ->
                        Text(mapWebSearchError(r.errorCode), color = MaterialTheme.colorScheme.error)
                    is WebSearchOutcome.Success ->
                        if (r.results.isEmpty()) {
                            Text(stringResource(R.string.search_no_results))
                        } else {
                            LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                                items(r.results) { item -> WebSearchResultItem(item) }
                            }
                        }
                    null -> if (isSearching) Text(stringResource(R.string.thinking))
                }
            }
        },
        confirmButton = {
            Button(onClick = onSearch, enabled = query.isNotBlank() && !isSearching) {
                Text(stringResource(R.string.web_search))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
        }
    )
}

@Composable
private fun WebSearchResultItem(result: WebSearchResult) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(
            text = result.title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = result.url,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = result.snippet.ifBlank { result.summary },
            style = MaterialTheme.typography.bodySmall,
            maxLines = 3
        )
        if (result.siteName != null) {
            Text(
                text = result.siteName,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun mapWebSearchError(code: String): String = when (code) {
    "empty" -> stringResource(R.string.search_empty)
    "no_key" -> stringResource(R.string.error_no_key)
    "auth" -> stringResource(R.string.error_auth)
    "quota" -> stringResource(R.string.error_quota)
    "rate" -> stringResource(R.string.error_rate)
    "network" -> stringResource(R.string.search_network)
    "timeout" -> stringResource(R.string.search_timeout)
    "server" -> stringResource(R.string.search_server)
    "malformed" -> stringResource(R.string.search_malformed)
    else -> stringResource(R.string.search_error)
}
