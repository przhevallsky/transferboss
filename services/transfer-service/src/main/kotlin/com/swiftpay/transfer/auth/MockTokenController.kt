package com.swiftpay.transfer.auth

import io.jsonwebtoken.Jwts
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Profile
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.nio.file.Files
import java.nio.file.Paths
import java.security.KeyFactory
import java.security.interfaces.RSAPrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Instant
import java.util.Base64
import java.util.Date
import java.util.UUID

@RestController
@RequestMapping("/auth")
@Profile("dev", "test", "docker")
class MockTokenController(
    @Value("\${jwt.private-key-path:classpath:keys/private.pem}")
    private val privateKeyPath: String
) {

    data class TokenRequest(
        val userId: String,
        val roles: List<String> = listOf("SENDER")
    )

    data class TokenResponse(
        val accessToken: String,
        val tokenType: String = "Bearer",
        val expiresIn: Long = 900
    )

    @PostMapping("/token")
    fun generateToken(@RequestBody request: TokenRequest): ResponseEntity<TokenResponse> {
        val now = Instant.now()
        val privateKey = loadPrivateKey()

        val token = Jwts.builder()
            .subject(request.userId)
            .id(UUID.randomUUID().toString())
            .claim("roles", request.roles)
            .issuedAt(Date.from(now))
            .expiration(Date.from(now.plusSeconds(900)))
            .signWith(privateKey)
            .compact()

        return ResponseEntity.ok(TokenResponse(accessToken = token))
    }

    private fun loadPrivateKey(): RSAPrivateKey {
        val keyContent = if (privateKeyPath.startsWith("classpath:")) {
            val resource = privateKeyPath.removePrefix("classpath:")
            javaClass.classLoader.getResourceAsStream(resource)?.bufferedReader()?.readText()
                ?: throw IllegalStateException("Private key not found: $privateKeyPath")
        } else {
            Files.readString(Paths.get(privateKeyPath))
        }

        val keyBytes = Base64.getDecoder().decode(
            keyContent
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replace("-----BEGIN RSA PRIVATE KEY-----", "")
                .replace("-----END RSA PRIVATE KEY-----", "")
                .replace("\\s".toRegex(), "")
        )
        val spec = PKCS8EncodedKeySpec(keyBytes)
        return KeyFactory.getInstance("RSA").generatePrivate(spec) as RSAPrivateKey
    }
}
