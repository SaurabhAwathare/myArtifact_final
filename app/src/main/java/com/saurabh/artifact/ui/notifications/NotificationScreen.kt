package com.saurabh.artifact.ui.notifications

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.os.ConfigurationCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.saurabh.artifact.model.NotificationItem
import com.saurabh.artifact.model.SigilConfig
import com.saurabh.artifact.model.avatar.AvatarConfiguration
import com.saurabh.artifact.ui.components.ArtifactAvatar
import com.saurabh.artifact.ui.theme.ArtifactTheme
import com.saurabh.artifact.ui.theme.Spacing
import com.saurabh.artifact.ui.util.NotificationMapper
import com.saurabh.artifact.ui.util.NotificationPresentation
import java.text.SimpleDateFormat

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationScreen(
    onBackClick: () -> Unit,
    onNotificationClick: (NotificationItem) -> Unit,
    viewModel: NotificationViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    val lazyListState = rememberLazyListState()
    val visibilityTracker = remember { NotificationVisibilityTracker(dwellDurationMs = 500L) }

    LaunchedEffect(lazyListState, uiState.items) {
        snapshotFlow {
            val layoutInfo = lazyListState.layoutInfo
            val visibleItems = layoutInfo.visibleItemsInfo
            val viewportStart = layoutInfo.viewportStartOffset
            val viewportEnd = layoutInfo.viewportEndOffset

            visibleItems.mapNotNull { itemInfo ->
                val itemKey = itemInfo.key as? String ?: return@mapNotNull null
                if (!itemKey.startsWith("notif_")) return@mapNotNull null
                val notifId = itemKey.removePrefix("notif_")

                val itemTop = itemInfo.offset
                val itemBottom = itemInfo.offset + itemInfo.size

                val visibleTop = maxOf(itemTop, viewportStart)
                val visibleBottom = minOf(itemBottom, viewportEnd)
                val visibleHeight = maxOf(0, visibleBottom - visibleTop)

                val ratio = visibleHeight.toFloat() / itemInfo.size.toFloat()
                if (ratio >= 0.5f) notifId else null
            }.toSet()
        }.collect { qualifyingIds ->
            val now = System.currentTimeMillis()
            val newlyDwelled = visibilityTracker.onViewportItemsChanged(qualifyingIds, now)
            if (newlyDwelled.isNotEmpty()) {
                viewModel.onNotificationsVisible(newlyDwelled.toList())
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Text(
                        "Notifications",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Light
                    ) 
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        if (uiState.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
        } else if (uiState.items.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Rounded.AutoAwesome,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        "The air is still",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.outline,
                        fontWeight = FontWeight.Light
                    )
                }
            }
        } else {
            LazyColumn(
                state = lazyListState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(Spacing.Medium),
                verticalArrangement = Arrangement.spacedBy(Spacing.Small)
            ) {
                items(uiState.items, key = { "notif_${it.id}" }) { notification ->
                    val index = uiState.items.indexOf(notification)
                    if (index >= uiState.items.size - 3 && uiState.hasMore && !uiState.isLoadingMore) {
                        LaunchedEffect(uiState.items.size) {
                            viewModel.loadMoreNotifications()
                        }
                    }

                    val actorId = notification.actorId ?: notification.followerId.takeIf { it.isNotBlank() }
                    val actorProfile = actorId?.let { uiState.actorProfiles[it] }

                    val actorAvatarConfig = actorProfile?.avatarConfig?.withSafeFallbacks()
                        ?: if (actorId != null) AvatarConfiguration.DEFAULT else null

                    NotificationCard(
                        notification = notification,
                        actorName = actorProfile?.anonymousName,
                        actorAvatarConfig = actorAvatarConfig,
                        onClick = {
                            viewModel.markAsRead(notification.id)
                            onNotificationClick(notification)
                        }
                    )
                }

                if (uiState.isLoadingMore) {
                    item(key = "loading_more") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(Spacing.Medium),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun NotificationCard(
    notification: NotificationItem,
    modifier: Modifier = Modifier,
    actorName: String? = null,
    actorSigilConfig: SigilConfig? = null,
    actorAvatarConfig: AvatarConfiguration? = null,
    onClick: () -> Unit
) {
    val configuration = LocalConfiguration.current
    val locale = ConfigurationCompat.getLocales(configuration)[0] ?: configuration.locales[0]
    val dateFormatter = remember(locale) {
        SimpleDateFormat("MMM d, h:mm a", locale)
    }

    val presentation = remember(notification, actorName) {
        NotificationMapper.mapToPresentation(notification, actorName)
    }

    val effectiveAvatarConfig = actorAvatarConfig
        ?: if (actorSigilConfig != null || notification.actorId != null || notification.followerId.isNotBlank()) {
            AvatarConfiguration.DEFAULT
        } else {
            null
        }

    val unreadBorder = BorderStroke(1.dp, ArtifactTheme.colors.goldAura.copy(alpha = 0.35f))
    val readBorder = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))

    Card(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 56.dp)
            .clickable(
                onClickLabel = "Open notification",
                role = Role.Button
            ) { onClick() }
            .semantics {
                contentDescription = if (notification.isRead) "Read notification" else "Unread notification"
            },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (notification.isRead) 
                MaterialTheme.colorScheme.surface
            else 
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        ),
        border = if (notification.isRead) readBorder else unreadBorder
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.Medium, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            NotificationLeadingIcon(
                presentation = presentation,
                actorAvatarConfig = effectiveAvatarConfig
            )

            Spacer(modifier = Modifier.width(Spacing.Medium))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = presentation.title.asString(),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = if (notification.isRead) FontWeight.Normal else FontWeight.SemiBold,
                        color = if (notification.isRead)
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
                        else
                            MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )

                    Spacer(modifier = Modifier.width(Spacing.Small))

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = dateFormatter.format(notification.createdAt.toDate()),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            fontWeight = FontWeight.Light
                        )

                        if (!notification.isRead) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .background(ArtifactTheme.colors.goldAura, CircleShape)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = presentation.subtitle.asString(),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (notification.isRead) FontWeight.Light else FontWeight.Normal,
                    color = if (notification.isRead)
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = MaterialTheme.typography.bodyMedium.lineHeight
                )
            }
        }
    }
}

@Composable
private fun NotificationLeadingIcon(
    presentation: NotificationPresentation,
    actorAvatarConfig: AvatarConfiguration?,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.size(44.dp),
        contentAlignment = Alignment.Center
    ) {
        if (actorAvatarConfig != null) {
            ArtifactAvatar(
                config = actorAvatarConfig.withSafeFallbacks(),
                size = 40.dp,
                contentDescription = "Notification actor avatar"
            )
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(18.dp)
                    .background(MaterialTheme.colorScheme.surface, CircleShape)
                    .padding(2.dp)
                    .background(ArtifactTheme.colors.surfaceHearth, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = presentation.eventIcon,
                    contentDescription = presentation.eventDescription,
                    modifier = Modifier.size(10.dp),
                    tint = ArtifactTheme.colors.goldAura
                )
            }
        } else {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(12.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = presentation.eventIcon,
                    contentDescription = presentation.eventDescription,
                    modifier = Modifier.size(20.dp),
                    tint = ArtifactTheme.colors.goldAura
                )
            }
        }
    }
}
