package com.rhl.user.application.auth;

import com.rhl.user.UserServiceProperties;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * OAuth 2.0 client credentials grant (RFC 6749 §4.4) for service-to-service calls: a configured
 * client proves itself with its secret and gets a short-lived service token limited to the
 * scopes configured for it.
 */
@Service
@RequiredArgsConstructor
public class ServiceTokenService {

    private static final Logger log = LoggerFactory.getLogger(ServiceTokenService.class);

    private final UserServiceProperties properties;
    private final TokenService tokens;
    private final Clock clock;

    public record ServiceToken(String value, Instant expiresAt, List<String> scopes) {
    }

    /** Why a token request was refused, named as in RFC 6749 §5.2. */
    public enum Refusal {
        INVALID_CLIENT, INVALID_SCOPE
    }

    public static class RefusedException extends RuntimeException {

        private final Refusal refusal;

        RefusedException(Refusal refusal, String message) {
            super(message);
            this.refusal = refusal;
        }

        public Refusal refusal() {
            return refusal;
        }
    }

    /**
     * @param requestedScope space-separated scopes, or {@code null} for every scope of the client
     * @throws RefusedException unknown client, wrong secret or a scope the client may not have
     */
    public ServiceToken issue(String clientId, String clientSecret, String requestedScope) {
        UserServiceProperties.ServiceClient client = authenticate(clientId, clientSecret)
                .orElseThrow(() -> {
                    log.warn("Service token refused: bad credentials for client '{}'", clientId);
                    return new RefusedException(Refusal.INVALID_CLIENT, "Client authentication failed");
                });
        List<String> scopes = client.scopes();
        if (requestedScope != null && !requestedScope.isBlank()) {
            scopes = Arrays.stream(requestedScope.trim().split(" +")).distinct().toList();
            if (!client.scopes().containsAll(scopes)) {
                throw new RefusedException(Refusal.INVALID_SCOPE, "Scope not allowed for this client");
            }
        }
        TokenService.AccessToken token = tokens.issueServiceToken(clientId, scopes, clock.instant());
        return new ServiceToken(token.value(), token.expiresAt(), scopes);
    }

    private Optional<UserServiceProperties.ServiceClient> authenticate(String clientId, String secret) {
        UserServiceProperties.ServiceClient client = clientId == null ? null
                : properties.serviceClients().get(clientId);
        // Compare digests in constant time; an unknown client costs the same as a wrong secret.
        String expected = client == null ? "" : client.secret();
        boolean matches = MessageDigest.isEqual(sha256(expected), sha256(secret == null ? "" : secret));
        return client != null && matches ? Optional.of(client) : Optional.empty();
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
