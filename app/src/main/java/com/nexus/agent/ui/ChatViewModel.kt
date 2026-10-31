package com.nexus.agent.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.nexus.agent.data.local.ConversationEntity
import com.nexus.agent.data.network.CompletionResult
import com.nexus.agent.data.repository.AppPreferencesRepository
import com.nexus.agent.data.repository.ChatRepository
import com.nexus.agent.data.repository.NeonRepository
import com.nexus.agent.data.repository.SendResult
import com.nexus.agent.data.work.BackgroundTaskRunner
import com.nexus.agent.domain.cognitive.CognitiveLoadDecision
import com.nexus.agent.domain.cognitive.CognitiveLoadManager
import com.nexus.agent.domain.emotion.EmotionAnalyzer
import com.nexus.agent.domain.model.ChatMessage
import com.nexus.agent.domain.culture.CultureContext
import com.nexus.agent.domain.commands.Command
import com.nexus.agent.domain.commands.CommandDetector
import com.nexus.agent.domain.commands.GreetingDetector
import com.nexus.agent.domain.memory.RememberCommandDetector
import com.nexus.agent.domain.orchestration.ToolLoop
import com.nexus.agent.domain.orchestration.ToolProposal
import com.nexus.agent.domain.persona.EmotionPoetry
import com.nexus.agent.domain.policy.ApprovalGate
import com.nexus.agent.domain.policy.ApprovalReceipt
import com.nexus.agent.domain.policy.ApprovalRequest
import com.nexus.agent.domain.persona.Sentiment
import com.nexus.agent.domain.persona.SentimentAnalyzer
import com.nexus.agent.domain.safety.IntentEvaluator
import com.nexus.agent.domain.safety.SafetyPolicy
import com.nexus.agent.domain.safety.UserBehaviorGuard
import com.nexus.agent.domain.tool.KnowledgeSearchResult
import com.nexus.agent.domain.tool.KnowledgeTool
import com.nexus.agent.domain.tool.TaskStatus
import com.nexus.agent.domain.tool.ToolCallParser
import com.nexus.agent.domain.tool.ToolOrchestrator
import com.nexus.agent.domain.tool.ToolResult
import com.nexus.agent.domain.tool.WebSearchOutcome
import com.nexus.agent.domain.tool.WebSearchTool
import com.nexus.agent.domain.tool.buildContextPrompt
import com.nexus.agent.domain.wellness.BreathingCompanion
import com.nexus.agent.domain.wellness.BreathingPattern
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

