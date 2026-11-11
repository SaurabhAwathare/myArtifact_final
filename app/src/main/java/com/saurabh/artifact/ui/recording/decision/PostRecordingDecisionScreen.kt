package com.saurabh.artifact.ui.recording.decision

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.saurabh.artifact.R
import com.saurabh.artifact.ui.theme.ActionDestructive
import com.saurabh.artifact.ui.theme.ArtifactTheme
import com.saurabh.artifact.ui.theme.CardShape
import com.saurabh.artifact.ui.theme.GoldAura400
import com.saurabh.artifact.ui.theme.GoldAura500
import com.saurabh.artifact.ui.theme.ManropeFont
import com.saurabh.artifact.ui.theme.MistGray
import com.saurabh.artifact.ui.theme.ModalShape
import com.saurabh.artifact.ui.theme.Obsidian700
import com.saurabh.artifact.ui.theme.Obsidian800
import com.saurabh.artifact.ui.theme.Obsidian900
import com.saurabh.artifact.ui.theme.Obsidian950
import com.saurabh.artifact.ui.theme.ReflectionWhite
import com.saurabh.artifact.ui.theme.StatusError

/**
 * Stateful entry-point for the Post-Recording Decision screen (Stage 3.6.3b).
 */
@Composable
fun PostRecordingDecisionScreen(
    draftId: String,
    onReview: (String) -> Unit,
    onSaveAsDraft: () -> Unit,
    onDiscardSuccess: () -> Unit = onSaveAsDraft,
    viewModel: PostRecordingDecisionViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(viewModel.events) {
        viewModel.events.collect { event ->
            when (event) {
                is PostRecordingDecisionEvent.DiscardSuccess -> {
                    onDiscardSuccess()
                }
            }
        }
    }

    PostRecordingDecisionContent(
        draftId = draftId,
        uiState = uiState,
        onReview = onReview,
        onSaveAsDraft = onSaveAsDraft,
        onDiscardClicked = viewModel::onDiscardClicked,
        onDismissDiscardDialog = viewModel::onDismissDiscardDialog,
        onConfirmDiscard = viewModel::onConfirmDiscard,
        onDismissError = viewModel::clearErrorMessage
    )
}

/**
 * Stateless content for the Post-Recording Decision screen.
 * Supports narrow device widths (e.g. 320dp), large system font scaling up to 200%,
 * and full TalkBack screen-reader accessibility.
 */
