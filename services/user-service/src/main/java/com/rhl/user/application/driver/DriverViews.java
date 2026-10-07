package com.rhl.user.application.driver;

import com.rhl.user.domain.driver.Availability;
import com.rhl.user.domain.driver.DocumentType;
import com.rhl.user.domain.driver.DriverDocument;
import com.rhl.user.domain.driver.DriverProfile;
import com.rhl.user.domain.driver.ReviewDecision;
import com.rhl.user.domain.driver.ReviewStatus;
import com.rhl.user.domain.driver.ServiceType;
import com.rhl.user.domain.driver.Vehicle;
import com.rhl.user.domain.driver.VehicleType;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Read models returned by the driver APIs. */
public final class DriverViews {

    private DriverViews() {
    }

    public record ProfileView(UUID driverId, String fullName, LocalDate dateOfBirth, Set<ServiceType> serviceTypes,
                              ReviewStatus reviewStatus, String statusReason, int profileVersion,
                              Instant submittedAt, Availability availability, UUID activeVehicleId,
                              List<VehicleView> vehicles, List<DocumentView> documents,
                              List<String> missingRequirements) {
    }

    public record VehicleView(UUID id, VehicleType type, String plateNumber, String brand, String model, String color,
                              int manufactureYear, Vehicle.Status status) {

        static VehicleView of(Vehicle v) {
            return new VehicleView(v.getId(), v.getType(), v.getPlateNumber(), v.getBrand(), v.getModel(), v.getColor(),
                    v.getManufactureYear(), v.getStatus());
        }
    }

    /**
     * @param documentNumber full number for reviewers; masked to the last 4 characters otherwise,
     *                       so a leaked driver session does not expose identity numbers
     */
    public record DocumentView(UUID id, DocumentType type, UUID vehicleId, String documentNumber, LocalDate issuedOn,
                               LocalDate expiresOn, boolean hasFile, DriverDocument.Status status) {

        static DocumentView of(DriverDocument d, boolean revealNumber) {
            return new DocumentView(d.getId(), d.getType(), d.getVehicleId(),
                    revealNumber ? d.getDocumentNumber() : mask(d.getDocumentNumber()),
                    d.getIssuedOn(), d.getExpiresOn(), d.getFileId() != null || d.getFileRef() != null, d.getStatus());
        }

        static String mask(String number) {
            int visible = Math.min(4, number.length() / 2);
            return "*".repeat(number.length() - visible) + number.substring(number.length() - visible);
        }
    }

    public record ReviewQueueItem(UUID driverId, String fullName, int profileVersion, Instant submittedAt,
                                  ReviewStatus reviewStatus) {

        static ReviewQueueItem of(DriverProfile p) {
            return new ReviewQueueItem(p.getDriverId(), p.getFullName(), p.getProfileVersion(), p.getSubmittedAt(),
                    p.getReviewStatus());
        }
    }

    public record DecisionView(UUID id, int profileVersion, ReviewDecision.Decision decision, String reason,
                               UUID reviewerId, Instant decidedAt) {

        static DecisionView of(ReviewDecision d) {
            return new DecisionView(d.getId(), d.getProfileVersion(), d.getDecision(), d.getReason(), d.getReviewerId(),
                    d.getDecidedAt());
        }
    }

    public record ReviewView(ProfileView profile, List<DecisionView> decisions) {
    }

    public record Page<T>(List<T> items, String nextCursor) {
    }
}
