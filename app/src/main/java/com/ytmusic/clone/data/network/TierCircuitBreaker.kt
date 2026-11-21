package com.ytmusic.clone.data.network

import android.util.Log

enum class CircuitState {
    CLOSED,    // Healthy: normal traffic passes through
    OPEN,      // Tripped: in 10-minute cooldown, traffic is blocked
    HALF_OPEN  // Cooldown elapsed: exactly one trial request allowed
}

/**
 * In-memory circuit breaker for stream resolvers.
 *
 * Rules:
 *  - 3 consecutive failures -> trips into OPEN (10-minute cooldown).
 *  - In OPEN state -> all requests skipped immediately.
 *  - After 10 minutes -> enters HALF_OPEN, allowing a single trial request.
 *  - Trial succeeds -> reset failures to 0, transitions to CLOSED.
 *  - Trial fails -> reset cooldown timer, transitions back to OPEN.
 */
object TierCircuitBreaker {
    private const val TAG = "TierCircuitBreaker"
    const val MAX_CONSECUTIVE_FAILURES = 3
    const val COOLDOWN_DURATION_MS = 10 * 60 * 1000L // 10 minutes

    data class TierHealth(
        var consecutiveFailures: Int = 0,
        var lastFailureTimeMs: Long = 0L,
        var trialInProgress: Boolean = false
    )

    private val healthMap = java.util.concurrent.ConcurrentHashMap<String, TierHealth>()

    @Synchronized
    fun getState(tierId: String, nowMs: Long = System.currentTimeMillis()): CircuitState {
        val health = healthMap[tierId] ?: return CircuitState.CLOSED
        if (health.consecutiveFailures < MAX_CONSECUTIVE_FAILURES) {
            return CircuitState.CLOSED
        }
        val elapsed = nowMs - health.lastFailureTimeMs
        return if (elapsed >= COOLDOWN_DURATION_MS) {
            CircuitState.HALF_OPEN
        } else {
            CircuitState.OPEN
        }
    }

    @Synchronized
    fun canExecute(tierId: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        return when (getState(tierId, nowMs)) {
            CircuitState.CLOSED -> true
            CircuitState.HALF_OPEN -> {
                val health = healthMap.getOrPut(tierId) { TierHealth() }
                if (!health.trialInProgress) {
                    health.trialInProgress = true
                    Log.i(TAG, "Tier $tierId entered HALF_OPEN: allowing 1 trial request")
                    true
                } else {
                    false
                }
            }
            CircuitState.OPEN -> false
        }
    }

    @Synchronized
    fun recordSuccess(tierId: String) {
        val health = healthMap.getOrPut(tierId) { TierHealth() }
        health.consecutiveFailures = 0
        health.trialInProgress = false
        Log.d(TAG, "Tier $tierId recorded success -> CLOSED (healthy)")
    }

    @Synchronized
    fun recordFailure(tierId: String, nowMs: Long = System.currentTimeMillis()) {
        val health = healthMap.getOrPut(tierId) { TierHealth() }
        health.consecutiveFailures++
        health.lastFailureTimeMs = nowMs
        health.trialInProgress = false
        if (health.consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
            Log.w(TAG, "Tier $tierId tripped into OPEN after ${health.consecutiveFailures} failures (10m cooldown)")
        } else {
            Log.d(TAG, "Tier $tierId failure recorded (${health.consecutiveFailures}/$MAX_CONSECUTIVE_FAILURES)")
        }
    }

    /**
     * Releases any in-progress trial flag (called in finally block on any completion, failure,
     * cancellation, or unplayable video error).
     */
    @Synchronized
    fun releaseTrial(tierId: String) {
        val health = healthMap[tierId] ?: return
        health.trialInProgress = false
    }

    /**
     * Transition tier state to HALF_OPEN (e.g. after a passing diagnostic self-test).
     * Does nothing unless the tier is currently OPEN; a passing self-test on a CLOSED tier leaves it CLOSED.
     */
    @Synchronized
    fun transitionToHalfOpen(tierId: String) {
        val currentState = getState(tierId)
        if (currentState != CircuitState.OPEN) {
            // Do nothing unless the tier is currently OPEN; a passing self-test on a CLOSED tier leaves it CLOSED
            return
        }
        val health = healthMap.getOrPut(tierId) { TierHealth() }
        health.consecutiveFailures = MAX_CONSECUTIVE_FAILURES
        health.lastFailureTimeMs = System.currentTimeMillis() - COOLDOWN_DURATION_MS - 1000L
        health.trialInProgress = false
        Log.i(TAG, "Tier $tierId manually transitioned from OPEN to HALF_OPEN")
    }

    /**
     * Find the least-recently-failed tier from candidate list when all tiers are blocked.
     */
    @Synchronized
    fun getLeastRecentlyFailedTier(tierIds: List<String>): String? {
        if (tierIds.isEmpty()) return null
        return tierIds.minByOrNull { tierId ->
            healthMap[tierId]?.lastFailureTimeMs ?: 0L
        }
    }

    @Synchronized
    fun getRemainingCooldownMs(tierId: String, nowMs: Long = System.currentTimeMillis()): Long {
        val health = healthMap[tierId] ?: return 0L
        if (health.consecutiveFailures < MAX_CONSECUTIVE_FAILURES) return 0L
        val elapsed = nowMs - health.lastFailureTimeMs
        return (COOLDOWN_DURATION_MS - elapsed).coerceAtLeast(0L)
    }

    @Synchronized
    fun getConsecutiveFailures(tierId: String): Int {
        return healthMap[tierId]?.consecutiveFailures ?: 0
    }

    @Synchronized
    fun resetAll() {
        healthMap.clear()
        Log.i(TAG, "Cleared all tier circuit breakers")
    }

    @Synchronized
    fun resetTier(tierId: String) {
        healthMap.remove(tierId)
    }
}
