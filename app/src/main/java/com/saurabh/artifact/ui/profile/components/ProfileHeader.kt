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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.saurabh.artifact.model.User
import com.saurabh.artifact.ui.theme.ArtifactTheme
import com.saurabh.artifact.ui.components.ArtifactSigil
import com.saurabh.artifact.model.SigilConfig
import com.saurabh.artifact.ui.components.state.LoadingPlaceholder

/**
 * Redesigned ProfileHeader: Compact layout.
 * Sigil and Anonymous Profile Name arranged horizontally.
 * Stats (Artifacts, Following, Resonators) in a compact, evenly spaced row.
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
    isLoading: Boolean = false,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Top Row: Sigil on the left, Profile Name & Actions on the right
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start
        ) {
            // Sigil (Compact size 64.dp)
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clickable(enabled = isSelf && !isLoading) { onEditClick() },
                contentAlignment = Alignment.Center
            ) {
                if (isLoading) {
                    LoadingPlaceholder(
                        height = 64.dp,
                        width = Modifier.size(64.dp),
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
                        size = 64.dp
                    )
                }
            }

            Spacer(modifier = Modifier.width(16.dp))

            // Username and Follow button column
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center
            ) {
                if (isLoading) {
                    LoadingPlaceholder(
                        height = 20.dp,
                        width = Modifier.width(120.dp),
                        shape = RoundedCornerShape(4.dp)
                    )
                } else {
                    Text(
                        text = user?.anonymousName ?: "quiet presence",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (!isSelf) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = onResonateClick,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isResonating) ArtifactTheme.colors.surfaceHearth else MaterialTheme.colorScheme.primary,
                            contentColor = if (isResonating) ArtifactTheme.colors.onSurfaceMain else MaterialTheme.colorScheme.onPrimary
                        ),
                        shape = MaterialTheme.shapes.medium,
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                        modifier = Modifier.defaultMinSize(minHeight = 36.dp)
                    ) {
                        Text(
                            if (isResonating) "Following" else "Follow",
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Stats Row (Artifacts, Following, Resonators)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isLoading) {
                repeat(3) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        LoadingPlaceholder(height = 18.dp, width = Modifier.width(30.dp))
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
                    displayValue = followingCount.toString(),
                    onClick = onResonatingClick,
                    enabled = isSelf
                )
                StatItem(
                    label = "resonators", 
                    displayValue = resonatorsCount.toString(),
                    onClick = onResonatorsClick,
                    enabled = isSelf
                )
            }
        }
    }
}

@Composable
private fun StatItem(label: String, displayValue: String, enabled: Boolean = true, onClick: () -> Unit = {}) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .defaultMinSize(minWidth = 64.dp, minHeight = 48.dp)
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.Center
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
