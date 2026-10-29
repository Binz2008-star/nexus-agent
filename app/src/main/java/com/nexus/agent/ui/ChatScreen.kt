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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import com.nexus.agent.data.repository.ApiKeyReadResult
import com.nexus.agent.data.repository.AppPreferencesRepository
import com.nexus.agent.data.repository.ChatRepository
import com.nexus.agent.data.repository.SecretStorageRepository
import com.nexus.agent.domain.model.ChatMessage
import com.nexus.agent.security.AndroidKeystoreKeyProvider
import com.nexus.agent.security.CryptoEngine
import kotlinx.coroutines.Dispatchers
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

    val viewModel: ChatViewModel = viewModel(factory = ChatViewModel.Factory(repository))

    val messages by viewModel.messages.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val selectedModel by viewModel.selectedModel.collectAsState()
    val streamingText by viewModel.streamingText.collectAsState()

    var status by remember { mutableStateOf("") }
    var showSettings by remember { mutableStateOf(false) }
    var keyInput by remember { mutableStateOf("") }
    var systemPromptInput by remember { mutableStateOf("") }
    var input by remember { mutableStateOf("") }

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

            Composer(
                value = input,
                onValueChange = { input = it },
                enabled = !isLoading,
                onSend = {
                    viewModel.sendMessage(input)
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
            onDismiss = { showSettings = false },
            onSave = {
                scope.launch {
                    val key = keyInput.trim()
                    if (key.isNotEmpty()) {
                        withContext(Dispatchers.IO) { secrets.saveApiKey(key) }
                        status = context.getString(R.string.status_key_saved) + " · $selectedModel"
                    }
                    withContext(Dispatchers.IO) { preferences.setSystemPrompt(systemPromptInput) }
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
private fun Composer(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
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
                enabled = value.isNotBlank() && enabled,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    Icons.Default.Send,
                    contentDescription = stringResource(R.string.send),
                    tint = if (value.isNotBlank() && enabled)
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
    return when (code) {
        "auth" -> stringResource(R.string.error_auth)
        "quota" -> stringResource(R.string.error_quota)
        "rate" -> stringResource(R.string.error_rate)
        "no_key" -> stringResource(R.string.error_no_key)
        "empty" -> stringResource(R.string.error_empty)
        "nothing_to_retry" -> stringResource(R.string.error_nothing_to_retry)
        else -> stringResource(R.string.error_generic)
    }
}
