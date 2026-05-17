package com.saurabh.artifact.ui.avatar

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.saurabh.artifact.model.AvatarExpression
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AvatarCreatorScreen(
    onBack: () -> Unit,
    onComplete: () -> Unit,
    viewModel: AvatarCreatorViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Identity Marker", style = MaterialTheme.typography.headlineSmall) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (uiState.undoStack.isNotEmpty()) {
                        IconButton(onClick = { viewModel.undo() }) {
                            Icon(Icons.AutoMirrored.Rounded.Undo, contentDescription = "Undo")
                        }
                    }
                    IconButton(onClick = { viewModel.randomize() }) {
                        Icon(Icons.Rounded.AutoAwesome, contentDescription = "Randomize")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent
                )
            )
        },
        bottomBar = {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                tonalElevation = 4.dp,
                shadowElevation = 8.dp
            ) {
                Box(modifier = Modifier.padding(24.dp).navigationBarsPadding()) {
                    Button(
                        onClick = { viewModel.saveAvatar(onComplete) },
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        shape = RoundedCornerShape(28.dp),
                        enabled = !uiState.isSaving,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    ) {
                        AnimatedContent(targetState = uiState.isSaving, label = "button_state") { saving ->
                            if (saving) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                            } else {
                                Text("Seal Identity", style = MaterialTheme.typography.titleMedium)
                            }
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
                .padding(innerPadding)
        ) {
            // Large Preview Area
            Box(
                modifier = Modifier
                    .weight(1.2f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                AvatarRenderer(
                    config = uiState.config,
                    modifier = Modifier.size(320.dp)
                )
            }

            // Customization Panel
            Surface(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            ) {
                Column(modifier = Modifier.padding(vertical = 16.dp)) {
                    CategoryTabs(
                        categories = uiState.categories,
                        selectedIndex = uiState.selectedCategoryIndex
                    ) { index ->
                        viewModel.onCategorySelected(index)
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    val currentCategory = uiState.categories.getOrNull(uiState.selectedCategoryIndex)?.id
                    
                    AnimatedContent(
                        targetState = currentCategory,
                        transitionSpec = {
                            fadeIn(animationSpec = tween(300)) togetherWith fadeOut(animationSpec = tween(300))
                        },
                        label = "panel_content"
                    ) { category ->
                        Box(modifier = Modifier.fillMaxSize()) {
                            when (category) {
                                "mood" -> ExpressionGrid(
                                    current = uiState.config.expression,
                                    onSelected = { expr -> viewModel.updateConfig { it.copy(expression = expr) } }
                                )
                                "hair" -> OptionGrid(
                                    options = (1..12).map { "hair_${it.toString().padStart(2, '0')}" },
                                    current = uiState.config.hairId,
                                    onSelected = { id -> viewModel.updateConfig { it.copy(hairId = id) } }
                                )
                                "head" -> OptionGrid(
                                    options = (1..6).map { "head_${it.toString().padStart(2, '0')}" },
                                    current = uiState.config.headId,
                                    onSelected = { id -> viewModel.updateConfig { it.copy(headId = id) } }
                                )
                                "outfit" -> OptionGrid(
                                    options = listOf("outfit_hoodie", "outfit_turtleneck", "outfit_jacket", "outfit_sweater"),
                                    current = uiState.config.outfitId,
                                    onSelected = { id -> viewModel.updateConfig { it.copy(outfitId = id) } }
                                )
                                "aura" -> ColorGrid(
                                    current = uiState.config.ambientGlow,
                                    onSelected = { color -> viewModel.updateConfig { it.copy(ambientGlow = color) } }
                                )
                                else -> PlaceholderGrid()
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CategoryTabs(
    categories: List<com.saurabh.artifact.model.AvatarCategory>,
    selectedIndex: Int,
    onCategorySelected: (Int) -> Unit
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        itemsIndexed(categories) { index, category ->
            val isSelected = index == selectedIndex
            Column(
                modifier = Modifier
                    .clickable { onCategorySelected(index) }
                    .padding(vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = category.label,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
                AnimatedVisibility(
                    visible = isSelected,
                    enter = scaleIn() + fadeIn(),
                    exit = scaleOut() + fadeOut()
                ) {
                    Box(
                        modifier = Modifier
                            .padding(top = 4.dp)
                            .size(width = 12.dp, height = 4.dp)
                            .background(MaterialTheme.colorScheme.primary, CircleShape)
                    )
                }
            }
        }
    }
}

@Composable
fun ExpressionGrid(
    current: AvatarExpression,
    onSelected: (AvatarExpression) -> Unit
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(AvatarExpression.entries) { expression ->
            val isSelected = current == expression
            Surface(
                modifier = Modifier
                    .height(56.dp)
                    .clickable { onSelected(expression) },
                shape = RoundedCornerShape(16.dp),
                color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                border = if (isSelected) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = expression.name.lowercase().replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
        }
    }
}

@Composable
fun OptionGrid(
    options: List<String>,
    current: String,
    onSelected: (String) -> Unit
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(options) { option ->
            val isSelected = current == option
            Surface(
                modifier = Modifier
                    .aspectRatio(1f)
                    .clickable { onSelected(option) },
                shape = RoundedCornerShape(16.dp),
                color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                border = if (isSelected) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            ) {
                // In a real app, this would show a small SVG preview
                Box(contentAlignment = Alignment.Center) {
                    Text(text = option.split("_").last(), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
fun ColorGrid(
    current: Long,
    onSelected: (Long) -> Unit
) {
    val colors = listOf(
        0xFFE0C3FC, 0xFFBDE0FE, 0xFFFFC8DD, 0xFFCAFFBF, 
        0xFFFDFFB6, 0xFFFFADAD, 0xFFD4A373, 0xFFCCD5AE
    )
    
    LazyVerticalGrid(
        columns = GridCells.Adaptive(56.dp),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        items(colors) { colorLong ->
            val isSelected = current == colorLong
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(Color(colorLong))
                    .clickable { onSelected(colorLong) }
                    .then(
                        if (isSelected) Modifier.border(
                            width = 3.dp,
                            color = MaterialTheme.colorScheme.primary,
                            shape = CircleShape
                        ) else Modifier
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (isSelected) {
                    Icon(
                        Icons.Rounded.AutoAwesome, 
                        contentDescription = null, 
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun PlaceholderGrid() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("Coming Soon", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
