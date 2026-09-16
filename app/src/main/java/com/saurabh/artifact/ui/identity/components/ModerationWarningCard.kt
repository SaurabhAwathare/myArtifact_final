package com.saurabh.artifact.ui.identity.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.PrivacyTip
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.saurabh.artifact.R
import com.saurabh.artifact.model.ModerationWarning
import com.saurabh.artifact.model.ValidationReason

@Composable
fun ModerationWarningCard(
    warning: ModerationWarning,
    modifier: Modifier = Modifier
) {
    val isBlocking = warning.isBlocking

    val containerColor = if (isBlocking) {
        when (warning.reason) {
            ValidationReason.HATEFUL_LANGUAGE, ValidationReason.HARASSMENT -> 
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f)
            ValidationReason.PHONE_NUMBER, ValidationReason.EMAIL_ADDRESS, ValidationReason.REAL_NAME -> 
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f)
            else -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
        }
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    }

    val icon = if (isBlocking) {
        when (warning.reason) {
            ValidationReason.PHONE_NUMBER, ValidationReason.EMAIL_ADDRESS, ValidationReason.REAL_NAME -> Icons.Rounded.PrivacyTip
            ValidationReason.HATEFUL_LANGUAGE, ValidationReason.HARASSMENT -> Icons.Rounded.WarningAmber
            else -> Icons.Rounded.Info
        }
    } else {
        when (warning.reason) {
            ValidationReason.MOTIF_REUSE, ValidationReason.POTENTIALLY_IDENTIFYING, 
            ValidationReason.INTRODUCTION_PATTERN, ValidationReason.CONTACT_PIVOT -> Icons.Rounded.PrivacyTip
            else -> Icons.Rounded.Info
        }
    }

    val iconTint = if (isBlocking) {
        contentColorFor(containerColor)
    } else {
        MaterialTheme.colorScheme.primary
    }

    val title = if (isBlocking) {
        stringResource(R.string.username_blocking_title)
    } else {
        stringResource(R.string.username_advisory_title)
    }

    val bodyText = warning.message.ifEmpty {
        if (warning.reason == ValidationReason.POTENTIALLY_IDENTIFYING) {
            stringResource(R.string.username_advisory_identifying_body)
        } else {
            stringResource(R.string.username_advisory_default_body)
        }
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isBlocking) iconTint.copy(alpha = 0.8f) else MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = bodyText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isBlocking) iconTint else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
