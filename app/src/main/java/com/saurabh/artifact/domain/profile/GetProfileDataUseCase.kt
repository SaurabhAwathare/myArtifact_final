package com.saurabh.artifact.domain.profile

import com.saurabh.artifact.data.local.ArtifactDraftEntity
import com.saurabh.artifact.model.Artifact
import com.saurabh.artifact.model.User
import com.saurabh.artifact.repository.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject

data class ProfileData(
    val userProfile: User?,
    val publishedArtifacts: List<Artifact>,
    val lastArtifactDocument: com.google.firebase.firestore.DocumentSnapshot?,
    val cloudDrafts: List<Artifact>,
    val savedArtifacts: List<Artifact>,
    val localDrafts: List<ArtifactDraftEntity>,
    val isResonating: Boolean,
    val isSelf: Boolean
)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class GetProfileDataUseCase @Inject constructor(
    private val userRepository: UserRepository,
    private val artifactRepository: ArtifactRepository,
    private val recordingRepository: RecordingRepository,
    private val authRepository: AuthRepository
) {
    operator fun invoke(targetUserId: String?): Flow<ProfileData?> {
        return authRepository.currentUser.flatMapLatest { currentUser ->
            val currentUserId = currentUser?.uid ?: ""
            val isSelf = (targetUserId == null) || (targetUserId == currentUserId)
            val finalId = targetUserId ?: currentUserId

            if (finalId.isEmpty()) return@flatMapLatest flowOf(null)

            combine(
                userRepository.streamUserProfile(finalId),
                artifactRepository.getUserArtifacts(finalId, onlyActive = !isSelf),
                if (isSelf) artifactRepository.getSavedArtifacts(finalId) else flowOf(emptyList()),
                if (isSelf) recordingRepository.observeDrafts() else flowOf(emptyList()),
                if (currentUserId.isNotEmpty()) userRepository.observeIsResonating(currentUserId, finalId) else flowOf(false)
            ) { profile, artifactsWithSnapshot, saved, localDrafts, isResonating ->
                val (allArtifacts, lastDoc) = artifactsWithSnapshot
                val statusPublished = com.saurabh.artifact.model.ArtifactStatus.ACTIVE
                val localDraftIds = localDrafts.map { it.id }.toSet()

                ProfileData(
                    userProfile = profile,
                    publishedArtifacts = allArtifacts.filter { it.status == statusPublished },
                    lastArtifactDocument = lastDoc,
                    cloudDrafts = allArtifacts.filter { 
                        it.status != statusPublished && 
                        it.status != com.saurabh.artifact.model.ArtifactStatus.DELETED &&
                        it.id !in localDraftIds
                    },
                    savedArtifacts = saved,
                    localDrafts = localDrafts,
                    isResonating = isResonating,
                    isSelf = isSelf
                )
            }
        }
    }
}
