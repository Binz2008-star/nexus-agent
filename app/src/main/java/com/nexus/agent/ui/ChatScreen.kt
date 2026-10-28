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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.nexus.agent.data.network.DeepSeekClient
import com.nexus.agent.data.repository.ApiKeyReadResult
import com.nexus.agent.data.repository.SecretStorageRepository
import com.nexus.agent.domain.model.ChatMessage
import com.nexus.agent.security.AndroidKeystoreKeyProvider
import com.nexus.agent.security.CryptoEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Phase 2A chat screen.
 * - Reads encrypted DeepSeek API key from SecretStorageRepository
 * - Sends conversation history to DeepSeek Chat Completions (Bearer auth)
 * - Default model: deepseek-flash; can be switched to deepseek-v4-pro
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val crypto = remember { CryptoEngine(AndroidKeystoreKeyProvider()) }
    val secrets = remember { SecretStorageRepository.create(context, crypto) }
    val client = remember {
        DeepSeekClient(apiKeyProvider = {
            when (val r = secrets.readApiKey()) {
                is ApiKeyReadResult.Present -> r.value
                else -> null
            }
        })
    }

    var messages by remember { mutableStateOf(listOf<ChatMessage>()) }
    var input by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Checking API key…") }
    var showKeyDialog by remember { mutableStateOf(false) }
    var keyInput by remember { mutableStateOf("") }
    var selectedModel by remember { mutableStateOf(DeepSeekClient.DEFAULT_MODEL) }

    LaunchedEffect(Unit) {
        status = when (val result = withContext(Dispatchers.IO) { secrets.readApiKey() }) {
            is ApiKeyReadResult.Present -> "Connected · ${selectedModel}"
            is ApiKeyReadResult.Missing -> "No API key — tap Set Key"
            is ApiKeyReadResult.Corrupted -> "Stored key corrupted — re-enter"
            is ApiKeyReadResult.KeyUnavailable -> "Keystore key unavailable — re-enter"
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("NEXUS Agent") },
                actions = {
                    TextButton(onClick = { showKeyDialog = true }) {
                        Text("Set Key")
                    }
                    TextButton(onClick = {
                        selectedModel = if (selectedModel == DeepSeekClient.DEFAULT_MODEL)
                            DeepSeekClient.PRO_MODEL else DeepSeekClient.DEFAULT_MODEL
                        status = "Connected · $selectedModel"
                    }) {
                        Text(if (selectedModel == DeepSeekClient.DEFAULT_MODEL) "Flash" else "Pro")
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

            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(messages) { message ->
                    MessageBubble(message)
                }
                if (isLoading) {
                    item {
                        Text(
                            "Thinking…",
                            modifier = Modifier.padding(8.dp),
                            style = MaterialTheme.typography.bodySmall
                        )
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
                    placeholder = { Text("Type a message…") },
                    singleLine = true,
                    enabled = !isLoading
                )
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = {
                        val text = input.trim()
                        if (text.isEmpty() || isLoading) return@Button
                        val userMsg = ChatMessage(ChatMessage.Role.USER, text)
                        messages = messages + userMsg
                        input = ""
                        isLoading = true
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                client.complete(messages, selectedModel)
                            }
                            isLoading = false
                            result.fold(
                                onSuccess = { reply ->
                                    messages = messages + ChatMessage(ChatMessage.Role.ASSISTANT, reply)
                                    status = "Connected · $selectedModel"
                                },
                                onFailure = { e ->
                                    messages = messages + ChatMessage(
                                        ChatMessage.Role.ASSISTANT,
                                        "Error: ${e.message ?: "request failed"}"
                                    )
                                    status = "Request failed"
                                }
                            )
                        }
                    },
                    enabled = input.isNotBlank() && !isLoading
                ) {
                    Text("Send")
                }
            }
        }
    }

    if (showKeyDialog) {
        AlertDialog(
            onDismissRequest = { showKeyDialog = false },
            title = { Text("DeepSeek API Key") },
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
                            status = "Connected · $selectedModel"
                            keyInput = ""
                            showKeyDialog = false
                        }
                    }
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showKeyDialog = false }) { Text("Cancel") }
            }
        )
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
