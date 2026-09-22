package org.videopocket.probe.resolver

import android.content.Context
import android.content.SharedPreferences

enum class ProviderHealth(val label: String, val arabicLabel: String) {
    AVAILABLE("Available", "متاح"),
    WORKING("Available", "متاح"),
    DEGRADED("Degraded", "أداء متأثر"),
    TEMPORARILY_BROKEN("Temporarily Unavailable", "غير متاح مؤقتاً"),
    TEMPORARILY_UNAVAILABLE("Temporarily Unavailable", "غير متاح مؤقتاً"),
    DISABLED("Disabled", "معطل"),
    UNKNOWN("Operational", "جاهز للعمل")
}

data class ProviderMetrics(
    val providerId: String,
    var successCount: Int = 0,
    var failureCount: Int = 0,
    var lastLatencyMs: Long = 0,
    var lastError: ResolverErrorType? = null
)

object ProviderHealthManager {
    private val metricsMap = mutableMapOf<String, ProviderMetrics>()

    @Synchronized
    fun recordSuccess(providerId: String, latencyMs: Long) {
        val metrics = metricsMap.getOrPut(providerId) { ProviderMetrics(providerId) }
        metrics.successCount++
        metrics.lastLatencyMs = latencyMs
    }

    @Synchronized
    fun recordFailure(providerId: String, errorType: ResolverErrorType) {
        val metrics = metricsMap.getOrPut(providerId) { ProviderMetrics(providerId) }
        metrics.failureCount++
        metrics.lastError = errorType
    }

    @Synchronized
    fun getHealth(providerId: String): ProviderHealth {
        if (!FeatureFlagsManager.isProviderEnabled(providerId)) {
            return ProviderHealth.DISABLED
        }
        val metrics = metricsMap[providerId] ?: return ProviderHealth.UNKNOWN
        val total = metrics.successCount + metrics.failureCount
        if (total == 0) return ProviderHealth.UNKNOWN
        val failRatio = metrics.failureCount.toFloat() / total.toFloat()
        return when {
            metrics.lastError == ResolverErrorType.RATE_LIMITED -> ProviderHealth.TEMPORARILY_BROKEN
            failRatio > 0.5f -> ProviderHealth.DEGRADED
            metrics.successCount > 0 -> ProviderHealth.AVAILABLE
            else -> ProviderHealth.TEMPORARILY_BROKEN
        }
    }

    @Synchronized
    fun getMetrics(providerId: String): ProviderMetrics {
        return metricsMap.getOrPut(providerId) { ProviderMetrics(providerId) }
    }
}

object FeatureFlagsManager {
    private const val PREFS_NAME = "videopocket_feature_flags"
    private var prefs: SharedPreferences? = null
    private val memoryOverrides = mutableMapOf<String, Boolean>()

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun isProviderEnabled(providerId: String): Boolean {
        memoryOverrides[providerId]?.let { return it }
        val p = prefs ?: return true
        return p.getBoolean("provider_enabled_$providerId", true)
    }

    fun setProviderEnabled(providerId: String, enabled: Boolean) {
        memoryOverrides[providerId] = enabled
        prefs?.edit()?.putBoolean("provider_enabled_$providerId", enabled)?.apply()
    }
}
