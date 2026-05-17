package com.saurabh.artifact.presentation.publish.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun HoldToPublishButton(
    onProgress: (Float) -> Unit,
    onComplete: () -> Unit,
    modifier: Modifier = Modifier
) {
    var progress by remember { mutableStateOf(0f) }
    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy)
    )
    
    val scope = rememberCoroutineScope()
    var holdJob by remember { mutableStateOf<Job?>(null) }

    Box(
        modifier = modifier
            .size(160.dp)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        holdJob = scope.launch {
                            val startTime = System.currentTimeMillis()
                            while (progress < 1f) {
                                val elapsed = System.currentTimeMillis() - startTime
                                progress = (elapsed / 3000f).coerceAtMost(1f)
                                onProgress(progress)
                                delay(16)
                            }
                            onComplete()
                        }
                        tryAwaitRelease()
                        holdJob?.cancel()
                        // Slow drain if released early
                        scope.launch {
                            while (progress > 0f) {
                                progress = (progress - 0.05f).coerceAtLeast(0f)
                                onProgress(progress)
                                delay(16)
                            }
                        }
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        // Background Circle
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawCircle(color = Color.White.copy(alpha = 0.1f))
            drawArc(
                color = Color.White,
                startAngle = -90f,
                sweepAngle = 360f * animatedProgress,
                useCenter = false,
                style = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round)
            )
        }
        
        Text(
            text = if (progress >= 1f) "Released" else "Hold to Share",
            style = MaterialTheme.typography.titleMedium,
            color = Color.White
        )
    }
}
