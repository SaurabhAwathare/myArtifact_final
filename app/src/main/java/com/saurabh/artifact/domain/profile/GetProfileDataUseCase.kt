package com.saurabh.artifact.domain.profile

import com.saurabh.artifact.data.local.ArtifactDraftEntity
import com.saurabh.artifact.model.Artifact
import com.saurabh.artifact.model.User
import com.saurabh.artifact.repository.*
import com.saurabh.artifact.domain.ArtifactVisibilityFilter
import kotlinx.coroutines.flow.*
import javax.inject.Inject

data class ProfileData(
    val userProfile: User?,
    val publishedArtifacts: List<Artifact>,
    val lastArtifactDocument: com.google.firebase.firestore.DocumentSnapshot?,
    val cloudDrafts: List<Artifact>,
    val localDrafts: List<ArtifactDraftEntity>,
    val isResonating: Boolean,
    val isSelf: Boolean
)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class GetProfileDataUseCase @Inject constructor(
    private val userRepository: UserRepository,
    private val artifactRepository: ArtifactRepository,
    private val recordingRepository: RecordingRepository,
    private val authRepository: AuthRepository,
    private val visibilityFilter: ArtifactVisibilityFilter
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
                if (isSelf) recordingRepository.observeDrafts() else flowOf(emptyList()),
                if (currentUserId.isNotEmpty()) userRepository.observeIsResonating(currentUserId, finalId) else flowOf(false),
                visibilityFilter.observeSuppressedIds(currentUserId)
            ) { params ->
                @Suppress("UNCHECKED_CAST")
                val profile = params[0] as User?
                @Suppress("UNCHECKED_CAST")
                val artifactsWithSnapshot = params[1] as Pair<List<Artifact>, com.google.firebase.firestore.DocumentSnapshot?>
                @Suppress("UNCHECKED_CAST")
                val localDrafts = params[2] as List<ArtifactDraftEntity>
                val isResonating = params[3] as Boolean
                @Suppress("UNCHECKED_CAST")
                val suppressedIds = params[4] as Set<String>

                val (allArtifacts, lastDoc) = artifactsWithSnapshot
                val statusPublished = com.saurabh.artifact.model.ArtifactStatus.ACTIVE
                val localDraftIds = localDrafts.map { it.id }.toSet()

                // Safety Invariant: Artifacts already filtered by Repository when viewing others.
                // We keep the additional local suppression check for immediate UI feedback.
                val filteredArtifacts = allArtifacts.filter { it.id !in suppressedIds }

                ProfileData(
                    userProfile = profile,
                    publishedArtifacts = filteredArtifacts.filter { it.status == statusPublished },
                    lastArtifactDocument = lastDoc,
                    cloudDrafts = filteredArtifacts.filter { 
                        it.status != statusPublished && 
                        it.status != com.saurabh.artifact.model.ArtifactStatus.DELETED &&
                        it.id !in localDraftIds
                    },
                    localDrafts = localDrafts,
                    isResonating = isResonating,
                    isSelf = isSelf
                )
            }
        }
    }
}
