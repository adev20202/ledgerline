package io.ledgerline.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.ledgerline.config.LedgerProperties;
import io.ledgerline.tenant.Tenant;
import io.ledgerline.tenant.TenantService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * /api/** needs a tenant API key in X-Api-Key. /admin/** needs the operator
 * token in X-Admin-Token. Everything else (actuator) passes through.
 */
@Component
public class ApiAuthFilter extends OncePerRequestFilter {

    public static final String TENANT_ATTRIBUTE = ApiAuthFilter.class.getName() + ".tenant";
    static final String API_KEY_HEADER = "X-Api-Key";
    static final String ADMIN_TOKEN_HEADER = "X-Admin-Token";

    private final TenantService tenantService;
    private final LedgerProperties properties;
    private final ObjectMapper objectMapper;

    public ApiAuthFilter(TenantService tenantService, LedgerProperties properties, ObjectMapper objectMapper) {
        this.tenantService = tenantService;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI().substring(request.getContextPath().length());

        if (path.startsWith("/admin/")) {
            if (!adminTokenMatches(request.getHeader(ADMIN_TOKEN_HEADER))) {
                reject(response, "Missing or invalid admin token");
                return;
            }
        } else if (path.startsWith("/api/")) {
            String key = request.getHeader(API_KEY_HEADER);
            Tenant tenant = key == null || key.isBlank() ? null : tenantService.findActiveByApiKey(key).orElse(null);
            if (tenant == null) {
                reject(response, "Missing or invalid API key");
                return;
            }
            request.setAttribute(TENANT_ATTRIBUTE, new TenantId(tenant.getId()));
        }
        chain.doFilter(request, response);
    }

    private boolean adminTokenMatches(String given) {
        String expected = properties.adminToken();
        if (given == null || expected == null || expected.isBlank()) {
            return false;
        }
        return MessageDigest.isEqual(given.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8));
    }

    private void reject(HttpServletResponse response, String detail) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(),
                Map.of("type", "about:blank", "title", "Unauthorized", "status", 401, "detail", detail));
    }
}
