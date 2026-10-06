package com.rhl.user.api;

import com.nimbusds.jose.jwk.JWKSet;
import com.rhl.common.security.CurrentUser;
import com.rhl.common.security.Role;
import com.rhl.common.web.ApiResponse;
import com.rhl.user.application.auth.AuthService;
import com.rhl.user.domain.user.User;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class AuthController {

    private final AuthService auth;
    private final JWKSet publicJwkSet;

    public record RegisterRequest(
            @Size(max = 254) String email,
            @Size(max = 20) String phone,
            @NotBlank @Size(max = 128) String password,
            @NotBlank @Size(max = 120) String fullName,
            @NotNull Role role) {

        @AssertTrue(message = "email or phone is required")
        public boolean isIdentifierPresent() {
            return (email != null && !email.isBlank()) || (phone != null && !phone.isBlank());
        }
    }

    public record LoginRequest(@NotBlank @Size(max = 254) String identifier,
                               @NotBlank @Size(max = 128) String password) {
    }

    public record RefreshRequest(@NotBlank @Size(max = 100) String refreshToken) {
    }

    public record LogoutRequest(@Size(max = 100) String refreshToken) {
    }

    public record TokenResponse(String tokenType, String accessToken, long expiresIn, String refreshToken,
                                long refreshExpiresIn) {

        static TokenResponse of(AuthService.TokenPair pair, java.time.Instant now) {
            return new TokenResponse("Bearer", pair.accessToken(),
                    Duration.between(now, pair.accessTokenExpiresAt()).toSeconds(),
                    pair.refreshToken(), Duration.between(now, pair.refreshTokenExpiresAt()).toSeconds());
        }
    }

    public record UserResponse(UUID id, String email, String phone, String fullName, Set<Role> roles, String status) {

        static UserResponse of(User u) {
            return new UserResponse(u.getId(), u.getEmail(), u.getPhone(), u.getFullName(), u.getRoles(),
                    u.getStatus().name());
        }
    }

    @PostMapping("/api/v1/auth/register")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<UserResponse> register(@Valid @RequestBody RegisterRequest request) {
        User user = auth.register(new AuthService.RegisterCommand(request.email(), request.phone(),
                request.password(), request.fullName(), request.role()));
        return ApiResponse.ok(UserResponse.of(user));
    }

    @PostMapping("/api/v1/auth/login")
    public ResponseEntity<ApiResponse<TokenResponse>> login(@Valid @RequestBody LoginRequest request) {
        return noStore(TokenResponse.of(auth.login(request.identifier(), request.password()), java.time.Instant.now()));
    }

    @PostMapping("/api/v1/auth/refresh")
    public ResponseEntity<ApiResponse<TokenResponse>> refresh(@Valid @RequestBody RefreshRequest request) {
        return noStore(TokenResponse.of(auth.refresh(request.refreshToken()), java.time.Instant.now()));
    }

    @PostMapping("/api/v1/auth/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody(required = false) LogoutRequest request) {
        auth.logout(CurrentUser.get().id(), jwt.getId(), jwt.getExpiresAt(),
                request == null ? null : request.refreshToken());
    }

    /** Public signing keys for the gateway and other services. */
    @GetMapping("/.well-known/jwks.json")
    public ResponseEntity<Map<String, Object>> jwks() {
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic())
                .body(publicJwkSet.toJSONObject());
    }

    private static <T> ResponseEntity<ApiResponse<T>> noStore(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok(body));
    }
}
