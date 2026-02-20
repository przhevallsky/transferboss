package com.swiftpay.transfer.lock

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service

@Service
@ConditionalOnProperty(name = ["consul.lock.enabled"], havingValue = "false", matchIfMissing = true)
class NoOpDistributedLockService : DistributedLockService {
    override fun <T> executeWithLock(key: String, action: () -> T): T = action()
}
