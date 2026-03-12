package com.swiftpay.analytics.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import javax.sql.DataSource

@Configuration
class ClickHouseConfig {

    @Bean
    fun clickHouseDataSource(
        @Value("\${clickhouse.url}") url: String,
        @Value("\${clickhouse.username}") username: String,
        @Value("\${clickhouse.password}") password: String
    ): DataSource {
        return DriverManagerDataSource().apply {
            setDriverClassName("com.clickhouse.jdbc.ClickHouseDriver")
            setUrl(url)
            setUsername(username)
            setPassword(password)
        }
    }

    @Bean
    fun clickHouseJdbcTemplate(clickHouseDataSource: DataSource): JdbcTemplate {
        return JdbcTemplate(clickHouseDataSource)
    }
}
