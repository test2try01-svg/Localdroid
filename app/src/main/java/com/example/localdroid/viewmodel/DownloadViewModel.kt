package com.example.localdroid.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.localdroid.data.DownloadRepository
import com.example.localdroid.data.VideoInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class UiState(
    val url: String = "",
    val isFetching: Boolean = false,
    val info: VideoInfo? = null,
    val selectedQuality: Int = 1,
    val error: String? = null
)

class DownloadViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = DownloadRepository(app)
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    fun onUrlChange(url: String) = _state.update { it.copy(url = url, error = null) }
    fun onQualitySelect(idx: Int) = _state.update { it.copy(selectedQuality = idx) }
    fun clearError() = _state.update { it.copy(error = null) }

    fun fetchInfo() {
        val url = _state.value.url
        if (url.isBlank()) {
            _state.update { it.copy(error = "Please paste a URL first") }
            return
        }
        _state.update { it.copy(isFetching = true, info = null, error = null) }
        viewModelScope.launch {
            try {
                val info = repo.fetchInfo(url)
                _state.update { it.copy(isFetching = false, info = info) }
            } catch (e: Exception) {
                _state.update { it.copy(isFetching = false, error = e.message ?: "Unknown error") }
            }
        }
    }
}
