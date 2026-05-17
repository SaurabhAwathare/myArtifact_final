package com.saurabh.artifact.ui.transcription

import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.saurabh.artifact.model.TopicTag
import com.saurabh.artifact.model.TopicSuggestion

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TopicTaggingScreen(
    uiState: TopicTaggingUiState,
    onSearchChange: (String) -> Unit,
    onTopicToggle: (TopicTag) -> Unit,
    onAddCustom: (String) -> Unit,
    onPublish: () -> Unit,
    onBack: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("What is this about?", color = Color(0xFFF2E7D5)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.Close, contentDescription = "Back", tint = Color(0xFFF2E7D5))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF0D0D0D))
            )
        },
        bottomBar = {
            Button(
                onClick = onPublish,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFB300)),
                shape = RoundedCornerShape(28.dp),
                enabled = !uiState.isPublishing && uiState.selectedTopics.isNotEmpty()
            ) {
                if (uiState.isPublishing) {
                    CircularProgressIndicator(color = Color.Black, modifier = Modifier.size(24.dp))
                } else {
                    Text("Confirm & Publish", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            }
        },
        containerColor = Color(0xFF0D0D0D)
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(horizontal = 20.dp)
        ) {
            Spacer(modifier = Modifier.height(16.dp))
            
            Text(
                "Select topics that resonate with your reflection. This helps find kindred spirits.",
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFFBDBDBD)
            )
            
            Spacer(modifier = Modifier.height(24.dp))
            
            // Selected Topics
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                uiState.selectedTopics.forEach { topic ->
                    TopicChip(
                        topic = topic,
                        isSelected = true,
                        onClick = { onTopicToggle(topic) }
                    )
                }
            }
            
            if (uiState.selectedTopics.isNotEmpty()) {
                Spacer(modifier = Modifier.height(24.dp))
            }
            
            // Search Bar
            TopicSearchBar(
                query = uiState.searchQuery,
                onQueryChange = onSearchChange,
                onAddCustom = { onAddCustom(it) }
            )
            
            Spacer(modifier = Modifier.height(24.dp))
            
            // Suggestions or Search Results
            if (uiState.searchQuery.isNotEmpty()) {
                SearchResults(uiState.searchResults, onTopicToggle)
            } else {
                SuggestedTopicsSection(uiState.suggestions, uiState.selectedTopics, onTopicToggle)
            }
            
            // Moderation Warning
            uiState.moderationWarning?.let { warning ->
                Spacer(modifier = Modifier.height(16.dp))
                Surface(
                    color = Color(0xFFE57373).copy(alpha = 0.1f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = warning,
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFE57373)
                    )
                }
            }
        }
    }
}

@Composable
fun TopicChip(
    topic: TopicTag,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        color = if (isSelected) Color(0xFFFFB300) else Color(0xFF1A1A1A),
        shape = CircleShape,
        border = if (isSelected) null else BorderStroke(1.dp, Color(0xFF333333)),
        modifier = Modifier.animateContentSize()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = topic.label,
                style = MaterialTheme.typography.labelLarge,
                color = if (isSelected) Color.Black else Color(0xFFF2E7D5)
            )
            if (isSelected) {
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    Icons.Default.Close,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = Color.Black
                )
            }
        }
    }
}

@Composable
fun SuggestedTopicsSection(
    suggestions: List<TopicSuggestion>,
    selectedTopics: Set<TopicTag>,
    onTopicToggle: (TopicTag) -> Unit
) {
    Column {
        Text(
            "Suggested for you",
            style = MaterialTheme.typography.titleSmall,
            color = Color(0xFFFFB300)
        )
        Spacer(modifier = Modifier.height(12.dp))
        
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            suggestions.forEach { suggestion ->
                val topic = TopicTag(label = suggestion.label)
                val isSelected = selectedTopics.any { it.label == suggestion.label }
                TopicChip(
                    topic = topic,
                    isSelected = isSelected,
                    onClick = { onTopicToggle(topic) }
                )
            }
        }
    }
}

@Composable
fun TopicSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onAddCustom: (String) -> Unit
) {
    val focusManager = LocalFocusManager.current
    
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text("Search or add custom topic...") },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onAddCustom(query); focusManager.clearFocus() }) {
                    Icon(Icons.Default.Add, contentDescription = "Add Custom")
                }
            }
        },
        colors = OutlinedTextFieldDefaults.colors(
            unfocusedContainerColor = Color(0xFF1A1A1A),
            focusedContainerColor = Color(0xFF1A1A1A),
            unfocusedBorderColor = Color(0xFF333333),
            focusedBorderColor = Color(0xFFFFB300),
            cursorColor = Color(0xFFFFB300)
        ),
        shape = RoundedCornerShape(12.dp),
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { 
            if (query.isNotEmpty()) onAddCustom(query)
            focusManager.clearFocus() 
        })
    )
}

@Composable
fun SearchResults(
    results: List<TopicTag>,
    onTopicToggle: (TopicTag) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        results.forEach { topic ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onTopicToggle(topic) }
                    .padding(vertical = 12.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Search, contentDescription = null, tint = Color(0xFF666666), modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(16.dp))
                Text(topic.label, color = Color(0xFFF2E7D5))
            }
            HorizontalDivider(color = Color(0xFF1A1A1A))
        }
    }
}
