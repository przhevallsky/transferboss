package com.swiftpay.transfer.lock

import com.ecwid.consul.v1.ConsulClient
import com.ecwid.consul.v1.kv.model.PutParams
import com.ecwid.consul.v1.session.model.NewSession
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service

@Service
@ConditionalOnProperty(name = ["consul.lock.enabled"], havingValue = "true")
class ConsulDistributedLockService(
    private val consulClient: ConsulClient,
    private val properties: ConsulLockProperties
) : DistributedLockService {

    private val log = LoggerFactory.getLogger(ConsulDistributedLockService::class.java)

    override fun <T> executeWithLock(key: String, action: () -> T): T {
        val fullKey = "${properties.keyPrefix}/$key"
        val sessionId = createSession()

        try {
            acquireLock(fullKey, sessionId)
            return action()
        } finally {
            releaseLock(fullKey, sessionId)
        }
    }

    private fun createSession(): String {
        val session = NewSession().apply {
            name = "transfer-lock"
            ttl = "${properties.sessionTtlSeconds}s"
        }
        val response = consulClient.sessionCreate(session, null)
        val sessionId = response.value
        log.debug("Created Consul session: {}", sessionId)
        return sessionId
    }

    private fun acquireLock(key: String, sessionId: String) {
        val deadline = System.currentTimeMillis() + properties.acquireTimeoutMs
        var retryInterval = properties.retryIntervalMs

        while (true) {
            val putParams = PutParams().apply { acquireSession = sessionId }
            val acquired = consulClient.setKVValue(key, "locked", putParams).value

            if (acquired) {
                log.debug("Lock acquired: key={}, session={}", key, sessionId)
                return
            }

            if (System.currentTimeMillis() >= deadline) {
                destroySession(sessionId)
                throw DistributedLockException(key)
            }

            log.debug("Lock busy, retrying in {}ms: key={}", retryInterval, key)
            Thread.sleep(retryInterval)
            retryInterval = (retryInterval * 2).coerceAtMost(500)
        }
    }

    private fun releaseLock(key: String, sessionId: String) {
        try {
            val putParams = PutParams().apply { releaseSession = sessionId }
            consulClient.setKVValue(key, "released", putParams)
            log.debug("Lock released: key={}, session={}", key, sessionId)
        } catch (e: Exception) {
            log.warn("Failed to release lock: key={}, session={}. TTL will auto-release.", key, sessionId, e)
        }
        destroySession(sessionId)
    }

    private fun destroySession(sessionId: String) {
        try {
            consulClient.sessionDestroy(sessionId, null)
            log.debug("Session destroyed: {}", sessionId)
        } catch (e: Exception) {
            log.warn("Failed to destroy session: {}. TTL will auto-expire.", sessionId, e)
        }
    }
}
