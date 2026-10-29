package com.nexus.agent.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.nexus.agent.data.local.ConversationEntity
import com.nexus.agent.data.repository.AppPreferencesRepository
import com.nexus.agent.data.repository.ChatRepository
import com.nexus.agent.data.repository.SendResult
import com.nexus.agent.domain.model.ChatMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ChatViewModel(
    private val repository: ChatRepository,
    private val preferences: AppPreferencesRepository
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

    init {
        // Restore the last-used model across restarts.
        viewModelScope.launch {
            _selectedModel.value = preferences.lastModel.first()
        }
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

    class Factory(
        private val repository: ChatRepository,
        private val preferences: AppPreferencesRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return ChatViewModel(repository, preferences) as T
        }
    }
}
