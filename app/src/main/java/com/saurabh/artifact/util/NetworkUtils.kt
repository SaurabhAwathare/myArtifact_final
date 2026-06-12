package com.saurabh.artifact.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.storage.StorageException
import kotlinx.coroutines.delay
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.time.Duration.Companion.seconds

object NetworkUtils {
    private const val TAG = "NetworkUtils"

    /**
     * Checks if the device has an active internet connection.
     */
    fun isNetworkAvailable(context: Context): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /**
     * Retries a suspending block with exponential backoff if a transient error occurs.
     */
    suspend fun <T> retryWithBackoff(
        maxAttempts: Int = 3,
        initialDelaySeconds: Long = 1,
        block: suspend () -> T
    ): T {
        var currentAttempt = 0
        while (true) {
            try {
                return block()
            } catch (e: Exception) {
                currentAttempt++
                if (currentAttempt >= maxAttempts || !isTransientError(e)) {
                    throw e
                }
                
                val delayTime = initialDelaySeconds * (1 shl (currentAttempt - 1))
                Log.w(TAG, "Transient error detected (attempt $currentAttempt/$maxAttempts). Retrying in $delayTime seconds...", e)
                delay(delayTime.seconds)
            }
        }
    }

    /**
     * Determines if an error is transient (retriable) or terminal.
     */
    fun isTransientError(e: Throwable): Boolean {
        return when (e) {
            is SocketTimeoutException,
            is UnknownHostException,
            is ConnectException,
            is SocketException,
            is InterruptedIOException -> true
            is StorageException -> {
                when (e.errorCode) {
                    StorageException.ERROR_RETRY_LIMIT_EXCEEDED,
                    StorageException.ERROR_NOT_AUTHENTICATED,
                    StorageException.ERROR_QUOTA_EXCEEDED,
                    StorageException.ERROR_NOT_AUTHORIZED -> true
                    else -> {
                        val httpCode = e.httpResultCode
                        httpCode == 408 || httpCode == 429 || httpCode >= 500
                    }
                }
            }
            is FirebaseFirestoreException -> {
                when (e.code) {
                    FirebaseFirestoreException.Code.UNAVAILABLE,
                    FirebaseFirestoreException.Code.DEADLINE_EXCEEDED,
                    FirebaseFirestoreException.Code.ABORTED -> true
                    else -> false
                }
            }
            else -> false
        }
    }
}
