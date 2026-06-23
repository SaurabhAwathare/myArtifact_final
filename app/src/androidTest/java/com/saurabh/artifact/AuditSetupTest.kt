package com.saurabh.artifact

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.saurabh.artifact.domain.auth.ProfileAuditTool
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class AuditSetupTest {

    @get:Rule
    var hiltRule = HiltAndroidRule(this)

    @Inject
    lateinit var auditTool: ProfileAuditTool

    @Before
    fun init() {
        hiltRule.inject()
    }

    @Test
    fun setupHealthy() = runBlocking {
        auditTool.setupHealthyProfile()
    }

    @Test
    fun setupLegacy() = runBlocking {
        auditTool.setupLegacyProfile()
    }

    @Test
    fun setupCorrupted() = runBlocking {
        auditTool.setupCorruptedProfile()
    }
}
