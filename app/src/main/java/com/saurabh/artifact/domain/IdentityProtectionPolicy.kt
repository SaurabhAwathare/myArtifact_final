package com.saurabh.artifact.domain

import com.google.firebase.Timestamp
import com.saurabh.artifact.model.User
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class IdentityProtectionPolicy @Inject constructor() {

    enum class ChangeSeverity {
        NORMAL,
        WARNING,
        REQUIRE_CONFIRMATION
    }

    /**
     * Determines the severity of the identity change based on the user's history.
     */
    fun getChangeSeverity(identityChangeCount30Days: Int): ChangeSeverity {
        return when {
            identityChangeCount30Days >= 6 -> ChangeSeverity.REQUIRE_CONFIRMATION
            identityChangeCount30Days >= 4 -> ChangeSeverity.WARNING
            else -> ChangeSeverity.NORMAL
        }
    }

    /**
     * Checks if a change is within the 30-day window for tracking.
     * Note: This is now for analytics/warnings, not for hard-blocking.
     */
    fun isWithinWindow(lastUpdate: Timestamp?): Boolean {
        if (lastUpdate == null) return false
        val thirtyDaysAgo = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, -30)
        }.time
        return lastUpdate.toDate().after(thirtyDaysAgo)
    }
}
