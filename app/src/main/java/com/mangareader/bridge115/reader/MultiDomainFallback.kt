package com.mangareader.bridge115.reader

import java.util.concurrent.atomic.AtomicInteger

class MultiDomainFallback(
    private val primaryDomain: String,
    private val backupDomains: List<String>,
) {
    private val domainIndex = AtomicInteger(0)

    fun currentDomain(): String {
        val i = domainIndex.get()
        return if (i == 0) primaryDomain else backupDomains.getOrElse(i - 1) { primaryDomain }
    }

    fun failover(): String {
        val next = domainIndex.incrementAndGet()
        return if (next <= backupDomains.size) {
            backupDomains[next - 1]
        } else {
            domainIndex.set(0)
            primaryDomain
        }
    }

    fun reset() {
        domainIndex.set(0)
    }

    fun replaceDomain(url: String): String {
        val domain = currentDomain()
        return url.replaceFirst(Regex("https?://[^/]+"), "https://$domain")
    }
}