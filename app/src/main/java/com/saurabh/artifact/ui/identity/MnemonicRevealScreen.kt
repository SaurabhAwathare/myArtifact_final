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
    val isProcessing by viewModel.isProcessing.collectAsStateWithLifecycle()
    val setupError by viewModel.setupError.collectAsStateWithLifecycle()
    
    var hasConfirmed by remember { mutableStateOf(false) }

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
                text = "Secure Your Journey",
                style = MaterialTheme.typography.headlineMedium,
                color = GoldAura500,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "These 12 words are the only way to recover your artifacts if you change devices or lose access. Store them somewhere safe and private.",
                style = MaterialTheme.typography.bodyLarge,
                color = ArtifactTheme.colors.onSurfaceMuted,
                textAlign = TextAlign.Center,
                lineHeight = 24.sp
            )

            Spacer(modifier = Modifier.height(32.dp))

            // Word Grid
            WordGrid(words = words)

            Spacer(modifier = Modifier.height(48.dp))

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
                text = if (isProcessing) "Securing..." else "Continue to Artifact",
                onClick = { viewModel.completeSetup(onComplete) },
                enabled = hasConfirmed && !isProcessing,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp)
            )
        }
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
