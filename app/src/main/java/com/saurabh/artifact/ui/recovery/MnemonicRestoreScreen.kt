package com.saurabh.artifact.ui.recovery

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.saurabh.artifact.ui.components.base.AppButton
import com.saurabh.artifact.ui.theme.ArtifactTheme
import com.saurabh.artifact.ui.util.UiText
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel as lifecycleHiltViewModel

@Composable
fun MnemonicRestoreScreen(
    onSuccess: () -> Unit,
    onStartFresh: () -> Unit,
    viewModel: MnemonicRestoreViewModel = lifecycleHiltViewModel()
) {
    val mnemonic by viewModel.mnemonic.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    
    var showConfirmFresh by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Lock,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = ArtifactTheme.colors.goldAura
        )
        
        Spacer(modifier = Modifier.height(24.dp))
        
        Text(
            text = "Restoring Your Journey",
            style = MaterialTheme.typography.headlineMedium,
            color = ArtifactTheme.colors.onSurfaceMain,
            textAlign = TextAlign.Center
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Text(
            text = "We found your existing Artifacts, but they are protected. Please enter your 12-word recovery phrase to unlock them.",
            style = MaterialTheme.typography.bodyLarge,
            color = ArtifactTheme.colors.onSurfaceMuted,
            textAlign = TextAlign.Center
        )
        
        Spacer(modifier = Modifier.height(32.dp))

        OutlinedTextField(
            value = mnemonic,
            onValueChange = { viewModel.onMnemonicChange(it) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Recovery Phrase") },
            placeholder = { Text("Enter 12 words...") },
            minLines = 3,
            enabled = uiState !is RecoveryUiState.Processing,
            isError = uiState is RecoveryUiState.Error,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = ArtifactTheme.colors.goldAura,
                unfocusedBorderColor = ArtifactTheme.colors.onSurfaceMuted.copy(alpha = 0.3f)
            )
        )

        if (uiState is RecoveryUiState.Error) {
            Text(
                text = (uiState as RecoveryUiState.Error).message.asString(),
                color = ArtifactTheme.colors.softError,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        Spacer(modifier = Modifier.height(32.dp))

        AppButton(
            text = if (uiState is RecoveryUiState.Processing) "Unlocking..." else "Unlock Data",
            onClick = { viewModel.attemptRecovery(onSuccess) },
            enabled = (mnemonic.isNotBlank() && uiState !is RecoveryUiState.Processing),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(16.dp))

        TextButton(
            onClick = { showConfirmFresh = true },
            enabled = uiState !is RecoveryUiState.Processing
        ) {
            Text(
                text = "I lost my phrase, start fresh",
                color = ArtifactTheme.colors.onSurfaceMuted
            )
        }
    }

    if (showConfirmFresh) {
        AlertDialog(
            onDismissRequest = { showConfirmFresh = false },
            title = { Text("Are you sure?") },
            text = { Text("Starting fresh will permanently abandon your existing artifacts and recordings on this device. This cannot be undone.") },
            confirmButton = {
                Button(
                    onClick = { 
                        showConfirmFresh = false
                        viewModel.startFresh(onStartFresh) 
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ArtifactTheme.colors.softError)
                ) {
                    Text("Start Fresh")
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmFresh = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}
