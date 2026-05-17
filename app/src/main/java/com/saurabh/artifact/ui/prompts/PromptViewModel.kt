package com.saurabh.artifact.ui.prompts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.saurabh.artifact.model.PromptCategory
import com.saurabh.artifact.model.ReflectionPrompt
import com.saurabh.artifact.repository.PromptRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PromptUiState(
    val prompts: List<ReflectionPrompt> = emptyList(),
    val categories: List<PromptCategory> = PromptCategory.entries.filter { it != PromptCategory.AI_GUIDED && it != PromptCategory.GENERAL },
    val selectedCategory: PromptCategory? = null,
    val searchQuery: String = "",
    val isLoading: Boolean = false,
    val randomPrompt: ReflectionPrompt? = null
)

@HiltViewModel
class PromptViewModel @Inject constructor(
    private val repository: PromptRepository
) : ViewModel() {

    private val _selectedCategory = MutableStateFlow<PromptCategory?>(null)
    private val _searchQuery = MutableStateFlow("")
    private val _randomPrompt = MutableStateFlow<ReflectionPrompt?>(null)
    private val _isLoading = MutableStateFlow(false)

    val uiState: StateFlow<PromptUiState> = combine(
        _selectedCategory,
        _searchQuery,
        _randomPrompt,
        _isLoading,
        repository.getAllPrompts()
    ) { category, query, random, loading, allPrompts ->
        val filteredPrompts = allPrompts.filter { prompt ->
            (category == null || prompt.category == category) &&
            (query.isBlank() || prompt.question.contains(query, ignoreCase = true))
        }

        PromptUiState(
            prompts = filteredPrompts,
            selectedCategory = category,
            searchQuery = query,
            randomPrompt = random,
            isLoading = loading
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), PromptUiState())

    init {
        viewModelScope.launch {
            repository.initializeIfEmpty()
        }
    }

    fun selectCategory(category: PromptCategory?) {
        _selectedCategory.value = category
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun toggleFavorite(prompt: ReflectionPrompt) {
        viewModelScope.launch {
            repository.toggleFavorite(prompt.id, !prompt.isFavorite)
        }
    }

    fun generateRandomPrompt() {
        val currentPrompts = uiState.value.prompts
        if (currentPrompts.isNotEmpty()) {
            _randomPrompt.value = currentPrompts.random()
        }
    }

    fun clearRandomPrompt() {
        _randomPrompt.value = null
    }

    fun recordUsage(prompt: ReflectionPrompt) {
        viewModelScope.launch {
            repository.recordUsage(prompt.id)
        }
    }
}
