package com.saurabh.artifact.ui.transcription

import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.saurabh.artifact.ui.splash.AnimatedWaveform
import com.saurabh.artifact.ui.theme.GoldAura500
import com.saurabh.artifact.ui.theme.Obsidian950

@Composable
fun ProcessingScreen(
    onCancel: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Obsidian950
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(modifier = Modifier.size(200.dp), contentAlignment = Alignment.Center) {
                AnimatedWaveform()
            }
            
            Spacer(modifier = Modifier.height(48.dp))
            
            Text(
                text = "Preparing your artifact...",
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White,
                textAlign = TextAlign.Center
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Text(
                text = "Generating transcript\nChecking sensitive information\nOptimizing audio",
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White.copy(alpha = 0.5f),
                textAlign = TextAlign.Center,
                lineHeight = 32.sp
            )
            
            Spacer(modifier = Modifier.height(64.dp))
            
            TextButton(onClick = onCancel) {
                Text("Cancel and Save Draft", color = Color.White.copy(alpha = 0.3f))
            }
        }
    }
}
