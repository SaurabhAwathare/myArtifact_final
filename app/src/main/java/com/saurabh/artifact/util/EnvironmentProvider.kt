package com.saurabh.artifact.util

import com.saurabh.artifact.BuildConfig
import javax.inject.Inject
import javax.inject.Singleton

enum class AppEnvironment {
    DEBUG,
    PRODUCTION
}

interface EnvironmentProvider {
    val environment: AppEnvironment
    val firebaseProjectId: String
    val isDebug: Boolean
}

@Singleton
class EnvironmentProviderImpl @Inject constructor() : EnvironmentProvider {
    override val environment: AppEnvironment = when (BuildConfig.FIREBASE_ENV) {
        "DEBUG" -> AppEnvironment.DEBUG
        else -> AppEnvironment.PRODUCTION
    }

    override val firebaseProjectId: String = BuildConfig.FIREBASE_PROJECT_ID
    
    override val isDebug: Boolean = BuildConfig.DEBUG
}
