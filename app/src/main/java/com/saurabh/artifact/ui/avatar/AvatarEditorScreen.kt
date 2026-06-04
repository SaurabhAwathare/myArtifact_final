package com.saurabh.artifact.ui.avatar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.saurabh.artifact.model.avatar.*
import com.saurabh.artifact.ui.components.ArtifactAvatar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AvatarEditorScreen(
    onBack: () -> Unit,
    onComplete: () -> Unit,
    viewModel: PresenceBuilderViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.updateTheme("CARTOON")
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Design Your Avatar", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.randomize("CARTOON") }) {
                        Icon(Icons.Rounded.AutoAwesome, contentDescription = "Randomize")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        },
        bottomBar = {
            Box(modifier = Modifier.padding(24.dp).navigationBarsPadding()) {
                Button(
                    onClick = { viewModel.savePresence(onComplete) },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = RoundedCornerShape(16.dp),
                    enabled = !uiState.isSaving
                ) {
                    if (uiState.isSaving) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    } else {
                        Text("Save Avatar", style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(24.dp))

            // Avatar Preview
            Box(
                modifier = Modifier
                    .size(200.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
                contentAlignment = Alignment.Center
            ) {
                ArtifactAvatar(
                    config = uiState.config,
                    size = 180.dp
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            // Editor Options
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
            ) {
                EditorSection("Face Shape") {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(FaceShape.entries) { shape ->
                            OptionChip(
                                label = shape.name,
                                selected = uiState.config.faceShape == shape,
                            ) { viewModel.updateFaceShape(shape) }
                        }
                    }
                }

                EditorSection("Hair Style") {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(HairType.entries) { hair ->
                            OptionChip(
                                label = hair.name,
                                selected = uiState.config.hairType == hair,
                            ) { viewModel.updateHairType(hair) }
                        }
                    }
                }

                EditorSection("Eyes") {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(EyeType.entries) { eyes ->
                            OptionChip(
                                label = eyes.name,
                                selected = uiState.config.eyeType == eyes,
                            ) { viewModel.updateEyeType(eyes) }
                        }
                    }
                }

                EditorSection("Mouth") {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(MouthType.entries) { mouth ->
                            OptionChip(
                                label = mouth.name,
                                selected = uiState.config.mouthType == mouth,
                            ) { viewModel.updateMouthType(mouth) }
                        }
                    }
                }
                
                EditorSection("Skin Color") {
                    val colors = listOf("#FFDBAC", "#F1C27D", "#E0AC69", "#8D5524")
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(colors) { colorHex ->
                            ColorOption(
                                colorHex = colorHex,
                                selected = uiState.config.skinColor == colorHex,
                                onClick = { viewModel.updateSkinColor(colorHex) }
                            )
                        }
                    }
                }

                EditorSection("Hair Color") {
                    val colors = listOf("#4A2C2C", "#000000", "#C68642", "#D6B672", "#FFFFFF")
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(colors) { colorHex ->
                            ColorOption(
                                colorHex = colorHex,
                                selected = uiState.config.hairColor == colorHex,
                                onClick = { viewModel.updateHairColor(colorHex) }
                            )
                        }
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun EditorSection(title: String, content: @Composable () -> Unit) {
    Column(modifier = Modifier.padding(vertical = 12.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(8.dp))
        content()
    }
}

@Composable
private fun OptionChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primary,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimary
        )
    )
}

@Composable
private fun ColorOption(colorHex: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(Color(android.graphics.Color.parseColor(colorHex)))
            .clickable { onClick() }
            .then(
                if (selected) Modifier.background(Color.White.copy(alpha = 0.3f), CircleShape)
                else Modifier
            ),
        contentAlignment = Alignment.Center
    ) {
        if (selected) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(Color.White, CircleShape)
            )
        }
    }
}
