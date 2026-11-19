package com.saurabh.artifact.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.saurabh.artifact.R
import com.saurabh.artifact.ui.splash.AnimatedWaveform
import com.saurabh.artifact.ui.theme.GoldAura500
import com.saurabh.artifact.ui.theme.Obsidian950
import com.saurabh.artifact.ui.components.HumanALogo
import androidx.compose.ui.tooling.preview.Preview
import com.saurabh.artifact.ui.theme.ArtifactTheme
import kotlinx.coroutines.launch

@Composable
fun OnboardingScreen(
    onOnboardingFinished: () -> Unit
) {
    val pagerState = rememberPagerState(pageCount = { 3 })
    val scope = rememberCoroutineScope()

    // Handle system back navigation to go to previous onboarding page
    BackHandler(enabled = pagerState.currentPage > 0) {
        scope.launch {
            pagerState.animateScrollToPage(pagerState.currentPage - 1)
        }
    }

    Scaffold(
        containerColor = Obsidian950
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f),
                userScrollEnabled = true // Explicitly allowing swipe
            ) { page ->
                when (page) {
                    0 -> OnboardingPage1()
                    1 -> OnboardingPage2()
                    2 -> OnboardingPage3()
                }
            }

            OnboardingFooter(
                currentPage = pagerState.currentPage,
                onContinue = {
                    // Guard against rapid double-taps skipping pages
                    if (pagerState.isScrollInProgress) return@OnboardingFooter
                    
                    if (pagerState.currentPage < 2) {
                        scope.launch {
                            pagerState.animateScrollToPage(pagerState.currentPage + 1)
                        }
                    } else {
                        onOnboardingFinished()
                    }
                },
                onSkip = onOnboardingFinished
            )
        }
    }
}

@Composable
fun OnboardingPage1() {
    BoxWithConstraints(
        modifier = Modifier.fillMaxSize()
    ) {
        val isShortHeight = maxHeight < 500.dp
        val isCompact = maxWidth < 360.dp || maxHeight < 640.dp

        val heroSize = when {
            isShortHeight -> 120.dp
            isCompact -> 150.dp
            else -> 200.dp
        }
        val spacerTop = when {
            isShortHeight -> 16.dp
            isCompact -> 24.dp
            else -> 48.dp
        }
        val spacerBetween = if (isShortHeight) 8.dp else 16.dp

        val titleStyle = if (isShortHeight || isCompact) {
            MaterialTheme.typography.headlineMedium
        } else {
            MaterialTheme.typography.displayMedium
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = if (isShortHeight) 12.dp else 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = if (isShortHeight) Arrangement.Top else Arrangement.Center
        ) {
            // Hero Section
            Box(
                modifier = Modifier
                    .size(heroSize)
                    .clip(CircleShape)
                    .background(
                        brush = Brush.radialGradient(
                            colors = listOf(GoldAura500.copy(alpha = 0.1f), Color.Transparent)
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                AnimatedWaveform()
            }

            Spacer(modifier = Modifier.height(spacerTop))

            Text(
                text = stringResource(R.string.onboarding_safe_space_title),
                style = titleStyle,
                color = Color.White,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(spacerBetween))

            Text(
                text = stringResource(R.string.onboarding_safe_space_desc),
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White.copy(alpha = 0.7f),
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
fun OnboardingPage2() {
    BoxWithConstraints(
        modifier = Modifier.fillMaxSize()
    ) {
        val isShortHeight = maxHeight < 500.dp
        val isCompact = maxWidth < 360.dp || maxHeight < 640.dp

        val logoSize = when {
            isShortHeight -> 64.dp
            isCompact -> 80.dp
            else -> 100.dp
        }
        val spacerTop = when {
            isShortHeight -> 16.dp
            isCompact -> 24.dp
            else -> 48.dp
        }
        val spacerBetween = if (isShortHeight) 8.dp else 16.dp

        val titleStyle = if (isShortHeight || isCompact) {
            MaterialTheme.typography.headlineMedium
        } else {
            MaterialTheme.typography.displayMedium
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = if (isShortHeight) 12.dp else 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = if (isShortHeight) Arrangement.Top else Arrangement.Center
        ) {
            HumanALogo(
                size = logoSize,
                isPulsing = false // Static focus for identity concept
            )

            Spacer(modifier = Modifier.height(spacerTop))

            Text(
                text = stringResource(R.string.onboarding_identity_title),
                style = titleStyle,
                color = Color.White,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(spacerBetween))

            Text(
                text = stringResource(R.string.onboarding_identity_desc),
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White.copy(alpha = 0.7f),
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
fun OnboardingPage3() {
    BoxWithConstraints(
        modifier = Modifier.fillMaxSize()
    ) {
        val isShortHeight = maxHeight < 500.dp
        val isCompact = maxWidth < 360.dp || maxHeight < 640.dp

        val heroHeight = when {
            isShortHeight -> 120.dp
            isCompact -> 150.dp
            else -> 200.dp
        }
        val spacerTop = when {
            isShortHeight -> 16.dp
            isCompact -> 24.dp
            else -> 48.dp
        }
        val spacerBetween = if (isShortHeight) 8.dp else 16.dp

        val titleStyle = if (isShortHeight || isCompact) {
            MaterialTheme.typography.headlineMedium
        } else {
            MaterialTheme.typography.displayMedium
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = if (isShortHeight) 12.dp else 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = if (isShortHeight) Arrangement.Top else Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(heroHeight),
                contentAlignment = Alignment.Center
            ) {
                AnimatedWaveform()
            }

            Spacer(modifier = Modifier.height(spacerTop))

            Text(
                text = stringResource(R.string.onboarding_voice_title),
                style = titleStyle,
                color = Color.White,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(spacerBetween))

            Text(
                text = stringResource(R.string.onboarding_voice_desc),
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White.copy(alpha = 0.7f),
                textAlign = TextAlign.Center
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF050505)
@Composable
fun OnboardingPage2Preview() {
    ArtifactTheme {
        OnboardingPage2()
    }
}

@Composable
fun OnboardingFooter(
    currentPage: Int,
    onContinue: () -> Unit,
    onSkip: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Button(
            onClick = onContinue,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = GoldAura500,
                contentColor = Obsidian950
            ),
            shape = RoundedCornerShape(28.dp)
        ) {
            Text(
                text = if (currentPage == 2) "Enter Artifact" else "Continue",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }

        if (currentPage < 2) {
            TextButton(
                onClick = onSkip,
                modifier = Modifier.defaultMinSize(minHeight = 48.dp)
            ) {
                Text(
                    text = stringResource(R.string.onboarding_skip),
                    color = Color.White.copy(alpha = 0.5f),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        } else {
            Spacer(modifier = Modifier.height(48.dp))
        }
    }
}
