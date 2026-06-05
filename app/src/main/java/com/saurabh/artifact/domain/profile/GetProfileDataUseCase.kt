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
    val cloudDrafts: List<Artifact>,
    val savedArtifacts: List<Artifact>,
    val localDrafts: List<ArtifactDraftEntity>,
    val isResonating: Boolean,
    val isSelf: Boolean
)

class GetProfileDataUseCase @Inject constructor(
    private val userRepository: UserRepository,
    private val artifactRepository: ArtifactRepository,
    private val recordingRepository: RecordingRepository,
    private val authRepository: AuthRepository
) {
    operator fun invoke(targetUserId: String?): Flow<ProfileData?> {
        return authRepository.userData.flatMapLatest { currentUser ->
            val isSelf = (targetUserId == null) || (targetUserId == currentUser?.id)
            val finalId = targetUserId ?: currentUser?.id
            val currentUserId = currentUser?.id

            val effectiveId = finalId ?: return@flatMapLatest flowOf(null)

            combine(
                userRepository.streamUserProfile(effectiveId),
                artifactRepository.getUserArtifacts(effectiveId, onlyActive = !isSelf),
                artifactRepository.getSavedArtifacts(effectiveId),
                if (isSelf) recordingRepository.observeDrafts().map { drafts ->
                    drafts.filter { it.lifecycle != com.saurabh.artifact.model.ArtifactLifecycle.PUBLISHED }
                } else flowOf(emptyList()),
                if (currentUserId != null) userRepository.observeIsResonating(currentUserId, effectiveId) else flowOf(false)
            ) { profile, allArtifacts, saved, localDrafts, isResonating ->
                val statusPublished = com.saurabh.artifact.model.ArtifactStatus.ACTIVE
                ProfileData(
                    userProfile = profile,
                    publishedArtifacts = allArtifacts.filter { it.status == statusPublished },
                    cloudDrafts = allArtifacts.filter { it.status != statusPublished },
                    savedArtifacts = saved,
                    localDrafts = localDrafts,
                    isResonating = isResonating,
                    isSelf = isSelf
                )
            }
        }
    }
}
