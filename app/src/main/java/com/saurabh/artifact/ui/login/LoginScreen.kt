package com.saurabh.artifact.ui.login

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.saurabh.artifact.R
import com.saurabh.artifact.auth.CredentialResult
import com.saurabh.artifact.domain.auth.RegistrationResult
import com.saurabh.artifact.ui.components.HumanALogo
import com.saurabh.artifact.ui.theme.ArtifactTheme
import com.saurabh.artifact.ui.theme.GoldAura500
import com.saurabh.artifact.ui.theme.Obsidian950
import com.saurabh.artifact.ui.util.UiText
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(
    onLoginSuccess: (RegistrationResult) -> Unit,
    viewModel: LoginViewModel = hiltViewModel(),
) {
    val loginState by viewModel.loginState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val serverClientId = stringResource(R.string.default_web_client_id)

    LaunchedEffect(loginState) {
        (loginState as? LoginState.Success)?.let { state ->
            onLoginSuccess(state.result)
        }
    }

    LoginScreenContent(
        loginState = loginState,
        onGoogleSignInClick = {
            scope.launch {
                val result = viewModel.credentialHelper.getGoogleCredential(
                    context = context,
                    serverClientId = serverClientId,
                )
                when (result) {
                    is CredentialResult.Success -> {
                        viewModel.signInWithGoogle(result.idToken)
                    }
                    is CredentialResult.Failure -> {
                        viewModel.onError(result.message.asString(context))
                    }
                    is CredentialResult.Canceled -> {
                        // User swiped away, no need to show error
                    }
                }
            }
        },
        onTransferSessionClick = {
            viewModel.transferActiveSession()
        },
        onSignOutClick = {
            viewModel.signOut()
        },
        onRetryClick = {
            viewModel.retrySessionGate()
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreenContent(
    loginState: LoginState,
    onGoogleSignInClick: () -> Unit,
    onRetryClick: () -> Unit,
    modifier: Modifier = Modifier,
    onTransferSessionClick: () -> Unit = {},
    onSignOutClick: () -> Unit = {},
) {
    Scaffold(
        modifier = modifier,
        containerColor = Obsidian950,
    ) { innerPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            val usableHeight = maxHeight
            val topSpace = (usableHeight * 0.18f).coerceAtLeast(24.dp)
            val bottomSpace = (usableHeight * 0.10f).coerceAtLeast(24.dp)

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(modifier = Modifier.height(topSpace))

                // 1. Human A logo
                HumanALogo(
                    size = 108.dp,
                    isPulsing = false,
                )

                Spacer(modifier = Modifier.height(20.dp))

                // 2. Artifact title
                Text(
                    text = "Artifact",
                    style = MaterialTheme.typography.displayLarge,
                    color = GoldAura500,
                    textAlign = TextAlign.Center,
                )

                Spacer(modifier = Modifier.height(8.dp))

                // 3. Subtitle
                Text(
                    text = stringResource(R.string.login_tagline),
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center,
                )

                Spacer(modifier = Modifier.height(48.dp))

                // 4. Primary action / Conflict UI
                if (loginState is LoginState.Loading) {
                    Box(
                        modifier = Modifier.height(56.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(color = GoldAura500)
                    }
                } else if (loginState is LoginState.Conflict) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .fillMaxWidth(0.9f)
                            .widthIn(max = 340.dp)
                            .padding(vertical = 8.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.session_conflict_title),
                            style = MaterialTheme.typography.titleLarge,
                            color = Color.White,
                            textAlign = TextAlign.Center,
                            fontWeight = FontWeight.Bold,
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        Text(
                            text = stringResource(R.string.session_conflict_description, loginState.activeDeviceName),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.8f),
                            textAlign = TextAlign.Center,
                        )

                        Spacer(modifier = Modifier.height(24.dp))

                        Button(
                            onClick = onTransferSessionClick,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = GoldAura500,
                                contentColor = Obsidian950,
                            ),
                            shape = RoundedCornerShape(25.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.action_transfer_session),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center,
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Button(
                            onClick = onSignOutClick,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color.White.copy(alpha = 0.12f),
                                contentColor = Color.White,
                            ),
                            shape = RoundedCornerShape(25.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.action_sign_out),
                                style = MaterialTheme.typography.titleSmall,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                } else {
                    Button(
                        onClick = onGoogleSignInClick,
                        modifier = Modifier
                            .fillMaxWidth(0.85f)
                            .widthIn(max = 320.dp)
                            .height(56.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = GoldAura500,
                            contentColor = Obsidian950,
                        ),
                        shape = RoundedCornerShape(28.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.login_continue_google),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                        )
                    }
                }

                if (loginState is LoginState.Error) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = loginState.message.asString(),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth(0.85f)
                            .widthIn(max = 320.dp),
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = onRetryClick,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = GoldAura500,
                            contentColor = Obsidian950,
                        ),
                        shape = RoundedCornerShape(20.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.action_retry),
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(28.dp))

                // 5. Privacy note
                Text(
                    text = stringResource(R.string.login_privacy_note),
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                    ),
                    color = Color.White.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center,
                )

                Spacer(modifier = Modifier.height(28.dp))

                // 6. Legal footer
                LegalFooter()

                Spacer(modifier = Modifier.height(bottomSpace))
            }
        }
    }
}

