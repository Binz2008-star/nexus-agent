package com.nexus.agent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nexus.agent.R
import com.nexus.agent.data.local.NexusDatabase
import com.nexus.agent.data.network.DeepSeekClient
import com.nexus.agent.data.repository.ApiKeyReadResult
import com.nexus.agent.data.repository.ChatRepository
import com.nexus.agent.data.repository.SecretStorageRepository
import com.nexus.agent.domain.model.ChatMessage
import com.nexus.agent.security.AndroidKeystoreKeyProvider
import com.nexus.agent.security.CryptoEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val crypto = remember { CryptoEngine(AndroidKeystoreKeyProvider()) }
    val secrets = remember { SecretStorageRepository.create(context, crypto) }
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
            }
        )
    }

    val viewModel: ChatViewModel = viewModel(factory = ChatViewModel.Factory(repository))

    val messages by viewModel.messages.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val selectedModel by viewModel.selectedModel.collectAsState()
    val streamingText by viewModel.streamingText.collectAsState()

    var status by remember { mutableStateOf("") }
    var showKeyDialog by remember { mutableStateOf(false) }
    var keyInput by remember { mutableStateOf("") }
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

    val listState = rememberLazyListState()
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    TextButton(onClick = { showKeyDialog = true }) {
                        Text(stringResource(R.string.settings))
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
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = status,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            if (errorMessage != null) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = mapErrorCode(errorMessage!!),
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall
                        )
                        TextButton(onClick = { viewModel.retryLast() }) {
                            Text(stringResource(R.string.retry))
                        }
                        TextButton(onClick = { viewModel.clearError() }) {
                            Text(stringResource(R.string.cancel))
                        }
                    }
                }
            }

            if (messages.isEmpty() && !isLoading) {
                Column(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = stringResource(R.string.empty_chat),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(messages, key = { it.id }) { message ->
                        MessageBubble(message)
                    }
                    if (isLoading) {
                        item {
                            if (streamingText.isNotEmpty()) {
                                MessageBubble(ChatMessage(ChatMessage.Role.ASSISTANT, streamingText))
                            } else {
                                Text(
                                    stringResource(R.string.thinking),
                                    modifier = Modifier.padding(8.dp),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(stringResource(R.string.type_message)) },
                    singleLine = true,
                    enabled = !isLoading
                )
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = {
                        viewModel.sendMessage(input)
                        input = ""
                    },
                    enabled = input.isNotBlank() && !isLoading
                ) {
                    Text(stringResource(R.string.send))
                }
            }
        }
    }

    if (showKeyDialog) {
        AlertDialog(
            onDismissRequest = { showKeyDialog = false },
            title = { Text(stringResource(R.string.api_key_title)) },
            text = {
                OutlinedTextField(
                    value = keyInput,
                    onValueChange = { keyInput = it },
                    label = { Text("sk-…") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val key = keyInput.trim()
                    if (key.isNotEmpty()) {
                        scope.launch {
                            withContext(Dispatchers.IO) { secrets.saveApiKey(key) }
                            status = context.getString(R.string.status_key_saved) + " · $selectedModel"
                            keyInput = ""
                            showKeyDialog = false
                        }
                    }
                }) { Text(stringResource(R.string.save)) }
            },
            dismissButton = {
                TextButton(onClick = { showKeyDialog = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }
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

@Composable
private fun MessageBubble(message: ChatMessage) {
    val isUser = message.role == ChatMessage.Role.USER
    Surface(
        color = if (isUser) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = message.text,
            modifier = Modifier.padding(12.dp),
            style = MaterialTheme.typography.bodyLarge
        )
    }
}
