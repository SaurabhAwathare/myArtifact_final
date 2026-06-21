package com.saurabh.artifact.ui.identity

import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.saurabh.artifact.ui.components.ArtifactAvatar
import com.saurabh.artifact.ui.theme.GoldAura500
import com.saurabh.artifact.ui.theme.Obsidian950
import com.saurabh.artifact.util.UsernameGenerator

@Composable
fun IdentityRevealScreen(
    onContinue: () -> Unit,
    viewModel: IdentityRevealViewModel = hiltViewModel()
) {
    val userProfile by viewModel.userProfile.collectAsStateWithLifecycle()
    
    // Animation states
    var startAnimations by remember { mutableStateOf(false) }
    
    LaunchedEffect(userProfile) {
        if (userProfile != null) {
            startAnimations = true
        }
    }

    val alpha by animateFloatAsState(
        targetValue = if (startAnimations) 1f else 0f,
        animationSpec = tween(durationMillis = 1000, easing = LinearOutSlowInEasing),
        label = "alpha"
    )
    
    val scale by animateFloatAsState(
        targetValue = if (startAnimations) 1f else 0.8f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "scale"
    )

    Scaffold(
        containerColor = Obsidian950
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Meet Your Artifact",
                    style = MaterialTheme.typography.headlineMedium,
                    color = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.alpha(alpha)
                )

                Spacer(modifier = Modifier.height(48.dp))

                Box(
                    modifier = Modifier
                        .size(200.dp)
                        .scale(scale)
                        .alpha(alpha),
                    contentAlignment = Alignment.Center
                ) {
                    userProfile?.let { user ->
                        ArtifactAvatar(
                            config = user.avatarConfig,
                            size = 200.dp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(32.dp))

                userProfile?.let { user ->
                    Text(
                        text = user.anonymousName,
                        style = MaterialTheme.typography.displaySmall,
                        color = GoldAura500,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.alpha(alpha)
                    )
                    
                    Text(
                        text = "· ${user.anonymousSigil}",
                        style = MaterialTheme.typography.headlineSmall,
                        color = GoldAura500.copy(alpha = 0.5f),
                        modifier = Modifier.alpha(alpha)
                    )
                }

                Spacer(modifier = Modifier.height(64.dp))

                Text(
                    text = "Your Artifact identity protects your privacy,\nso you can share more honestly.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White.copy(alpha = 0.5f),
                    textAlign = TextAlign.Center,
                    lineHeight = 24.sp,
                    modifier = Modifier.alpha(alpha)
                )

                Spacer(modifier = Modifier.height(48.dp))

                Button(
                    onClick = onContinue,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .alpha(alpha),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = GoldAura500,
                        contentColor = Obsidian950
                    ),
                    shape = RoundedCornerShape(28.dp)
                ) {
                    Text(
                        text = "Step Into Artifact",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