@Composable
private fun LegalFooter(
    modifier: Modifier = Modifier,
) {
    val uriHandler = LocalUriHandler.current
    val privacyUrl = stringResource(R.string.privacy_policy_url)
    val termsUrl = stringResource(R.string.terms_of_service_url)

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.legal_acknowledgment),
            style = MaterialTheme.typography.bodySmall.copy(
                fontSize = 12.sp,
                lineHeight = 16.sp,
            ),
            color = Color.White.copy(alpha = 0.45f),
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(0.88f),
        )

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.privacy_policy),
                style = MaterialTheme.typography.labelMedium,
                color = GoldAura500.copy(alpha = 0.8f),
                textDecoration = TextDecoration.Underline,
                modifier = Modifier
                    .padding(vertical = 12.dp, horizontal = 8.dp)
                    .clickable {
                        try {
                            uriHandler.openUri(privacyUrl)
                        } catch (_: Exception) {
                            // Handle browser not found or invalid URL
                        }
                    },
            )

            Text(
                text = "|",
                color = Color.White.copy(alpha = 0.2f),
            )

            Text(
                text = stringResource(R.string.terms_of_service),
                style = MaterialTheme.typography.labelMedium,
                color = GoldAura500.copy(alpha = 0.8f),
                textDecoration = TextDecoration.Underline,
                modifier = Modifier
                    .padding(vertical = 12.dp, horizontal = 8.dp)
                    .clickable {
                        try {
                            uriHandler.openUri(termsUrl)
                        } catch (_: Exception) {
                            // Handle browser not found or invalid URL
                        }
                    },
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF050505)
@Composable
fun LoginScreenPreview() {
    ArtifactTheme {
        LoginScreenContent(
            loginState = LoginState.Idle,
            onGoogleSignInClick = {},
            onRetryClick = {},
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF050505)
@Composable
fun LoginScreenLoadingPreview() {
    ArtifactTheme {
        LoginScreenContent(
            loginState = LoginState.Loading,
            onGoogleSignInClick = {},
            onRetryClick = {},
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF050505)
@Composable
fun LoginScreenErrorPreview() {
    ArtifactTheme {
        LoginScreenContent(
            loginState = LoginState.Error(UiText.DynamicString("Failed to sign in. Please try again.")),
            onGoogleSignInClick = {},
            onRetryClick = {},
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF050505)
@Composable
fun LoginScreenConflictPreview() {
    ArtifactTheme {
        LoginScreenContent(
            loginState = LoginState.Conflict("Pixel 9 Pro"),
            onGoogleSignInClick = {},
            onRetryClick = {},
        )
    }
}
