package com.rhl.user.api;

import com.rhl.common.security.CurrentUser;
import com.rhl.common.security.Role;
import com.rhl.common.web.ApiException;
import com.rhl.common.web.ApiResponse;
import com.rhl.user.application.admin.UserAdminService;
import com.rhl.user.domain.user.UserStatus;
import com.rhl.user.infrastructure.persistence.UserRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class UserController {

    private final UserRepository users;
    private final UserAdminService admin;

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
