package com.swiftpay.transfer.config

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.net.URI

@Component
class RateLimitFilter(
    private val redisTemplate: StringRedisTemplate,
    private val objectMapper: ObjectMapper
) : OncePerRequestFilter() {

    companion object {
        private const val AUTHENTICATED_LIMIT = 100
        private const val ANONYMOUS_LIMIT = 20
        private const val WINDOW_SECONDS = 60L

        private val SLIDING_WINDOW_SCRIPT = DefaultRedisScript<List<*>>(
            """
            local key = KEYS[1]
            local now = tonumber(ARGV[1])
            local window = tonumber(ARGV[2])
            local limit = tonumber(ARGV[3])

            redis.call('ZREMRANGEBYSCORE', key, 0, now - window * 1000)
            local count = redis.call('ZCARD', key)

            if count < limit then
                redis.call('ZADD', key, now, now .. '-' .. math.random(1000000))
                redis.call('EXPIRE', key, window)
                return {count + 1, limit}
            end

            return {count, limit}
            """.trimIndent(),
            List::class.java
        )
    }

    override fun shouldNotFilter(request: HttpServletRequest): Boolean {
        val path = request.requestURI
        return path.startsWith("/actuator") || path.startsWith("/auth") || path.startsWith("/swagger-ui") || path.startsWith("/api-docs")
    }

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        val authentication = SecurityContextHolder.getContext().authentication
        val (key, limit) = if (authentication is JwtAuthenticationToken) {
            "rate_limit:user:${authentication.name}" to AUTHENTICATED_LIMIT
        } else {
            val ip = request.remoteAddr
            "rate_limit:ip:$ip" to ANONYMOUS_LIMIT
        }

        try {
            val now = System.currentTimeMillis()
            val result = redisTemplate.execute(
                SLIDING_WINDOW_SCRIPT,
                listOf(key),
                now.toString(),
                WINDOW_SECONDS.toString(),
                limit.toString()
            )

            val currentCount = (result?.get(0) as? Long) ?: 0
            val maxLimit = (result?.get(1) as? Long) ?: limit.toLong()

            response.setHeader("X-RateLimit-Limit", maxLimit.toString())
            response.setHeader("X-RateLimit-Remaining", (maxLimit - currentCount).coerceAtLeast(0).toString())

            if (currentCount > maxLimit) {
                response.status = HttpStatus.TOO_MANY_REQUESTS.value()
                response.contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
                response.setHeader("Retry-After", "60")
                val problem = ProblemDetail.forStatusAndDetail(
                    HttpStatus.TOO_MANY_REQUESTS, "Rate limit exceeded. Try again in 60 seconds."
                )
                problem.type = URI.create("https://api.transferhub.com/errors/rate-limit-exceeded")
                problem.title = "Too Many Requests"
                response.writer.write(objectMapper.writeValueAsString(problem))
                return
            }
        } catch (e: Exception) {
            logger.warn("Rate limiting unavailable, allowing request", e)
        }

        filterChain.doFilter(request, response)
    }
}
