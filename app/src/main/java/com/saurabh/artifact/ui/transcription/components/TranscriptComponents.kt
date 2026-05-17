package com.saurabh.artifact.ui.transcription.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

@Composable
fun SensitiveInfoBanner(visible: Boolean) {
    if (visible) {
        Surface(
            color = Color(0xFFE57373).copy(alpha = 0.15f),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Shield, contentDescription = null, tint = Color(0xFFE57373))
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        "Potential Personal Info Detected",
                        style = MaterialTheme.typography.labelLarge,
                        color = Color(0xFFE57373),
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "We've highlighted names or locations. You can edit them to stay anonymous.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFE57373).copy(alpha = 0.8f)
                    )
                }
            }
        }
    }
}

@Composable
fun PublishConfirmationDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = Color(0xFF1A1A1A),
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    tint = Color(0xFFFFB300),
                    modifier = Modifier.size(48.dp)
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                Text(
                    "Ready to share your voice?",
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color(0xFFF2E7D5),
                    textAlign = TextAlign.Center
                )
                
                Spacer(modifier = Modifier.height(12.dp))
                
                Text(
                    "This reflection will become public for others to hear and respond to empathetically.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFFBDBDBD),
                    textAlign = TextAlign.Center
                )
                
                Spacer(modifier = Modifier.height(24.dp))
                
                Button(
                    onClick = onConfirm,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFB300)),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text("Publish Reflection", color = Color.Black, fontWeight = FontWeight.Bold)
                }
                
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Go back and edit", color = Color(0xFFBDBDBD))
                }
            }
        }
    }
}
