package com.swiftpay.transfer.domain.model

import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter

/**
 * JPA конвертер: TransferStatus sealed class ↔ VARCHAR в PostgreSQL.
 * autoApply = true: применяется ко всем полям типа TransferStatus автоматически.
 */
@Converter(autoApply = true)
class TransferStatusConverter : AttributeConverter<TransferStatus, String> {

    override fun convertToDatabaseColumn(attribute: TransferStatus?): String? =
        attribute?.value

    override fun convertToEntityAttribute(dbData: String?): TransferStatus? =
        dbData?.let { TransferStatus.fromString(it) }
}
