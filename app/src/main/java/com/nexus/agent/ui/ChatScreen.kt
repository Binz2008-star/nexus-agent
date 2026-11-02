package com.nexus.agent.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.material.icons.filled.Mic
import androidx.core.content.ContextCompat
import com.nexus.agent.data.voice.AndroidSpeechRecognizerEngine
import com.nexus.agent.domain.voice.VoiceInputError
import com.nexus.agent.domain.voice.VoiceInputListener
import com.nexus.agent.domain.voice.VoiceInputPolicy
import com.nexus.agent.domain.voice.VoiceInputState
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.nexus.agent.data.work.ConversationIntegrityScheduler
import com.nexus.agent.data.work.ConversationIntegrityWorker
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nexus.agent.analytics.NexusAnalytics
import com.nexus.agent.R
import com.nexus.agent.data.local.NexusDatabase
import com.nexus.agent.data.local.ConversationEntity
import com.nexus.agent.data.network.DeepSeekClient
import com.nexus.agent.data.remote.NeonJdbcClient
import com.nexus.agent.data.remote.NeonHttpClient
import com.nexus.agent.data.remote.WebFetchClient
import com.nexus.agent.data.remote.WebSearchApiClient
import com.nexus.agent.data.repository.ApiKeyReadResult
import com.nexus.agent.data.repository.AppPreferencesRepository
import com.nexus.agent.data.repository.ChatRepository
import com.nexus.agent.data.repository.ConversationMirror
import com.nexus.agent.data.repository.NeonRepository
import com.nexus.agent.data.repository.SecretStorageRepository
import com.nexus.agent.data.repository.EncryptedEmailDraftStore
import com.nexus.agent.data.repository.WebSearchRepository
import com.nexus.agent.domain.cognitive.CognitiveLoadManager
import com.nexus.agent.domain.culture.CultureContext
import com.nexus.agent.domain.culture.CultureContextEngine
import com.nexus.agent.domain.culture.Region
import com.nexus.agent.domain.emotion.EmotionAnalyzer
import com.nexus.agent.domain.model.ChatMessage
import com.nexus.agent.domain.neon.CredentialFileParser
import com.nexus.agent.domain.neon.NeonConnectionParser
import com.nexus.agent.domain.persona.AdaptiveTone
import com.nexus.agent.domain.persona.SoftPersonaPolicy
import com.nexus.agent.domain.persona.Dialect
import com.nexus.agent.domain.persona.Tone
import com.nexus.agent.domain.persona.regionToDialect
import com.nexus.agent.domain.policy.ApprovalGate
import com.nexus.agent.domain.policy.ApprovalRequest
import com.nexus.agent.domain.router.AiModelRouter
import com.nexus.agent.domain.router.ModelClient
import com.nexus.agent.domain.tool.KnowledgeSearchTool
import com.nexus.agent.domain.tool.MemorySaveTool
import com.nexus.agent.domain.tool.TaskStatus
import com.nexus.agent.domain.tool.ToolOrchestrator
import com.nexus.agent.domain.tool.WebSearchAdapter
import com.nexus.agent.domain.tool.WebFetchTool
import com.nexus.agent.data.connectors.GoogleAuthManager
import com.nexus.agent.data.connectors.GoogleScopePolicy
import com.nexus.agent.data.remote.GmailSendApiClient
import com.nexus.agent.data.voice.AndroidTextToSpeechEngine
import com.nexus.agent.domain.voice.VoiceOption
import com.nexus.agent.domain.voice.VoiceSelectionPolicy
import com.nexus.agent.domain.tool.GmailSendTool
import com.nexus.agent.domain.tool.GoogleWorkspaceApiClient
import com.nexus.agent.domain.tool.GoogleDriveSearchTool
import com.nexus.agent.domain.tool.GoogleDriveCreateTool
import com.nexus.agent.domain.tool.GoogleCalendarUpcomingTool
import com.nexus.agent.domain.tool.GoogleTasksListsTool
import com.nexus.agent.domain.tool.GoogleContactsListTool
import com.nexus.agent.data.remote.GmailApiClient
import com.nexus.agent.data.remote.HermesRemoteClientImpl
import com.nexus.agent.data.repository.GmailRepository
import com.nexus.agent.data.repository.HermesConnectionState
import com.nexus.agent.data.repository.HermesRepository
import com.nexus.agent.domain.connectors.GmailConnector
import com.nexus.agent.domain.hermes.AiEngine
import com.nexus.agent.domain.hermes.HermesAuditLog
import com.nexus.agent.domain.hermes.HermesRunEvent
import com.nexus.agent.domain.hermes.HermesSafety
import com.nexus.agent.domain.tool.GmailSearchTool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

