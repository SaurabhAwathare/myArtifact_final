package com.saurabh.artifact.data.local

import android.content.Context
import android.util.Log
import com.google.android.gms.auth.blockstore.Blockstore
import com.google.android.gms.auth.blockstore.RetrieveBytesRequest
import com.google.android.gms.auth.blockstore.StoreBytesData
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BlockStoreManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val client = Blockstore.getClient(context)

    suspend fun saveAnonymousId(id: String) {
        try {
            val data = StoreBytesData.Builder()
                .setBytes(id.toByteArray())
                .setKey(ANONYMOUS_ID_KEY)
                .build()
            client.storeBytes(data).await()
            Log.d(TAG, "Anonymous ID saved to Block Store: $id")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save Anonymous ID to Block Store", e)
        }
    }

    suspend fun getAnonymousId(): String? {
        return try {
            val request = RetrieveBytesRequest.Builder()
                .setKeys(listOf(ANONYMOUS_ID_KEY))
                .build()
            val result = client.retrieveBytes(request).await()
            val idBytes = result.blockstoreDataMap[ANONYMOUS_ID_KEY]?.bytes
            val id = idBytes?.let { String(it) }
            Log.d(TAG, "Retrieved Anonymous ID from Block Store: $id")
            id
        } catch (e: Exception) {
            Log.e(TAG, "Failed to retrieve Anonymous ID from Block Store", e)
            null
        }
    }

    companion object {
        private const val TAG = "BlockStoreManager"
        private const val ANONYMOUS_ID_KEY = "anonymous_id"
    }
}
