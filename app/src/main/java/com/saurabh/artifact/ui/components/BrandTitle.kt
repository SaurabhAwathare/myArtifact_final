package com.saurabh.artifact.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import com.saurabh.artifact.ui.theme.ArtifactTheme
import com.saurabh.artifact.ui.theme.GoldAura500

/**
 * Standardized branding for the application.
 * Displays "myArtifact" with specific typography requirements:
 * - "my": lowercase, italic, medium weight, warm accent color.
 * - "Artifact": bold, primary identity color.
 */
@Composable
fun BrandTitle(
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.displaySmall,
    colorOverride: Color? = null
) {
    Text(
        text = buildAnnotatedString {
            withStyle(
                SpanStyle(
                    fontStyle = FontStyle.Italic,
                    fontWeight = FontWeight.Medium,
                    color = GoldAura500
                )
            ) {
                append("my")
            }
            withStyle(
                SpanStyle(
                    fontWeight = FontWeight.Bold,
                    color = colorOverride ?: MaterialTheme.colorScheme.onBackground
                )
            ) {
                append("Artifact")
            }
        },
        style = style,
        modifier = modifier
    )
}

@Preview(showBackground = true, backgroundColor = 0xFF050505)
@Composable
fun BrandTitlePreview() {
    ArtifactTheme {
        BrandTitle()
    }
}
