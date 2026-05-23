package com.saurabh.artifact.repository

import android.util.Log
import com.saurabh.artifact.model.Artifact
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import javax.inject.Inject
import javax.inject.Singleton

@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class SavedArtifactManager @Inject constructor(
    private val artifactRepository: ArtifactRepository,
    private val authRepository: AuthRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    
    private val _savedIds = MutableStateFlow<Set<String>>(emptySet())
    val savedIds: StateFlow<Set<String>> = _savedIds.asStateFlow()

    private val _messages = MutableSharedFlow<String>()
    val messages = _messages.asSharedFlow()

    init {
        observeSavedIds()
    }

    private fun observeSavedIds() {
        scope.launch {
            authRepository.currentUser.flatMapLatest { user ->
                if (user != null) {
                    artifactRepository.getSavedArtifactIds(user.uid)
                } else {
                    flowOf(emptySet())
                }
            }.collect { ids ->
                _savedIds.value = ids
            }
        }
    }

    fun toggleSave(artifact: Artifact) {
        val userId = authRepository.currentUser.value?.uid ?: return
        val isSaved = _savedIds.value.contains(artifact.id)
        
        // Optimistic Update
        val newIds = if (isSaved) {
            _savedIds.value - artifact.id
        } else {
            _savedIds.value + artifact.id
        }
        _savedIds.value = newIds

        scope.launch {
            val result = if (isSaved) {
                artifactRepository.unsaveArtifact(userId, artifact.id)
            } else {
                artifactRepository.saveArtifact(userId, artifact)
            }

            result.onSuccess {
                val message = if (isSaved) {
                    "Removed from your private archive"
                } else {
                    "Saved to your private archive"
                }
                _messages.emit(message)
            }.onFailure {
                // Rollback on failure
                _savedIds.value = if (isSaved) {
                    _savedIds.value + artifact.id
                } else {
                    _savedIds.value - artifact.id
                }
                _messages.emit("Unable to update archive")
            }
        }
    }

    fun isSaved(artifactId: String): Flow<Boolean> {
        return _savedIds.map { it.contains(artifactId) }.distinctUntilChanged()
    }
}
