package com.swiftpay.transfer.config

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.util.UUID

@Component
class TraceFilter : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        val traceId = request.getHeader("X-Trace-Id") ?: UUID.randomUUID().toString()
        try {
            MDC.put("traceId", traceId)
            response.setHeader("X-Trace-Id", traceId)
            filterChain.doFilter(request, response)
        } finally {
            MDC.clear()
        }
    }
}
