package com.saurabh.artifact.ui.identity.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.saurabh.artifact.model.UsernameUiState
import com.saurabh.artifact.model.ValidationReason
import com.saurabh.artifact.ui.theme.MossSafe

@Composable
fun UsernameInput(
    state: UsernameUiState,
    onUsernameChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val isAvailable = state.isAvailable == true
    val isTaken = state.isAvailable == false && state.validationResult?.reason == ValidationReason.ALREADY_TAKEN
    
    val statusColor = when {
        isAvailable -> MossSafe
        isTaken -> MaterialTheme.colorScheme.error
        state.validationResult?.isValid == false -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.outlineVariant
    }

    Column(modifier = modifier) {
        OutlinedTextField(
            value = state.username,
            onValueChange = onUsernameChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Enter a name...") },
            label = { Text("Anonymous Name") },
            shape = RoundedCornerShape(16.dp),
            isError = state.validationResult?.isValid == false || isTaken,
            singleLine = true,
            trailingIcon = {
                when {
                    state.isValidating -> CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    isAvailable -> Icon(
                        Icons.Rounded.CheckCircle,
                        contentDescription = "Available",
                        tint = statusColor
                    )
                    isTaken || state.validationResult?.isValid == false -> Icon(
                        Icons.Rounded.Error,
                        contentDescription = "Attention Needed",
                        tint = statusColor
                    )
                }
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = if (isAvailable) statusColor else MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = if (isAvailable) statusColor else MaterialTheme.colorScheme.outlineVariant
            )
        )
        
        Spacer(modifier = Modifier.height(8.dp))

        // Display moderation warnings with protective guidance
        state.validationResult?.warnings?.forEach { warning ->
            ModerationWarningCard(
                warning = warning,
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        // Specific feedback for availability
        if (isTaken) {
            Text(
                text = "This name is already taken. Try one of the suggestions below.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(start = 16.dp, top = 4.dp)
            )
        } else if (isAvailable) {
            Text(
                text = "Name is available",
                color = MossSafe,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(start = 16.dp, top = 4.dp)
            )
        }
    }
}