class ChatViewModel(
    private val repository: ChatRepository,
    private val preferences: AppPreferencesRepository,
    private val knowledgeTool: KnowledgeTool,
    private val webSearchTool: WebSearchTool,
    private val toolOrchestrator: ToolOrchestrator,
    private val emotionAnalyzer: EmotionAnalyzer,
    private val loadManager: CognitiveLoadManager,
    private val cultureContext: CultureContext,
    private val neonRepository: NeonRepository? = null,
    private val gmailScopesProvider: suspend () -> Set<String> = { emptySet() },
    private val approvalGate: ApprovalGate = ApprovalGate()
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

    // Monotonic task id so a superseded/cancelled task's finally can't clear the busy flag
    // for a newer task.
    @Volatile
    private var taskGeneration = 0L

    // Escalating safety guard: blocks the session after repeated policy violations.
    private val behaviorGuard = UserBehaviorGuard()
    private val intentEvaluator = IntentEvaluator(behaviorGuard)

    // Single safety policy (block + harm + sensitive-topic) applied to EVERY message before it
    // reaches the model/tool layer — no execution path bypasses it.
    private val safetyPolicy =
        SafetyPolicy(emotionAnalyzer, behaviorGuard, intentEvaluator, cultureContext, BLOCKED_MESSAGE)

    // Model-driven tool loop: the model proposes tools, the orchestrator validates + executes.
    private val toolLoop = ToolLoop(toolOrchestrator, requestApproval = ::requestApproval)

    // ---- Consent/approval (COMMIT tools pause here until the user approves/rejects) ----

    private val _pendingApproval = MutableStateFlow<ApprovalRequest?>(null)
    val pendingApproval: StateFlow<ApprovalRequest?> = _pendingApproval.asStateFlow()

    @Volatile
    private var pendingDeferred: CompletableDeferred<ApprovalReceipt?>? = null

    /** Suspends the tool loop until the user approves/rejects the pending COMMIT action. */
    private suspend fun requestApproval(request: ApprovalRequest): ApprovalReceipt? {
        val deferred = CompletableDeferred<ApprovalReceipt?>()
        pendingDeferred = deferred
        _pendingApproval.value = request
        return deferred.await()
    }

    /** User approved the pending action — issue a receipt and resume the loop. */
    fun approvePending() {
        val request = _pendingApproval.value ?: return
        _pendingApproval.value = null
        val deferred = pendingDeferred
        pendingDeferred = null
        deferred?.complete(approvalGate.approve(request))
    }

    /** User rejected the pending action — resume the loop with no receipt. */
    fun rejectPending() {
        if (_pendingApproval.value == null) return
        _pendingApproval.value = null
        val deferred = pendingDeferred
        pendingDeferred = null
        deferred?.complete(null)
    }

    /** Clears any in-flight approval (rejection) so a stale card/dangling deferred can't linger. */
    private fun clearPendingApproval() {
        _pendingApproval.value = null
        val deferred = pendingDeferred
        pendingDeferred = null
        deferred?.complete(null)
    }

    init {
        // Restore the last-used model across restarts.
        viewModelScope.launch {
            _selectedModel.value = preferences.lastModel.first()
        }
    }

    override fun onCleared() {
        clearPendingApproval()
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
        lastContext = null
    }

    fun createNewConversation() {
        viewModelScope.launch {
            val id = repository.createConversation()
            _currentConversationId.value = id
            _streamingText.value = ""
            _errorMessage.value = null
            lastContext = null
        }
    }

    /**
     * Legacy entry point kept for API compatibility. Delegates to the agentic path so every
     * message goes through the same safety gates — this path can no longer bypass them.
     */
    fun sendMessage(text: String) = sendMessageWithAgent(text)

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
        val generation = ++taskGeneration
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

                // 2) Safety policy — active block + harmful-intent + sensitive-topic, enforced
                //    uniformly for every message before it reaches the model/tool layer.
                when (val decision = safetyPolicy.evaluate(SESSION_ID, text.trim())) {
                    is SafetyPolicy.Decision.Reject -> {
                        val convId = _currentConversationId.value ?: repository.createConversation().also {
                            _currentConversationId.value = it
                        }
                        repository.persistUserMessage(convId, text.trim())
                        repository.persistAssistantMessage(convId, decision.reply)
                        return@launch
                    }
                    SafetyPolicy.Decision.Allow -> Unit
                }

                // 2b) "remember …" instruction → persist the fact to Neon memory + acknowledge.
                RememberCommandDetector.detect(text.trim())?.let { remembered ->
                    val convId = _currentConversationId.value ?: repository.createConversation().also {
                        _currentConversationId.value = it
                    }
                    repository.persistUserMessage(convId, text.trim())
                    repository.persistAssistantMessage(convId, rememberFact(remembered.fact))
                    return@launch
                }

                // 3) Greeting/small talk → direct concise answer; commands → dedicated flow;
                //    otherwise the agentic (grounded) flow.
                val convId = _currentConversationId.value ?: repository.createConversation().also {
                    _currentConversationId.value = it
                }
                val command = CommandDetector.detect(text)
                when {
                    command == Command.Poetry -> {
                        launchPromptTask(convId, text, EmotionPoetry.buildPoetryPrompt(CommandDetector.poetryEmotion(text)), generation)
                        handedOffToTask = true
                    }
                    command == Command.Breathing -> {
                        repository.persistUserMessage(convId, text.trim())
                        repository.persistAssistantMessage(convId, breathingGuideText())
                        return@launch
                    }
                    command == Command.Email -> {
                        launchGmailTask(convId, text, generation)
                        handedOffToTask = true
                    }
                    GreetingDetector.isConversational(text) -> {
                        launchConversationalTask(convId, text, generation)
                        handedOffToTask = true
                    }
                    else -> {
                        launchAgentTask(convId, text, generation)
                        handedOffToTask = true
                    }
                }
            } finally {
                // Clear the re-entry guard on every path that did NOT hand off to the
                // BackgroundTaskRunner (whose own finally clears it). This also covers
                // exceptions thrown while creating the conversation or persisting.
                if (!handedOffToTask && generation == taskGeneration) _agentBusy.value = false
            }
        }
    }

    fun cancelTask() {
        taskRunner.cancel()
        clearPendingApproval()
        _streamingText.value = ""
        _agentBusy.value = false
    }

    private fun launchAgentTask(conversationId: Long, text: String, generation: Long) {
        // The agentic path now uses the model-driven tool loop (with a scripted fallback).
        launchModelDrivenTask(conversationId, text, generation)
    }

    private fun launchModelDrivenTask(conversationId: Long, text: String, generation: Long) {
        _streamingText.value = ""
        _errorMessage.value = null
        taskRunner.run { update ->
            try {
                val startedAt = System.currentTimeMillis()
                update(TaskStatus.Running(startedAt, "thinking"))
                repository.persistUserMessage(conversationId, text)

                val history = repository.buildHistory(conversationId)
                val schemas = toolOrchestrator.functionSchemas()
                val outcome = toolLoop.run(
                    history, _selectedModel.value, schemas,
                    propose = { h, m, s -> repository.completeWithTools(h, m, s).map { it.toProposal() } }
                )
                when (outcome) {
                    is ToolLoop.Outcome.Answered -> {
                        repository.persistAssistantMessage(conversationId, outcome.content)
                        update(TaskStatus.Completed(outcome.sources))
                    }
                    is ToolLoop.Outcome.Failed -> {
                        runScriptedGrounding(conversationId, text, update, startedAt)
                    }
                }
            } finally {
                if (generation == taskGeneration) _agentBusy.value = false
            }
        }
    }

    /** Fallback: the pre-loop scripted web + Second Brain grounding. */
    private suspend fun runScriptedGrounding(
        conversationId: Long,
        text: String,
        update: (TaskStatus) -> Unit,
        startedAt: Long
    ) {
        update(TaskStatus.Running(startedAt, "searching"))
        val webResult = toolOrchestrator.execute(TOOL_WEB_SEARCH, buildJsonObject {
            put("query", JsonPrimitive(text.trim()))
            put("count", JsonPrimitive("5"))
        })
        val brainResult = toolOrchestrator.execute(TOOL_SECOND_BRAIN, buildJsonObject {
            put("query", JsonPrimitive(text.trim()))
            put("top_k", JsonPrimitive("8"))
        })
        if (webResult is ToolResult.Blocked || brainResult is ToolResult.Blocked) {
            _errorMessage.value = (webResult as? ToolResult.Blocked)?.reason
                ?: (brainResult as? ToolResult.Blocked)?.reason
            update(TaskStatus.Failed("blocked"))
            return
        }

        // Merge web + Second Brain grounding. The Second Brain search is best-effort
        // (a missing Neon config simply contributes no sources).
        val sources = (webResult as? ToolResult.Success)?.sources.orEmpty() +
            (brainResult as? ToolResult.Success)?.sources.orEmpty()
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
    }

    /** Maps a data-layer completion result to the domain tool proposal. */
    private fun CompletionResult.toProposal(): ToolProposal = when (this) {
        is CompletionResult.Answer -> ToolProposal.Answer(content)
        is CompletionResult.ToolCallRequested -> ToolProposal.Calls(
            toolCalls.mapNotNull { ToolCallParser.parse(it.function.name, it.function.arguments) }
        )
    }

    /**
     * Streams an answer using a fixed instruction [prompt] (e.g. poetry) instead of web search.
     * Same lifecycle/cancel semantics as [launchAgentTask].
     */
    private fun launchPromptTask(conversationId: Long, text: String, prompt: String, generation: Long) {
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
                if (generation == taskGeneration) _agentBusy.value = false
            }
        }
    }

    /**
     * Streams a direct, un-grounded answer for casual conversation (greetings/small talk).
     * No web/knowledge search, so "hi" gets a natural short greeting instead of a dictionary
     * explanation. Same lifecycle/cancel semantics as [launchAgentTask].
     */
    private fun launchConversationalTask(conversationId: Long, text: String, generation: Long) {
        _streamingText.value = ""
        _errorMessage.value = null
        taskRunner.run { update ->
            try {
                update(TaskStatus.Running(System.currentTimeMillis(), "thinking"))
                repository.persistUserMessage(conversationId, text)
                val result = repository.completeWithContext(
                    conversationId, _selectedModel.value, null
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
                if (generation == taskGeneration) _agentBusy.value = false
            }
        }
    }

    /**
     * Runs a read-only Gmail inbox search and streams an answer grounded in the matching
     * messages. Same lifecycle/cancel semantics as [launchAgentTask]. A failed search (e.g. not
     * connected) contributes no sources and falls back to a direct answer.
     */
    private fun launchGmailTask(conversationId: Long, text: String, generation: Long) {
        _streamingText.value = ""
        _errorMessage.value = null
        taskRunner.run { update ->
            try {
                val startedAt = System.currentTimeMillis()
                update(TaskStatus.Running(startedAt, "searching"))
                repository.persistUserMessage(conversationId, text)

                val toolResult = toolOrchestrator.execute(
                    TOOL_GMAIL,
                    buildJsonObject {
                        put("query", JsonPrimitive(gmailQuery(text)))
                        put("max_results", JsonPrimitive("10"))
                    },
                    grantedScopes = gmailScopesProvider()
                )
                if (toolResult is ToolResult.Blocked) {
                    _errorMessage.value = toolResult.reason
                    update(TaskStatus.Failed("blocked"))
                    return@run
                }

                val sources = (toolResult as? ToolResult.Success)?.sources.orEmpty()
                val context = if (sources.isEmpty()) null else buildContextPrompt(sources)
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
                if (generation == taskGeneration) _agentBusy.value = false
            }
        }
    }

    /** Extracts a Gmail search term from a "check/read my email" command; empty = list recent. */
    private fun gmailQuery(text: String): String =
        text.trim()
            .replace(Regex("(?i)^\\s*(check|read|search|show|list)\\s+(my\\s+)?(email|emails|inbox|gmail|mail|messages)\\s*"), "")
            .replace(Regex("^\\s*(تفقد|اقرأ|ابحث|اعرض|أظهر|افحص|استعرض)\\s+(بريدي|ايميلي|إيميلي|رسائلي|رسائل|البريد|الايميل|الإيميل)\\s*"), "")
            .replace(Regex("(?i)^\\s*(for|about|regarding)\\s+"), "")
            .replace(Regex("^\\s*(عن|بخصوص|حول)\\s+"), "")
            .trim()

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

    /** Saves a remembered fact to Neon memory and returns a user-facing acknowledgment. */
    private suspend fun rememberFact(fact: String): String {
        val neon = neonRepository ?: return REMEMBER_UNAVAILABLE
        return if (neon.saveMemory("fact", fact).isSuccess) REMEMBER_SAVED else REMEMBER_FAILED
    }

    fun retryLast() {
        val convId = _currentConversationId.value ?: return
        if (_isLoading.value || taskStatus.value is TaskStatus.Running) return
        viewModelScope.launch {
            // Enforce the same safety policy: a blocked session cannot retry.
            if (behaviorGuard.isUserBlocked(SESSION_ID)) {
                repository.persistAssistantMessage(convId, BLOCKED_MESSAGE)
                return@launch
            }
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
            _searchResults.value = withContext(Dispatchers.IO) { knowledgeTool.search(query, DEFAULT_TOP_K) }
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
            _webResults.value = withContext(Dispatchers.IO) { webSearchTool.search(query, DEFAULT_COUNT) }
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
        private val loadManager: CognitiveLoadManager,
        private val cultureContext: CultureContext,
        private val neonRepository: NeonRepository? = null,
        private val gmailScopesProvider: suspend () -> Set<String> = { emptySet() },
        private val approvalGate: ApprovalGate = ApprovalGate()
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return ChatViewModel(
                repository, preferences, knowledgeTool, webSearchTool, toolOrchestrator,
                emotionAnalyzer, loadManager, cultureContext, neonRepository, gmailScopesProvider,
                approvalGate
            ) as T
        }
    }

    companion object {
        private const val DEFAULT_TOP_K = 8
        private const val DEFAULT_COUNT = 10
        private const val TOOL_WEB_SEARCH = "web_search"
        private const val TOOL_SECOND_BRAIN = "second_brain_search"
        private const val TOOL_GMAIL = "gmail_search"
        // Single-user app: a fixed session id scopes the behavior guard to this install.
        private const val SESSION_ID = "default"
        private const val BLOCKED_MESSAGE =
            "تم تجاوز الحد المسموح من المحاولات المخالفة. تم إيقاف الردود مؤقتًا، حاول لاحقًا."
        private const val EMPATHETIC_NOTE =
            "المستخدم يبدو متضايقًا، كن متعاطفًا ولطيفًا في ردك."
        private const val REMEMBER_SAVED = "تم حفظها في ذاكرتي ✓"
        private const val REMEMBER_FAILED = "تعذّر حفظها في الذاكرة، حاول لاحقًا."
        private const val REMEMBER_UNAVAILABLE =
            "قاعدة البيانات غير مربوطة بعد — اربطها من الإعدادات أولًا."
    }
}
