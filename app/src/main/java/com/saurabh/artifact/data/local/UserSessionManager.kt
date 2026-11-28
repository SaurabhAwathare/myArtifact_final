package com.saurabh.artifact.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import com.saurabh.artifact.model.UserProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

@Singleton
class UserSessionManager @Inject constructor(
    @Named("sessionDataStore") private val dataStore: DataStore<Preferences>,
    private val blockStoreManager: BlockStoreManager,
) {
    internal object PreferencesKeys {
        val OWNING_UID = stringPreferencesKey("owning_uid")
        val ANONYMOUS_ID = stringPreferencesKey("anonymous_id")
        val SIGIL_SEED = stringPreferencesKey("sigil_seed")
        val SIGIL_CONFIG_JSON = stringPreferencesKey("sigil_config_json")
        val SIGIL_COLOR = stringPreferencesKey("sigil_color")
        val USERNAME = stringPreferencesKey("username")
        val SIGIL = stringPreferencesKey("sigil")
        val IS_ANONYMOUS = booleanPreferencesKey("is_anonymous")
        val RESONANCE_IN = longPreferencesKey("resonance_in")
        val RESONANCE_OUT = longPreferencesKey("resonance_out")
        val ACTIVE_DRAFT_ID = stringPreferencesKey("active_draft_id")
        val ACTIVE_PROMPT_ID = stringPreferencesKey("active_prompt_id")
        val IS_LOGGING_OUT = booleanPreferencesKey("is_logging_out")
        val LOCAL_SESSION_ID = stringPreferencesKey("local_session_id")
        val AVATAR_CONFIG_JSON = stringPreferencesKey("avatar_config_json")
    }

    internal val rawPreferences: Flow<Preferences> = dataStore.data

    /**
     * Exposes the local active session ID assigned to this device.
     */
    val localSessionId: Flow<String?> = dataStore.data
        .map { preferences -> preferences[PreferencesKeys.LOCAL_SESSION_ID] }

    suspend fun setLocalSessionId(sessionId: String?) {
        dataStore.edit { preferences ->
            if (sessionId == null) {
                preferences.remove(PreferencesKeys.LOCAL_SESSION_ID)
            } else {
                preferences[PreferencesKeys.LOCAL_SESSION_ID] = sessionId
            }
        }
    }

    /**
     * Exposes the UID of the user who authoritatively owns the local DataStore state.
     * Used by MainViewModel to detect cross-account leakage.
     */
    val owningUid: Flow<String?> = dataStore.data
        .map { preferences -> preferences[PreferencesKeys.OWNING_UID] }

    suspend fun getOwningUid(): String? {
        return dataStore.data.map { it[PreferencesKeys.OWNING_UID] }.first()
    }

    /**
     * Retrieves the current anonymous profile. 
     */
    val userProfile: Flow<UserProfile> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            val id = preferences[PreferencesKeys.ANONYMOUS_ID] ?: ("gen_" + UUID.randomUUID().toString().take(8))
            
            val seed = preferences[PreferencesKeys.SIGIL_SEED] ?: id
                
            val username = preferences[PreferencesKeys.USERNAME] ?: com.saurabh.artifact.util.UsernameGenerator.generate()
            val sigil = preferences[PreferencesKeys.SIGIL] ?: com.saurabh.artifact.util.UsernameGenerator.deriveSigil(id)
            val sigilColor = preferences[PreferencesKeys.SIGIL_COLOR] ?: "#FFD700"
            val isAnonymous = preferences[PreferencesKeys.IS_ANONYMOUS] ?: true
            val resonanceIn = preferences[PreferencesKeys.RESONANCE_IN] ?: 0L
            val resonanceOut = preferences[PreferencesKeys.RESONANCE_OUT] ?: 0L
            
            val configJson = preferences[PreferencesKeys.SIGIL_CONFIG_JSON]

            val config = configJson?.let { 
                try {
                    Json.decodeFromString<com.saurabh.artifact.model.SigilConfig>(it).copy(seed = seed)
                } catch (_: Exception) {
                    com.saurabh.artifact.model.SigilConfig(seed = seed)
                }
            } ?: com.saurabh.artifact.model.SigilConfig(seed = seed)

            val avatarJson = preferences[PreferencesKeys.AVATAR_CONFIG_JSON]
            val avatarConfig = avatarJson?.let {
                try {
                    Json.decodeFromString<com.saurabh.artifact.model.avatar.AvatarConfiguration>(it)
                        .withSafeFallbacks(com.saurabh.artifact.model.avatar.DefaultAvatarAssetValidator)
                } catch (_: Exception) {
                    null
                }
            }
            
            UserProfile(
                anonymousId = id, 
                username = username, 
                sigil = sigil,
                sigilSeed = seed,
                sigilColor = sigilColor,
                sigilConfig = config,
                isAnonymous = isAnonymous,
                resonanceInCount = resonanceIn,
                resonanceOutCount = resonanceOut,
                avatarConfig = avatarConfig,
            )
        }

    val activePromptId: Flow<String?> = dataStore.data
        .map { preferences -> preferences[PreferencesKeys.ACTIVE_PROMPT_ID] }

    val activeDraftId: Flow<String?> = dataStore.data
        .map { preferences -> preferences[PreferencesKeys.ACTIVE_DRAFT_ID] }

    val isLoggingOut: Flow<Boolean> = dataStore.data
        .map { preferences -> preferences[PreferencesKeys.IS_LOGGING_OUT] ?: false }

    /**
     * Sets the "Cleanup In Progress" flag to block dirty restoration during logout.
     */
    suspend fun setLoggingOut(loggingOut: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.IS_LOGGING_OUT] = loggingOut
        }
    }

    suspend fun setActiveDraftId(id: String?) {
        dataStore.edit { preferences ->
            if (id == null) {
                preferences.remove(PreferencesKeys.ACTIVE_DRAFT_ID)
            } else {
                preferences[PreferencesKeys.ACTIVE_DRAFT_ID] = id
            }
        }
    }

    suspend fun setActivePromptId(id: String?) {
        dataStore.edit { preferences ->
            if (id == null) {
                preferences.remove(PreferencesKeys.ACTIVE_PROMPT_ID)
            } else {
                preferences[PreferencesKeys.ACTIVE_PROMPT_ID] = id
            }
        }
    }

    /**
     * Updates the user's sigil configuration.
     * Guaranteed not to modify DataStore if:
     * - OWNING_UID is non-null and expectedUid does not match OWNING_UID (including null expectedUid)
     * - OWNING_UID is null and expectedUid is non-null
     * Does not establish or transfer cache ownership.
     */
    suspend fun updateSigilConfig(config: com.saurabh.artifact.model.SigilConfig, expectedUid: String? = null) {
        if (expectedUid != null && expectedUid.isBlank()) return
        dataStore.edit { preferences ->
            val currentOwner = preferences[PreferencesKeys.OWNING_UID]?.takeIf { it.isNotBlank() }
            if (currentOwner != expectedUid) {
                return@edit
            }
            preferences[PreferencesKeys.SIGIL_CONFIG_JSON] = Json.encodeToString(config)
            preferences[PreferencesKeys.SIGIL_SEED] = config.seed
        }
    }

    /**
     * Updates the user's identity username.
     * Guaranteed not to modify DataStore if:
     * - OWNING_UID is non-null and expectedUid does not match OWNING_UID (including null expectedUid)
     * - OWNING_UID is null and expectedUid is non-null
     * Does not establish or transfer cache ownership.
     */
    suspend fun updateUsername(username: String, expectedUid: String? = null) {
        if (expectedUid != null && expectedUid.isBlank()) return
        dataStore.edit { preferences ->
            val currentOwner = preferences[PreferencesKeys.OWNING_UID]?.takeIf { it.isNotBlank() }
            if (currentOwner != expectedUid) {
                return@edit
            }
            preferences[PreferencesKeys.USERNAME] = username
        }
    }

    /**
     * Updates the user's avatar configuration.
     * Guaranteed not to modify DataStore if:
     * - OWNING_UID is non-null and expectedUid does not match OWNING_UID (including null expectedUid)
     * - OWNING_UID is null and expectedUid is non-null
     * Does not establish or transfer cache ownership.
     */
    suspend fun updateAvatarConfig(
        config: com.saurabh.artifact.model.avatar.AvatarConfiguration?,
        expectedUid: String? = null
    ) {
        if (expectedUid != null && expectedUid.isBlank()) return
        dataStore.edit { preferences ->
            val currentOwner = preferences[PreferencesKeys.OWNING_UID]?.takeIf { it.isNotBlank() }
            if (currentOwner != expectedUid) {
                return@edit
            }
            if (config == null) {
                preferences.remove(PreferencesKeys.AVATAR_CONFIG_JSON)
            } else {
                val safe = config.withSafeFallbacks(com.saurabh.artifact.model.avatar.DefaultAvatarAssetValidator)
                preferences[PreferencesKeys.AVATAR_CONFIG_JSON] = Json.encodeToString(safe)
            }
        }
    }

    /**
     * Synchronizes local DataStore with a remote User profile from Firestore.
     * Enforces account ownership, rejects stale or mismatched synchronizations,
     * and atomically synchronizes identity fields while cleaning unconfigured values.
     */
    suspend fun syncFromRemote(user: com.saurabh.artifact.model.User, expectedUid: String? = null) {
        if (user.id.isBlank()) return
        if (expectedUid != null && (expectedUid.isBlank() || expectedUid != user.id)) return

        dataStore.edit { preferences ->
            val currentOwner = preferences[PreferencesKeys.OWNING_UID]
            if (expectedUid != null && currentOwner != null && currentOwner != expectedUid) {
                return@edit
            }
            if (currentOwner != null && currentOwner != user.id) {
                return@edit
            }

            preferences[PreferencesKeys.OWNING_UID] = user.id

            if (user.anonymousId.isNotBlank()) {
                preferences[PreferencesKeys.ANONYMOUS_ID] = user.anonymousId
            } else {
                preferences.remove(PreferencesKeys.ANONYMOUS_ID)
            }

            if (user.anonymousName.isNotBlank()) {
                preferences[PreferencesKeys.USERNAME] = user.anonymousName
            } else {
                preferences.remove(PreferencesKeys.USERNAME)
            }

            if (user.anonymousSigil.isNotBlank()) {
                preferences[PreferencesKeys.SIGIL] = user.anonymousSigil
            } else {
                preferences.remove(PreferencesKeys.SIGIL)
            }

            if (user.sigilSeed.isNotBlank()) {
                preferences[PreferencesKeys.SIGIL_SEED] = user.sigilSeed
            } else {
                preferences.remove(PreferencesKeys.SIGIL_SEED)
            }

            val effectiveSeed = user.sigilConfig.seed.ifBlank { user.sigilSeed }
            val isSigilConfigured = user.anonymousSigil.isNotBlank() && effectiveSeed.isNotBlank()

            if (isSigilConfigured) {
                val configuredConfig = user.sigilConfig.copy(seed = effectiveSeed)
                preferences[PreferencesKeys.SIGIL_CONFIG_JSON] = Json.encodeToString(configuredConfig)
                if (user.sigilColor.isNotBlank()) {
                    preferences[PreferencesKeys.SIGIL_COLOR] = user.sigilColor
                } else {
                    preferences[PreferencesKeys.SIGIL_COLOR] = "#FFD700"
                }
            } else {
                preferences.remove(PreferencesKeys.SIGIL_CONFIG_JSON)
                preferences.remove(PreferencesKeys.SIGIL_COLOR)
            }

            if (user.avatarConfig != null) {
                val safe = user.avatarConfig.withSafeFallbacks(com.saurabh.artifact.model.avatar.DefaultAvatarAssetValidator)
                preferences[PreferencesKeys.AVATAR_CONFIG_JSON] = Json.encodeToString(safe)
            } else {
                preferences.remove(PreferencesKeys.AVATAR_CONFIG_JSON)
            }

            preferences[PreferencesKeys.IS_ANONYMOUS] = user.isAnonymous
            preferences[PreferencesKeys.RESONANCE_IN] = user.resonanceInCount
            preferences[PreferencesKeys.RESONANCE_OUT] = user.resonanceOutCount
        }
    }

    /**
     * Clears cached identity and profile fields for a specific account.
     * Leaves operational session state (session ID, draft ID, prompt ID, logout flag) untouched.
     * Does NOT invoke BlockStoreManager.clear().
     */
    suspend fun clearIdentityCache(expectedUid: String) {
        if (expectedUid.isBlank()) return
        dataStore.edit { preferences ->
            val currentOwner = preferences[PreferencesKeys.OWNING_UID]
            if (currentOwner == expectedUid) {
                preferences.remove(PreferencesKeys.OWNING_UID)
                preferences.remove(PreferencesKeys.ANONYMOUS_ID)
                preferences.remove(PreferencesKeys.USERNAME)
                preferences.remove(PreferencesKeys.SIGIL)
                preferences.remove(PreferencesKeys.SIGIL_SEED)
                preferences.remove(PreferencesKeys.SIGIL_COLOR)
                preferences.remove(PreferencesKeys.SIGIL_CONFIG_JSON)
                preferences.remove(PreferencesKeys.AVATAR_CONFIG_JSON)
                preferences.remove(PreferencesKeys.IS_ANONYMOUS)
                preferences.remove(PreferencesKeys.RESONANCE_IN)
                preferences.remove(PreferencesKeys.RESONANCE_OUT)
                preferences.remove(booleanPreferencesKey("suppression_restored_$expectedUid"))
            }
        }
    }

    /**
     * Clears all session data. Used during full account deletion or sign out.
     * Purges both local DataStore and persistent Block Store identity markers.
     */
    suspend fun clear() {
        try {
            dataStore.edit { it.clear() }
        } catch (e: Exception) {
            android.util.Log.e("UserSessionManager", "Failed to clear local DataStore", e)
        }
        
        // Purge persistent identity markers from Google Play Services
        blockStoreManager.clear()
    }

    /**
     * Reactive stream of durable suppression restoration completion status for [userId].
     */
    fun observeSuppressionRestorationCompleted(userId: String): Flow<Boolean> {
        if (userId.isBlank()) return kotlinx.coroutines.flow.flowOf(false)
        return dataStore.data.map { preferences ->
            preferences[booleanPreferencesKey("suppression_restored_$userId")] ?: false
        }
    }

    /**
     * One-shot check for durable suppression restoration completion for [userId].
     */
    suspend fun isSuppressionRestorationCompleted(userId: String): Boolean {
        if (userId.isBlank()) return false
        return dataStore.data.map { preferences ->
            preferences[booleanPreferencesKey("suppression_restored_$userId")] ?: false
        }.first()
    }

    /**
     * Sets the durable suppression restoration completion marker for [userId].
     */
    suspend fun setSuppressionRestorationCompleted(userId: String, completed: Boolean) {
        if (userId.isBlank()) return
        dataStore.edit { preferences ->
            val key = booleanPreferencesKey("suppression_restored_$userId")
            if (completed) {
                preferences[key] = true
            } else {
                preferences.remove(key)
            }
        }
    }

    /**
     * Ensures that an anonymous ID exists, saving it if necessary.
     * This should be called from the UI or a Repository at startup, NOT from within userProfile Flow.
     */
    suspend fun ensureAnonymousId() {
        dataStore.edit { preferences ->
            if (preferences[PreferencesKeys.ANONYMOUS_ID] == null) {
                val blockStoreId = blockStoreManager.getAnonymousId()
                val idToUse = blockStoreId ?: UUID.randomUUID().toString()
                preferences[PreferencesKeys.ANONYMOUS_ID] = idToUse
                blockStoreManager.saveAnonymousId(idToUse)
            }
            if (preferences[PreferencesKeys.SIGIL_SEED] == null) {
                val seedToUse = preferences[PreferencesKeys.ANONYMOUS_ID] ?: UUID.randomUUID().toString()
                preferences[PreferencesKeys.SIGIL_SEED] = seedToUse
            }
        }
    }
}
