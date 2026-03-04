package com.swiftpay.transfer.service.dto

import com.swiftpay.transfer.domain.model.Recipient
import com.swiftpay.transfer.domain.model.Transfer

data class TransferWithRecipient(
    val transfer: Transfer,
    val recipient: Recipient?
)
