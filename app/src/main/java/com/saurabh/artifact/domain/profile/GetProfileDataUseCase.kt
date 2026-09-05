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
    val isIgnored: Boolean = false,
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
    operator fun invoke(targetUserId: String?, targetPersonaId: String?): Flow<ProfileData?> {
        return authRepository.currentUser.flatMapLatest { currentUser ->
            val currentUserId = currentUser?.uid ?: ""
            
            // Proactive Profile Fetch to resolve current user's anonymous identity for Self-comparison
            userRepository.streamUserProfile(currentUserId).flatMapLatest { ownProfile ->
                val currentPersonaId = ownProfile?.anonymousId ?: ""
                
                // SELF DETECTION: True if no target supplied, or target matches current UID, 
                // or target persona matches current user's active persona.
                val isSelf = (targetUserId == null && targetPersonaId == null) || 
                            (targetUserId != null && targetUserId == currentUserId) ||
                            (targetPersonaId != null && targetPersonaId == currentPersonaId)
                
                val profileLookupId = if (isSelf) {
                    currentUserId
                } else {
                    targetPersonaId ?: targetUserId ?: ""
                }

                val artifactQueryId = if (isSelf) {
                    currentUserId
                } else {
                    targetPersonaId ?: targetUserId ?: ""
                }

                if (profileLookupId.isEmpty()) return@flatMapLatest flowOf(null)

                combine(
                    userRepository.streamUserProfile(profileLookupId),
                    artifactRepository.getUserArtifacts(
                        userId = artifactQueryId,
                        isSelf = isSelf,
                        onlyActive = !isSelf
                    ),
                    if (isSelf) recordingRepository.observeDrafts() else flowOf(emptyList()),
                    if (currentUserId.isNotEmpty() && !isSelf) userRepository.observeIsResonating(currentUserId, profileLookupId) else flowOf(false),
                    if (currentUserId.isNotEmpty() && !isSelf) userRepository.observeIgnoredUsers().map { it.contains(profileLookupId) } else flowOf(false),
                    visibilityFilter.observeSuppressedIds(currentUserId)
                ) { params ->
                    @Suppress("UNCHECKED_CAST")
                    val profile = params[0] as User?
                    @Suppress("UNCHECKED_CAST")
                    val artifactsWithSnapshot = params[1] as Pair<List<Artifact>, com.google.firebase.firestore.DocumentSnapshot?>
                    @Suppress("UNCHECKED_CAST")
                    val localDrafts = params[2] as List<ArtifactDraftEntity>
                    val isResonating = params[3] as Boolean
                    val isIgnored = params[4] as Boolean
                    @Suppress("UNCHECKED_CAST")
                    val suppressedIds = params[5] as Set<String>

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
                        isIgnored = isIgnored,
                        isSelf = isSelf
                    )
                }
            }
        }
    }
}
