package com.smartgn.management.configuration;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.UUID;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationFilter extends OncePerRequestFilter {
    @Override protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String requested = req.getHeader("X-Correlation-ID");
        UUID correlation;
        try { correlation = UUID.fromString(requested); }
        catch (IllegalArgumentException | NullPointerException ex) { correlation = UUID.randomUUID(); }
        req.setAttribute("correlationId", correlation);
        res.setHeader("X-Correlation-ID", correlation.toString());
        MDC.put("correlationId", correlation.toString());
        try { chain.doFilter(req, res); } finally { MDC.remove("correlationId"); }
    }
}
