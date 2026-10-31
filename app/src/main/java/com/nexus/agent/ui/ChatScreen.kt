package com.nexus.agent.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nexus.agent.BuildConfig
import com.nexus.agent.R
import com.nexus.agent.data.local.NexusDatabase
import com.nexus.agent.data.network.DeepSeekClient
import com.nexus.agent.data.remote.NeonJdbcClient
import com.nexus.agent.data.remote.WebSearchApiClient
import com.nexus.agent.data.repository.ApiKeyReadResult
import com.nexus.agent.data.repository.AppPreferencesRepository
import com.nexus.agent.data.repository.ChatRepository
import com.nexus.agent.data.repository.ConversationMirror
import com.nexus.agent.data.repository.NeonRepository
import com.nexus.agent.data.repository.SecretStorageRepository
import com.nexus.agent.data.repository.WebSearchRepository
import com.nexus.agent.domain.cognitive.CognitiveLoadManager
import com.nexus.agent.domain.culture.CultureContext
import com.nexus.agent.domain.culture.CultureContextEngine
import com.nexus.agent.domain.culture.Region
import com.nexus.agent.domain.emotion.EmotionAnalyzer
import com.nexus.agent.domain.model.ChatMessage
import com.nexus.agent.domain.neon.NeonConnectionParser
import com.nexus.agent.domain.persona.AdaptiveTone
import com.nexus.agent.domain.persona.Tone
import com.nexus.agent.domain.persona.regionToDialect
import com.nexus.agent.domain.policy.ApprovalGate
import com.nexus.agent.domain.policy.ApprovalRequest
import com.nexus.agent.domain.router.AiModelRouter
import com.nexus.agent.domain.router.ModelClient
import com.nexus.agent.domain.tool.KnowledgeSearchTool
import com.nexus.agent.domain.tool.TaskStatus
import com.nexus.agent.domain.tool.ToolOrchestrator
import com.nexus.agent.domain.tool.WebSearchAdapter
import com.nexus.agent.data.connectors.GoogleAuthManager
import com.nexus.agent.data.remote.GmailApiClient
import com.nexus.agent.data.repository.GmailRepository
import com.nexus.agent.domain.connectors.GmailConnector
import com.nexus.agent.domain.tool.GmailSearchTool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    secrets: SecretStorageRepository,
    googleAuthManager: GoogleAuthManager
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

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
    val router = remember(client) {
        val deepSeek = object : ModelClient {
            override val supportedModels = setOf(
                AiModelRouter.MODEL_DEEPSEEK_FLASH,
                AiModelRouter.MODEL_DEEPSEEK_V4_PRO
            )
            override suspend fun complete(
                history: List<ChatMessage>,
                model: String
            ): Result<String> = client.complete(history, model)
            override suspend fun completeStream(
                history: List<ChatMessage>,
                model: String,
                onDelta: suspend (String) -> Unit
            ): Result<String> = client.completeStream(history, model, onDelta)
        }
        AiModelRouter(listOf(deepSeek))
    }
    val emotionAnalyzer = remember { EmotionAnalyzer() }
    val loadManager = remember { CognitiveLoadManager() }
    val cultureContext = remember {
        val region = CultureContextEngine.detectRegionFromLocale(java.util.Locale.getDefault().toLanguageTag())
        CultureContext(region = region)
    }
    val personaInstruction = remember(cultureContext) {
        val dialect = regionToDialect(cultureContext.region)
        val english = cultureContext.region == Region.EN_GLOBAL || cultureContext.region == Region.FR_GLOBAL
        if (english) {
            AdaptiveTone.buildRewriteInstructionEnglish(dialect, Tone.FRIENDLY)
        } else {
            AdaptiveTone.buildRewriteInstruction(dialect, Tone.FRIENDLY)
        }
    }
    val neonRepository = remember {
        NeonRepository(
            readUrlProvider = {
                when (val r = secrets.readSecret(SecretStorageRepository.NEON_READ_URL_PREF)) {
                    is ApiKeyReadResult.Present -> r.value
                    else -> null
                }
            },
            writeUrlProvider = {
                when (val r = secrets.readSecret(SecretStorageRepository.NEON_WRITE_URL_PREF)) {
                    is ApiKeyReadResult.Present -> r.value
                    else -> null
                }
            },
            jdbc = NeonJdbcClient()
        )
    }

    val repository = remember(neonRepository) {
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
            systemPromptProvider = { preferences.systemPrompt.first() },
            router = router,
            personaProvider = { personaInstruction },
            mirror = object : ConversationMirror {
                override suspend fun mirror(sessionId: String, role: String, content: String) {
                    // Opt-in gate: never mirror transcripts unless the user enabled it.
                    if (preferences.mirrorEnabled.first()) {
                        neonRepository.mirror(sessionId, role, content)
                    }
                }
            }
        )
    }

    val knowledgeTool = neonRepository
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
    val webSearchAdapter = remember { WebSearchAdapter(webSearchTool) }
    val gmailConnector = remember { GmailConnector(googleAuthManager) }
    val gmailSearchTool = remember(gmailConnector) {
        val api = GmailApiClient(tokenProvider = { gmailConnector.accessToken() })
        val repo = GmailRepository(searchApi = { q, n -> api.searchMessages(q, n) })
        GmailSearchTool(repo)
    }
    val approvalGate = remember { ApprovalGate() }
    val toolOrchestrator = remember(approvalGate) {
        ToolOrchestrator(
            mapOf(
                webSearchAdapter.name to webSearchAdapter,
                knowledgeSearchTool.name to knowledgeSearchTool,
                gmailSearchTool.name to gmailSearchTool
            ),
            approvalGate
        )
    }

    val viewModel: ChatViewModel = viewModel(
        factory = ChatViewModel.Factory(
            repository, preferences, knowledgeTool, webSearchTool, toolOrchestrator,
            emotionAnalyzer, loadManager, cultureContext, neonRepository,
            gmailScopesProvider = {
                if (gmailConnector.isConnected.value) gmailConnector.scopes.toSet() else emptySet()
            },
            approvalGate = approvalGate
        )
    )

    val messages by viewModel.messages.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val streamingText by viewModel.streamingText.collectAsState()
    val taskStatus by viewModel.taskStatus.collectAsState()
    val gmailConnected by gmailConnector.isConnected.collectAsState()
    val gmailEmail by gmailConnector.email.collectAsState()
    val mirrorEnabled by preferences.mirrorEnabled.collectAsState(initial = false)
    val pendingApproval by viewModel.pendingApproval.collectAsState()

    var showSettings by remember { mutableStateOf(false) }
    var keyInput by remember { mutableStateOf("") }
    var systemPromptInput by remember { mutableStateOf("") }
    var knowledgeBaseUrlInput by remember { mutableStateOf("") }
    // Neon fields show only a MASKED value (password hidden). The dirty flags track whether the
    // user actually edited them, so a masked value is never written back as real credentials.
    var neonReadUrlInput by remember { mutableStateOf("") }
    var neonWriteUrlInput by remember { mutableStateOf("") }
    var neonReadDirty by remember { mutableStateOf(false) }
    var neonWriteDirty by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }

    LaunchedEffect(showSettings) {
        if (showSettings) {
            systemPromptInput = withContext(Dispatchers.IO) { preferences.systemPrompt.first() }
            knowledgeBaseUrlInput = withContext(Dispatchers.IO) { preferences.knowledgeBaseUrl.first() }
            neonReadUrlInput = withContext(Dispatchers.IO) {
                when (val r = secrets.readSecret(SecretStorageRepository.NEON_READ_URL_PREF)) {
                    is ApiKeyReadResult.Present -> NeonConnectionParser.maskCredentials(r.value)
                    else -> ""
                }
            }
            neonWriteUrlInput = withContext(Dispatchers.IO) {
                when (val r = secrets.readSecret(SecretStorageRepository.NEON_WRITE_URL_PREF)) {
                    is ApiKeyReadResult.Present -> NeonConnectionParser.maskCredentials(r.value)
                    else -> ""
                }
            }
            neonReadDirty = false
            neonWriteDirty = false
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(
                            painter = painterResource(R.drawable.nexus_logo_premium_dark),
                            contentDescription = null,
                            modifier = Modifier
                                .size(28.dp)
                                .clip(RoundedCornerShape(8.dp))
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleMedium)
                    }
                },
                actions = {
                    TextButton(onClick = { viewModel.createNewConversation() }) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.new_chat))
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

            if (isLoading || taskStatus is TaskStatus.Running) {
                BackgroundTasksBar()
            }

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

    if (showSettings) {
        SettingsDialog(
            keyInput = keyInput,
            onKeyChange = { keyInput = it },
            systemPrompt = systemPromptInput,
            onSystemPromptChange = { systemPromptInput = it },
            knowledgeBaseUrl = knowledgeBaseUrlInput,
            onKnowledgeBaseUrlChange = { knowledgeBaseUrlInput = it },
            neonReadUrl = neonReadUrlInput,
            onNeonReadUrlChange = { neonReadUrlInput = it; neonReadDirty = true },
            neonWriteUrl = neonWriteUrlInput,
            onNeonWriteUrlChange = { neonWriteUrlInput = it; neonWriteDirty = true },
            gmailConnected = gmailConnected,
            gmailEmail = gmailEmail,
            onConnectGmail = {
                if (BuildConfig.GOOGLE_OAUTH_CLIENT_ID.isBlank()) {
                    context.showToast(context.getString(R.string.oauth_client_missing))
                } else {
                    googleAuthManager.startAuthorization(gmailConnector.scopes)
                }
            },
            onDisconnectGmail = { scope.launch { gmailConnector.disconnect() } },
            mirrorEnabled = mirrorEnabled,
            onMirrorEnabledChange = { scope.launch { preferences.setMirrorEnabled(it) } },
            onDismiss = { showSettings = false },
            onSave = {
                scope.launch {
                    try {
                        val key = keyInput.trim()
                        if (key.isNotEmpty()) {
                            withContext(Dispatchers.IO) { secrets.saveApiKey(key) }
                        }
                        withContext(Dispatchers.IO) { preferences.setSystemPrompt(systemPromptInput) }
                        withContext(Dispatchers.IO) { preferences.setKnowledgeBaseUrl(knowledgeBaseUrlInput) }
                        withContext(Dispatchers.IO) {
                            // Only persist when the user actually edited a field — otherwise the
                            // masked placeholder would be written back and destroy the real URL.
                            if (neonReadDirty) {
                                saveNeonUrl(secrets, neonReadUrlInput, SecretStorageRepository.NEON_READ_URL_PREF)
                            }
                            if (neonWriteDirty) {
                                saveNeonUrl(secrets, neonWriteUrlInput, SecretStorageRepository.NEON_WRITE_URL_PREF)
                            }
                        }
                        keyInput = ""
                        showSettings = false
                        context.showToast(context.getString(R.string.saved))
                    } catch (e: Exception) {
                        context.showToast(context.getString(R.string.error_generic))
                    }
                }
            }
        )
    }

    pendingApproval?.let { request ->
        ApprovalCard(
            request = request,
            onApprove = { viewModel.approvePending() },
            onReject = { viewModel.rejectPending() }
        )
    }
}

