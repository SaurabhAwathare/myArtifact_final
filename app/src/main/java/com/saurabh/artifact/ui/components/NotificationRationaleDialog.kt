package com.saurabh.artifact.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.saurabh.artifact.ui.theme.GoldAura500
import com.saurabh.artifact.ui.theme.Obsidian900

@Composable
fun NotificationRationaleDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Obsidian900,
        title = {
            Text(
                "Stay Connected",
                color = Color.White,
                style = MaterialTheme.typography.headlineSmall
            )
        },
        text = {
            Column {
                Text(
                    "To help you stay in touch with your artifacts and the community, we use notifications for:",
                    color = Color.White.copy(alpha = 0.7f)
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text("• Updates on your artifact publications", color = Color.White.copy(alpha = 0.7f))
                Text("• Replies and interactions from others", color = Color.White.copy(alpha = 0.7f))
                Text("• Gentle reminders for your ritual", color = Color.White.copy(alpha = 0.7f))
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(contentColor = GoldAura500)
            ) {
                Text("Enable")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Not Now", color = Color.White.copy(alpha = 0.5f))
            }
        }
    )
}

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun NotificationRationaleDialogPreview() {
    com.saurabh.artifact.ui.theme.ArtifactTheme {
        NotificationRationaleDialog(
            onConfirm = {},
            onDismiss = {}
        )
    }
}
