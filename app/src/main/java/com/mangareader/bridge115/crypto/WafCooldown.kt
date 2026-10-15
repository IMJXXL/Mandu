package com.mangareader.bridge115.crypto

import com.mangareader.bridge115.Constants115
import com.mangareader.bridge115.api.WafBlockedException
import java.util.concurrent.atomic.AtomicLong

class WafCooldown(
    private val cooldownMs: Long = Constants115.WAF_COOLDOWN_MS
) {
    private val cooldownUntilMs = AtomicLong(0L)

    fun ensureAllowed() {
        val until = cooldownUntilMs.get()
        val now = System.currentTimeMillis()
        if (now < until) {
            val remaining = (until - now) / 1000
            throw WafBlockedException("触发风控，请约 ${remaining + 1} 秒后重试")
        }
    }

    fun markBlocked() {
        cooldownUntilMs.set(System.currentTimeMillis() + cooldownMs)
    }

    fun isCooling(): Boolean {
        return System.currentTimeMillis() < cooldownUntilMs.get()
    }
}