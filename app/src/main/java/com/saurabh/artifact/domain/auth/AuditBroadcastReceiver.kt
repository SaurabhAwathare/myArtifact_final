package com.saurabh.artifact.domain.auth

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class AuditBroadcastReceiver : BroadcastReceiver() {

    @Inject
    lateinit var auditTool: ProfileAuditTool

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.getStringExtra("action") ?: return
        Log.i("AuditReceiver", "ACTION_RECEIVED: $action")
        
        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (action) {
                    "SETUP_HEALTHY" -> auditTool.setupHealthyProfile()
                    "SETUP_LEGACY" -> auditTool.setupLegacyProfile()
                    "SETUP_CORRUPTED" -> auditTool.setupCorruptedProfile()
                    "SKIP_ONBOARDING" -> auditTool.skipOnboarding()
                }
                Log.i("AuditReceiver", "ACTION_SUCCESS: $action")
            } catch (e: Exception) {
                Log.e("AuditReceiver", "ACTION_FAILED: $action", e)
            }
        }
    }
}
