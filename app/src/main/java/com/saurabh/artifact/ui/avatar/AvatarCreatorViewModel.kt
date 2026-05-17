package com.saurabh.artifact.ui.avatar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.saurabh.artifact.model.AvatarCategory
import com.saurabh.artifact.model.AvatarConfig
import com.saurabh.artifact.model.AvatarExpression
import com.saurabh.artifact.repository.UserProfileManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AvatarUiState(
    val config: AvatarConfig = AvatarConfig(),
    val categories: List<AvatarCategory> = emptyList(),
    val selectedCategoryIndex: Int = 0,
    val isSaving: Boolean = false,
    val isSaved: Boolean = false,
    val undoStack: List<AvatarConfig> = emptyList()
)

@HiltViewModel
class AvatarCreatorViewModel @Inject constructor(
    private val userProfileManager: UserProfileManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(AvatarUiState())
    val uiState: StateFlow<AvatarUiState> = _uiState.asStateFlow()

    init {
        val categories = listOf(
            AvatarCategory("mood", "Mood"),
            AvatarCategory("hair", "Hair"),
            AvatarCategory("head", "Face"),
            AvatarCategory("outfit", "Outfit"),
            AvatarCategory("aura", "Aura")
        )
        _uiState.update { it.copy(categories = categories) }

        viewModelScope.launch {
            userProfileManager.activeAvatarConfig.collect { config ->
                if (config != null) {
                    _uiState.update { it.copy(config = config) }
                }
            }
        }
    }

    fun onCategorySelected(index: Int) {
        _uiState.update { it.copy(selectedCategoryIndex = index) }
    }

    fun updateConfig(transform: (AvatarConfig) -> AvatarConfig) {
        val current = _uiState.value.config
        val newStack = (_uiState.value.undoStack + current).takeLast(20)
        _uiState.update { 
            it.copy(
                config = transform(current),
                undoStack = newStack,
                isSaved = false
            ) 
        }
    }

    fun undo() {
        val stack = _uiState.value.undoStack
        if (stack.isNotEmpty()) {
            val previous = stack.last()
            _uiState.update { 
                it.copy(
                    config = previous,
                    undoStack = stack.dropLast(1)
                ) 
            }
        }
    }

    fun randomize() {
        updateConfig { current ->
            current.copy(
                expression = AvatarExpression.entries.random(),
                hairId = "hair_${(1..10).random().toString().padStart(2, '0')}",
                skinTone = listOf(0xFFF5E0D3, 0xFFE0AC69, 0xFF8D5524, 0xFFC68642).random(),
                outfitId = listOf("outfit_hoodie", "outfit_turtleneck", "outfit_jacket").random(),
                outfitColor = listOf(0xFF4A5568, 0xFF6B7280, 0xFF7C3AED, 0xFF059669).random(),
                ambientGlow = listOf(0xFFE0C3FC, 0xFFBDE0FE, 0xFFFFC8DD, 0xFFCAFFBF).random()
            )
        }
    }

    fun saveAvatar(onComplete: () -> Unit) {
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true) }
            userProfileManager.updateAvatarConfig(_uiState.value.config)
            _uiState.update { it.copy(isSaving = false, isSaved = true) }
            onComplete()
        }
    }
}
