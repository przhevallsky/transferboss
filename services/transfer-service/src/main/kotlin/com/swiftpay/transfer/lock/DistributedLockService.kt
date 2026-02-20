package com.swiftpay.transfer.lock

interface DistributedLockService {
    fun <T> executeWithLock(key: String, action: () -> T): T
}
