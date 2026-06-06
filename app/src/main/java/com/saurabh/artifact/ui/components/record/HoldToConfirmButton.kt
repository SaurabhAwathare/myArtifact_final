package com.saurabh.artifact.ui.components.record

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun HoldToConfirmButton(
    text: String,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    holdDuration: Duration = 2.seconds
) {
    var isHolding by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()

    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        label = "holdProgress"
    )

    Surface(
        modifier = modifier
            .height(56.dp)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(
                    onPress = {
                        isHolding = true
                        progress = 0f
                        val startTime = System.currentTimeMillis()
                        val holdJob = scope.launch {
                            val durationMs = holdDuration.inWholeMilliseconds
                            while (isHolding && progress < 1f) {
                                val elapsed = System.currentTimeMillis() - startTime
                                progress = (elapsed.toFloat() / durationMs).coerceIn(0f, 1f)
                                if (progress >= 1f) {
                                    onConfirm()
                                    isHolding = false
                                }
                                delay(16.milliseconds)
                            }
                        }
                        try {
                            awaitRelease()
                        } finally {
                            isHolding = false
                            if (progress < 1f) {
                                progress = 0f
                            }
                            holdJob.cancel()
                        }
                    }
                )
            },
        shape = CircleShape,
        color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (enabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    ) {
        Box(contentAlignment = Alignment.Center) {
            // Background Progress Fill
            LinearProgressIndicator(
                progress = { animatedProgress },
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                trackColor = Color.Transparent
            )

            Text(
                text = if (isHolding) "Keep holding..." else text,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 24.dp)
            )
        }
    }
}

@Composable
@androidx.compose.ui.tooling.preview.Preview
fun HoldToConfirmButtonPreview() {
    MaterialTheme {
        Box(modifier = Modifier.padding(16.dp)) {
            HoldToConfirmButton(
                text = "Hold to Delete",
                onConfirm = { println("Confirmed!") }
            )
        }
    }
}
