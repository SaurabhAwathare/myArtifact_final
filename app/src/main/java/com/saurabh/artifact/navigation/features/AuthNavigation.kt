package com.saurabh.artifact.navigation.features

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.saurabh.artifact.navigation.Screen
import com.saurabh.artifact.ui.login.LoginScreen
import com.saurabh.artifact.ui.onboarding.OnboardingScreen
import com.saurabh.artifact.util.OnboardingManager
import kotlinx.coroutines.launch
import android.util.Log

fun NavGraphBuilder.authNavigation(
    navController: NavHostController,
    onboardingManager: OnboardingManager,
) {
    composable(Screen.Onboarding.route) {
        val scope = rememberCoroutineScope()
        val onOnboardingFinished = remember(navController, onboardingManager, scope) {
            {
                scope.launch {
                    onboardingManager.setOnboardingCompleted(emptySet())
                    navController.navigate(Screen.Login.route) {
                        popUpTo(Screen.Onboarding.route) { inclusive = true }
                    }
                }
                Unit
            }
        }
        OnboardingScreen(onOnboardingFinished = onOnboardingFinished)
    }

    composable(Screen.Login.route) {
        val onLoginSuccess = remember(navController) {
            {
                Log.d("APP_FLOW", "Action: Login Success -> Navigating Home")
                navController.navigate(Screen.Home.route) {
                    popUpTo(Screen.Login.route) { inclusive = true }
                }
            }
        }
        LoginScreen(onLoginSuccess = onLoginSuccess)
    }
}