private suspend fun saveNeonUrl(
    secrets: SecretStorageRepository,
    raw: String,
    prefName: String
) {
    val trimmed = raw.trim()
    // Never persist a value that still carries the masked password placeholder — an edited
    // field must have the real password re-entered (otherwise the credential would be destroyed).
    if (NeonConnectionParser.hasMaskedPassword(trimmed)) return
    val parsed = NeonConnectionParser.parse(trimmed)
    when {
        // Store the NORMALIZED jdbc:postgresql:// URL so pgjdbc's acceptsURL() recognizes it —
        // the raw Neon-console URI form (postgresql://…) would otherwise fail to connect.
        parsed != null -> secrets.saveSecret(prefName, parsed.jdbcUrl)
        // Only a truly blank field clears the secret; invalid input leaves the old value intact.
        trimmed.isEmpty() -> secrets.clearSecret(prefName)
        else -> Unit
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
private fun BackgroundTasksBar() {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(10.dp))
            Text(
                stringResource(R.string.background_task_running),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
            )
        }
    }
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
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { /* attach — reserved */ }, modifier = Modifier.size(40.dp)) {
                Icon(
                    Icons.Default.AttachFile,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(4.dp))
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text(stringResource(R.string.type_message)) },
                enabled = enabled,
                maxLines = 5,
                shape = RoundedCornerShape(24.dp)
            )
            Spacer(Modifier.width(4.dp))
            if (value.isNotBlank()) {
                IconButton(
                    onClick = onSend,
                    enabled = sendEnabled,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        Icons.Default.Send,
                        contentDescription = stringResource(R.string.send),
                        tint = if (sendEnabled)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                IconButton(onClick = { /* voice — reserved */ }, modifier = Modifier.size(48.dp)) {
                    Icon(
                        Icons.Default.Mic,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
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
    neonReadUrl: String,
    onNeonReadUrlChange: (String) -> Unit,
    neonWriteUrl: String,
    onNeonWriteUrlChange: (String) -> Unit,
    gmailConnected: Boolean,
    gmailEmail: String?,
    onConnectGmail: () -> Unit,
    onDisconnectGmail: () -> Unit,
    mirrorEnabled: Boolean,
    onMirrorEnabledChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    onSave: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings)) },
        text = {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.gmail),
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            text = if (gmailConnected) {
                                gmailEmail?.let { stringResource(R.string.gmail_connected_as, it) }
                                    ?: stringResource(R.string.gmail_connected)
                            } else {
                                stringResource(R.string.gmail_not_connected)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (gmailConnected) {
                        TextButton(onClick = onDisconnectGmail) {
                            Text(stringResource(R.string.disconnect))
                        }
                    } else {
                        Button(onClick = onConnectGmail) {
                            Text(stringResource(R.string.connect))
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
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
                    value = neonReadUrl,
                    onValueChange = onNeonReadUrlChange,
                    label = { Text(stringResource(R.string.neon_read_url)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = neonWriteUrl,
                    onValueChange = onNeonWriteUrlChange,
                    label = { Text(stringResource(R.string.neon_write_url)) },
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
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.mirror_enabled),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Switch(
                        checked = mirrorEnabled,
                        onCheckedChange = onMirrorEnabledChange
                    )
                }
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
private fun ApprovalCard(
    request: ApprovalRequest,
    onApprove: () -> Unit,
    onReject: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onReject,
        title = { Text(stringResource(R.string.approval_title)) },
        text = {
            Column {
                Text(stringResource(R.string.approval_action, request.toolName))
                Spacer(Modifier.height(8.dp))
                Text(
                    text = request.arguments,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(onClick = onApprove) { Text(stringResource(R.string.approve)) }
        },
        dismissButton = {
            TextButton(onClick = onReject) { Text(stringResource(R.string.reject)) }
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
