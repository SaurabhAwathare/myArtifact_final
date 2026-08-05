package com.saurabh.artifact.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.saurabh.artifact.model.User
import com.saurabh.artifact.ui.theme.ArtifactTheme

/**
 * PRESENCE ITEM
 * A standardized row representing a user's anonymous presence.
 * Used in resonance lists and discovery views.
 */
@Composable
fun PresenceItem(
    user: User,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isResonating: Boolean = false,
    showResonateButton: Boolean = false,
    onResonateClick: () -> Unit = {}
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ArtifactSigil(
            config = user.sigilConfig,
            size = 48.dp,
            isStatic = true
        )
        
        Spacer(Modifier.width(16.dp))
        
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = user.anonymousName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                
                if (user.anonymousSigil.isNotEmpty()) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "· ${user.anonymousSigil}",
                        style = MaterialTheme.typography.labelSmall,
                        color = ArtifactTheme.colors.onSurfaceMuted,
                        modifier = Modifier.alpha(0.6f)
                    )
                }
            }
            
            Text(
                text = user.emotionalProfile,
                style = MaterialTheme.typography.labelMedium,
                color = ArtifactTheme.colors.onSurfaceMuted,
                modifier = Modifier.alpha(0.8f)
            )
        }

        if (showResonateButton) {
            Spacer(Modifier.width(16.dp))
            androidx.compose.material3.Button(
                onClick = onResonateClick,
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = if (isResonating) ArtifactTheme.colors.surfaceHearth else MaterialTheme.colorScheme.primary,
                    contentColor = if (isResonating) ArtifactTheme.colors.onSurfaceMain else MaterialTheme.colorScheme.onPrimary
                ),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                modifier = Modifier.height(32.dp),
                shape = MaterialTheme.shapes.medium
            ) {
                Text(
                    text = if (isResonating) "Following" else "Follow",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
