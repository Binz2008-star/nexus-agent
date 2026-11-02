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
import com.nexus.agent.data.remote.HermesApprovalRequest
import com.nexus.agent.data.remote.HermesHistoryMessage
import com.nexus.agent.data.remote.HermesRunRequest
import com.nexus.agent.data.repository.HermesConnectionState
import com.nexus.agent.data.repository.HermesError
import com.nexus.agent.data.repository.HermesRepository
import com.nexus.agent.domain.hermes.AiEngine
import com.nexus.agent.domain.hermes.HermesAuditLog
import com.nexus.agent.domain.hermes.HermesRunEvent
import com.nexus.agent.domain.cognitive.CognitiveLoadDecision
import com.nexus.agent.domain.cognitive.CognitiveLoadManager
import com.nexus.agent.domain.emotion.EmotionAnalyzer
import com.nexus.agent.domain.model.ChatMessage
import com.nexus.agent.domain.culture.CultureContext
import com.nexus.agent.domain.commands.Command
import com.nexus.agent.domain.commands.CommandDetector
import com.nexus.agent.domain.commands.EmailIntent
import com.nexus.agent.domain.commands.EmailIntentRouter
import com.nexus.agent.domain.commands.EmailSendStatus
import com.nexus.agent.domain.commands.EmailSendStatusResolver
import com.nexus.agent.domain.commands.EmailSendResponsePolicy
import com.nexus.agent.domain.commands.GmailQueryParser
import com.nexus.agent.domain.commands.EmailTaskMessages
import com.nexus.agent.domain.commands.PendingEmailDraft
import com.nexus.agent.domain.commands.SendIntent
import com.nexus.agent.domain.commands.SendIntentClassifier
import com.nexus.agent.domain.connectors.GmailConnector
import com.nexus.agent.domain.commands.GreetingDetector
import com.nexus.agent.domain.memory.RememberCommandDetector
import com.nexus.agent.domain.memory.ConversationResumeGuard
import com.nexus.agent.domain.memory.ConversationResumePolicy
import com.nexus.agent.domain.neon.MemoryRecall
import com.nexus.agent.domain.orchestration.ToolLoop
import com.nexus.agent.domain.orchestration.ToolProposal
import com.nexus.agent.domain.orchestration.ActionEvidence
import com.nexus.agent.domain.orchestration.AgentTaskContract
import com.nexus.agent.domain.persona.EmotionPoetry
import com.nexus.agent.domain.persona.SoftPersonaPolicy
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
import kotlinx.coroutines.CancellationException
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private val approvalGate: ApprovalGate = ApprovalGate(),
    private val hermesRepository: HermesRepository? = null,
    private val hermesAudit: HermesAuditLog? = null
) : ViewModel() {

    val conversations: StateFlow<List<ConversationEntity>> = repository.observeConversations()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // User actions take precedence over a slow Room/DataStore cold-start restore.
    private val resumeGuard = ConversationResumeGuard()
    private val selectionWriteMutex = Mutex()
    private val initialRestoreComplete = CompletableDeferred<Unit>()

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

    // ---- AI engine selection (DeepSeek default; Hermes = autonomous remote agent, Beta) ----

    private val _selectedEngine = MutableStateFlow(AiEngine.DEEPSEEK)
    val selectedEngine: StateFlow<AiEngine> = _selectedEngine.asStateFlow()

    /** Backend connection state (drives the "disabled until verified" gate in the UI). */
    val hermesConnectionState: StateFlow<HermesConnectionState> =
        hermesRepository?.connectionState
            ?: MutableStateFlow(HermesConnectionState.Unconfigured).asStateFlow()

    private val _hermesApproval = MutableStateFlow<HermesRunEvent.ApprovalRequested?>(null)
    val hermesApproval: StateFlow<HermesRunEvent.ApprovalRequested?> = _hermesApproval.asStateFlow()

    @Volatile
    private var hermesApprovalDeferred: CompletableDeferred<Boolean>? = null

    @Volatile
    private var hermesRunId: String? = null

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

    // Held across turns so NEXUS does not re-ask recipient/subject/body the user already gave.
    private val _pendingEmailDraft = MutableStateFlow<PendingEmailDraft?>(null)
    val pendingEmailDraft: StateFlow<PendingEmailDraft?> = _pendingEmailDraft.asStateFlow()

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

    // ---- Hermes remote approval (bridged to the same approval-card UI) ----

    /** User approved the pending Hermes run approval — the gated tool runs once. */
    fun approveHermesApproval() {
        hermesApprovalDeferred?.complete(true)
    }

    /** User rejected the pending Hermes run approval — the gated tool is blocked. */
    fun rejectHermesApproval() {
        hermesApprovalDeferred?.complete(false)
    }

    /** Suspends the Hermes run until the user approves/rejects the remote approval request. */
    private suspend fun awaitHermesApproval(event: HermesRunEvent.ApprovalRequested): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        hermesApprovalDeferred = deferred
        _hermesApproval.value = event
        return try {
            deferred.await()
        } finally {
            if (hermesApprovalDeferred === deferred) hermesApprovalDeferred = null
            _hermesApproval.value = null
        }
    }

    /** Clears any in-flight approval (rejection) so a stale card/dangling deferred can't linger. */
    private fun clearPendingApproval() {
        _pendingApproval.value = null
        val deferred = pendingDeferred
        pendingDeferred = null
        deferred?.complete(null)
    }

    init {
        // Snapshot before the first suspended preference read; user clicks during initialization win.
        val startupSnapshot = resumeGuard.snapshot()
        // A new send awaits local restore so the first message is not placed in a stray chat.
        viewModelScope.launch {
            try {
                _selectedModel.value = preferences.lastModel.first()
                _selectedEngine.value = AiEngine.fromId(preferences.lastEngine.first())
                restoreLastConversation(startupSnapshot)
            } finally {
                initialRestoreComplete.complete(Unit)
            }
        }
    }

    /**
     * Restore only an existing conversation. A user interaction during slow Room/DataStore
     * reads invalidates the snapshot, so it cannot be overwritten by stale startup state.
     */
    private suspend fun restoreLastConversation(snapshot: Long) {
        val lastId = preferences.lastConversationId.first()
        val persistedExists = lastId > 0 && repository.getConversation(lastId) != null
        val mostRecentId = if (persistedExists) null else repository.getMostRecentConversation()?.id
        val restored = ConversationResumePolicy.resolve(lastId, persistedExists, mostRecentId)
        selectionWriteMutex.withLock {
            if (!resumeGuard.canApplyRestore(snapshot)) return@withLock
            _currentConversationId.value = restored
            preferences.setLastConversationId(restored ?: -1L)
        }
    }

    /** Keep stale asynchronous writes from overwriting a newer chosen conversation. */
    private suspend fun persistSelectionIfCurrent(id: Long?) {
        selectionWriteMutex.withLock {
            if (_currentConversationId.value == id) {
                preferences.setLastConversationId(id ?: -1L)
            }
        }
    }

    /** Persist newly created chats even on safety, greeting, and memory command paths. */
    private suspend fun activateConversation(id: Long) {
        resumeGuard.userChangedSelection()
        _currentConversationId.value = id
        persistSelectionIfCurrent(id)
    }

    private suspend fun requireActiveConversation(): Long {
        initialRestoreComplete.await()
        return _currentConversationId.value ?: repository.createConversation().also {
            activateConversation(it)
        }
    }

    override fun onCleared() {
        clearPendingApproval()
        hermesApprovalDeferred?.complete(false)
        hermesApprovalDeferred = null
        taskRunner.close()
        super.onCleared()
    }

    fun setModel(model: String) {
        _selectedModel.value = model
        viewModelScope.launch { preferences.setLastModel(model) }
    }

    fun setEngine(engine: AiEngine) {
        _selectedEngine.value = engine
        viewModelScope.launch { preferences.setLastEngine(engine.id) }
    }

    /** Re-verifies the Hermes backend (capabilities + health); updates [hermesConnectionState]. */
    fun verifyHermes() {
        val repo = hermesRepository ?: return
        viewModelScope.launch { repo.verifyConnection() }
    }

    fun selectConversation(id: Long) {
        resumeGuard.userChangedSelection()
        _currentConversationId.value = id
        _streamingText.value = ""
        _errorMessage.value = null
        lastContext = null
        viewModelScope.launch { persistSelectionIfCurrent(id) }
    }

    fun createNewConversation() {
        // Invalidate an in-flight startup restore immediately, not after the Room insert.
        resumeGuard.userChangedSelection()
        viewModelScope.launch {
            val id = repository.createConversation()
            activateConversation(id)
            _streamingText.value = ""
            _errorMessage.value = null
            lastContext = null
        }
    }

    /**
     * Deletes a conversation (and its messages). If it was the active one, switches to the most
     * recently updated remaining conversation, or a true empty state when none remain.
     */
    fun deleteConversation(id: Long) {
        resumeGuard.userChangedSelection()
        viewModelScope.launch {
            repository.deleteConversation(id)
            if (_currentConversationId.value == id) {
                val next = repository.getMostRecentConversation()?.id
                resumeGuard.userChangedSelection()
                _currentConversationId.value = next
                persistSelectionIfCurrent(next)
                _streamingText.value = ""
                _errorMessage.value = null
                lastContext = null
            }
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
                        val convId = requireActiveConversation()
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
                        val convId = requireActiveConversation()
                        repository.persistUserMessage(convId, text.trim())
                        repository.persistAssistantMessage(convId, decision.reply)
                        return@launch
                    }
                    SafetyPolicy.Decision.Allow -> Unit
                }

                // 2b) "remember …" instruction → persist the fact to Neon memory + acknowledge.
                RememberCommandDetector.detect(text.trim())?.let { remembered ->
                    val convId = requireActiveConversation()
                    repository.persistUserMessage(convId, text.trim())
                    repository.persistAssistantMessage(convId, rememberFact(remembered.fact))
                    return@launch
                }

                // 2c) Mashaaer Good Mode (الوضع الحنون) activation/deactivation — local, persisted,
                // before any agent/tool routing. Tools/permissions/approvals are unaffected.
                when (SoftPersonaPolicy.detectCommand(text.trim())) {
                    SoftPersonaPolicy.Command.Enable -> {
                        val convId = requireActiveConversation()
                        repository.persistUserMessage(convId, text.trim())
                        preferences.setSoftModeEnabled(true)
                        repository.persistAssistantMessage(convId, SOFT_MODE_ENABLED_REPLY)
                        return@launch
                    }
                    SoftPersonaPolicy.Command.Disable -> {
                        val convId = requireActiveConversation()
                        repository.persistUserMessage(convId, text.trim())
                        preferences.setSoftModeEnabled(false)
                        repository.persistAssistantMessage(convId, SOFT_MODE_DISABLED_REPLY)
                        return@launch
                    }
                    null -> Unit
                }

                // 3) Greeting/small talk → direct concise answer; commands → dedicated flow;
                //    otherwise the agentic (grounded) flow.
                val convId = requireActiveConversation()
                // Hermes is an autonomous engine: after the local safety + memory gates, hand the
                // whole message to the remote agent (no DeepSeek-specific command shortcuts).
                if (_selectedEngine.value == AiEngine.HERMES) {
                    launchHermesTask(convId, text, generation)
                    handedOffToTask = true
                    return@launch
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
                        when (EmailIntentRouter.classify(text)) {
                            EmailIntent.STATUS -> {
                                // Report the actual NEXUS OAuth status, not generic Gmail tips
                                // or a fabricated claim of inbox access.
                                repository.persistUserMessage(convId, text.trim())
                                val connected = GmailConnector.GMAIL_READONLY_SCOPE in gmailScopesProvider()
                                repository.persistAssistantMessage(
                                    convId, EmailTaskMessages.connectionStatus(connected)
                                )
                            }
                            EmailIntent.READ -> {
                                launchGmailTask(convId, text, generation)
                                handedOffToTask = true
                            }
                            EmailIntent.AGENT -> {
                                // Writing, sending, drafting and generic questions must go through
                                // the tool-aware agent + existing one-shot approval gate.
                                launchAgentTask(convId, text, generation)
                                handedOffToTask = true
                            }
                        }
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
        val runId = hermesRunId
        taskRunner.cancel()
        clearPendingApproval()
        hermesApprovalDeferred?.complete(false)
        hermesApprovalDeferred = null
        _hermesApproval.value = null
        _streamingText.value = ""
        _agentBusy.value = false
        hermesRunId = null
        // Best-effort stop of any in-flight remote run, on a fresh (non-cancelled) coroutine.
        val repo = hermesRepository
        if (runId != null && repo != null) {
            viewModelScope.launch {
                try {
                    repo.stopRun(runId)
                } catch (_: Exception) {
                    // best-effort — the server also settles the run if the client disconnects.
                }
            }
        }
    }

    private fun launchAgentTask(conversationId: Long, text: String, generation: Long) {
        // The agentic path now uses the model-driven tool loop (with a scripted fallback).
        launchModelDrivenTask(conversationId, text, generation)
    }

    private fun launchModelDrivenTask(conversationId: Long, text: String, generation: Long) {
        _streamingText.value = ""
        _errorMessage.value = null
        // Trusted (model-independent) email intent: a real "send" must be backed by an executed
        // Gmail send before the model may answer, and the visible confirmation is bound to the
        // tool's evidence — never to a model's "sent" narrative.
        val sendIntent = SendIntentClassifier.classify(text)
        taskRunner.run { update ->
            try {
                val startedAt = System.currentTimeMillis()
                update(TaskStatus.Running(startedAt, "thinking"))
                repository.persistUserMessage(conversationId, text)

                val history = repository.conversationHistory(conversationId)
                val memoryContext = recallMemoryContext(text)
                val fullHistory = buildList {
                    if (!memoryContext.isNullOrBlank()) {
                        add(ChatMessage(ChatMessage.Role.SYSTEM, memoryContext))
                    }
                    addAll(history)
                }
                val schemas = toolOrchestrator.functionSchemas()
                val taskContract = if (sendIntent == SendIntent.SEND) {
                    AgentTaskContract(
                        actionRequired = true,
                        maxFailedActions = 1,
                        maxActions = 1,
                        requiredToolName = EmailSendStatusResolver.GMAIL_SEND_TOOL
                    )
                } else AgentTaskContract()
                val outcome = toolLoop.run(
                    fullHistory, _selectedModel.value, schemas,
                    propose = { h, m, s ->
                        repository.completeWithTools(h, m, s).map { it.toProposal() }.also { result ->
                            result.getOrNull()?.let { proposal ->
                                if (proposal is ToolProposal.Calls) captureDraftFrom(proposal)
                            }
                        }
                    },
                    grantedScopes = gmailScopesProvider(),
                    taskContract = taskContract
                )
                when (outcome) {
                    is ToolLoop.Outcome.Answered -> {
                        if (sendIntent == SendIntent.SEND) {
                            val status = EmailSendResponsePolicy.status(outcome.evidence)
                            settleDraft(status)
                            repository.persistAssistantMessage(
                                conversationId,
                                EmailSendResponsePolicy.response(outcome.evidence, outcome.content)
                            )
                            if (status == EmailSendStatus.GMAIL_ACCEPTED) {
                                update(TaskStatus.Completed(outcome.sources))
                            } else {
                                update(TaskStatus.Failed("gmail_acceptance_not_verified"))
                            }
                        } else {
                            repository.persistAssistantMessage(conversationId, outcome.content)
                            update(TaskStatus.Completed(outcome.sources))
                        }
                    }
                    is ToolLoop.Outcome.Failed -> {
                        if (sendIntent == SendIntent.SEND) {
                            // Critical: never route a failed SEND through generic model grounding,
                            // retryCompletionStream, or a second Gmail POST.
                            val status = EmailSendResponsePolicy.status(outcome.evidence)
                            settleDraft(status)
                            repository.persistAssistantMessage(
                                conversationId, EmailSendResponsePolicy.response(outcome.evidence)
                            )
                            if (status == EmailSendStatus.GMAIL_ACCEPTED) {
                                update(TaskStatus.Completed(emptyList()))
                            } else {
                                update(TaskStatus.Failed("gmail_acceptance_not_verified"))
                            }
                        } else {
                            runScriptedGrounding(conversationId, text, update, startedAt)
                        }
                    }
                }
            } finally {
                if (generation == taskGeneration) _agentBusy.value = false
            }
        }
    }

    /** Captures the recipient/subject/body a model proposed to send, as a held draft. */
    private fun captureDraftFrom(proposal: ToolProposal.Calls) {
        proposal.calls
            .firstOrNull { it.name == EmailSendStatusResolver.GMAIL_SEND_TOOL }
            ?.let { call ->
                _pendingEmailDraft.value = PendingEmailDraft(
                    recipient = call.arguments["to"],
                    subject = call.arguments["subject"],
                    body = call.arguments["body"],
                    status = EmailSendStatus.AWAITING_APPROVAL
                )
            }
    }

    /** Updates the held draft's status from the tool evidence (accept/fail), preserving fields. */
    private fun settleDraft(status: EmailSendStatus) {
        val current = _pendingEmailDraft.value ?: return
        _pendingEmailDraft.value = current.copy(status = status)
    }

    /**
     * Runs a message through the Hermes agent backend: create a run, stream lifecycle/tool/delta
     * events, bridge remote approvals to the local approval card, and persist the final answer.
     * The remote agent runs its own tool loop server-side; NEXUS's ToolLoop is not invoked here.
     */
    private fun launchHermesTask(conversationId: Long, text: String, generation: Long) {
        val repo = hermesRepository
        if (repo == null) {
            _errorMessage.value = "hermes_unavailable"
            _agentBusy.value = false
            return
        }
        _streamingText.value = ""
        _errorMessage.value = null
        taskRunner.run { update ->
            var runId: String? = null
            try {
                update(TaskStatus.Running(System.currentTimeMillis(), "connecting"))
                // Capture PRIOR turns before persisting the current one, so `input` carries only
                // the new message and `conversation_history` carries only prior context (no
                // double-send of the current turn). Only the user/assistant transcript crosses to
                // Hermes — never memory recall, Second Brain, or Gmail content (Phase 4/5 boundary).
                val history = repository.conversationHistory(conversationId)
                    .filter { it.role == ChatMessage.Role.USER || it.role == ChatMessage.Role.ASSISTANT }
                    .map { HermesHistoryMessage(it.role.name.lowercase(), it.text) }
                repository.persistUserMessage(conversationId, text)
                val request = HermesRunRequest(
                    input = text.trim(),
                    sessionId = "nexus-android-$conversationId",
                    conversationHistory = history
                )

                val created = repo.createRun(request).getOrElse { e ->
                    val code = HermesError.codeOf(e)
                    _errorMessage.value = code
                    update(TaskStatus.Failed(code))
                    return@run
                }
                runId = created.runId
                if (runId.isBlank()) {
                    _errorMessage.value = "hermes_no_run_id"
                    update(TaskStatus.Failed("hermes_no_run_id"))
                    return@run
                }
                hermesRunId = runId
                hermesAudit?.append("create_run", runId)
                update(TaskStatus.Running(System.currentTimeMillis(), "running"))

                val answer = StringBuilder()
                var terminal: HermesRunEvent? = null
                val seenApprovals = mutableSetOf<Pair<String, Int>>()
                val result = repo.streamRunEvents(runId) { event ->
                    when (event) {
                        is HermesRunEvent.Delta -> {
                            answer.append(event.text)
                            _streamingText.value = answer.toString()
                        }
                        is HermesRunEvent.ToolStarted -> {
                            update(TaskStatus.Running(System.currentTimeMillis(), event.tool))
                            hermesAudit?.append("tool_started", runId, mapOf("tool" to event.tool))
                        }
                        is HermesRunEvent.ToolCompleted ->
                            hermesAudit?.append(
                                "tool_completed", runId,
                                mapOf("tool" to event.tool, "error_code" to if (event.error) "error" else "ok")
                            )
                        is HermesRunEvent.ApprovalRequested -> {
                            val key = (event.requestId ?: "") to event.executionGeneration
                            // Dedupe: a re-emitted approval (after reconnect) must not re-prompt.
                            if (seenApprovals.add(key)) {
                                val approved = awaitHermesApproval(event)
                                val requestId = event.requestId
                                val choice = if (approved) HermesApprovalRequest.CHOICE_ONCE else HermesApprovalRequest.CHOICE_DENY
                                if (requestId.isNullOrBlank()) {
                                    hermesAudit?.append("approval_error", runId, mapOf("reason" to "missing_request_id"))
                                    repo.stopRun(runId)
                                } else {
                                    val r = repo.approveRun(
                                        runId,
                                        HermesApprovalRequest(requestId, event.executionGeneration, choice)
                                    )
                                    hermesAudit?.append(
                                        if (r.isSuccess) (if (approved) "approval_approved" else "approval_denied") else "approval_error",
                                        runId, mapOf("tool" to (event.tool ?: ""))
                                    )
                                    if (r.isFailure) repo.stopRun(runId)
                                }
                            }
                        }
                        is HermesRunEvent.Completed -> {
                            answer.setLength(0)
                            answer.append(event.output)
                            _streamingText.value = event.output
                            terminal = event
                        }
                        is HermesRunEvent.Failed -> terminal = event
                        is HermesRunEvent.Cancelled -> terminal = event
                        is HermesRunEvent.Interrupted -> terminal = event
                        is HermesRunEvent.Interim -> Unit
                        is HermesRunEvent.Unknown -> Unit
                    }
                }

                when {
                    result.isSuccess -> {
                        // A remotely-failed/cancelled/interrupted run is NOT success — only persist
                        // + complete on a real Completed terminal event.
                        when (val t = terminal) {
                            is HermesRunEvent.Completed -> {
                                val final = answer.toString()
                                if (final.isNotBlank()) repository.persistAssistantMessage(conversationId, final)
                                _streamingText.value = ""
                                update(TaskStatus.Completed(emptyList()))
                            }
                            is HermesRunEvent.Failed -> failHermesRun("hermes_run_failed", update)
                            is HermesRunEvent.Cancelled -> failHermesRun("hermes_run_cancelled", update)
                            is HermesRunEvent.Interrupted -> failHermesRun("hermes_run_interrupted", update)
                            else -> {
                                val final = answer.toString()
                                if (final.isNotBlank()) repository.persistAssistantMessage(conversationId, final)
                                _streamingText.value = ""
                                update(TaskStatus.Completed(emptyList()))
                            }
                        }
                    }
                    result.isFailure -> {
                        val e = result.exceptionOrNull()
                        if (e is CancellationException) throw e
                        // Do NOT stop the run on plain connectivity exhaustion — the remote run may
                        // still be executing healthily server-side. Explicit cancel stops it via cancelTask.
                        val code = HermesError.codeOf(e ?: Exception("unknown"))
                        _errorMessage.value = code
                        update(TaskStatus.Failed(code))
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } finally {
                if (generation == taskGeneration) {
                    _agentBusy.value = false
                    hermesApprovalDeferred?.complete(false)
                    hermesApprovalDeferred = null
                    _hermesApproval.value = null
                }
                if (hermesRunId == runId) hermesRunId = null
            }
        }
    }

    /** Reports a terminal (failed/cancelled/interrupted) Hermes run as a failure, not success. */
    private fun failHermesRun(code: String, update: (TaskStatus) -> Unit) {
        _streamingText.value = ""
        _errorMessage.value = code
        update(TaskStatus.Failed(code))
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
        // (a missing Neon config simply contributes no sources). Personal memory recall is
        // injected first, fenced as untrusted data.
        val sources = (webResult as? ToolResult.Success)?.sources.orEmpty() +
            (brainResult as? ToolResult.Success)?.sources.orEmpty()
        val memoryContext = recallMemoryContext(text)
        val context = buildList {
            if (!memoryContext.isNullOrBlank()) add(memoryContext)
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
     * Read-only Gmail search. Never use the generic model fallback when Gmail is disconnected,
     * the API fails, or returns zero results: that fallback produced fabricated Gmail guidance.
     */
    private fun launchGmailTask(conversationId: Long, text: String, generation: Long) {
        _streamingText.value = ""
        _errorMessage.value = null
        taskRunner.run { update ->
            try {
                val startedAt = System.currentTimeMillis()
                update(TaskStatus.Running(startedAt, "searching"))
                repository.persistUserMessage(conversationId, text)

                val scopes = gmailScopesProvider()
                if (GmailConnector.GMAIL_READONLY_SCOPE !in scopes) {
                    repository.persistAssistantMessage(
                        conversationId, EmailTaskMessages.connectionStatus(false)
                    )
                    update(TaskStatus.Completed(emptyList()))
                    return@run
                }

                // The Gmail API accepts q=in:inbox to list recent inbox messages.
                // Never send the bare word "gmail" as the search query.
                val query = GmailQueryParser.fromReadRequest(text)
                val toolResult = toolOrchestrator.execute(
                    TOOL_GMAIL,
                    buildJsonObject {
                        put("query", JsonPrimitive(query))
                        put("max_results", JsonPrimitive("10"))
                    },
                    grantedScopes = scopes
                )
                when (toolResult) {
                    is ToolResult.Blocked -> {
                        repository.persistAssistantMessage(
                            conversationId, EmailTaskMessages.searchFailure(toolResult.reason)
                        )
                        update(TaskStatus.Completed(emptyList()))
                        return@run
                    }
                    is ToolResult.Failure -> {
                        repository.persistAssistantMessage(
                            conversationId, EmailTaskMessages.searchFailure(toolResult.errorCode)
                        )
                        update(TaskStatus.Completed(emptyList()))
                        return@run
                    }
                    is ToolResult.Success -> {
                        if (toolResult.sources.isEmpty()) {
                            repository.persistAssistantMessage(
                                conversationId, EmailTaskMessages.NO_MATCHES
                            )
                            update(TaskStatus.Completed(emptyList()))
                            return@run
                        }
                        val sources = toolResult.sources
                        val context = buildContextPrompt(sources)
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
                }
            } finally {
                if (generation == taskGeneration) _agentBusy.value = false
            }
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

    /** Saves a remembered fact to Neon memory and returns a user-facing acknowledgment. */
    private suspend fun rememberFact(fact: String): String {
        val neon = neonRepository ?: return REMEMBER_UNAVAILABLE
        return if (neon.saveMemory("fact", fact).isSuccess) REMEMBER_SAVED else REMEMBER_FAILED
    }

    /**
     * Recalls the personal memories relevant to [query] and returns a fenced, bounded context
     * string (or null when none are available / Neon is not configured / the DB is unreachable).
     * Retrieved content is treated as untrusted data, never as system instructions.
     */
    private suspend fun recallMemoryContext(query: String): String? {
        val neon = neonRepository ?: return null
        val items = neon.recallMemory(query, MEMORY_RECALL_LIMIT).getOrNull().orEmpty()
        return MemoryRecall.buildMemoryContext(items).takeIf { it.isNotBlank() }
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
        private val approvalGate: ApprovalGate = ApprovalGate(),
        private val hermesRepository: HermesRepository? = null,
        private val hermesAudit: HermesAuditLog? = null
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return ChatViewModel(
                repository, preferences, knowledgeTool, webSearchTool, toolOrchestrator,
                emotionAnalyzer, loadManager, cultureContext, neonRepository, gmailScopesProvider,
                approvalGate, hermesRepository, hermesAudit
            ) as T
        }
    }

    companion object {
        private const val DEFAULT_TOP_K = 8
        private const val DEFAULT_COUNT = 10
        private const val MEMORY_RECALL_LIMIT = 8
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
        private const val SOFT_MODE_ENABLED_REPLY =
            "تم تفعيل الوضع الحنون ✓ سأرد عليك بأسلوب هادئ ودود ومحترم من الآن."
        private const val SOFT_MODE_DISABLED_REPLY =
            "تم تعطيل الوضع الحنون ✓ سأعود إلى الأسلوب الافتراضي."
    }
}
