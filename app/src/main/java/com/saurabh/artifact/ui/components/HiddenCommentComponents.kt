package com.saurabh.artifact.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.core.os.ConfigurationCompat
import com.saurabh.artifact.model.ArtifactComment
import com.saurabh.artifact.ui.theme.GoldAura400
import com.saurabh.artifact.ui.theme.GoldAura500
import com.saurabh.artifact.ui.theme.MistGray
import com.saurabh.artifact.ui.theme.Obsidian800
import com.saurabh.artifact.ui.theme.ReflectionWhite
import java.text.SimpleDateFormat

/**
 * Notice banner explaining the Hidden Comments Mode.
 * Obsidian + Amber theme for emotional safety.
 */
@Composable
fun HiddenCommentNotice(modifier: Modifier = Modifier) {
    Surface(
        color = Obsidian800,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, GoldAura500.copy(alpha = 0.2f)),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = null,
                    tint = GoldAura500,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = "Private Reflection Mode",
                    style = MaterialTheme.typography.titleSmall,
                    color = GoldAura400
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Your response is a private bridge between you and the creator. It will not be visible to other listeners.",
                style = MaterialTheme.typography.bodyMedium,
                color = ReflectionWhite.copy(alpha = 0.8f)
            )
        }
    }
}

/**
 * A simple text-based comment item.
 */
@Composable
fun TextCommentItem(
    comment: ArtifactComment,
    modifier: Modifier = Modifier
) {
    val configuration = LocalConfiguration.current
    val locale = ConfigurationCompat.getLocales(configuration)[0] ?: configuration.locales[0]
    val dateFormatter = remember(locale) {
        SimpleDateFormat("MMM d, h:mm a", locale)
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = comment.authorAnonymousName ?: "Quiet Presence",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = dateFormatter.format(comment.createdAt.toDate()),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
                
                ArtifactAvatar(
                    config = com.saurabh.artifact.model.AvatarConfig(seed = comment.authorAvatarSeed),
                    size = 24.dp
                )
            }
            
            if (comment.content.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = comment.content,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

/**
 * Empty state for the Creator Inbox (The Hearth).
 */
@Composable
fun EmptyHearthState() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Default.Visibility,
                contentDescription = null,
                tint = GoldAura500.copy(alpha = 0.3f),
                modifier = Modifier.size(64.dp)
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = "The Hearth is quiet.",
                style = MaterialTheme.typography.titleMedium,
                color = ReflectionWhite
            )
            Text(
                text = "Reflections will appear here when listeners respond.",
                style = MaterialTheme.typography.bodySmall,
                color = MistGray
            )
        }
    }
}
