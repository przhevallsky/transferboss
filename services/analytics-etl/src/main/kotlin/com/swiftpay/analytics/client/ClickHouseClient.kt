package com.swiftpay.analytics.client

import com.swiftpay.analytics.model.TransferAnalyticsRecord
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.sql.Timestamp

@Component
class ClickHouseClient(
    private val clickHouseJdbcTemplate: JdbcTemplate,
    private val meterRegistry: MeterRegistry
) {

    private val logger = LoggerFactory.getLogger(ClickHouseClient::class.java)

    fun batchInsert(records: List<TransferAnalyticsRecord>) {
        if (records.isEmpty()) return

        val timer = Timer.start(meterRegistry)

        val sql = """
            INSERT INTO transfers_analytics
            (transfer_id, sender_id, source_country, dest_country, corridor,
             send_amount, send_currency, receive_amount, receive_currency,
             exchange_rate, fee_amount, fee_currency, delivery_method,
             status, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent()

        clickHouseJdbcTemplate.batchUpdate(sql, records.map { record ->
            arrayOf(
                record.transferId.toString(),
                record.senderId.toString(),
                record.sourceCountry,
                record.destCountry,
                record.corridor,
                record.sendAmount,
                record.sendCurrency,
                record.receiveAmount,
                record.receiveCurrency,
                record.exchangeRate,
                record.feeAmount,
                record.feeCurrency,
                record.deliveryMethod,
                record.status,
                Timestamp.from(record.createdAt),
                Timestamp.from(record.updatedAt)
            )
        })

        timer.stop(meterRegistry.timer("etl.clickhouse.batch_insert"))
        meterRegistry.counter("etl.clickhouse.rows_inserted").increment(records.size.toDouble())

        logger.info("Batch inserted {} records into ClickHouse", records.size)
    }
}
