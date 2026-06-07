package com.saurabh.artifact.ui.login

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.NoCredentialException
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.saurabh.artifact.R
import com.saurabh.artifact.ui.theme.GoldAura500
import com.saurabh.artifact.ui.theme.Obsidian950
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(
    onLoginSuccess: () -> Unit,
    viewModel: LoginViewModel = hiltViewModel()
) {
    val loginState by viewModel.loginState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val serverClientId = stringResource(R.string.default_web_client_id)
    val credentialManager = remember { CredentialManager.create(context) }

    LaunchedEffect(loginState) {
        if (loginState is LoginState.Success) {
            onLoginSuccess()
        }
    }

    Scaffold(
        containerColor = Obsidian950
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "myArtifact",
                style = MaterialTheme.typography.displayLarge,
                color = GoldAura500
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                text = "Protected thoughts. Private identity.",
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White.copy(alpha = 0.7f)
            )

            Spacer(modifier = Modifier.height(64.dp))

            if (loginState is LoginState.Loading) {
                CircularProgressIndicator(color = GoldAura500)
            } else {
                Button(
                    onClick = {
                        scope.launch {
                            launchGoogleSignIn(
                                context = context,
                                credentialManager = credentialManager,
                                serverClientId = serverClientId,
                                onTokenReceived = { token ->
                                    viewModel.signInWithGoogle(token)
                                },
                                onError = { message ->
                                    viewModel.onError(message)
                                }
                            )
                        }
                    },
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
                        text = "Continue with Google",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            if (loginState is LoginState.Error) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = (loginState as LoginState.Error).message.asString(),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center
                )
            }

            Spacer(modifier = Modifier.height(48.dp))

            Text(
                text = "Your identity stays private through generated nicknames.",
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.5f),
                textAlign = TextAlign.Center
            )
        }
    }
}

suspend fun launchGoogleSignIn(
    context: android.content.Context,
    credentialManager: CredentialManager,
    serverClientId: String,
    onTokenReceived: (String) -> Unit,
    onError: (String) -> Unit
) {
    try {
        val googleIdOption = GetGoogleIdOption.Builder()
            .setServerClientId(serverClientId)
            .setFilterByAuthorizedAccounts(false)
            .build()

        val request = GetCredentialRequest.Builder()
            .addCredentialOption(googleIdOption)
            .build()

        val result = credentialManager.getCredential(
            context = context,
            request = request
        )

        val credential = result.credential

        if (
            credential is CustomCredential &&
            credential.type ==
            GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
        ) {
            val googleCredential =
                GoogleIdTokenCredential.createFrom(
                    credential.data
                )

            onTokenReceived(
                googleCredential.idToken
            )
        } else {
            onError("Google credential unavailable")
        }
    } catch (_: NoCredentialException) {
        onError("No accounts found. Please sign in to your Google account first.")
    } catch (e: Exception) {
        onError(
            e.message ?: "Google sign-in failed"
        )
    }
}
