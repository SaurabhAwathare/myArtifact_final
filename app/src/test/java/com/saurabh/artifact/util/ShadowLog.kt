package com.saurabh.artifact.util

import android.util.Log
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

@Implements(Log::class)
object ShadowLog {
    @Implementation
    @JvmStatic
    fun d(tag: String, msg: String): Int = 0

    @Implementation
    @JvmStatic
    fun i(tag: String, msg: String): Int = 0

    @Implementation
    @JvmStatic
    fun w(tag: String, msg: String): Int = 0

    @Implementation
    @JvmStatic
    fun e(tag: String, msg: String): Int = 0

    @Implementation
    @JvmStatic
    fun e(tag: String, msg: String, tr: Throwable): Int = 0
}
