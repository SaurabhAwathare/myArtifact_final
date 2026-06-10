package com.saurabh.artifact.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    @Test
    fun generate() = baselineProfileRule.collect(
        packageName = "com.saurabh.artifact",
        includeInStartupProfile = true
    ) {
        pressHome()
        startActivityAndWait()
        
        // Add interactions here if needed to cover more code paths
        // For example, waiting for some UI element to appear
        // device.waitForIdle()
    }
}
