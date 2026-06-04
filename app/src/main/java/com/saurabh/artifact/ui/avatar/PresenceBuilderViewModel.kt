package com.saurabh.artifact.ui.avatar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.saurabh.artifact.model.AvatarConfig
import com.saurabh.artifact.model.avatar.*
import com.saurabh.artifact.repository.UserProfileManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

data class PresenceUiState(
    val config: AvatarConfig = AvatarConfig(),
    val isSaving: Boolean = false,
    val isSaved: Boolean = false
)

@HiltViewModel
class PresenceBuilderViewModel @Inject constructor(
    private val userProfileManager: UserProfileManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(PresenceUiState())
    val uiState: StateFlow<PresenceUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            userProfileManager.activeAvatarConfig.collect { config ->
                _uiState.update { it.copy(config = config) }
            }
        }
    }

    fun updateFaceShape(shape: FaceShape) {
        _uiState.update { it.copy(config = it.config.copy(faceShape = shape)) }
    }

    fun updateHairType(type: HairType) {
        _uiState.update { it.copy(config = it.config.copy(hairType = type)) }
    }

    fun updateEyeType(type: EyeType) {
        _uiState.update { it.copy(config = it.config.copy(eyeType = type)) }
    }

    fun updateMouthType(type: MouthType) {
        _uiState.update { it.copy(config = it.config.copy(mouthType = type)) }
    }

    fun updateSkinColor(color: String) {
        _uiState.update { it.copy(config = it.config.copy(skinColor = color)) }
    }

    fun updateHairColor(color: String) {
        _uiState.update { it.copy(config = it.config.copy(hairColor = color)) }
    }

    fun updateTheme(theme: String) {
        _uiState.update { it.copy(config = it.config.copy(theme = theme)) }
    }

    fun randomize(forcedTheme: String? = null) {
        val currentTheme = forcedTheme ?: _uiState.value.config.theme
        val randomConfig = AvatarConfig(
            seed = UUID.randomUUID().toString(),
            theme = currentTheme,
            faceShape = FaceShape.entries.random(),
            hairType = HairType.entries.random(),
            eyeType = EyeType.entries.random(),
            mouthType = MouthType.entries.random(),
            skinColor = listOf("#FFDBAC", "#F1C27D", "#E0AC69", "#8D5524").random(),
            hairColor = listOf("#4A2C2C", "#000000", "#C68642", "#D6B672").random()
        )
        _uiState.update { it.copy(config = randomConfig, isSaved = false) }
    }

    fun savePresence(onComplete: () -> Unit) {
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true) }
            userProfileManager.updateAvatarConfig(_uiState.value.config)
            _uiState.update { it.copy(isSaving = false, isSaved = true) }
            onComplete()
        }
    }
}
