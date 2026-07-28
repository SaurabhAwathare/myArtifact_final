package com.saurabh.artifact.domain.auth

import android.util.Log
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot
import com.saurabh.artifact.model.*
import com.saurabh.artifact.model.sigil.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProfileRepairService @Inject constructor() {

    /**
     * Attempts to load a User from a snapshot, repairing it if it's corrupted or legacy.
     * Clarification: Handles the migration from legacy human-like "Avatar" fields 
     * to abstract geometric "Sigil" fields.
     * @return A pair of (Repaired User, Boolean flag indicating if repair was performed)
     */
    fun loadAndRepair(snapshot: DocumentSnapshot): Pair<User, Boolean> {
        val startTime = if (com.saurabh.artifact.BuildConfig.DEBUG) System.nanoTime() else 0L
        val uid = snapshot.id
        val rawData = snapshot.data ?: return User(id = uid) to false
        
        var repairPerformed = false
        val repairReasons = mutableListOf<String>()

        // 1. Detect Legacy Fields for Cleanup (Requirement 1: Idempotency & Verification)
        // Note: These fields belong to the retired Avatar system.
        val hasLegacyAvatarFields = snapshot.contains("avatarSeed") || 
                                   snapshot.contains("avatarColor") || 
                                   snapshot.contains("avatarConfig") ||
                                   snapshot.contains("followersCount") ||
                                   snapshot.contains("followingCount")

        if (hasLegacyAvatarFields) {
            repairPerformed = true
            repairReasons.add("LEGACY_AVATAR_FIELDS_PRESENT")
        }

        // 2. Initial Deserialization Attempt
        val user = try {
            snapshot.toObject(User::class.java)?.copy(id = uid)
        } catch (e: Exception) {
            Log.e("ProfileRepair", "INITIAL_DESERIALIZATION_CRASH", e)
            null
        }

        // 3. Deterministic Integrity Check (Audit Mechanism)
        val validationResult = if (user != null) {
            UserIdentityValidator.validate(user)
        } else {
            IdentityValidationResult(isValid = false, reasons = listOf("DESERIALIZATION_FAILURE"))
        }

        val finalUser = if (!validationResult.isValid) {
            repairPerformed = true
            repairReasons.addAll(validationResult.reasons)
            Log.i("ProfileRepair", "INTEGRITY_VIOLATION_DETECTED | Reasons: ${validationResult.reasons.distinct().joinToString(", ")}")
            
            // Perform repair (Sanitization)
            val repaired = sanitizeFromMap(uid, rawData, repairReasons)
            
            // Verify repaired profile consistency
            repaired.copy(schemaVersion = CURRENT_SCHEMA_VERSION)
        } else {
            user!! // Validator confirmed non-null
        }

        // 4. Telemetry & Audit Logging
        if (repairPerformed) {
            Log.i("ProfileRepair", "PROFILE_REPAIR_IDENTIFIED | Reasons: ${repairReasons.distinct().joinToString(", ")}")
        }

        if (com.saurabh.artifact.BuildConfig.DEBUG) {
            val durationNs = System.nanoTime() - startTime
            Log.i("ProfileRepair", "LOAD_AND_REPAIR_NS: $durationNs | Repaired: $repairPerformed")
        }

        return finalUser to repairPerformed
    }

    private fun sanitizeFromMap(uid: String, map: Map<String, Any>, reasons: MutableList<String>): User {
        Log.i("ProfileRepair", "STARTING_MANUAL_SANITIZATION")
        
        val anonymousId = safeString(map["anonymousId"], "usr_${uid.takeLast(5)}", "anonymousId", reasons)
        val anonymousName = safeString(map["anonymousName"], "Quiet Soul", "anonymousName", reasons)
        
        return User(
            id = uid,
            anonymousId = anonymousId,
            anonymousName = anonymousName,
            anonymousSigil = safeString(map["anonymousSigil"], com.saurabh.artifact.util.UsernameGenerator.deriveSigil(anonymousId), "anonymousSigil", reasons),
            sigilSeed = safeString(map["sigilSeed"] ?: map["avatarSeed"], uid, "sigilSeed", reasons),
            sigilColor = safeString(map["sigilColor"] ?: map["avatarColor"], "#FFD700", "sigilColor", reasons),
            sigilConfig = sanitizeSigilConfig(map["sigilConfig"] ?: map["avatarConfig"] as? Map<*, *>, reasons),
            emotionalProfile = safeString(map["emotionalProfile"], "Quiet Observer", "emotionalProfile", reasons),
            isAnonymous = safeBoolean(map["isAnonymous"], true, "isAnonymous", reasons),
            dominantEmotion = map["dominantEmotion"] as? String,
            usernameUpdatedAt = map["usernameUpdatedAt"] as? Timestamp,
            createdAt = map["createdAt"] as? Timestamp,
            lastSeen = map["lastSeen"] as? Timestamp,
            emotionPreferences = (map["emotionPreferences"] as? Map<*, *>)?.mapNotNull { 
                val key = it.key as? String
                val value = (it.value as? Number)?.toLong()
                if (key != null && value != null) key to value else null
            }?.toMap() ?: emptyMap(),
            bio = safeString(map["bio"], "", "bio", reasons),
            resonanceInCount = safeLong(map["resonanceInCount"] ?: map["followersCount"], 0L, "resonanceInCount", reasons),
            resonanceOutCount = safeLong(map["resonanceOutCount"] ?: map["followingCount"], 0L, "resonanceOutCount", reasons),
            lastActivityTimestamp = map["lastActivityTimestamp"] as? Timestamp,
            artifactsCount = safeLong(map["artifactsCount"], 0L, "artifactsCount", reasons),
            softStreakCount = safeLong(map["softStreakCount"], 0L, "softStreakCount", reasons),
            totalContributions = safeLong(map["totalContributions"], 0L, "totalContributions", reasons),
            lastPromptId = safeString(map["lastPromptId"], "", "lastPromptId", reasons),
            identityMetadata = sanitizeIdentityMetadata(map["identityMetadata"] as? Map<*, *>, reasons)
        )
    }

    private fun sanitizeSigilConfig(value: Any?, reasons: MutableList<String>): SigilConfig {
        val map = value as? Map<*, *>
        if (map == null) {
            reasons.add("MISSING_SIGIL_CONFIG")
            return SigilConfig()
        }
        
        val version = (map["version"] as? Number)?.toInt() ?: 0
        
        // Migration logic: If legacy AvatarConfig (v1 or v2), reset to SigilConfig (v3) 
        // but preserve the seed.
        if (version < 3) {
            reasons.add("MIGRATED_LEGACY_AVATAR_CONFIG_V$version")
            return SigilConfig(
                seed = safeString(map["seed"], "", "sigilConfig.seed", reasons),
                version = 3
            )
        }

        return SigilConfig(
            seed = safeString(map["seed"], "", "sigilConfig.seed", reasons),
            version = version,
            palette = safeEnum(map["palette"], SigilPalette.AURORA, "sigilConfig.palette", reasons),
            variant = safeEnum(map["variant"], SigilVariant.LIGHT, "sigilConfig.variant", reasons),
            style = safeEnum(map["style"], SigilStyle.OUTLINE, "sigilConfig.style", reasons),
            weight = (map["weight"] as? Number)?.toFloat() ?: 2.0f
        )
    }

    private fun sanitizeIdentityMetadata(map: Map<*, *>?, reasons: MutableList<String>): IdentityMetadata {
        if (map == null) return IdentityMetadata()
        return IdentityMetadata(
            lastIdentityChangeAt = map["lastIdentityChangeAt"] as? Timestamp,
            identityChangeCount30Days = (map["identityChangeCount30Days"] as? Number)?.toInt() ?: 0,
            emergencyResetCount = (map["emergencyResetCount"] as? Number)?.toInt() ?: 0,
            identityResetVersion = (map["identityResetVersion"] as? Number)?.toLong() ?: 0L,
            lastCompletedIdentityVersion = (map["lastCompletedIdentityVersion"] as? Number)?.toLong() ?: 0L,
            resetStartedAt = map["resetStartedAt"] as? Timestamp,
            resetCompletedAt = map["resetCompletedAt"] as? Timestamp
        )
    }

    // --- HELPER UTILITIES ---

    private fun safeString(value: Any?, default: String, fieldName: String, reasons: MutableList<String>): String {
        val strValue = value as? String
        return if (strValue != null && strValue.isNotBlank()) strValue else {
            if (value != null && (value as? String)?.isBlank() != true) reasons.add("TYPE_MISMATCH_$fieldName")
            if (strValue != null && strValue.isBlank()) reasons.add("EMPTY_$fieldName")
            default
        }
    }

    private fun safeLong(value: Any?, default: Long, fieldName: String, reasons: MutableList<String>): Long {
        return when (value) {
            is Number -> value.toLong()
            is String -> {
                val parsed = value.toLongOrNull()
                if (parsed != null) {
                    reasons.add("COERCED_STRING_TO_LONG_$fieldName")
                    parsed
                } else {
                    reasons.add("INVALID_STRING_FOR_LONG_$fieldName")
                    default
                }
            }
            else -> {
                if (value != null) reasons.add("TYPE_MISMATCH_$fieldName")
                default
            }
        }
    }

    private fun safeBoolean(value: Any?, default: Boolean, fieldName: String, reasons: MutableList<String>): Boolean {
        return when (value) {
            is Boolean -> value
            is Number -> {
                reasons.add("COERCED_NUMBER_TO_BOOL_$fieldName")
                value.toInt() != 0
            }
            is String -> {
                reasons.add("COERCED_STRING_TO_BOOL_$fieldName")
                value.lowercase() == "true" || value == "1"
            }
            else -> {
                if (value != null) reasons.add("TYPE_MISMATCH_$fieldName")
                default
            }
        }
    }

    private inline fun <reified T : Enum<T>> safeEnum(value: Any?, default: T, fieldName: String, reasons: MutableList<String>): T {
        val stringValue = value as? String ?: return default
        return try {
            java.lang.Enum.valueOf(T::class.java, stringValue.uppercase())
        } catch (e: Exception) {
            reasons.add("INVALID_ENUM_$fieldName($stringValue)")
            Log.w("ProfileRepair", "Invalid enum for $fieldName: $stringValue. Falling back to $default")
            default
        }
    }
}
