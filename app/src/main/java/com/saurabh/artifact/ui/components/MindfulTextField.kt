package com.saurabh.artifact.ui.components

import android.util.Log
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.saurabh.artifact.ui.theme.EmberGlow
import com.saurabh.artifact.ui.components.motion.MotionTokens

/**
 * MindfulTextField - A journaling-focused text input.
 * No heavy borders, just a breathing glow and intimate focus.
 */
@Composable
fun MindfulTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None
) {
    Log.d("COMMENT_FOCUS", "MindfulTextField: enabled=$enabled valueLength=${value.length}")
    SideEffect {
        Log.d("COMMENT_FOCUS", "MindfulTextField: Recomposition")
    }

    var isFocused by remember { mutableStateOf(false) }
    
    val underlineColor by animateColorAsState(
        targetValue = if (isFocused) EmberGlow else Color.White.copy(alpha = 0.12f),
        animationSpec = tween(MotionTokens.DURATION_MEDIUM, easing = MotionTokens.CalmEasing),
        label = "UnderlineColor"
    )

    Box(modifier = modifier
        .padding(vertical = 8.dp)
        .onGloballyPositioned { coordinates ->
            Log.d("COMMENT_FOCUS", "MindfulTextField: size=${coordinates.size}")
        }
        .pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    Log.d("COMMENT_FOCUS", "MindfulTextField: PointerEvent: type=${event.type}")
                }
            }
        }
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { 
                    Log.d("COMMENT_FOCUS", "MindfulTextField: onFocusChanged: isFocused=${it.isFocused} hasFocus=${it.hasFocus}")
                    isFocused = it.isFocused 
                }
                .padding(bottom = 8.dp),
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = Color.White),
            cursorBrush = SolidColor(EmberGlow),
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            visualTransformation = visualTransformation,
            decorationBox = { innerTextField ->
                Box {
                    if (value.isEmpty()) {
                        Text(
                            text = placeholder,
                            style = MaterialTheme.typography.bodyLarge,
                            color = Color.White.copy(alpha = 0.3f)
                        )
                    }
                    innerTextField()
                }
            }
        )
        
        HorizontalDivider(
            modifier = Modifier.padding(top = 40.dp), // Adjust based on font size
            thickness = if (isFocused) 1.5.dp else 1.dp,
            color = underlineColor
        )
    }
}
