package com.saurabh.artifact.domain.auth

import android.util.Log
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot
import com.saurabh.artifact.model.*
import com.saurabh.artifact.model.avatar.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProfileRepairService @Inject constructor() {

    /**
     * Attempts to load a User from a snapshot, repairing it if it's corrupted or legacy.
     * @return A pair of (Repaired User, Boolean flag indicating if repair was performed)
     */
    fun loadAndRepair(snapshot: DocumentSnapshot): Pair<User, Boolean> {
        val startTime = if (com.saurabh.artifact.BuildConfig.DEBUG) System.nanoTime() else 0L
        val uid = snapshot.id
        val rawData = snapshot.data ?: return User(id = uid) to false
        
        var repairPerformed = false
        val repairReasons = mutableListOf<String>()

        // 1. Version Migration Check
        val version = (rawData["schemaVersion"] as? Number)?.toInt() ?: 1
        if (version < CURRENT_SCHEMA_VERSION) {
            repairPerformed = true
            repairReasons.add("LEGACY_SCHEMA_V$version")
            Log.i("ProfileRepair", "MIGRATION_REQUIRED | UID: $uid | V$version -> V$CURRENT_SCHEMA_VERSION")
        }

        // 2. Deserialization Attempt
        val user = try {
            val parsedUser = snapshot.toObject(User::class.java)
            if (parsedUser == null) {
                repairPerformed = true
                repairReasons.add("DESERIALIZATION_NULL")
                Log.w("ProfileRepair", "DESERIALIZATION_RETURNED_NULL | UID: $uid")
                sanitizeFromMap(uid, rawData, repairReasons)
            } else {
                parsedUser.copy(id = uid)
            }
        } catch (e: Exception) {
            repairPerformed = true
            repairReasons.add("DESERIALIZATION_CRASH: ${e.javaClass.simpleName}")
            Log.e("ProfileRepair", "DESERIALIZATION_CRASH | UID: $uid", e)
            sanitizeFromMap(uid, rawData, repairReasons)
        }

        // 3. Final Consistency Check (Ensure IDs match and version is bumped)
        val finalUser = user.copy(
            id = uid,
            schemaVersion = CURRENT_SCHEMA_VERSION
        )

        // 4. Telemetry
        if (repairPerformed) {
            Log.i("ProfileRepair", "PROFILE_REPAIRED | UID: $uid | Reasons: ${repairReasons.joinToString(", ")}")
            if (com.saurabh.artifact.BuildConfig.DEBUG) {
                Log.i("ProfileRepair", "WRITE_INTENT | UID: $uid")
            }
        }

        if (com.saurabh.artifact.BuildConfig.DEBUG) {
            val durationNs = System.nanoTime() - startTime
            Log.i("ProfileRepair", "LOAD_AND_REPAIR_NS: $durationNs | Repaired: $repairPerformed")
        }

        return finalUser to repairPerformed
    }

    private fun sanitizeFromMap(uid: String, map: Map<String, Any>, reasons: MutableList<String>): User {
        Log.i("ProfileRepair", "STARTING_MANUAL_SANITIZATION | UID: $uid")
        
        return User(
            id = uid,
            anonymousId = safeString(map["anonymousId"], "usr_${uid.takeLast(5)}", "anonymousId", reasons),
            anonymousName = safeString(map["anonymousName"], "Quiet Soul", "anonymousName", reasons),
            anonymousSigil = safeString(map["anonymousSigil"], "", "anonymousSigil", reasons),
            avatarSeed = safeString(map["avatarSeed"], uid, "avatarSeed", reasons),
            avatarColor = safeString(map["avatarColor"], "#FFD700", "avatarColor", reasons),
            avatarConfig = sanitizeAvatarConfig(map["avatarConfig"] as? Map<*, *>, reasons),
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

    private fun sanitizeAvatarConfig(map: Map<*, *>?, reasons: MutableList<String>): AvatarConfig {
        if (map == null) {
            reasons.add("MISSING_AVATAR_CONFIG")
            return AvatarConfig()
        }
        
        return AvatarConfig(
            seed = safeString(map["seed"], "", "avatarConfig.seed", reasons),
            version = (map["version"] as? Number)?.toInt() ?: 2,
            theme = safeString(map["theme"], "CARTOON", "avatarConfig.theme", reasons),
            faceShape = safeEnum(map["faceShape"], FaceShape.ROUND, "faceShape", reasons),
            hairType = safeEnum(map["hairType"], HairType.NONE, "hairType", reasons),
            eyeType = safeEnum(map["eyeType"], EyeType.NEUTRAL, "eyeType", reasons),
            mouthType = safeEnum(map["mouthType"], MouthType.SMILE, "mouthType", reasons),
            accessoryType = safeEnum(map["accessoryType"], AccessoryType.NONE, "accessoryType", reasons),
            skinColor = safeString(map["skinColor"], "#FFDBAC", "avatarConfig.skinColor", reasons),
            hairColor = safeString(map["hairColor"], "#4A2C2C", "avatarConfig.hairColor", reasons),
            outfitColor = safeString(map["outfitColor"], "#4A90E2", "avatarConfig.outfitColor", reasons)
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
        return if (value is String) value else {
            if (value != null) reasons.add("TYPE_MISMATCH_$fieldName")
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
