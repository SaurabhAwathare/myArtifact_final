package com.saurabh.artifact.ui.profile.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.saurabh.artifact.model.User
import com.saurabh.artifact.ui.theme.ArtifactTheme

import com.saurabh.artifact.ui.components.ArtifactSigil
import com.saurabh.artifact.model.SigilConfig
import com.saurabh.artifact.ui.components.state.LoadingPlaceholder
import com.saurabh.artifact.util.QualitativeLanguage

/**
 * Redesigned ProfileHeader: Compact, Dense, Instagram-style hierarchy.
 * [ Sigil ]   Posts   Resonating   Resonators
 * @username
 */
@Composable
fun ProfileHeader(
    user: User?,
    sigilConfig: SigilConfig,
    isSelf: Boolean,
    isResonating: Boolean,
    onResonateClick: () -> Unit,
    onEditClick: () -> Unit,
    modifier: Modifier = Modifier,
    onResonatorsClick: () -> Unit = {},
    onResonatingClick: () -> Unit = {},
    isLoading: Boolean = false
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(120.dp)
                .clickable(enabled = isSelf && !isLoading) { onEditClick() },
            contentAlignment = Alignment.Center
        ) {
            if (isLoading) {
                LoadingPlaceholder(
                    height = 110.dp,
                    width = Modifier.size(110.dp),
                    shape = CircleShape
                )
            } else {
                val displayConfig = when {
                    isSelf -> sigilConfig
                    user != null -> user.sigilConfig
                    else -> SigilConfig(seed = "fallback")
                }

                ArtifactSigil(
                    config = displayConfig,
                    size = 110.dp
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Username
        if (isLoading) {
            LoadingPlaceholder(
                height = 24.dp,
                width = Modifier.width(120.dp),
                shape = RoundedCornerShape(4.dp)
            )
        } else {
            Text(
                text = user?.anonymousName ?: "quiet presence",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }

        Spacer(modifier = Modifier.height(32.dp))

        // Stats
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isLoading) {
                repeat(3) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        LoadingPlaceholder(height = 20.dp, width = Modifier.width(30.dp))
                        Spacer(Modifier.height(4.dp))
                        LoadingPlaceholder(height = 12.dp, width = Modifier.width(60.dp))
                    }
                }
            } else {
                val resonatorsCount = when {
                    user == null -> 0L
                    user.resonanceInCount > 0 -> user.resonanceInCount
                    else -> user.followersCount
                }
                val followingCount = when {
                    user == null -> 0L
                    user.resonanceOutCount > 0 -> user.resonanceOutCount
                    else -> user.followingCount
                }

                StatItem(
                    label = "artifacts",
                    displayValue = (user?.artifactsCount ?: 0L).toString()
                )
                StatItem(
                    label = "following", 
                    displayValue = QualitativeLanguage.getResonanceLabel(followingCount),
                    onClick = onResonatingClick
                )
                StatItem(
                    label = "resonators", 
                    displayValue = QualitativeLanguage.getResonanceLabel(resonatorsCount),
                    onClick = onResonatorsClick
                )
            }
        }

        if (!isSelf) {
            Spacer(modifier = Modifier.height(24.dp))
            Button(
                onClick = onResonateClick,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isResonating) ArtifactTheme.colors.surfaceHearth else MaterialTheme.colorScheme.primary,
                    contentColor = if (isResonating) ArtifactTheme.colors.onSurfaceMain else MaterialTheme.colorScheme.onPrimary
                ),
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(0.6f)
            ) {
                Text(if (isResonating) "Following" else "Follow")
            }
        }
    }
}

@Composable
private fun StatItem(label: String, displayValue: String, onClick: () -> Unit = {}) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(enabled = true) { onClick() }
    ) {
        Text(
            text = displayValue,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = ArtifactTheme.colors.onSurfaceMain
        )
        Text(
            text = label.lowercase(),
            style = MaterialTheme.typography.labelSmall,
            color = ArtifactTheme.colors.onSurfaceMuted,
            modifier = Modifier.alpha(0.6f)
        )
    }
}
