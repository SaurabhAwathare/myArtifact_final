package com.saurabh.artifact.navigation.features

import android.util.Log
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.saurabh.artifact.navigation.Login
import com.saurabh.artifact.navigation.Onboarding
import com.saurabh.artifact.navigation.Home
import com.saurabh.artifact.ui.login.LoginScreen
import com.saurabh.artifact.ui.onboarding.OnboardingScreen
import com.saurabh.artifact.util.OnboardingManager
import kotlinx.coroutines.launch

fun NavGraphBuilder.authNavigation(
    navController: NavHostController,
    onboardingManager: OnboardingManager,
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
            {
                Log.d("APP_FLOW", "Action: Login Success -> Navigating Home")
                navController.navigate(Home) {
                    popUpTo(Login) { inclusive = true }
                }
            }
        }
        LoginScreen(onLoginSuccess = onLoginSuccess)
    }
}
