package com.seatreserve.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatreserve.repo.UserAccountRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;

@Component
@Order(20)
public class AuthFilter extends OncePerRequestFilter {

    private final UserAccountRepository userAccountRepository;
    private final AppProperties appProperties;
    private final ObjectMapper objectMapper;

    public AuthFilter(
            UserAccountRepository userAccountRepository,
            AppProperties appProperties,
            ObjectMapper objectMapper) {
        this.userAccountRepository = userAccountRepository;
        this.appProperties = appProperties;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        String method = request.getMethod();
        if ("OPTIONS".equalsIgnoreCase(method)) {
            return true;
        }
        if (path.equals("/healthz") || path.equals("/readyz") || path.equals("/prometheus") || path.equals("/metrics")) {
            return true;
        }
        if (path.startsWith("/v3/api-docs") || path.startsWith("/swagger-ui") || path.equals("/swagger-ui.html")) {
            return true;
        }
        if ("GET".equalsIgnoreCase(method) && path.startsWith("/shows/")) {
            return true;
        }
        return false;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        try {
            String path = request.getRequestURI();
            String method = request.getMethod();

            if ("POST".equalsIgnoreCase(method) && path.equals("/shows")) {
                String adminToken = request.getHeader("X-Admin-Token");
                if (adminToken == null || !adminToken.equals(appProperties.getAdminToken())) {
                    writeUnauthorized(response, "Missing or invalid X-Admin-Token");
                    return;
                }
                filterChain.doFilter(request, response);
                return;
            }

            String authorization = request.getHeader("Authorization");
            if (authorization == null || !authorization.startsWith("Bearer ")) {
                writeUnauthorized(response, "Missing Bearer token");
                return;
            }
            String token = authorization.substring("Bearer ".length()).trim();
            var user = userAccountRepository.findByApiToken(token);
            if (user.isEmpty()) {
                writeUnauthorized(response, "Invalid Bearer token");
                return;
            }
            AuthContext.setUserId(user.get().getId());
            filterChain.doFilter(request, response);
        } finally {
            AuthContext.clear();
        }
    }

    private void writeUnauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), Map.of(
                "error", "unauthorized",
                "message", message
        ));
    }
}
