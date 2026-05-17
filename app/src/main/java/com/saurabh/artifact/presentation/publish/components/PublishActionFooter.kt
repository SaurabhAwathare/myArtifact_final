package com.saurabh.artifact.presentation.publish.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun PublishActionFooter(
    onPublishClick: () -> Unit,
    onReviewClick: () -> Unit,
    isPublishEnabled: Boolean,
    modifier: Modifier = Modifier,
    accentColor: Color = Color(0xFFFFBF00)
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Button(
            onClick = onPublishClick,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            enabled = isPublishEnabled,
            shape = RoundedCornerShape(28.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = accentColor,
                contentColor = Color.Black,
                disabledContainerColor = accentColor.copy(alpha = 0.3f)
            )
        ) {
            Text(text = "Publish Artifact", style = MaterialTheme.typography.titleMedium)
        }
        
        Spacer(modifier = Modifier.height(12.dp))
        
        Button(
            onClick = onReviewClick,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(28.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.Transparent,
                contentColor = Color.White
            )
        ) {
            Text(text = "Review Transcript Again", style = MaterialTheme.typography.bodyMedium)
        }
    }
}
