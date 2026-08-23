package com.saurabh.artifact.navigation.features

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.saurabh.artifact.diagnostics.DiagnosticCategory
import com.saurabh.artifact.diagnostics.DiagnosticLogger
import com.saurabh.artifact.domain.auth.RegistrationResult
import com.saurabh.artifact.navigation.Login
import com.saurabh.artifact.navigation.Onboarding
import com.saurabh.artifact.navigation.IdentityReveal
import com.saurabh.artifact.navigation.MnemonicReveal
import com.saurabh.artifact.navigation.Home
import com.saurabh.artifact.ui.login.LoginScreen
import com.saurabh.artifact.ui.onboarding.OnboardingScreen
import com.saurabh.artifact.ui.identity.IdentityRevealScreen
import com.saurabh.artifact.ui.identity.MnemonicRevealScreen
import com.saurabh.artifact.util.OnboardingManager
import kotlinx.coroutines.launch

fun NavGraphBuilder.authNavigation(
    navController: NavHostController,
    onboardingManager: OnboardingManager,
    diagnosticLogger: DiagnosticLogger
) {
    composable<Onboarding> {
        val scope = rememberCoroutineScope()
        val onOnboardingFinished = remember(navController, onboardingManager, scope) {
            {
                scope.launch {
                    onboardingManager.setOnboardingCompleted(emptySet())
                    navController.navigate(Login) {
                        popUpTo(Onboarding) { inclusive = true }
                    }
                }
                Unit
            }
        }
        OnboardingScreen(onOnboardingFinished = onOnboardingFinished)
    }

    composable<Login> {
        val onLoginSuccess = remember(navController) {
            { result: RegistrationResult ->
                diagnosticLogger.info(DiagnosticCategory.AUTH, "LOGIN_SUCCESS", mapOf("result" to result.javaClass.simpleName))
                when (result) {
                    is RegistrationResult.SuccessNewUser -> {
                        navController.navigate(IdentityReveal) {
                            popUpTo(Login) { inclusive = true }
                        }
                    }
                    is RegistrationResult.SuccessExistingUser -> {
                        navController.navigate(Home) {
                            popUpTo(Login) { inclusive = true }
                        }
                    }
                    is RegistrationResult.Failure -> {
                        // Should be handled in LoginScreen/LoginViewModel already
                    }
                }
            }
        }
        LoginScreen(onLoginSuccess = onLoginSuccess)
    }

    composable<IdentityReveal> {
        IdentityRevealScreen(
            onContinue = {
                navController.navigate(MnemonicReveal) {
                    popUpTo(IdentityReveal) { inclusive = true }
                }
            }
        )
    }

    composable<MnemonicReveal> {
        MnemonicRevealScreen(
            onComplete = {
                navController.navigate(Home) {
                    popUpTo(MnemonicReveal) { inclusive = true }
                }
            }
        )
    }
}
