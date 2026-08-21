package com.saurabh.artifact.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * Manages application-level maintenance state that must survive user-level data wipes.
 * Currently used to track pending account deletions for recovery purposes.
 */
@Singleton
class MaintenanceRepository @Inject constructor(
    @Named("maintenanceDataStore") private val dataStore: DataStore<Preferences>
) {
    private object Keys {
        val DELETION_PENDING_UID = stringPreferencesKey("deletion_pending_uid")
    }

    /**
     * Stores the UID of a user whose account deletion has been initiated but local cleanup
     * has not yet been confirmed complete.
     */
    suspend fun setPendingDeletion(uid: String?) {
        dataStore.edit { preferences ->
            if (uid == null) {
                preferences.remove(Keys.DELETION_PENDING_UID)
            } else {
                preferences[Keys.DELETION_PENDING_UID] = uid
            }
        }
    }

    /**
     * Retrieves the UID of any pending deletion.
     */
    suspend fun getPendingDeletionUid(): String? {
        return dataStore.data.map { it[Keys.DELETION_PENDING_UID] }.first()
    }
}
