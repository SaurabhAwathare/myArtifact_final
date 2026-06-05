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

    private val _events = MutableSharedFlow<SavedEvent>()
    val events = _events.asSharedFlow()

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
                _events.emit(SavedEvent.Success(isSaved = !isSaved))
            }.onFailure {
                // Rollback on failure
                _savedIds.value = if (isSaved) {
                    _savedIds.value + artifact.id
                } else {
                    _savedIds.value - artifact.id
                }
                _events.emit(SavedEvent.Failure(isSaved = !isSaved))
            }
        }
    }

    sealed class SavedEvent {
        data class Success(val isSaved: Boolean) : SavedEvent()
        data class Failure(val isSaved: Boolean) : SavedEvent()
    }

    fun isSaved(artifactId: String): Flow<Boolean> {
        return _savedIds.map { it.contains(artifactId) }.distinctUntilChanged()
    }
}
