package com.saurabh.artifact.ui.identity

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.saurabh.artifact.security.MnemonicGenerator
import com.saurabh.artifact.ui.components.base.AppButton
import com.saurabh.artifact.ui.theme.ArtifactTheme
import com.saurabh.artifact.ui.theme.GoldAura500
import com.saurabh.artifact.ui.theme.Obsidian800
import com.saurabh.artifact.ui.theme.Obsidian950
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel as lifecycleHiltViewModel

@Composable
fun MnemonicRevealScreen(
    onComplete: () -> Unit,
    viewModel: MnemonicRevealViewModel = lifecycleHiltViewModel()
) {
    val words by viewModel.mnemonicWords.collectAsStateWithLifecycle()
    val isExistingPhrase by viewModel.isExistingPhrase.collectAsStateWithLifecycle()
    val isProcessing by viewModel.isProcessing.collectAsStateWithLifecycle()
    val setupError by viewModel.setupError.collectAsStateWithLifecycle()
    
    var hasConfirmed by remember { mutableStateOf(false) }
    var showRestoreDialog by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = Obsidian950
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(48.dp))

            Text(
                text = if (isExistingPhrase) "Your Recovery Phrase" else "Secure Your Journey",
                style = MaterialTheme.typography.headlineMedium,
                color = GoldAura500,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = if (isExistingPhrase) 
                    "These 12 words are your recovery phrase for your artifacts. Store them somewhere safe and private."
                else 
                    "These 12 words are the only way to recover your artifacts if you change devices or lose access. Store them somewhere safe and private.",
                style = MaterialTheme.typography.bodyLarge,
                color = ArtifactTheme.colors.onSurfaceMuted,
                textAlign = TextAlign.Center,
                lineHeight = 24.sp
            )

            Spacer(modifier = Modifier.height(32.dp))

            // Word Grid
            if (words.isNotEmpty()) {
                WordGrid(words = words)
            }

            Spacer(modifier = Modifier.height(24.dp))

            TextButton(
                onClick = { showRestoreDialog = true },
                enabled = !isProcessing,
                modifier = Modifier.padding(vertical = 4.dp)
            ) {
                Text(
                    text = "Already have a recovery phrase from another device? Restore phrase",
                    style = MaterialTheme.typography.bodyMedium,
                    color = GoldAura500,
                    textAlign = TextAlign.Center
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            if (!isExistingPhrase) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Checkbox(
                        checked = hasConfirmed,
                        onCheckedChange = { hasConfirmed = it },
                        colors = CheckboxDefaults.colors(
                            checkedColor = GoldAura500,
                            uncheckedColor = ArtifactTheme.colors.onSurfaceMuted
                        )
                    )
                    Text(
                        text = "I have written down or saved my recovery words in a safe place.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = ArtifactTheme.colors.onSurfaceMain,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            }

            if (setupError != null) {
                Text(
                    text = setupError!!,
                    color = ArtifactTheme.colors.softError,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 16.dp)
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            AppButton(
                text = when {
                    isProcessing -> if (isExistingPhrase) "Loading..." else "Securing..."
                    isExistingPhrase -> "Done"
                    else -> "Continue to Artifact"
                },
                onClick = { viewModel.completeSetup(onComplete) },
                enabled = if (isExistingPhrase) {
                    !isProcessing && setupError == null && words.isNotEmpty()
                } else {
                    hasConfirmed && !isProcessing && setupError == null
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp)
            )
        }
    }

    if (showRestoreDialog) {
        var inputPhrase by remember { mutableStateOf("") }
        var localError by remember { mutableStateOf<String?>(null) }

        AlertDialog(
            onDismissRequest = {
                if (!isProcessing) showRestoreDialog = false
            },
            containerColor = Obsidian800,
            title = {
                Text(
                    text = "Restore Recovery Phrase",
                    style = MaterialTheme.typography.titleLarge,
                    color = GoldAura500
                )
            },
            text = {
                Column {
                    Text(
                        text = "Enter your 12-word recovery phrase from your existing device:",
                        style = MaterialTheme.typography.bodyMedium,
                        color = ArtifactTheme.colors.onSurfaceMuted,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )

                    OutlinedTextField(
                        value = inputPhrase,
                        onValueChange = {
                            inputPhrase = it
                            localError = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Recovery Phrase") },
                        placeholder = { Text("Enter 12 words separated by spaces...") },
                        minLines = 3,
                        enabled = !isProcessing,
                        isError = localError != null || setupError != null,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = GoldAura500,
                            unfocusedBorderColor = ArtifactTheme.colors.onSurfaceMuted.copy(alpha = 0.3f),
                            focusedLabelColor = GoldAura500
                        )
                    )

                    if (localError != null) {
                        Text(
                            text = localError!!,
                            color = ArtifactTheme.colors.softError,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    } else if (setupError != null) {
                        Text(
                            text = setupError!!,
                            color = ArtifactTheme.colors.softError,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            },
            confirmButton = {
                AppButton(
                    text = if (isProcessing) "Restoring..." else "Restore Phrase",
                    onClick = {
                        val trimmed = inputPhrase.trim()
                        val wordList = trimmed.split("\\s+".toRegex()).filter { it.isNotBlank() }
                        if (wordList.size != 12 || !MnemonicGenerator.validateMnemonic(wordList)) {
                            localError = "Please enter a valid 12-word recovery phrase."
                        } else {
                            viewModel.restoreExistingPhrase(trimmed) {
                                showRestoreDialog = false
                                onComplete()
                            }
                        }
                    },
                    enabled = inputPhrase.isNotBlank() && !isProcessing
                )
            },
            dismissButton = {
                TextButton(
                    onClick = { showRestoreDialog = false },
                    enabled = !isProcessing
                ) {
                    Text(
                        text = "Cancel",
                        color = ArtifactTheme.colors.onSurfaceMuted
                    )
                }
            }
        )
    }
}

@Composable
private fun WordGrid(words: List<String>) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .background(Obsidian800, RoundedCornerShape(16.dp))
            .padding(16.dp)
    ) {
        val chunked = words.chunked(2)
        chunked.forEachIndexed { rowIndex, pair ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                pair.forEachIndexed { colIndex, word ->
                    val index = rowIndex * 2 + colIndex + 1
                    WordItem(
                        index = index,
                        word = word,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun WordItem(
    index: Int,
    word: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .background(Obsidian950.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "$index.",
            style = MaterialTheme.typography.bodySmall,
            color = GoldAura500.copy(alpha = 0.5f),
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(24.dp)
        )
        Text(
            text = word,
            style = MaterialTheme.typography.bodyMedium,
            color = ArtifactTheme.colors.onSurfaceMain,
            fontWeight = FontWeight.Medium
        )
    }
}
