package com.saurabh.artifact.ui.recovery

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.saurabh.artifact.ui.components.base.AppButton
import com.saurabh.artifact.ui.settings.SettingsViewModel
import com.saurabh.artifact.ui.theme.ArtifactTheme
import com.saurabh.artifact.util.RescueTracker

@Composable
fun RescueScreen(
    onRestart: () -> Unit,
    settingsViewModel: SettingsViewModel = hiltViewModel()
) {
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Refresh,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = ArtifactTheme.colors.softError
        )
        
        Spacer(modifier = Modifier.height(24.dp))
        
        Text(
            text = "Rescue Mode",
            style = MaterialTheme.typography.headlineMedium,
            color = ArtifactTheme.colors.onSurfaceMain
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Text(
            text = "Artifact has crashed multiple times during startup. This usually happens due to corrupted local data.",
            style = MaterialTheme.typography.bodyLarge,
            color = ArtifactTheme.colors.onSurfaceMuted,
            textAlign = TextAlign.Center
        )
        
        Spacer(modifier = Modifier.height(32.dp))

        AppButton(
            text = "Try Again",
            onClick = {
                RescueTracker.getInstance(context).reset()
                onRestart()
            },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(12.dp))

        Button(
            onClick = {
                context.cacheDir.deleteRecursively()
                RescueTracker.getInstance(context).reset()
                onRestart()
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = ArtifactTheme.colors.surfaceHearth
            )
        ) {
            Text("Clear Cache & Data")
        }

        Spacer(modifier = Modifier.height(12.dp))

        Button(
            onClick = {
                settingsViewModel.logout()
                RescueTracker.getInstance(context).reset()
                onRestart()
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = ArtifactTheme.colors.surfaceHearth
            )
        ) {
            Text("Sign Out")
        }
    }
}