@Composable
fun PostRecordingDecisionContent(
    draftId: String,
    uiState: PostRecordingDecisionUiState,
    onReview: (String) -> Unit,
    onSaveAsDraft: () -> Unit,
    onDiscardClicked: () -> Unit,
    onDismissDiscardDialog: () -> Unit,
    onConfirmDiscard: (String) -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()

    Scaffold(
        containerColor = Obsidian950,
        modifier = modifier.fillMaxSize()
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(scrollState),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                // 1. REASSURING CONFIRMATION BADGE
                val savedIconCd = stringResource(R.string.cd_post_recording_saved_icon)
                Surface(
                    shape = CircleShape,
                    color = GoldAura500.copy(alpha = 0.12f),
                    modifier = Modifier
                        .size(72.dp)
                        .semantics { contentDescription = savedIconCd }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Rounded.CheckCircle,
                            contentDescription = null,
                            tint = GoldAura500,
                            modifier = Modifier.size(40.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // 2. HEADLINE (CALM & FACTUAL CONFIRMATION)
                Text(
                    text = stringResource(R.string.post_recording_headline),
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontFamily = ManropeFont,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 26.sp,
                        letterSpacing = (-0.5).sp
                    ),
                    color = ReflectionWhite,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(12.dp))

                // 3. SUBTEXT
                Text(
                    text = stringResource(R.string.post_recording_subtext),
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = ManropeFont,
                        fontWeight = FontWeight.Normal,
                        lineHeight = 22.sp
                    ),
                    color = MistGray,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(28.dp))

                // 4. PERSISTENT DRAFT DURABILITY NOTICE
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics(mergeDescendants = true) {},
                    shape = CardShape,
                    color = Obsidian900,
                    border = BorderStroke(1.dp, Obsidian800)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Info,
                            contentDescription = null,
                            tint = GoldAura400,
                            modifier = Modifier
                                .size(20.dp)
                                .padding(top = 2.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = stringResource(R.string.post_recording_durability_notice),
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = ManropeFont,
                                fontWeight = FontWeight.Normal,
                                fontSize = 13.sp,
                                lineHeight = 19.sp
                            ),
                            color = MistGray
                        )
                    }
                }

                Spacer(modifier = Modifier.height(32.dp))

                // Error banner (if cleanup failed)
                if (uiState.errorMessage != null) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 16.dp)
                            .clickable { onDismissError() }
                            .semantics(mergeDescendants = true) {},
                        shape = CardShape,
                        color = StatusError.copy(alpha = 0.15f),
                        border = BorderStroke(1.dp, StatusError.copy(alpha = 0.4f))
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.WarningAmber,
                                contentDescription = null,
                                tint = StatusError,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = uiState.errorMessage,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = ManropeFont,
                                    fontWeight = FontWeight.Normal
                                ),
                                color = ReflectionWhite,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                // 5. PRIMARY ACTION: REVIEW NOW
                val reviewCd = stringResource(R.string.cd_post_recording_review)
                Button(
                    onClick = { onReview(draftId) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp)
                        .semantics { contentDescription = reviewCd },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = GoldAura500,
                        contentColor = Obsidian950
                    ),
                    shape = CardShape
                ) {
                    Text(
                        text = stringResource(R.string.post_recording_action_review),
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontFamily = ManropeFont,
                            fontWeight = FontWeight.SemiBold
                        )
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                // 6. SECONDARY ACTION: SAVE FOR LATER
                val saveLaterCd = stringResource(R.string.cd_post_recording_save_later)
                OutlinedButton(
                    onClick = onSaveAsDraft,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp)
                        .semantics { contentDescription = saveLaterCd },
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = ReflectionWhite
                    ),
                    border = BorderStroke(1.dp, Obsidian700),
                    shape = CardShape
                ) {
                    Text(
                        text = stringResource(R.string.post_recording_action_save_later),
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontFamily = ManropeFont,
                            fontWeight = FontWeight.Medium
                        )
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // 7. TERTIARY ACTION: DISCARD RECORDING
                val discardCd = stringResource(R.string.cd_post_recording_discard)
                TextButton(
                    onClick = onDiscardClicked,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .semantics { contentDescription = discardCd },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = ActionDestructive.copy(alpha = 0.85f)
                    )
                ) {
                    Text(
                        text = stringResource(R.string.post_recording_action_discard),
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = ManropeFont,
                            fontWeight = FontWeight.Medium
                        )
                    )
                }
            }
        }
    }

    // 8. EXPLICIT DISCARD CONFIRMATION DIALOG
    if (uiState.showDiscardDialog) {
        AlertDialog(
            onDismissRequest = {
                if (!uiState.isDeleting) {
                    onDismissDiscardDialog()
                }
            },
            shape = ModalShape,
            containerColor = Obsidian900,
            icon = {
                Icon(
                    imageVector = Icons.Rounded.DeleteOutline,
                    contentDescription = null,
                    tint = ActionDestructive,
                    modifier = Modifier.size(28.dp)
                )
            },
            title = {
                Text(
                    text = stringResource(R.string.post_recording_discard_dialog_title),
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontFamily = ManropeFont,
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = ReflectionWhite,
                    textAlign = TextAlign.Center
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.post_recording_discard_dialog_message),
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = ManropeFont
                    ),
                    color = MistGray,
                    textAlign = TextAlign.Center,
                    lineHeight = 22.sp
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { onConfirmDiscard(draftId) },
                    enabled = !uiState.isDeleting,
                    modifier = Modifier
                        .defaultMinSize(minHeight = 48.dp)
                        .semantics {
                            contentDescription = "Confirm discard recording"
                        }
                ) {
                    if (uiState.isDeleting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = ActionDestructive
                        )
                    } else {
                        Text(
                            text = stringResource(R.string.post_recording_discard_dialog_action_discard),
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontFamily = ManropeFont,
                                fontWeight = FontWeight.SemiBold
                            ),
                            color = ActionDestructive
                        )
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = onDismissDiscardDialog,
                    enabled = !uiState.isDeleting,
                    modifier = Modifier
                        .defaultMinSize(minHeight = 48.dp)
                        .semantics {
                            contentDescription = "Keep recording and dismiss dialog"
                        }
                ) {
                    Text(
                        text = stringResource(R.string.post_recording_discard_dialog_action_keep),
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontFamily = ManropeFont
                        ),
                        color = MistGray
                    )
                }
            }
        )
    }
}

@Preview
@Composable
fun PostRecordingDecisionScreenPreview() {
    ArtifactTheme {
        PostRecordingDecisionContent(
            draftId = "preview_id",
            uiState = PostRecordingDecisionUiState(),
            onReview = {},
            onSaveAsDraft = {},
            onDiscardClicked = {},
            onDismissDiscardDialog = {},
            onConfirmDiscard = {},
            onDismissError = {}
        )
    }
}

@Preview
@Composable
fun PostRecordingDecisionScreenDialogPreview() {
    ArtifactTheme {
        PostRecordingDecisionContent(
            draftId = "preview_id",
            uiState = PostRecordingDecisionUiState(showDiscardDialog = true),
            onReview = {},
            onSaveAsDraft = {},
            onDiscardClicked = {},
            onDismissDiscardDialog = {},
            onConfirmDiscard = {},
            onDismissError = {}
        )
    }
}