/** Upper bound on the credential file size accepted by the import parser (64 KiB). */
private const val MAX_CREDENTIAL_FILE_BYTES = 64 * 1024

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
    val preferredDialect by preferences.preferredDialect.collectAsState(initial = "auto")
    val preferredTone by preferences.preferredTone.collectAsState(initial = "friendly")
    val mashaaerEmotionEnabled by preferences.mashaaerEmotionEnabled.collectAsState(initial = false)
    val softModeEnabled by preferences.softModeEnabled.collectAsState(initial = false)
    val ttsEnabled by preferences.ttsEnabled.collectAsState(initial = false)
    val ttsAutoplay by preferences.ttsAutoplay.collectAsState(initial = false)
    val ttsRate by preferences.ttsSpeechRate.collectAsState(initial = 1.0f)
    val ttsPitch by preferences.ttsPitch.collectAsState(initial = 1.0f)
    val ttsVoiceId by preferences.ttsVoiceId.collectAsState(initial = "")

    // Voice engine (Issue #24): zero-cost Android TTS; voice (sound) is separate from persona.
    val speechEngine = remember { AndroidTextToSpeechEngine(context) }
    var ttsReady by remember { mutableStateOf(false) }
    var ttsSpeaking by remember { mutableStateOf(false) }
    var ttsVoiceError by remember { mutableStateOf<String?>(null) }
    var installedOfflineVoices by remember { mutableStateOf<List<VoiceOption>>(emptyList()) }
    DisposableEffect(Unit) {
        onDispose { speechEngine.shutdown() }
    }
    LaunchedEffect(ttsEnabled, ttsVoiceId) {
        if (ttsEnabled) {
            speechEngine.initialize { ready ->
                ttsReady = ready
                installedOfflineVoices = if (ready) VoiceSelectionPolicy.available(speechEngine.voices())
                    else emptyList()
                ttsVoiceError = when {
                    !ready -> "تعذّر بدء محرك الصوت على الهاتف"
                    installedOfflineVoices.isEmpty() -> "لا تتوفر أصوات محلية. ثبّت صوتاً عربياً من إعدادات تحويل النص إلى كلام."
                    ttsVoiceId.isNotBlank() && !speechEngine.selectVoice(ttsVoiceId) ->
                        "الصوت السابق غير موجود على هذا الهاتف. اختر صوتاً متاحاً."
                    else -> null
                }
                // When no voice was chosen yet, a local Arabic voice is preferred.
                if (ready && ttsVoiceId.isBlank()) {
                    VoiceSelectionPolicy.resolveOffline(installedOfflineVoices, "")?.let {
                        speechEngine.selectVoice(it.id)
                    }
                }
            }
        } else {
            speechEngine.shutdown()
            ttsReady = false
            ttsSpeaking = false
            installedOfflineVoices = emptyList()
            ttsVoiceError = null
        }
    }
    fun speakText(text: String) {
        if (!ttsEnabled || text.isBlank()) return
        if (ttsSpeaking) { speechEngine.stop(); ttsSpeaking = false; return }
        if (ttsVoiceError != null || !ttsReady) return
        if (ttsVoiceId.isNotBlank() && !speechEngine.selectVoice(ttsVoiceId)) {
            ttsVoiceError = "الصوت المحدد لم يعد متاحاً. اختر صوتاً جديداً."
            return
        }
        ttsSpeaking = true
        speechEngine.speak(text, ttsRate, ttsPitch) { ttsSpeaking = false }
    }
    val neonRepository = remember {
        val httpClient = NeonHttpClient(
            baseUrlProvider = { preferences.neonBackendUrl.first().takeIf { it.isNotBlank() } },
            apiKeyProvider = {
                when (val r = secrets.readNeonBackendKey()) {
                    is ApiKeyReadResult.Present -> r.value
                    else -> null
                }
            }
        )
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
            jdbc = NeonJdbcClient(),
            httpClient = httpClient,
            useBackendProvider = { preferences.useNeonBackend.first() }
        )
    }

    // Hermes backend: configurable endpoint (non-secret, in prefs) + encrypted bearer key.
    val hermesRepository = remember {
        HermesRepository(
            HermesRemoteClientImpl(
                endpointProvider = { preferences.hermesEndpoint.first() },
                apiKeyProvider = {
                    when (val r = secrets.readHermesApiKey()) {
                        is ApiKeyReadResult.Present -> r.value
                        else -> null
                    }
                }
            )
        )
    }
    val hermesAudit = remember {
        // Whitelist-based audit log: only event/run_id/tool/status/error_code/reason are written.
        HermesAuditLog(logFile = File(context.filesDir, "hermes_audit.log.jsonl"))
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
            personaProvider = {
                // Read current persisted preferences at request-time (repository is remembered).
                val selectedDialect = preferences.preferredDialect.first()
                val selectedTone = Tone.fromId(preferences.preferredTone.first())
                val dialect = if (selectedDialect == "auto") regionToDialect(cultureContext.region)
                    else Dialect.fromId(selectedDialect)
                val englishLocale = cultureContext.region == Region.EN_GLOBAL ||
                    cultureContext.region == Region.FR_GLOBAL
                val base = if (englishLocale) {
                    AdaptiveTone.buildRewriteInstructionEnglish(dialect, selectedTone)
                } else {
                    AdaptiveTone.buildRewriteInstruction(dialect, selectedTone)
                }
                // Layer the opt-in Good Mode (الوضع الحنون) style on top when enabled.
                val soft = if (preferences.softModeEnabled.first()) {
                    if (englishLocale) SoftPersonaPolicy.buildStyleInstructionEnglish(dialect, selectedTone)
                    else SoftPersonaPolicy.buildStyleInstruction(dialect, selectedTone)
                } else null
                listOfNotNull(base, soft).joinToString("\n\n")
            },
            mashaaerEmotionEnabledProvider = { preferences.mashaaerEmotionEnabled.first() },
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
    val googleApi = remember(googleAuthManager) {
        GoogleWorkspaceApiClient(tokenFor = { scopes ->
            googleAuthManager.accessTokenForScopes(scopes)
        })
    }
    val gmailSendTool = remember(googleAuthManager) {
        GmailSendTool(GmailSendApiClient(tokenProvider = {
            googleAuthManager.accessTokenForScopes(listOf(GoogleScopePolicy.GMAIL_SEND))
        }))
    }
    val driveSearchTool = remember(googleApi) { GoogleDriveSearchTool(googleApi) }
    val driveCreateTool = remember(googleApi) { GoogleDriveCreateTool(googleApi) }
    val calendarTool = remember(googleApi) { GoogleCalendarUpcomingTool(googleApi) }
    val tasksTool = remember(googleApi) { GoogleTasksListsTool(googleApi) }
    val contactsTool = remember(googleApi) { GoogleContactsListTool(googleApi) }

    val memorySaveTool = remember(neonRepository) {
        MemorySaveTool(save = { content -> neonRepository.saveMemory("fact", content) })
    }
    val webFetchTool = remember {
        val client = WebFetchClient()
        WebFetchTool(fetch = { url -> client.fetch(url) })
    }
    val approvalGate = remember { ApprovalGate() }
    val toolOrchestrator = remember(approvalGate, memorySaveTool, googleApi, gmailSendTool) {
        ToolOrchestrator(
            mapOf(
                webSearchAdapter.name to webSearchAdapter,
                knowledgeSearchTool.name to knowledgeSearchTool,
                gmailSearchTool.name to gmailSearchTool,
                memorySaveTool.name to memorySaveTool,
                webFetchTool.name to webFetchTool,
                gmailSendTool.name to gmailSendTool,
                driveSearchTool.name to driveSearchTool,
                driveCreateTool.name to driveCreateTool,
                calendarTool.name to calendarTool,
                tasksTool.name to tasksTool,
                contactsTool.name to contactsTool
            ),
            approvalGate
        )
    }

    val viewModel: ChatViewModel = viewModel(
        factory = ChatViewModel.Factory(
            repository, preferences, knowledgeTool, webSearchTool, toolOrchestrator,
            emotionAnalyzer, loadManager, cultureContext, neonRepository,
            gmailScopesProvider = {
                googleAuthManager.grantedScopes.value
            },
            approvalGate = approvalGate,
            hermesRepository = hermesRepository,
            hermesAudit = hermesAudit,
            emailDraftStore = EncryptedEmailDraftStore(secrets)
        )
    )

    val messages by viewModel.messages.collectAsState()
    // Autoplay is deliberately suspended until replies can be classified as safe to speak.
    // A restored chat, Google content, tool output or secrets must never be read aloud on launch.
    val conversations by viewModel.conversations.collectAsState()
    val currentConversationId by viewModel.currentConversationId.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val streamingText by viewModel.streamingText.collectAsState()
    val taskStatus by viewModel.taskStatus.collectAsState()
    val gmailConnected by gmailConnector.isConnected.collectAsState()
    val grantedGoogleScopes by googleAuthManager.grantedScopes.collectAsState()
    val gmailEmail by gmailConnector.email.collectAsState()
    val gmailAuthError by googleAuthManager.error.collectAsState()
    val gmailNeedsReconnect by googleAuthManager.needsReconnect.collectAsState()
    LaunchedEffect(gmailAuthError) {
        gmailAuthError?.let { message ->
            context.showToast(message)
            googleAuthManager.dismissError()
        }
    }
    val mirrorEnabled by preferences.mirrorEnabled.collectAsState(initial = false)
    val analyticsEnabled by preferences.analyticsEnabled.collectAsState(initial = false)

    // Restore explicit consent only. No automatic capture or app data is sent without it.
    LaunchedEffect(analyticsEnabled) {
        NexusAnalytics.setConsent(analyticsEnabled)
        if (analyticsEnabled) NexusAnalytics.track(NexusAnalytics.Event.APP_OPENED)
    }
    val pendingApproval by viewModel.pendingApproval.collectAsState()
    val selectedEngine by viewModel.selectedEngine.collectAsState()
    val hermesConnectionState by viewModel.hermesConnectionState.collectAsState()
    val hermesApproval by viewModel.hermesApproval.collectAsState()

    var showSettings by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    var keyInput by remember { mutableStateOf("") }
    var systemPromptInput by remember { mutableStateOf("") }
    var knowledgeBaseUrlInput by remember { mutableStateOf("") }
    // Neon fields show only a MASKED value (password hidden). The dirty flags track whether the
    // user actually edited them, so a masked value is never written back as real credentials.
    var neonReadUrlInput by remember { mutableStateOf("") }
    var neonWriteUrlInput by remember { mutableStateOf("") }
    var neonReadDirty by remember { mutableStateOf(false) }
    var neonWriteDirty by remember { mutableStateOf(false) }
    var hermesEndpointInput by remember { mutableStateOf("") }
    var hermesKeyInput by remember { mutableStateOf("") }
    var neonBackendUrlInput by remember { mutableStateOf("") }
    var neonBackendKeyInput by remember { mutableStateOf("") }
    var useNeonBackend by remember { mutableStateOf(false) }
    // Configured-status flags (never expose secret values; only whether a secret is present).
    var deepSeekConfigured by remember { mutableStateOf(false) }
    var neonReadConfigured by remember { mutableStateOf(false) }
    var neonWriteConfigured by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }

    // ---- Voice input (dictation → composer) ----
    // Prefers the on-device recognizer so audio can stay on the phone. Dictation only fills the
    // composer: it never sends automatically and never bypasses an approval gate.
    val voiceInputEngine = remember { AndroidSpeechRecognizerEngine(context) }
    var voiceInputState by remember { mutableStateOf(VoiceInputState.IDLE) }
    var voicePartial by remember { mutableStateOf("") }
    var voiceError by remember { mutableStateOf<String?>(null) }
    DisposableEffect(Unit) {
        onDispose { voiceInputEngine.destroy() }
    }

    fun startDictation() {
        // Stop any spoken reply first so the recognizer does not hear the app's own voice.
        if (ttsSpeaking) {
            speechEngine.stop()
            ttsSpeaking = false
        }
        voicePartial = ""
        voiceInputState = VoiceInputState.LISTENING
        val locale = VoiceInputPolicy.localeForDialect(
            preferredDialect,
            java.util.Locale.getDefault().toLanguageTag()
        )
        voiceInputEngine.start(locale, object : VoiceInputListener {
            override fun onState(state: VoiceInputState) {
                voiceInputState = state
            }

            override fun onPartial(text: String) {
                voicePartial = text
            }

            override fun onFinal(text: String) {
                voicePartial = ""
                input = VoiceInputPolicy.appendTranscript(input, text)
            }

            override fun onError(error: VoiceInputError) {
                voicePartial = ""
                voiceError = error.message
            }
        })
    }

    val voicePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            voiceError = null
            startDictation()
        } else {
            voiceError = context.getString(R.string.voice_permission_denied)
        }
    }

    fun onMicClick() {
        when (voiceInputState) {
            VoiceInputState.LISTENING, VoiceInputState.PROCESSING -> {
                voiceInputEngine.cancel()
                voicePartial = ""
            }
            else -> {
                val granted = ContextCompat.checkSelfPermission(
                    context, Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
                if (granted) {
                    voiceError = null
                    startDictation()
                } else {
                    voicePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            }
        }
    }

    // Import credentials from a user-placed file (nexus-neon-rotation.txt) so they don't need
    // to be retyped. Values pre-fill the fields and are stored encrypted on Save.
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                val content = withContext(Dispatchers.IO) {
                    try {
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            readBounded(input, MAX_CREDENTIAL_FILE_BYTES)
                        }
                    } catch (e: Exception) {
                        null
                    }
                }
                val creds = content?.let { CredentialFileParser.parse(it) }
                if (creds == null || creds.isEmpty) {
                    context.showToast(context.getString(R.string.import_failed))
                } else {
                    creds.deepSeekKey?.let { keyInput = it }
                    creds.readUrl?.let { neonReadUrlInput = it; neonReadDirty = true }
                    creds.writeUrl?.let { neonWriteUrlInput = it; neonWriteDirty = true }
                    context.showToast(context.getString(R.string.import_ok))
                }
            }
        }
    }

    LaunchedEffect(showSettings) {
        if (showSettings) {
            systemPromptInput = withContext(Dispatchers.IO) { preferences.systemPrompt.first() }
            knowledgeBaseUrlInput = withContext(Dispatchers.IO) { preferences.knowledgeBaseUrl.first() }
            hermesEndpointInput = withContext(Dispatchers.IO) { preferences.hermesEndpoint.first() }
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
            neonBackendUrlInput = withContext(Dispatchers.IO) { preferences.neonBackendUrl.first() }
            useNeonBackend = withContext(Dispatchers.IO) { preferences.useNeonBackend.first() }
            // Configured status (presence of encrypted secrets only — never the values).
            deepSeekConfigured = withContext(Dispatchers.IO) {
                secrets.readApiKey() is ApiKeyReadResult.Present
            }
            neonReadConfigured = withContext(Dispatchers.IO) {
                secrets.readSecret(SecretStorageRepository.NEON_READ_URL_PREF) is ApiKeyReadResult.Present
            }
            neonWriteConfigured = withContext(Dispatchers.IO) {
                secrets.readSecret(SecretStorageRepository.NEON_WRITE_URL_PREF) is ApiKeyReadResult.Present
            }
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
            NexusXHeader(
                engineLabel = if (selectedEngine == AiEngine.HERMES) "Hermes" else "DeepSeek",
                conversationCount = conversations.size,
                onNewChat = { viewModel.createNewConversation() },
                onHistory = { showHistory = true },
                onSettings = { showSettings = true }
            )
        },
        containerColor = MaterialTheme.colorScheme.background
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
                if (messages.isEmpty() && streamingText.isEmpty() && !isLoading) {
                    item(key = "nexus-x-welcome") {
                        NexusXWelcome(onSuggestionSelected = { suggestion ->
                            input = suggestion
                        })
                    }
                }
                items(messages, key = { it.id }) { message ->
                    if (message.role == ChatMessage.Role.ASSISTANT && ttsEnabled) {
                        Column {
                            MessageBubble(message)
                            TextButton(onClick = { speakText(message.text) }) {
                                Icon(
                                    if (ttsSpeaking) Icons.Default.Stop else Icons.Default.PlayArrow,
                                    contentDescription = if (ttsSpeaking) "Stop" else "Speak",
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(if (ttsSpeaking) "إيقاف" else "استماع")
                            }
                        }
                    } else {
                        MessageBubble(message)
                    }
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
                sendEnabled = !isLoading && taskStatus !is TaskStatus.Running && input.isNotBlank(),
                onSend = {
                    if (voiceInputState == VoiceInputState.LISTENING ||
                        voiceInputState == VoiceInputState.PROCESSING
                    ) {
                        voiceInputEngine.cancel()
                        voicePartial = ""
                    }
                    viewModel.sendMessageWithAgent(input)
                    NexusAnalytics.track(NexusAnalytics.Event.CHAT_SUBMITTED)
                    input = ""
                },
                voiceState = voiceInputState,
                voicePartial = voicePartial,
                voiceError = voiceError,
                onMicClick = { onMicClick() },
                onDismissVoiceError = { voiceError = null }
            )
        }
    }

    if (showSettings) {
        SettingsDialog(
            keyInput = keyInput,
            deepSeekConfigured = deepSeekConfigured,
            neonReadConfigured = neonReadConfigured,
            neonWriteConfigured = neonWriteConfigured,
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
            gmailNeedsReconnect = gmailNeedsReconnect,
            grantedGoogleScopes = grantedGoogleScopes,
            onAuthorizeGoogle = { requested ->
                googleAuthManager.startAuthorization(requested)
            },
            gmailEmail = gmailEmail,
            onConnectGmail = {
                // Google Play services identifies the registered Android package + signing key.
                googleAuthManager.startAuthorization(gmailConnector.scopes)
            },
            onDisconnectGmail = { scope.launch { gmailConnector.disconnect() } },
            mirrorEnabled = mirrorEnabled,
            analyticsEnabled = analyticsEnabled,
            onAnalyticsEnabledChange = { enabled ->
                NexusAnalytics.setConsent(enabled)
                scope.launch { preferences.setAnalyticsEnabled(enabled) }
            },
            mashaaerEmotionEnabled = mashaaerEmotionEnabled,
            onMashaaerEmotionEnabledChange = { scope.launch { preferences.setMashaaerEmotionEnabled(it) } },
            softModeEnabled = softModeEnabled,
            onSoftModeEnabledChange = { scope.launch { preferences.setSoftModeEnabled(it) } },
            ttsEnabled = ttsEnabled,
            onTtsEnabledChange = { scope.launch { preferences.setTtsEnabled(it) } },
            ttsAutoplay = ttsAutoplay,
            onTtsAutoplayChange = { scope.launch { preferences.setTtsAutoplay(it) } },
            ttsRate = ttsRate,
            onTtsRateChange = { scope.launch { preferences.setTtsSpeechRate(it) } },
            ttsPitch = ttsPitch,
            onTtsPitchChange = { scope.launch { preferences.setTtsPitch(it) } },
            ttsVoices = installedOfflineVoices,
            ttsVoiceId = ttsVoiceId,
            ttsVoiceError = ttsVoiceError,
            ttsReady = ttsReady,
            onTtsVoiceChange = { voice ->
                if (speechEngine.selectVoice(voice.id)) {
                    ttsVoiceError = null
                    scope.launch {
                        preferences.setTtsVoiceId(voice.id)
                        preferences.setTtsEnginePackage(voice.enginePackage.orEmpty())
                    }
                } else {
                    ttsVoiceError = "تعذّر تحديد هذا الصوت. جرّب صوتاً آخر."
                }
            },
            onTtsPreview = { speakText("مرحبًا، هذا صوت NEXUS. أهلاً بك في الوضع الصوتي.") },
            preferredDialect = preferredDialect,
            onPreferredDialectChange = { scope.launch { preferences.setPreferredDialect(it) } },
            preferredTone = preferredTone,
            onPreferredToneChange = { scope.launch { preferences.setPreferredTone(it) } },
            onMirrorEnabledChange = { scope.launch { preferences.setMirrorEnabled(it) } },
            onImport = { importLauncher.launch(arrayOf("*/*")) },
            selectedEngine = selectedEngine,
            hermesReady = hermesConnectionState is HermesConnectionState.Ready,
            onEngineChange = { viewModel.setEngine(it) },
            hermesEndpoint = hermesEndpointInput,
            onHermesEndpointChange = { hermesEndpointInput = it },
            hermesApiKey = hermesKeyInput,
            onHermesApiKeyChange = { hermesKeyInput = it },
            hermesState = hermesConnectionState,
            onVerifyHermes = { viewModel.verifyHermes() },
            useNeonBackend = useNeonBackend,
            onUseNeonBackendChange = { enabled ->
                // Fail closed: the HTTPS backend requires a valid, sanitized HTTPS URL before the
                // flag can be enabled — never a blank/invalid URL with the toggle on.
                if (enabled && !HermesSafety.normalizeEndpoint(neonBackendUrlInput).isSuccess) {
                    context.showToast("Neon backend requires a valid HTTPS URL first")
                    useNeonBackend = false
                } else {
                    useNeonBackend = enabled
                }
            },
            neonBackendUrl = neonBackendUrlInput,
            onNeonBackendUrlChange = { neonBackendUrlInput = it },
            neonBackendKey = neonBackendKeyInput,
            onNeonBackendKeyChange = { neonBackendKeyInput = it },
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
                            // Strip userinfo/path/query before persisting — never store credentials.
                            preferences.setHermesEndpoint(HermesSafety.sanitizeForStorage(hermesEndpointInput))
                        }
                        val hermesKey = hermesKeyInput.trim()
                        if (hermesKey.isNotEmpty()) {
                            withContext(Dispatchers.IO) { secrets.saveHermesApiKey(hermesKey) }
                        }
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
                        withContext(Dispatchers.IO) {
                            // Strip userinfo/path/query — never store credentials in plaintext prefs.
                            preferences.setNeonBackendUrl(HermesSafety.sanitizeForStorage(neonBackendUrlInput))
                            preferences.setUseNeonBackend(useNeonBackend)
                        }
                        val backendKey = neonBackendKeyInput.trim()
                        if (backendKey.isNotEmpty()) {
                            withContext(Dispatchers.IO) { secrets.saveNeonBackendKey(backendKey) }
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

    if (showHistory) {
        HistoryDialog(
            conversations = conversations,
            currentId = currentConversationId,
            onSelect = { id ->
                viewModel.selectConversation(id)
                showHistory = false
            },
            onDelete = { id -> viewModel.deleteConversation(id) },
            onDismiss = { showHistory = false }
        )
    }

    pendingApproval?.let { request ->
        ApprovalCard(
            request = request,
            onApprove = { viewModel.approvePending() },
            onReject = { viewModel.rejectPending() }
        )
    }

    hermesApproval?.let { request ->
        HermesApprovalCard(
            request = request,
            onApprove = { viewModel.approveHermesApproval() },
            onReject = { viewModel.rejectHermesApproval() }
        )
    }
}

/**
 * Reads at most [maxBytes] from [input] and returns the decoded UTF-8 string, or null when the
 * stream is larger than the cap (rejects oversized credential files instead of slurping them).
 */
private fun readBounded(input: InputStream, maxBytes: Int): String? {
    val buffer = ByteArray(maxBytes + 1)
    var total = 0
    while (total < buffer.size) {
        val read = input.read(buffer, total, buffer.size - total)
        if (read <= 0) break
        total += read
    }
    if (total > maxBytes) return null
    return String(buffer, 0, total, Charsets.UTF_8)
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
            Column(modifier = Modifier.padding(horizontal = 13.dp, vertical = 8.dp)) {
                SelectionContainer {
                    Text(
                        text = ChatMarkdown.render(message.text),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isUser)
                            MaterialTheme.colorScheme.onPrimary
                        else
                            MaterialTheme.colorScheme.onSurface
                    )
                }
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
    onSend: () -> Unit,
    voiceState: VoiceInputState = VoiceInputState.IDLE,
    voicePartial: String = "",
    voiceError: String? = null,
    onMicClick: () -> Unit = {},
    onDismissVoiceError: () -> Unit = {}
) {
    val listening = voiceState == VoiceInputState.LISTENING ||
        voiceState == VoiceInputState.PROCESSING
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        shadowElevation = 4.dp
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            if (voiceError != null) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = voiceError,
                        modifier = Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                    TextButton(onClick = onDismissVoiceError) {
                        Text(stringResource(R.string.voice_dismiss))
                    }
                }
            }
            if (listening) {
                Text(
                    text = when {
                        voicePartial.isNotBlank() -> voicePartial
                        voiceState == VoiceInputState.PROCESSING ->
                            stringResource(R.string.voice_processing)
                        else -> stringResource(R.string.voice_listening)
                    },
                    modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("اكتب رسالتك هنا…") },
                    enabled = enabled,
                    maxLines = 5,
                    shape = RoundedCornerShape(22.dp)
                )
                Spacer(Modifier.width(8.dp))
                IconButton(
                    onClick = onMicClick,
                    enabled = enabled,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(
                            if (listening) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.surfaceVariant
                        )
                ) {
                    Icon(
                        imageVector = if (listening) Icons.Default.Stop else Icons.Default.Mic,
                        contentDescription = stringResource(
                            if (listening) R.string.voice_stop else R.string.voice_input
                        ),
                        tint = if (listening) MaterialTheme.colorScheme.onError
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.width(8.dp))
                IconButton(
                    onClick = onSend,
                    enabled = sendEnabled,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(
                            if (sendEnabled) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceVariant
                        )
                ) {
                    Icon(
                        Icons.Default.Send,
                        contentDescription = stringResource(R.string.send),
                        tint = if (sendEnabled) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "الردود والمعلومات الحساسة قد تحتاج إلى مراجعتك.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

@Composable
private fun HistoryDialog(
    conversations: List<ConversationEntity>,
    currentId: Long?,
    onSelect: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var pendingDelete by remember { mutableStateOf<ConversationEntity?>(null) }
    // WorkManager's own Room database preserves the result even when the app process dies.
    val auditFlow = remember(context) {
        WorkManager.getInstance(context.applicationContext).getWorkInfosForUniqueWorkFlow(
            ConversationIntegrityScheduler.UNIQUE_WORK
        )
    }
    val auditInfos by auditFlow.collectAsState(initial = emptyList())
    val snapshot = auditInfos.firstOrNull { "nexus-audit-snapshot" in it.tags }
    val verify = auditInfos.firstOrNull { "nexus-audit-verify" in it.tags }
    val audit = verify ?: snapshot
    val auditStatus = when {
        snapshot?.state == WorkInfo.State.RUNNING -> "جارٍ تسجيل حالة البيانات محلياً"
        snapshot?.state == WorkInfo.State.FAILED -> "تعذّر تسجيل حالة البيانات؛ لم تُمسح المحادثات"
        verify?.state == WorkInfo.State.BLOCKED -> "تم حفظ الخطوة الأولى؛ بانتظار التحقق"
        else -> when (audit?.state) {
        WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> "الفحص في قائمة الانتظار"
        WorkInfo.State.RUNNING -> "جارٍ فحص البيانات المحلية"
        WorkInfo.State.SUCCEEDED -> {
            val chats = audit.outputData.getInt(ConversationIntegrityWorker.KEY_CHAT_COUNT, 0)
            val messages = audit.outputData.getInt(ConversationIntegrityWorker.KEY_MESSAGE_COUNT, 0)
            "تم عدّ $chats محادثة و$messages رسالة دون سجلات يتيمة"
        }
        WorkInfo.State.FAILED -> "تعذر تأكيد سلامة السجل — البيانات لم تُمسح"
        WorkInfo.State.CANCELLED -> "تم إلغاء الفحص"
        null -> "لم يُجرَ فحص حفظ محلي بعد"
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Chat history · سجل المحادثات") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    auditStatus,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
                if (conversations.isEmpty()) {
                    Text("No saved conversations yet.")
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 350.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(conversations, key = { it.id }) { conv ->
                            Row(
                                modifier = Modifier.fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { onSelect(conv.id) }
                                    .padding(horizontal = 8.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        conv.title.ifBlank { "Chat ${conv.id}" },
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = if (conv.id == currentId) FontWeight.Bold else null
                                    )
                                    Text(
                                        formatTimestamp(conv.updatedAt),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                IconButton(onClick = { pendingDelete = conv }) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = "Delete",
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("إغلاق") } },
        dismissButton = {
            TextButton(onClick = { ConversationIntegrityScheduler.enqueueReadOnlyAudit(context) }) {
                Text("فحص حفظ المحادثات")
            }
        }
    )

    pendingDelete?.let { conv ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete conversation?") },
            text = { Text("This removes the chat and its messages. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(conv.id)
                    pendingDelete = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } }
        )
    }
}

private fun formatTimestamp(ts: Long): String =
    java.text.DateFormat.getDateTimeInstance(
        java.text.DateFormat.SHORT, java.text.DateFormat.SHORT
    ).format(java.util.Date(ts))

@Composable
private fun SettingsDialog(
    keyInput: String,
    deepSeekConfigured: Boolean,
    neonReadConfigured: Boolean,
    neonWriteConfigured: Boolean,
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
    gmailNeedsReconnect: Boolean = false,
    grantedGoogleScopes: Set<String>,
    onAuthorizeGoogle: (List<String>) -> Unit,
    gmailEmail: String?,
    onConnectGmail: () -> Unit,
    onDisconnectGmail: () -> Unit,
    mirrorEnabled: Boolean,
    onMirrorEnabledChange: (Boolean) -> Unit,
    analyticsEnabled: Boolean,
    onAnalyticsEnabledChange: (Boolean) -> Unit,
    mashaaerEmotionEnabled: Boolean,
    onMashaaerEmotionEnabledChange: (Boolean) -> Unit,
    softModeEnabled: Boolean = false,
    onSoftModeEnabledChange: (Boolean) -> Unit = {},
    ttsEnabled: Boolean = false,
    onTtsEnabledChange: (Boolean) -> Unit = {},
    ttsAutoplay: Boolean = false,
    onTtsAutoplayChange: (Boolean) -> Unit = {},
    ttsRate: Float = 1.0f,
    onTtsRateChange: (Float) -> Unit = {},
    ttsPitch: Float = 1.0f,
    onTtsPitchChange: (Float) -> Unit = {},
    ttsVoices: List<VoiceOption> = emptyList(),
    ttsVoiceId: String = "",
    ttsVoiceError: String? = null,
    ttsReady: Boolean = false,
    onTtsVoiceChange: (VoiceOption) -> Unit = {},
    onTtsPreview: () -> Unit = {},
    preferredDialect: String,
    onPreferredDialectChange: (String) -> Unit,
    preferredTone: String,
    onPreferredToneChange: (String) -> Unit,
    onImport: () -> Unit,
    selectedEngine: AiEngine,
    hermesReady: Boolean,
    onEngineChange: (AiEngine) -> Unit,
    hermesEndpoint: String,
    onHermesEndpointChange: (String) -> Unit,
    hermesApiKey: String,
    onHermesApiKeyChange: (String) -> Unit,
    hermesState: HermesConnectionState,
    onVerifyHermes: () -> Unit,
    useNeonBackend: Boolean,
    onUseNeonBackendChange: (Boolean) -> Unit,
    neonBackendUrl: String,
    onNeonBackendUrlChange: (String) -> Unit,
    neonBackendKey: String,
    onNeonBackendKeyChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onSave: () -> Unit
) {
    var selectedSection by remember { mutableStateOf(NexusXSettingsSection.SERVICES) }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
                Text(
                    "الإعدادات · Settings",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onSave) { Text(stringResource(R.string.save)) }
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
            ) {
                NexusXSettingsNavigation(
                    selected = selectedSection,
                    onSelect = { selectedSection = it }
                )
                Spacer(Modifier.height(16.dp))
                if (selectedSection == NexusXSettingsSection.SERVICES) {
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
                            text = when {
                                gmailConnected -> gmailEmail?.let { stringResource(R.string.gmail_connected_as, it) }
                                    ?: stringResource(R.string.gmail_connected)
                                gmailNeedsReconnect -> "Reconnect required · أعد الاتصال"
                                else -> stringResource(R.string.gmail_not_connected)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (gmailNeedsReconnect) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (grantedGoogleScopes.isNotEmpty()) {
                        TextButton(onClick = onDisconnectGmail) {
                            Text(stringResource(R.string.disconnect))
                        }
                    } else {
                        Button(onClick = onConnectGmail) {
                            Text(stringResource(R.string.connect))
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text("Google services · خدمات جوجل", style = MaterialTheme.typography.titleSmall)
                Text("Authorize each capability separately. Sending and changes require confirmation.",
                    style = MaterialTheme.typography.bodySmall)
                val services = listOf(
                    "Gmail · قراءة" to listOf(GoogleScopePolicy.GMAIL_READ),
                    "Gmail · إرسال" to listOf(GoogleScopePolicy.GMAIL_SEND),
                    "Drive · قراءة" to listOf(GoogleScopePolicy.DRIVE_READ),
                    "Drive · إنشاء ملفات Docs / Sheets / Slides" to listOf(GoogleScopePolicy.DRIVE_FILE),
                    "Calendar · قراءة" to listOf(GoogleScopePolicy.CALENDAR_READ),
                    "Contacts · قراءة" to listOf(GoogleScopePolicy.CONTACTS_READ),
                    "Tasks · قراءة" to listOf(GoogleScopePolicy.TASKS_READ)
                )
                services.forEach { (name, scopes) ->
                    Row(modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(name, modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall)
                        if (grantedGoogleScopes.containsAll(scopes)) {
                            Text("✓", color = MaterialTheme.colorScheme.primary)
                        } else {
                            TextButton(onClick = { onAuthorizeGoogle(scopes) }) {
                                Text("Connect")
                            }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                }
                if (selectedSection == NexusXSettingsSection.AI) {
                TextButton(onClick = onImport, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.import_from_file))
                }
                OutlinedTextField(
                    value = keyInput,
                    onValueChange = onKeyChange,
                    label = { Text(stringResource(R.string.api_key_title)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )
                Text(
                    if (deepSeekConfigured) "✓ Configured" else "Not configured",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (deepSeekConfigured) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(16.dp))
                }
                if (selectedSection == NexusXSettingsSection.KNOWLEDGE) {
                OutlinedTextField(
                    value = neonReadUrl,
                    onValueChange = onNeonReadUrlChange,
                    label = { Text(stringResource(R.string.neon_read_url)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )
                Text(
                    if (neonReadConfigured) "✓ Configured" else "Not configured",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (neonReadConfigured) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
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
                Text(
                    if (neonWriteConfigured) "✓ Configured" else "Not configured",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (neonWriteConfigured) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(16.dp))
                Text("Neon backend · خادم Neon", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Optional: route Neon through an HTTPS backend instead of a direct connection.",
                    style = MaterialTheme.typography.bodySmall
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Use backend · استخدم الخادم", modifier = Modifier.weight(1f))
                    Switch(checked = useNeonBackend, onCheckedChange = onUseNeonBackendChange)
                }
                OutlinedTextField(
                    value = neonBackendUrl,
                    onValueChange = onNeonBackendUrlChange,
                    label = { Text("Neon backend URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = neonBackendKey,
                    onValueChange = onNeonBackendKeyChange,
                    label = { Text("Neon backend key") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )
                Spacer(Modifier.height(16.dp))
                }
                if (selectedSection == NexusXSettingsSection.ADVANCED) {
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
                Text("Anonymous app analytics · إحصاءات التطبيق", style = MaterialTheme.typography.titleSmall)
                Text(
                    if (NexusAnalytics.isConfigured) {
                        "Optional. Sends only app-open and chat-submitted counts. No messages, prompts, Gmail, memories, email addresses, or session recordings."
                    } else {
                        "Unavailable in this build. Configure POSTHOG_PROJECT_TOKEN and POSTHOG_HOST during build."
                    },
                    style = MaterialTheme.typography.bodySmall
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Share anonymous usage counts · مشاركة إحصاءات مجهولة",
                        modifier = Modifier.weight(1f))
                    Switch(
                        checked = analyticsEnabled && NexusAnalytics.isConfigured,
                        onCheckedChange = onAnalyticsEnabledChange,
                        enabled = NexusAnalytics.isConfigured
                    )
                }
                Spacer(Modifier.height(16.dp))
                }
                if (selectedSection == NexusXSettingsSection.PERSONA) {
                Text("Mashaaer · مشاعر", style = MaterialTheme.typography.titleSmall)
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("تحليل المشاعر الاختياري (محلي، دون حفظ)", modifier = Modifier.weight(1f))
                    Switch(checked = mashaaerEmotionEnabled,
                        onCheckedChange = onMashaaerEmotionEnabledChange)
                }
                Spacer(Modifier.height(8.dp))
                Text("الشخصية والصوت · Persona & Voice", style = MaterialTheme.typography.titleSmall)
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("الوضع الحنون · Soft mode (أسلوب هادئ ودود)", modifier = Modifier.weight(1f))
                    Switch(checked = softModeEnabled, onCheckedChange = onSoftModeEnabledChange)
                }
                Spacer(Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("الصوت · Voice (قراءة الردود بصوت)", modifier = Modifier.weight(1f))
                    Switch(checked = ttsEnabled, onCheckedChange = onTtsEnabledChange)
                }
                if (ttsEnabled) {
                    Text("القراءة التلقائية متوقفة مؤقتاً لحماية الرسائل والمعلومات الحساسة.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    var voiceMenuExpanded by remember { mutableStateOf(false) }
                    Box {
                        TextButton(
                            enabled = ttsReady && ttsVoices.isNotEmpty(),
                            onClick = { voiceMenuExpanded = true }
                        ) {
                            val current = ttsVoices.firstOrNull { it.id == ttsVoiceId }
                            Text("الصوت: " + (current?.let { "${it.name} (${it.locale})" }
                                ?: if (ttsVoices.isEmpty()) "لا توجد أصوات محلية"
                                else "اختر صوتاً عربياً"))
                        }
                        DropdownMenu(
                            expanded = voiceMenuExpanded,
                            onDismissRequest = { voiceMenuExpanded = false }
                        ) {
                            ttsVoices.forEach { voice ->
                                DropdownMenuItem(
                                    text = { Text("${voice.name} · ${voice.locale} · بدون إنترنت") },
                                    onClick = {
                                        voiceMenuExpanded = false
                                        onTtsVoiceChange(voice)
                                    }
                                )
                            }
                        }
                    }
                    if (ttsVoiceError != null) {
                        Text(ttsVoiceError, color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall)
                    }
                    Text("السرعة · Speed (${"%.2f".format(ttsRate)})", style = MaterialTheme.typography.bodySmall)
                    Slider(value = ttsRate, onValueChange = onTtsRateChange, valueRange = 0.85f..1.05f)
                    Text("النبرة · Pitch (${"%.2f".format(ttsPitch)})", style = MaterialTheme.typography.bodySmall)
                    Slider(value = ttsPitch, onValueChange = onTtsPitchChange, valueRange = 0.95f..1.08f)
                    TextButton(onClick = onTtsPreview,
                        enabled = ttsReady && ttsVoiceError == null,
                        modifier = Modifier.fillMaxWidth()) {
                        Text("معاينة الصوت · Preview")
                    }
                }
                Text("اللهجة", style = MaterialTheme.typography.bodyMedium)
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("auto" to "تلقائي", "gulf" to "خليجي",
                        "levant" to "شامي", "egypt" to "مصري").forEach { (id, label) ->
                        TextButton(onClick = { onPreferredDialectChange(id) }) {
                            Text(if (preferredDialect == id) "✓ $label" else label)
                        }
                    }
                }
                Text("النبرة", style = MaterialTheme.typography.bodyMedium)
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("friendly" to "ودّي", "calm" to "هادئ",
                        "professional" to "مهني").forEach { (id, label) ->
                        TextButton(onClick = { onPreferredToneChange(id) }) {
                            Text(if (preferredTone == id) "✓ $label" else label)
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                }
                if (selectedSection == NexusXSettingsSection.KNOWLEDGE) {
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
                Spacer(Modifier.height(16.dp))
                }
                if (selectedSection == NexusXSettingsSection.AI) {
                EngineSelector(selectedEngine, hermesReady, onEngineChange)
                Spacer(Modifier.height(16.dp))
                HermesSettingsSection(
                    endpoint = hermesEndpoint,
                    onEndpointChange = onHermesEndpointChange,
                    apiKey = hermesApiKey,
                    onApiKeyChange = onHermesApiKeyChange,
                    state = hermesState,
                    onVerify = onVerifyHermes
                )
                }
            }
        }
    }
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
private fun EngineSelector(
    selected: AiEngine,
    hermesReady: Boolean,
    onSelect: (AiEngine) -> Unit
) {
    Column {
        Text(stringResource(R.string.ai_engine), style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onSelect(AiEngine.DEEPSEEK) }, enabled = selected != AiEngine.DEEPSEEK) {
                Text(stringResource(R.string.engine_deepseek))
            }
            Button(onClick = { onSelect(AiEngine.HERMES) }, enabled = selected != AiEngine.HERMES && hermesReady) {
                Text(stringResource(R.string.engine_hermes))
            }
        }
    }
}

@Composable
private fun HermesSettingsSection(
    endpoint: String,
    onEndpointChange: (String) -> Unit,
    apiKey: String,
    onApiKeyChange: (String) -> Unit,
    state: HermesConnectionState,
    onVerify: () -> Unit
) {
    Column {
        Text(stringResource(R.string.hermes_section), style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = endpoint,
            onValueChange = onEndpointChange,
            label = { Text(stringResource(R.string.hermes_endpoint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = apiKey,
            onValueChange = onApiKeyChange,
            label = { Text(stringResource(R.string.hermes_api_key)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = hermesStateLabel(state),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(onClick = onVerify) { Text(stringResource(R.string.verify)) }
        }
    }
}

@Composable
private fun hermesStateLabel(state: HermesConnectionState): String = when (state) {
    HermesConnectionState.Ready -> stringResource(R.string.hermes_state_ready)
    HermesConnectionState.Verifying -> stringResource(R.string.hermes_state_verifying)
    HermesConnectionState.Unconfigured -> stringResource(R.string.hermes_state_unconfigured)
    is HermesConnectionState.Error -> stringResource(R.string.hermes_state_error)
}

@Composable
private fun HermesApprovalCard(
    request: HermesRunEvent.ApprovalRequested,
    onApprove: () -> Unit,
    onReject: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onReject,
        title = { Text(stringResource(R.string.hermes_approval_title)) },
        text = {
            Column {
                Text(stringResource(R.string.approval_action, request.tool ?: "?"))
                if (!request.arguments.isNullOrBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        request.arguments,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (!request.prompt.isNullOrBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        request.prompt,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
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
        code == "no_connection" -> "Neon connection not configured"
        code == "not_found" -> "Not found"
        code == "permission" -> "Neon access denied"
        code == "server" -> "Neon server error"
        code.startsWith("http_") -> "Neon server error (HTTP ${code.removePrefix("http_")})"
        code.startsWith("api_") -> "API error ${code.removePrefix("api_")}"
        code == "https_required" -> stringResource(R.string.hermes_https_required)
        code == "endpoint" -> stringResource(R.string.hermes_endpoint_invalid)
        code == "hermes_runs_unsupported" -> stringResource(R.string.hermes_runs_unsupported)
        code == "hermes_unhealthy" -> stringResource(R.string.hermes_unhealthy)
        code == "hermes_unavailable" -> stringResource(R.string.hermes_unavailable)
        code.startsWith("generic:") -> "Request failed (${code.removePrefix("generic:")})"
        else -> stringResource(R.string.error_generic)
    }
}
