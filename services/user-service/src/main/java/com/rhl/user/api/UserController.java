package com.rhl.user.api;

import com.rhl.common.security.CurrentUser;
import com.rhl.common.security.Role;
import com.rhl.common.web.ApiException;
import com.rhl.common.web.ApiResponse;
import com.rhl.user.application.admin.UserAdminService;
import com.rhl.user.application.admin.UserAdminViews;
import com.rhl.user.application.admin.UserSearch;
import com.rhl.user.application.auth.ContactVerificationService;
import com.rhl.user.domain.user.ContactChannel;
import com.rhl.user.domain.user.UserStatus;
import com.rhl.user.infrastructure.persistence.UserRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class UserController {

    private final UserRepository users;
    private final UserAdminService admin;
    private final ContactVerificationService verification;

    public record CodeRequest(@NotBlank @Pattern(regexp = "^[0-9]{6}$") String code) {
    }

    /** @param expiresInSeconds the code's lifetime; {@code resendAfterSeconds} the wait before asking again */
    public record CodeSentResponse(String channel, String destination, long expiresInSeconds,
                                   long resendAfterSeconds) {
    }

    public record RolesRequest(@NotEmpty Set<@NotNull Role> roles) {
    }

    public record StatusRequest(@NotNull UserStatus status, @Size(max = 500) String reason) {
    }

    @GetMapping("/api/v1/users/me")
    public ApiResponse<AuthController.UserResponse> me() {
        return ApiResponse.ok(users.findById(CurrentUser.get().id())
                .map(AuthController.UserResponse::of)
                .orElseThrow(() -> ApiException.notFound("User")));
    }

    /** Sends a one-time code to the account's email or phone ({@code channel} = email | phone). */
    @PostMapping("/api/v1/users/me/contacts/{channel}/verification")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<CodeSentResponse> sendVerificationCode(@PathVariable String channel) {
        ContactVerificationService.CodeSent sent = verification.send(CurrentUser.get().id(),
                ContactChannel.parse(channel));
        return ApiResponse.ok(new CodeSentResponse(sent.channel().name(), sent.destination(),
                sent.expiresIn().toSeconds(), sent.resendAfter().toSeconds()));
    }

    @PostMapping("/api/v1/users/me/contacts/{channel}/verification/confirm")
    public ApiResponse<AuthController.UserResponse> confirmVerificationCode(@PathVariable String channel,
                                                                            @Valid @RequestBody CodeRequest request) {
        return ApiResponse.ok(AuthController.UserResponse.of(
                verification.confirm(CurrentUser.get().id(), ContactChannel.parse(channel), request.code())));
    }

    /**
     * Administrator lookup (FR-ADM): {@code q} is a name, email or phone fragment (at least two
     * characters) or a user id; newest first, pass {@code nextBefore} as {@code before}.
     */
    @GetMapping("/api/v1/admin/users")
    @PreAuthorize("hasRole('ADMINISTRATOR')")
    public ApiResponse<UserAdminViews.UserPage> search(
            @RequestParam(required = false) @Size(min = 2, max = 100) String q,
            @RequestParam(required = false) Role role,
            @RequestParam(required = false) UserStatus status,
            @RequestParam(required = false) UUID before,
            @RequestParam(defaultValue = "20") @Min(1) @Max(UserAdminService.MAX_PAGE_SIZE) int limit) {
        return ApiResponse.ok(admin.search(UserSearch.of(q), role, status, before, limit));
    }

    /** One account with verification state and driver review status; audited (USER_VIEWED). */
    @GetMapping("/api/v1/admin/users/{userId}")
    @PreAuthorize("hasRole('ADMINISTRATOR')")
    public ApiResponse<UserAdminViews.UserDetail> view(@PathVariable UUID userId) {
        return ApiResponse.ok(admin.view(CurrentUser.get().id(), userId));
    }

    @PutMapping("/api/v1/admin/users/{userId}/roles")
    @PreAuthorize("hasRole('ADMINISTRATOR')")
    public ApiResponse<AuthController.UserResponse> replaceRoles(@PathVariable UUID userId,
                                                                 @Valid @RequestBody RolesRequest request) {
        return ApiResponse.ok(AuthController.UserResponse.of(
                admin.replaceRoles(CurrentUser.get().id(), userId, request.roles())));
    }

    @PutMapping("/api/v1/admin/users/{userId}/status")
    @PreAuthorize("hasRole('ADMINISTRATOR')")
    public ApiResponse<AuthController.UserResponse> changeStatus(@PathVariable UUID userId,
                                                                 @Valid @RequestBody StatusRequest request) {
        return ApiResponse.ok(AuthController.UserResponse.of(
                admin.changeStatus(CurrentUser.get().id(), userId, request.status(), request.reason())));
    }
}
