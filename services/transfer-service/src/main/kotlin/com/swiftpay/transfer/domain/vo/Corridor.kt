package com.swiftpay.transfer.domain.vo

/**
 * Коридор перевода: откуда → куда.
 * Определяет доступные delivery methods, fee structure, compliance rules.
 */
data class Corridor(
    val sourceCountry: String,  // ISO 3166-1 alpha-2: US, GB
    val destCountry: String     // ISO 3166-1 alpha-2: PH, MX, IN
) {
    init {
        require(sourceCountry.length == 2) { "Source country must be ISO 3166-1 alpha-2, got: $sourceCountry" }
        require(destCountry.length == 2) { "Dest country must be ISO 3166-1 alpha-2, got: $destCountry" }
    }

    /** Строковый идентификатор коридора: "US_PH", "GB_IN" */
    val id: String get() = "${sourceCountry}_${destCountry}"

    override fun toString(): String = "$sourceCountry → $destCountry"
}
