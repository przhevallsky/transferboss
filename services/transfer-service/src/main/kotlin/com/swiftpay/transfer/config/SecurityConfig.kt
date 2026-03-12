package com.swiftpay.transfer.config

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.convert.converter.Converter
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import org.springframework.security.authentication.AbstractAuthenticationToken
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.security.web.SecurityFilterChain
import java.net.URI
import java.nio.file.Files
import java.nio.file.Paths
import java.security.KeyFactory
import java.security.interfaces.RSAPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
class SecurityConfig(
    private val objectMapper: ObjectMapper
) {

    @Value("\${jwt.public-key-path:classpath:keys/public.pem}")
    private lateinit var publicKeyPath: String

    @Bean
    fun securityFilterChain(http: HttpSecurity, jwtDecoder: JwtDecoder): SecurityFilterChain {
        http
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests { auth ->
                auth
                    .requestMatchers("/actuator/**").permitAll()
                    .requestMatchers("/swagger-ui/**", "/api-docs/**", "/swagger-ui.html").permitAll()
                    .requestMatchers("/auth/**").permitAll()
                    .requestMatchers("/api/v1/transfers/*/events").permitAll()
                    .requestMatchers("/api/v1/**").authenticated()
                    .anyRequest().permitAll()
            }
            .oauth2ResourceServer { oauth2 ->
                oauth2.jwt { jwt ->
                    jwt.decoder(jwtDecoder)
                    jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())
                }
                oauth2.authenticationEntryPoint { _, response, _ ->
                    response.status = HttpStatus.UNAUTHORIZED.value()
                    response.contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
                    val problem = ProblemDetail.forStatusAndDetail(
                        HttpStatus.UNAUTHORIZED, "Authentication required"
                    )
                    problem.type = URI.create("https://api.transferhub.com/errors/unauthorized")
                    problem.title = "Unauthorized"
                    response.writer.write(objectMapper.writeValueAsString(problem))
                }
                oauth2.accessDeniedHandler { _, response, _ ->
                    response.status = HttpStatus.FORBIDDEN.value()
                    response.contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
                    val problem = ProblemDetail.forStatusAndDetail(
                        HttpStatus.FORBIDDEN, "Insufficient permissions"
                    )
                    problem.type = URI.create("https://api.transferhub.com/errors/forbidden")
                    problem.title = "Forbidden"
                    response.writer.write(objectMapper.writeValueAsString(problem))
                }
            }

        return http.build()
    }

    @Bean
    fun jwtDecoder(): JwtDecoder {
        val publicKey = loadPublicKey()
        return NimbusJwtDecoder.withPublicKey(publicKey).build()
    }

    private fun jwtAuthenticationConverter(): Converter<Jwt, AbstractAuthenticationToken> {
        return Converter { jwt ->
            val roles = jwt.getClaimAsStringList("roles") ?: emptyList()
            val authorities = roles.map { SimpleGrantedAuthority("ROLE_$it") }
            JwtAuthenticationToken(jwt, authorities, jwt.subject)
        }
    }

    private fun loadPublicKey(): RSAPublicKey {
        val keyContent = if (publicKeyPath.startsWith("classpath:")) {
            val resource = publicKeyPath.removePrefix("classpath:")
            javaClass.classLoader.getResourceAsStream(resource)?.bufferedReader()?.readText()
                ?: throw IllegalStateException("Public key not found: $publicKeyPath")
        } else {
            Files.readString(Paths.get(publicKeyPath))
        }

        val keyBytes = Base64.getDecoder().decode(
            keyContent
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replace("\\s".toRegex(), "")
        )
        val spec = X509EncodedKeySpec(keyBytes)
        return KeyFactory.getInstance("RSA").generatePublic(spec) as RSAPublicKey
    }
}
