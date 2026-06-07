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
import androidx.compose.ui.unit.dp
import com.saurabh.artifact.model.ModerationWarning
import com.saurabh.artifact.model.ValidationReason

@Composable
fun ModerationWarningCard(
    warning: ModerationWarning,
    modifier: Modifier = Modifier
) {
    val containerColor = when (warning.reason) {
        ValidationReason.PHONE_NUMBER, ValidationReason.EMAIL_ADDRESS, ValidationReason.REAL_NAME -> 
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
        ValidationReason.HATEFUL_LANGUAGE, ValidationReason.HARASSMENT -> 
            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f)
        else -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f)
    }

    val icon = when (warning.reason) {
        ValidationReason.PHONE_NUMBER, ValidationReason.EMAIL_ADDRESS, ValidationReason.REAL_NAME -> Icons.Rounded.PrivacyTip
        ValidationReason.HATEFUL_LANGUAGE, ValidationReason.HARASSMENT -> Icons.Rounded.WarningAmber
        else -> Icons.Rounded.Info
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
                tint = contentColorFor(containerColor),
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(
                    text = "Protective Guidance",
                    style = MaterialTheme.typography.labelMedium,
                    color = contentColorFor(containerColor).copy(alpha = 0.7f)
                )
                Text(
                    text = warning.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = contentColorFor(containerColor)
                )
            }
        }
    }
}
