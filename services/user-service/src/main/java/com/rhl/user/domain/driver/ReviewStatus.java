package com.rhl.user.domain.driver;

/** Driver profile review lifecycle (FR-DRV): DRAFT → PENDING_REVIEW → APPROVED | REJECTED, plus SUSPENDED. */
public enum ReviewStatus {
    DRAFT,
    PENDING_REVIEW,
    APPROVED,
    REJECTED,
    SUSPENDED;

    /** Personal data, vehicles and documents can only change while the profile is not under review or approved. */
    public boolean isEditable() {
        return this == DRAFT || this == REJECTED;
    }
}
