package com.swiftpay.transfer.security

import io.jsonwebtoken.Jwts
import java.security.KeyFactory
import java.security.interfaces.RSAPrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Instant
import java.util.Base64
import java.util.Date
import java.util.UUID

object TestJwtHelper {

    private val privateKey: RSAPrivateKey by lazy {
        val keyContent = javaClass.classLoader.getResourceAsStream("keys/private.pem")!!
            .bufferedReader().readText()
        val keyBytes = Base64.getDecoder().decode(
            keyContent
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replace("\\s".toRegex(), "")
        )
        val spec = PKCS8EncodedKeySpec(keyBytes)
        KeyFactory.getInstance("RSA").generatePrivate(spec) as RSAPrivateKey
    }

    fun generateToken(
        userId: String,
        roles: List<String> = listOf("SENDER"),
        expiresIn: Long = 900
    ): String {
        val now = Instant.now()
        return Jwts.builder()
            .subject(userId)
            .id(UUID.randomUUID().toString())
            .claim("roles", roles)
            .issuedAt(Date.from(now))
            .expiration(Date.from(now.plusSeconds(expiresIn)))
            .signWith(privateKey)
            .compact()
    }

    fun senderToken(senderId: String = "00000000-0000-0000-0000-000000000001"): String =
        generateToken(senderId, listOf("SENDER"))

    fun operatorToken(userId: String = "99999999-9999-9999-9999-999999999999"): String =
        generateToken(userId, listOf("OPERATOR"))

    fun expiredToken(userId: String = "00000000-0000-0000-0000-000000000001"): String =
        generateToken(userId, expiresIn = -60)
}
