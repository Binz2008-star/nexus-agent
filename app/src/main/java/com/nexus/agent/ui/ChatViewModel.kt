package com.nexus.agent.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.nexus.agent.data.repository.ChatRepository
import com.nexus.agent.data.repository.SendResult
import com.nexus.agent.domain.model.ChatMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ChatViewModel(
    private val repository: ChatRepository
) : ViewModel() {

    val messages: StateFlow<List<ChatMessage>> = repository.observeMessages()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _selectedModel = MutableStateFlow("deepseek-flash")
    val selectedModel: StateFlow<String> = _selectedModel.asStateFlow()

    fun setModel(model: String) {
        _selectedModel.value = model
    }

    fun sendMessage(text: String) {
        if (_isLoading.value || text.isBlank()) return
        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null
            when (val result = repository.sendMessage(text, _selectedModel.value)) {
                is SendResult.Success -> { /* UI observes Flow */ }
                is SendResult.Failure -> _errorMessage.value = result.errorCode
            }
            _isLoading.value = false
        }
    }

    fun clearError() {
        _errorMessage.value = null
    }

    fun retryLast() {
        if (_isLoading.value) return
        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null
            when (val result = repository.retryCompletion(_selectedModel.value)) {
                is SendResult.Success -> { /* UI observes Flow */ }
                is SendResult.Failure -> _errorMessage.value = result.errorCode
            }
            _isLoading.value = false
        }
    }

    class Factory(private val repository: ChatRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return ChatViewModel(repository) as T
        }
    }
}
