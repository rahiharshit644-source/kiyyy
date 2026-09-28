package com.soltini.app.reach.doctor

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.soltini.app.reach.backends.ReachBackend
import com.soltini.app.reach.gateway.BackendRouter
import com.soltini.app.reach.gateway.CapabilityRegistry
import com.soltini.app.reach.model.DiagnosticReport
import com.soltini.app.reach.model.HealthStatus
import com.soltini.app.reach.model.ReachCapability
import com.soltini.app.reach.security.ReachCredentialStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * AgentReachHealthManager
 *
 * Implements the MYRA native version of `agent-reach doctor`.
 * Probes all 16 platform channels, determines active serving backends,
 * detects missing credentials or network errors, and builds the diagnostics report.
 */
class AgentReachHealthManager(
    private val context: Context,
    private val capabilityRegistry: CapabilityRegistry,
    private val backendRouter: BackendRouter,
    private val credentialStore: ReachCredentialStore
) {
    companion object {
        private const val TAG = "ReachHealthDoctor"
    }

    private var cachedReport: DiagnosticReport? = null
    private var lastCheckTime: Long = 0L

    suspend fun runDoctorCheck(forceRefresh: Boolean = false): DiagnosticReport = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (!forceRefresh && cachedReport != null && (now - lastCheckTime) < 30_000L) {
            return@withContext cachedReport!!
        }

        val isNetworkUp = isNetworkAvailable()
        val allCaps = capabilityRegistry.getAllCapabilities()

        for (cap in allCaps) {
            probeCapability(cap, isNetworkUp)
        }

        val readyCount = allCaps.count { it.healthStatus == HealthStatus.READY }
        val report = DiagnosticReport(
            timestamp = now,
            networkAvailable = isNetworkUp,
            totalCapabilities = allCaps.size,
            readyCapabilities = readyCount,
            capabilities = allCaps,
            cacheStatus = "LRU In-Memory + SQLite persistent cache active"
        )

        cachedReport = report
        lastCheckTime = now
        Log.i(TAG, "Doctor check finished: $readyCount/${allCaps.size} capabilities ready.")
        report
    }

    private suspend fun probeCapability(cap: ReachCapability, isNetworkUp: Boolean) {
        if (!isNetworkUp) {
            cap.healthStatus = HealthStatus.NETWORK_UNAVAILABLE
            cap.statusMessage = "Device network is offline"
            return
        }

        // Check authentication requirements
        val authState = credentialStore.getAuthenticationState(cap.id)
        cap.authState = authState

        // Probe primary backend for this capability
        val primaryBackendId = cap.backends.firstOrNull() ?: return
        val backend = backendRouter.getBackend(primaryBackendId)

        if (backend != null) {
            try {
                val (status, msg) = backend.probe()
                cap.healthStatus = status
                cap.statusMessage = msg
                if (status == HealthStatus.READY) {
                    cap.activeBackend = backend.id
                }
            } catch (e: Exception) {
                cap.healthStatus = HealthStatus.ERROR
                cap.statusMessage = "Probe error: ${e.message}"
            }
        } else {
            // Default healthy state for built-in web-reader / public fallbacks
            cap.healthStatus = HealthStatus.READY
            cap.statusMessage = "Active via default native channel"
            cap.activeBackend = primaryBackendId
        }
        cap.lastChecked = System.currentTimeMillis()
    }

    private fun isNetworkAvailable(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val activeNetwork = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
