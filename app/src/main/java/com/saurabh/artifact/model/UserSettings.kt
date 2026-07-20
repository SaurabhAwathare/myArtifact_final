package com.saurabh.artifact.model

import androidx.annotation.Keep
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.PropertyName

@Keep
@IgnoreExtraProperties
data class UserSettings(
    @get:PropertyName("anonymousMode")
    @set:PropertyName("anonymousMode")
    var isAnonymousMode: Boolean = true,
    val notificationsEnabled: Boolean = true,
    val smartRemindersEnabled: Boolean = true,
    val emotionalSafetyEnabled: Boolean = true,
    val dataCollectionConsent: Boolean = false,
    val biometricLockEnabled: Boolean = false,
    val autoLockEnabled: Boolean = true,
    val stealthModeEnabled: Boolean = false,
    val preferredLanguage: String = "en",
    val lastSyncTimestamp: Long = 0L
) {
    // Empty constructor for Firestore
    constructor() : this(
        isAnonymousMode = true,
        notificationsEnabled = true,
        smartRemindersEnabled = true,
        emotionalSafetyEnabled = true,
        dataCollectionConsent = false,
        biometricLockEnabled = false,
        autoLockEnabled = true,
        stealthModeEnabled = false,
        preferredLanguage = "en",
        lastSyncTimestamp = 0L
    )
}
