package com.rhl.user.domain.driver;

public enum DocumentType {
    NATIONAL_ID(false),
    DRIVER_LICENSE(false),
    VEHICLE_REGISTRATION(true),
    VEHICLE_INSURANCE(true);

    private final boolean vehicleScoped;

    DocumentType(boolean vehicleScoped) {
        this.vehicleScoped = vehicleScoped;
    }

    /** Vehicle-scoped documents belong to one vehicle; the others belong to the driver. */
    public boolean isVehicleScoped() {
        return vehicleScoped;
    }
}
