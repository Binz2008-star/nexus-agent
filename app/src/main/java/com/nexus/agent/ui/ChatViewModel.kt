package com.nexus.agent.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.nexus.agent.data.local.ConversationEntity
import com.nexus.agent.data.repository.AppPreferencesRepository
import com.nexus.agent.data.repository.ChatRepository
import com.nexus.agent.data.repository.SendResult
import com.nexus.agent.data.work.BackgroundTaskRunner
import com.nexus.agent.domain.model.ChatMessage
import com.nexus.agent.domain.tool.KnowledgeSearchResult
import com.nexus.agent.domain.tool.KnowledgeTool
import com.nexus.agent.domain.tool.TaskStatus
import com.nexus.agent.domain.tool.ToolOrchestrator
import com.nexus.agent.domain.tool.ToolResult
import com.nexus.agent.domain.tool.WebSearchOutcome
import com.nexus.agent.domain.tool.WebSearchTool
import com.nexus.agent.domain.tool.buildContextPrompt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

class ChatViewModel(
    private val repository: ChatRepository,
    private val preferences: AppPreferencesRepository,
    private val knowledgeTool: KnowledgeTool,
    private val webSearchTool: WebSearchTool,
    private val toolOrchestrator: ToolOrchestrator
) : ViewModel() {

    val conversations: StateFlow<List<ConversationEntity>> = repository.observeConversations()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _currentConversationId = MutableStateFlow<Long?>(null)
    val currentConversationId: StateFlow<Long?> = _currentConversationId.asStateFlow()

    val messages: StateFlow<List<ChatMessage>> = _currentConversationId
        .flatMapLatest { id ->
            if (id == null) flowOf(emptyList())
            else repository.observeMessages(id)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _selectedModel = MutableStateFlow("deepseek-flash")
    val selectedModel: StateFlow<String> = _selectedModel.asStateFlow()

    private val _streamingText = MutableStateFlow("")
    val streamingText: StateFlow<String> = _streamingText.asStateFlow()

    // ---- Background agent task (tool search -> grounded answer) ----

    private val taskRunner = BackgroundTaskRunner()
    val taskStatus: StateFlow<TaskStatus> = taskRunner.status

    init {
        // Restore the last-used model across restarts.
        viewModelScope.launch {
            _selectedModel.value = preferences.lastModel.first()
        }
    }

    override fun onCleared() {
        taskRunner.close()
        super.onCleared()
    }

    fun setModel(model: String) {
        _selectedModel.value = model
        viewModelScope.launch { preferences.setLastModel(model) }
    }

    fun selectConversation(id: Long) {
        _currentConversationId.value = id
        _streamingText.value = ""
        _errorMessage.value = null
    }

    fun createNewConversation() {
        viewModelScope.launch {
            val id = repository.createConversation()
            _currentConversationId.value = id
            _streamingText.value = ""
            _errorMessage.value = null
        }
    }

    fun sendMessage(text: String) {
        if (_isLoading.value || text.isBlank()) return
        val convId = _currentConversationId.value
        _isLoading.value = true
        if (convId == null) {
            viewModelScope.launch {
                val id = repository.createConversation()
                _currentConversationId.value = id
                doSend(id, text)
            }
            return
        }
        viewModelScope.launch { doSend(convId, text) }
    }

    private suspend fun doSend(conversationId: Long, text: String) {
        _isLoading.value = true
        _errorMessage.value = null
        _streamingText.value = ""
        when (val result = repository.sendMessageStream(conversationId, text, _selectedModel.value) { delta ->
            _streamingText.value += delta
        }) {
            is SendResult.Success -> {}
            is SendResult.Failure -> _errorMessage.value = result.errorCode
        }
        _streamingText.value = ""
        _isLoading.value = false
    }

    fun clearError() {
        _errorMessage.value = null
    }

    /**
     * Agentic send: search the knowledge base for grounding excerpts, then stream an answer
     * grounded in those excerpts. Runs on [BackgroundTaskRunner] so the UI stays non-blocking
     * and can show a "Working for Ns" timer + cancel. If the tool has no results (or is not
     * configured), it falls back to a direct answer (existing chat behaviour preserved).
     */
    fun sendMessageWithAgent(text: String) {
        if (text.isBlank()) return
        val convId = _currentConversationId.value
        if (convId == null) {
            viewModelScope.launch {
                val id = repository.createConversation()
                _currentConversationId.value = id
                launchAgentTask(id, text)
            }
            return
        }
        launchAgentTask(convId, text)
    }

    fun cancelTask() {
        taskRunner.cancel()
        _streamingText.value = ""
    }

    private fun launchAgentTask(conversationId: Long, text: String) {
        _streamingText.value = ""
        _errorMessage.value = null
        taskRunner.run { update ->
            val startedAt = System.currentTimeMillis()
            update(TaskStatus.Running(startedAt, "searching"))
            repository.persistUserMessage(conversationId, text)

            val input = buildJsonObject {
                put("query", JsonPrimitive(text.trim()))
                put("top_k", JsonPrimitive(5))
            }
            val toolResult = toolOrchestrator.execute(TOOL_KNOWLEDGE_SEARCH, input)
            if (toolResult is ToolResult.Blocked) {
                _errorMessage.value = toolResult.reason
                update(TaskStatus.Failed("blocked"))
                return@run
            }

            val sources = (toolResult as? ToolResult.Success)?.sources.orEmpty()
            val context = if (sources.isEmpty()) null else buildContextPrompt(sources)

            update(TaskStatus.Running(startedAt, "thinking"))
            val result = repository.completeWithContext(
                conversationId, _selectedModel.value, context
            ) { delta -> _streamingText.value += delta }
            _streamingText.value = ""

            when (result) {
                is SendResult.Success -> update(TaskStatus.Completed(sources))
                is SendResult.Failure -> {
                    _errorMessage.value = result.errorCode
                    update(TaskStatus.Failed(result.errorCode))
                }
            }
        }
    }

    fun retryLast() {
        val convId = _currentConversationId.value ?: return
        if (_isLoading.value) return
        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null
            _streamingText.value = ""
            when (val result = repository.retryCompletionStream(convId, _selectedModel.value) { delta ->
                _streamingText.value += delta
            }) {
                is SendResult.Success -> {}
                is SendResult.Failure -> _errorMessage.value = result.errorCode
            }
            _streamingText.value = ""
            _isLoading.value = false
        }
    }

    // ---- Knowledge search (read-only) ----

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    private val _searchResults = MutableStateFlow<KnowledgeSearchResult?>(null)
    val searchResults: StateFlow<KnowledgeSearchResult?> = _searchResults.asStateFlow()

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun searchKnowledge() {
        val query = _searchQuery.value.trim()
        if (query.isEmpty() || _isSearching.value) return
        viewModelScope.launch {
            _isSearching.value = true
            _searchResults.value = knowledgeTool.search(query, DEFAULT_TOP_K)
            _isSearching.value = false
        }
    }

    fun clearSearch() {
        _searchQuery.value = ""
        _searchResults.value = null
    }

    // ---- Web search (read-only) ----

    private val _webQuery = MutableStateFlow("")
    val webQuery: StateFlow<String> = _webQuery.asStateFlow()

    private val _isWebSearching = MutableStateFlow(false)
    val isWebSearching: StateFlow<Boolean> = _isWebSearching.asStateFlow()

    private val _webResults = MutableStateFlow<WebSearchOutcome?>(null)
    val webResults: StateFlow<WebSearchOutcome?> = _webResults.asStateFlow()

    fun setWebQuery(query: String) {
        _webQuery.value = query
    }

    fun searchWeb() {
        val query = _webQuery.value.trim()
        if (query.isEmpty() || _isWebSearching.value) return
        viewModelScope.launch {
            _isWebSearching.value = true
            _webResults.value = webSearchTool.search(query, DEFAULT_COUNT)
            _isWebSearching.value = false
        }
    }

    fun clearWebSearch() {
        _webQuery.value = ""
        _webResults.value = null
    }

    class Factory(
        private val repository: ChatRepository,
        private val preferences: AppPreferencesRepository,
        private val knowledgeTool: KnowledgeTool,
        private val webSearchTool: WebSearchTool,
        private val toolOrchestrator: ToolOrchestrator
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return ChatViewModel(
                repository, preferences, knowledgeTool, webSearchTool, toolOrchestrator
            ) as T
        }
    }

    companion object {
        private const val DEFAULT_TOP_K = 8
        private const val DEFAULT_COUNT = 10
        private const val TOOL_KNOWLEDGE_SEARCH = "second_brain_search"
    }
}
