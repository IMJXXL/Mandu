package com.mangareader.bridge115.reader

class WafCooldown {
    @Volatile
    private var coolingStartAt: Long = 0

    fun enterCooldown() {
        coolingStartAt = System.currentTimeMillis()
    }

    fun isCooling(): Boolean {
        return System.currentTimeMillis() < coolingStartAt + COOLDOWN_MS
    }

    fun checkAndThrow() {
        if (isCooling()) {
            throw WafCooldownException()
        }
    }

    companion object {
        private const val COOLDOWN_MS = 60_000L
    }
}

class WafCooldownException : java.io.IOException("115 WAF cooldown active, please wait")