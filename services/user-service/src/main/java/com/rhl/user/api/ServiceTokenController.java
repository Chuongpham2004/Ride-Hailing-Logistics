package com.rhl.user.api;

import com.rhl.user.application.auth.ServiceTokenService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Token endpoint for services (OAuth 2.0 client credentials, RFC 6749 §4.4). Requests and
 * answers follow the RFC rather than the {@code ApiResponse} envelope, so any OAuth 2.0 client
 * library can use it. The gateway does not route {@code /oauth2/**}: only services inside the
 * network reach it.
 */
@RestController
@RequiredArgsConstructor
public class ServiceTokenController {

    private static final String BASIC = "Basic ";

    private final ServiceTokenService serviceTokens;

    @PostMapping(path = "/oauth2/token", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<Map<String, Object>> token(
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestParam(name = "grant_type", required = false) String grantType,
            @RequestParam(name = "scope", required = false) String scope) {
        String[] credentials = basicCredentials(authorization);
        if (credentials == null) {
            return invalidClient();
        }
        if (!"client_credentials".equals(grantType)) {
            return error(HttpStatus.BAD_REQUEST, "unsupported_grant_type", "Only client_credentials is supported");
        }
        ServiceTokenService.ServiceToken token;
        try {
            token = serviceTokens.issue(credentials[0], credentials[1], scope);
        } catch (ServiceTokenService.RefusedException e) {
            return e.refusal() == ServiceTokenService.Refusal.INVALID_CLIENT
                    ? invalidClient()
                    : error(HttpStatus.BAD_REQUEST, "invalid_scope", e.getMessage());
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("access_token", token.value());
        body.put("token_type", "Bearer");
        body.put("expires_in", Math.max(0, Duration.between(Instant.now(), token.expiresAt()).toSeconds()));
        body.put("scope", String.join(" ", token.scopes()));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("Pragma", "no-cache").body(body);
    }

    /** {@code [clientId, secret]} from {@code Authorization: Basic}, form-decoded (RFC 6749 §2.3.1). */
    static String[] basicCredentials(String authorization) {
        if (authorization == null || !authorization.regionMatches(true, 0, BASIC, 0, BASIC.length())) {
            return null;
        }
        String decoded;
        try {
            decoded = new String(Base64.getDecoder().decode(authorization.substring(BASIC.length()).trim()),
                    StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return null;
        }
        int colon = decoded.indexOf(':');
        if (colon <= 0) {
            return null;
        }
        try {
            return new String[] {URLDecoder.decode(decoded.substring(0, colon), StandardCharsets.UTF_8),
                    URLDecoder.decode(decoded.substring(colon + 1), StandardCharsets.UTF_8)};
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static ResponseEntity<Map<String, Object>> invalidClient() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .header(HttpHeaders.WWW_AUTHENTICATE, "Basic realm=\"rhl-services\"")
                .cacheControl(CacheControl.noStore())
                .body(Map.of("error", "invalid_client", "error_description", "Client authentication failed"));
    }

    private static ResponseEntity<Map<String, Object>> error(HttpStatus status, String code, String description) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
                .body(Map.of("error", code, "error_description", description));
    }
}
