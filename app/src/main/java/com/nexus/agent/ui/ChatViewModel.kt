package com.nexus.agent.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.nexus.agent.data.local.ConversationEntity
import com.nexus.agent.data.repository.AppPreferencesRepository
import com.nexus.agent.data.repository.ChatRepository
import com.nexus.agent.data.repository.SendResult
import com.nexus.agent.data.work.BackgroundTaskRunner
import com.nexus.agent.domain.cognitive.CognitiveLoadDecision
import com.nexus.agent.domain.cognitive.CognitiveLoadManager
import com.nexus.agent.domain.emotion.EmotionAnalysis
import com.nexus.agent.domain.emotion.EmotionAnalyzer
import com.nexus.agent.domain.model.ChatMessage
import com.nexus.agent.domain.culture.CultureContext
import com.nexus.agent.domain.persona.Emotion
import com.nexus.agent.domain.persona.EmotionPoetry
import com.nexus.agent.domain.persona.Sentiment
import com.nexus.agent.domain.persona.SentimentAnalyzer
import com.nexus.agent.domain.safety.IntentEvaluator
import com.nexus.agent.domain.safety.SensitiveTopicDetector
import com.nexus.agent.domain.safety.UserBehaviorGuard
import com.nexus.agent.domain.tool.KnowledgeSearchResult
import com.nexus.agent.domain.tool.KnowledgeTool
import com.nexus.agent.domain.tool.TaskStatus
import com.nexus.agent.domain.tool.ToolOrchestrator
import com.nexus.agent.domain.tool.ToolResult
import com.nexus.agent.domain.tool.WebSearchOutcome
import com.nexus.agent.domain.tool.WebSearchTool
import com.nexus.agent.domain.tool.buildContextPrompt
import com.nexus.agent.domain.wellness.BreathingCompanion
import com.nexus.agent.domain.wellness.BreathingPattern
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
    private val toolOrchestrator: ToolOrchestrator,
    private val emotionAnalyzer: EmotionAnalyzer,
    private val loadManager: CognitiveLoadManager
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

    // Re-entry guard so a rapid second send can't create a second conversation while
    // the first is still creating its own. Set atomically on the UI thread, cleared on task end.
    private val _agentBusy = MutableStateFlow(false)

    // Last grounding context (tool excerpts) used by the most recent agentic answer, so
    // "retry" re-grounds instead of silently falling back to a direct answer.
    @Volatile
    private var lastContext: String? = null

    // Escalating safety guard: blocks the session after repeated policy violations.
    private val behaviorGuard = UserBehaviorGuard()
    private val intentEvaluator = IntentEvaluator(behaviorGuard)

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
     * Agentic send: search the web for grounding excerpts, then stream an answer grounded in
     * those excerpts. Runs on [BackgroundTaskRunner] so the UI stays non-blocking and can show
     * a "Working for Ns" timer + cancel. If the search fails or returns no results, it falls
     * back to a direct answer (existing chat behaviour preserved).
     */
    fun sendMessageWithAgent(text: String) {
        if (text.isBlank()) return
        if (!_agentBusy.compareAndSet(false, true)) return
        viewModelScope.launch {
            var handedOffToTask = false
            try {
                // 1) Cognitive-load (spam) gate — suppresses the request before it reaches the
                //    tool/network layer and shows a polite local warning instead.
                when (val decision = loadManager.tryAcquire()) {
                    is CognitiveLoadDecision.Throttled -> {
                        val convId = _currentConversationId.value ?: repository.createConversation().also {
                            _currentConversationId.value = it
                        }
                        repository.persistUserMessage(convId, text.trim())
                        repository.persistAssistantMessage(convId, decision.message)
                        return@launch
                    }
                    CognitiveLoadDecision.Allowed -> Unit
                }

                // 2) Emotion / intent safety gate — blocks harmful requests locally and aborts the
                //    network request, surfacing the refusal as an assistant message.
                when (val analysis = emotionAnalyzer.analyze(text.trim())) {
                    is EmotionAnalysis.Blocked -> {
                        val convId = _currentConversationId.value ?: repository.createConversation().also {
                            _currentConversationId.value = it
                        }
                        repository.persistUserMessage(convId, text.trim())
                        // Escalate repeated violations; block the session after the threshold.
                        val reply = when (val decision = behaviorGuard.registerBadIntent(SESSION_ID)) {
                            is UserBehaviorGuard.BehaviorDecision.Blocked -> BLOCKED_MESSAGE
                            else -> analysis.localResponse
                        }
                        repository.persistAssistantMessage(convId, reply)
                        return@launch
                    }
                    EmotionAnalysis.Passed -> Unit
                }

                // 2b) Sensitive-topic gate — religion/provocation/personal questions get a
                //     culturally-adapted neutral reply + escalation (instead of an answer).
                SensitiveTopicDetector.detect(text.trim())?.let { category ->
                    val convId = _currentConversationId.value ?: repository.createConversation().also {
                        _currentConversationId.value = it
                    }
                    repository.persistUserMessage(convId, text.trim())
                    val result = intentEvaluator.handleSensitiveContent(SESSION_ID, category, CultureContext())
                    repository.persistAssistantMessage(convId, result.reply)
                    return@launch
                }

                // 3) Commands (poetry / breathing) or the normal agentic flow.
                val convId = _currentConversationId.value ?: repository.createConversation().also {
                    _currentConversationId.value = it
                }
                when (detectCommand(text)) {
                    Command.Poetry -> {
                        launchPromptTask(convId, text, EmotionPoetry.buildPoetryPrompt(Emotion.NEUTRAL))
                        handedOffToTask = true
                    }
                    Command.Breathing -> {
                        repository.persistUserMessage(convId, text.trim())
                        repository.persistAssistantMessage(convId, breathingGuideText())
                        return@launch
                    }
                    null -> {
                        launchAgentTask(convId, text)
                        handedOffToTask = true
                    }
                }
            } finally {
                // Clear the re-entry guard on every path that did NOT hand off to the
                // BackgroundTaskRunner (whose own finally clears it). This also covers
                // exceptions thrown while creating the conversation or persisting.
                if (!handedOffToTask) _agentBusy.value = false
            }
        }
    }

    fun cancelTask() {
        taskRunner.cancel()
        _streamingText.value = ""
        _agentBusy.value = false
    }

    private fun launchAgentTask(conversationId: Long, text: String) {
        _streamingText.value = ""
        _errorMessage.value = null
        taskRunner.run { update ->
            try {
                val startedAt = System.currentTimeMillis()
                update(TaskStatus.Running(startedAt, "searching"))
                repository.persistUserMessage(conversationId, text)

                val input = buildJsonObject {
                    put("query", JsonPrimitive(text.trim()))
                    put("count", JsonPrimitive("5"))
                }
                val toolResult = toolOrchestrator.execute(TOOL_WEB_SEARCH, input)
                if (toolResult is ToolResult.Blocked) {
                    _errorMessage.value = toolResult.reason
                    update(TaskStatus.Failed("blocked"))
                    return@run
                }

                val sources = (toolResult as? ToolResult.Success)?.sources.orEmpty()
                val context = buildList {
                    if (sources.isNotEmpty()) add(buildContextPrompt(sources))
                    empatheticNote(text)?.let { add(it) }
                }.takeIf { it.isNotEmpty() }?.joinToString("\n\n")
                lastContext = context

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
            } finally {
                _agentBusy.value = false
            }
        }
    }

    /**
     * Streams an answer using a fixed instruction [prompt] (e.g. poetry) instead of web search.
     * Same lifecycle/cancel semantics as [launchAgentTask].
     */
    private fun launchPromptTask(conversationId: Long, text: String, prompt: String) {
        _streamingText.value = ""
        _errorMessage.value = null
        taskRunner.run { update ->
            try {
                update(TaskStatus.Running(System.currentTimeMillis(), "thinking"))
                repository.persistUserMessage(conversationId, text)
                val fullPrompt = buildList {
                    add(prompt)
                    empatheticNote(text)?.let { add(it) }
                }.joinToString("\n\n")
                val result = repository.completeWithContext(
                    conversationId, _selectedModel.value, fullPrompt
                ) { delta -> _streamingText.value += delta }
                _streamingText.value = ""
                when (result) {
                    is SendResult.Success -> update(TaskStatus.Completed(emptyList()))
                    is SendResult.Failure -> {
                        _errorMessage.value = result.errorCode
                        update(TaskStatus.Failed(result.errorCode))
                    }
                }
            } finally {
                _agentBusy.value = false
            }
        }
    }

    private enum class Command { Poetry, Breathing }

    private fun detectCommand(text: String): Command? {
        val t = text.trim().lowercase()
        return when {
            t.contains("قصيدة") || t.contains("شعر") || t.contains("poem") || t.contains("poetry") -> Command.Poetry
            t.contains("تنفس") || t.contains("تنفّس") || t.contains("breathe") || t.contains("breathing") -> Command.Breathing
            else -> null
        }
    }

    private fun breathingGuideText(): String {
        val guide = BreathingCompanion.buildGuide(BreathingPattern.RELAXING, "ar")
        return buildString {
            appendLine(guide.startInstruction)
            for (phase in guide.phases) {
                appendLine("${phase.instruction} (${phase.durationSeconds} ثوانٍ)")
            }
            append(guide.completeInstruction)
        }
    }

    /** Returns an empathetic directive when the user's message reads negative, else null. */
    private fun empatheticNote(text: String): String? =
        if (SentimentAnalyzer.analyze(text) == Sentiment.NEGATIVE) EMPATHETIC_NOTE else null

    fun retryLast() {
        val convId = _currentConversationId.value ?: return
        if (_isLoading.value) return
        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null
            _streamingText.value = ""
            when (val result = repository.retryCompletionStream(convId, _selectedModel.value, lastContext) { delta ->
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
        private val toolOrchestrator: ToolOrchestrator,
        private val emotionAnalyzer: EmotionAnalyzer,
        private val loadManager: CognitiveLoadManager
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return ChatViewModel(
                repository, preferences, knowledgeTool, webSearchTool, toolOrchestrator,
                emotionAnalyzer, loadManager
            ) as T
        }
    }

    companion object {
        private const val DEFAULT_TOP_K = 8
        private const val DEFAULT_COUNT = 10
        private const val TOOL_WEB_SEARCH = "web_search"
        // Single-user app: a fixed session id scopes the behavior guard to this install.
        private const val SESSION_ID = "default"
        private const val BLOCKED_MESSAGE =
            "تم تجاوز الحد المسموح من المحاولات المخالفة. تم إيقاف الردود مؤقتًا، حاول لاحقًا."
        private const val EMPATHETIC_NOTE =
            "المستخدم يبدو متضايقًا، كن متعاطفًا ولطيفًا في ردك."
    }
}
