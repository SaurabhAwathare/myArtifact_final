package com.saurabh.artifact.ui.components.base

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Standardized TextField for Artifact app.
 * Consistent focus colors, shapes, and typography.
 */
@Composable
fun AppTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    isError: Boolean = false,
    supportingText: String? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    leadingIcon: ImageVector? = null,
    singleLine: Boolean = true,
    isQuiet: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default
) {
    if (isQuiet) {
        TextField(
            value = value,
            onValueChange = onValueChange,
            modifier = modifier.fillMaxWidth(),
            placeholder = placeholder?.let { { Text(it, style = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))) } },
            singleLine = singleLine,
            textStyle = MaterialTheme.typography.bodyLarge.copy(lineHeight = androidx.compose.ui.unit.TextUnit.Unspecified),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                disabledContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent
            ),
            keyboardOptions = keyboardOptions
        )
    } else {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = modifier.fillMaxWidth(),
            label = { Text(label) },
            placeholder = placeholder?.let { { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)) } },
            isError = isError,
            supportingText = supportingText?.let { { Text(it) } },
            trailingIcon = trailingIcon,
            leadingIcon = leadingIcon?.let { { Icon(it, contentDescription = null) } },
            singleLine = singleLine,
            shape = MaterialTheme.shapes.medium,
            keyboardOptions = keyboardOptions,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                errorBorderColor = MaterialTheme.colorScheme.error
            )
        )
    }
}
