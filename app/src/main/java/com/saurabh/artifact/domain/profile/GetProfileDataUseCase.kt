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
        val currentUserId = authRepository.currentUserId
        val isSelf = (targetUserId == null) || (targetUserId == currentUserId)
        val finalId = targetUserId ?: currentUserId

        if (finalId.isEmpty()) return flowOf(null)

        return combine(
            userRepository.streamUserProfile(finalId),
            artifactRepository.getUserArtifacts(finalId, onlyActive = !isSelf),
            artifactRepository.getSavedArtifacts(finalId),
            if (isSelf) recordingRepository.observeDrafts().map { drafts ->
                drafts.filter { 
                    it.lifecycle != com.saurabh.artifact.model.ArtifactLifecycle.PUBLISHED &&
                    it.lifecycle != com.saurabh.artifact.model.ArtifactLifecycle.DELETING &&
                    it.lifecycle != com.saurabh.artifact.model.ArtifactLifecycle.DELETED
                }
            } else flowOf(emptyList()),
            if (currentUserId.isNotEmpty()) userRepository.observeIsResonating(currentUserId, finalId) else flowOf(false)
        ) { profile, allArtifacts, saved, localDrafts, isResonating ->
            val statusPublished = com.saurabh.artifact.model.ArtifactStatus.ACTIVE
            ProfileData(
                userProfile = profile,
                publishedArtifacts = allArtifacts.filter { it.status == statusPublished },
                cloudDrafts = allArtifacts.filter { it.status != statusPublished && it.status != com.saurabh.artifact.model.ArtifactStatus.DELETED },
                savedArtifacts = saved,
                localDrafts = localDrafts,
                isResonating = isResonating,
                isSelf = isSelf
            )
        }
    }
}
