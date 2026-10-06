package com.rhl.user.api;

import com.rhl.common.security.CurrentUser;
import com.rhl.common.web.ApiResponse;
import com.rhl.user.application.driver.DriverService;
import com.rhl.user.application.driver.DriverViews;
import com.rhl.user.domain.driver.DocumentType;
import com.rhl.user.domain.driver.ServiceType;
import com.rhl.user.domain.driver.VehicleType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

/** Driver self-service. Every endpoint acts on the caller's own profile only. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/drivers/me")
@PreAuthorize("hasRole('DRIVER')")
public class DriverController {

    private final DriverService drivers;

    public record ProfileRequest(@NotBlank @Size(max = 120) String fullName, @Past LocalDate dateOfBirth,
                                 @NotEmpty Set<@NotNull ServiceType> serviceTypes) {

        DriverService.ProfileCommand toCommand() {
            return new DriverService.ProfileCommand(fullName, dateOfBirth, serviceTypes);
        }
    }

    public record VehicleRequest(@NotNull VehicleType type, @NotBlank @Size(max = 16) String plateNumber,
                                 @NotBlank @Size(max = 60) String brand, @NotBlank @Size(max = 60) String model,
                                 @NotBlank @Size(max = 30) String color,
                                 @Min(1980) @Max(2100) int manufactureYear) {
    }

    /** {@code fileRef} comes from the protected upload store; the upload endpoint is not part of this API yet. */
    public record DocumentRequest(@NotNull DocumentType type, UUID vehicleId,
                                  @NotBlank @Size(max = 40) @Pattern(regexp = "^[A-Za-z0-9-]+$") String documentNumber,
                                  LocalDate issuedOn, LocalDate expiresOn,
                                  @Size(max = 300) @Pattern(regexp = "^[A-Za-z0-9/_.-]+$") String fileRef) {
    }

    public record OnlineRequest(@NotNull UUID vehicleId, @NotEmpty Set<@NotNull ServiceType> serviceTypes) {
    }

    @PostMapping("/profile")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<DriverViews.ProfileView> createProfile(@Valid @RequestBody ProfileRequest request) {
        return ApiResponse.ok(drivers.createProfile(me(), request.toCommand()));
    }

    @PutMapping("/profile")
    public ApiResponse<DriverViews.ProfileView> updateProfile(@Valid @RequestBody ProfileRequest request) {
        return ApiResponse.ok(drivers.updateProfile(me(), request.toCommand()));
    }

    @GetMapping("/profile")
    public ApiResponse<DriverViews.ProfileView> profile() {
        return ApiResponse.ok(drivers.myProfile(me()));
    }

    @PostMapping("/vehicles")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<DriverViews.VehicleView> addVehicle(@Valid @RequestBody VehicleRequest r) {
        return ApiResponse.ok(drivers.addVehicle(me(), new DriverService.VehicleCommand(r.type(), r.plateNumber(),
                r.brand(), r.model(), r.color(), r.manufactureYear())));
    }

    @DeleteMapping("/vehicles/{vehicleId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deactivateVehicle(@PathVariable UUID vehicleId) {
        drivers.deactivateVehicle(me(), vehicleId);
    }

    @PostMapping("/documents")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<DriverViews.DocumentView> submitDocument(@Valid @RequestBody DocumentRequest r) {
        return ApiResponse.ok(drivers.submitDocument(me(), new DriverService.DocumentCommand(r.type(), r.vehicleId(),
                r.documentNumber(), r.issuedOn(), r.expiresOn(), r.fileRef())));
    }

    @PostMapping("/profile/submit")
    public ApiResponse<DriverViews.ProfileView> submit() {
        return ApiResponse.ok(drivers.submitForReview(me()));
    }

    @PostMapping("/availability/online")
    public ApiResponse<DriverViews.ProfileView> goOnline(@Valid @RequestBody OnlineRequest request) {
        return ApiResponse.ok(drivers.goOnline(me(), request.vehicleId(), request.serviceTypes()));
    }

    @PostMapping("/availability/offline")
    public ApiResponse<DriverViews.ProfileView> goOffline() {
        return ApiResponse.ok(drivers.goOffline(me()));
    }

    private static UUID me() {
        return CurrentUser.get().id();
    }
}
